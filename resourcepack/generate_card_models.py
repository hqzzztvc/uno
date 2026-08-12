#!/usr/bin/env python3
"""
Generate every card model in the pack from the hand-authored Blockbench exports.

`uno_json/` at the repo root is the single source of truth for card GEOMETRY the way
`assets/minecraft/textures/item/cards/` is for card ART. Each file there is a Blockbench
export of one upright card: a rounded-corner slab 16 wide x 24 tall x 1 thick, built from
17 boxes, with the card face on `north`, `back.png` on `south` and a plain white edge
texture on the four rims. `deck_10/50/100.json` are the same slab thickened to 1/5/10
units to stand in for a draw pile.

Everything under `assets/uno/models/item/` is derived from those files and must not be
hand-edited. Three families come out, matching the three ways the plugin shows a card:

    cards/<card>        upright, front on +Z, bottom-centre at the origin  -> CardTester
    cards_flat/<card>   lying flat, face up   (and down_<card>, face down) -> PileRenderer
    cards_held/<card>   upright, pivot at (8,8,8), no rim faces  -> parent of the held fan

The rotations are BAKED IN rather than applied at render time, because an ItemDisplay
rotation swings the card off its spawn point and drops it under the table (see
PileRenderer). Baking means rewriting each face's UV, which is what `rotate_model` below
is for.
"""
import json
import os
import shutil

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "uno_json")
ITEMS = os.path.join(HERE, "assets/uno/items")
MODELS = os.path.join(HERE, "assets/uno/models/item")

TEX_NS = "minecraft:item/cards"
SIDES = "sides"          # the plain white rim texture, generated alongside the card art
BACK = "back"            # the shared card reverse
DECKS = ("deck_10", "deck_50", "deck_100")

# How much of its authored thickness a card keeps once it is lying flat. Only the discard
# pile stacks cards, and at full thickness five of them build a tower rather than a heap.
# The decks are exempt: their thickness is the whole point of having three of them.
FLAT_THICKNESS = 0.5

# ----------------------------------------------------------------- face geometry

# Every cuboid face samples its texture through a fixed (u, v) frame given by the face's
# direction. Rotating the model moves a face to a new direction whose frame is different,
# so the UV rect has to be re-expressed in the new frame or the art lands sideways.
#
# frame = (normal, +u direction, +v direction). Derived by standing outside the face
# looking in: +u runs to the viewer's left-to-right, +v runs top-to-bottom. `up`/`down`
# follow the usual "a top texture's north edge is v=0" convention.
FRAME = {
    "north": ((0, 0, -1), (-1, 0, 0), (0, -1, 0)),
    "south": ((0, 0, 1), (1, 0, 0), (0, -1, 0)),
    "east": ((1, 0, 0), (0, 0, -1), (0, -1, 0)),
    "west": ((-1, 0, 0), (0, 0, 1), (0, -1, 0)),
    "up": ((0, 1, 0), (1, 0, 0), (0, 0, 1)),
    "down": ((0, -1, 0), (1, 0, 0), (0, 0, -1)),
}

# 90-degree rotations, as matrices applied to (x, y, z). Only these three are needed.
RX90 = ((1, 0, 0), (0, 0, -1), (0, 1, 0))     # +90 about X: north -> up
RXM90 = ((1, 0, 0), (0, 0, 1), (0, -1, 0))    # -90 about X: north -> down
RY180 = ((-1, 0, 0), (0, 1, 0), (0, 0, -1))   # 180 about Y: north -> south


def apply(m, v):
    return tuple(sum(m[r][c] * v[c] for c in range(3)) for r in range(3))


def neg(v):
    return tuple(-c for c in v)


def direction_of(normal):
    for name, (n, _, _) in FRAME.items():
        if n == normal:
            return name
    raise ValueError(f"not an axis normal: {normal}")


def remap_uv(face_dir, uv, m):
    """Move one face's UV rect through rotation `m`. Returns (new_dir, new_uv, rotation)."""
    normal, u_axis, v_axis = FRAME[face_dir]
    new_dir = direction_of(apply(m, normal))
    u2, v2 = apply(m, u_axis), apply(m, v_axis)
    tu, tv = FRAME[new_dir][1], FRAME[new_dir][2]

    u1, v1, u3, v3 = uv
    if u2 in (tu, neg(tu)):
        # Axes still line up; a reversed axis is just a flipped UV rect, which Minecraft
        # renders mirrored — exactly what a rotation should look like.
        if u2 == neg(tu):
            u1, u3 = u3, u1
        if v2 == neg(tv):
            v1, v3 = v3, v1
        return new_dir, [u1, v1, u3, v3], 0

    # The axes swapped, so the texture has to turn on the face as well. In this pack that
    # only ever happens on the east/west rims, which carry the flat white edge texture, so
    # the exact quarter-turn is not observable — but emit a consistent one anyway.
    rotation = 90 if v2 == tu else 270
    return new_dir, [u1, v1, u3, v3], rotation


def rotate_model(elements, m):
    out = []
    for el in elements:
        a, b = apply(m, el["from"]), apply(m, el["to"])
        faces = {}
        for face_dir, face in el["faces"].items():
            new_dir, uv, rot = remap_uv(face_dir, face["uv"], m)
            new_face = {"uv": [round(c, 5) for c in uv], "texture": face["texture"]}
            if rot:
                new_face["rotation"] = rot
            faces[new_dir] = new_face
        out.append({
            "from": [min(a[i], b[i]) for i in range(3)],
            "to": [max(a[i], b[i]) for i in range(3)],
            "faces": faces,
        })
    return out


def translate(elements, delta):
    for el in elements:
        el["from"] = [round(el["from"][i] + delta[i], 5) for i in range(3)]
        el["to"] = [round(el["to"][i] + delta[i], 5) for i in range(3)]
    return elements


def bounds(elements):
    lo = [min(el["from"][i] for el in elements) for i in range(3)]
    hi = [max(el["to"][i] for el in elements) for i in range(3)]
    return lo, hi


def centre_on(elements, target, axes=(0, 1, 2)):
    """Shift so the bounding box centre sits at `target` on the listed axes."""
    lo, hi = bounds(elements)
    delta = [0.0, 0.0, 0.0]
    for i in axes:
        delta[i] = target[i] - (lo[i] + hi[i]) / 2.0
    return translate(elements, delta)


def thin(elements, axis, factor):
    """Squash the model along one axis, keeping its centre put.

    A card exported at 1 unit thick reads well on its own but stacks into a tower in the
    discard pile, where five of them sit on top of each other. Halving the thickness is a
    geometry change rather than a display scale so the pile keeps its footprint — scaling
    the ItemDisplay would shrink the card's face along with its edge.
    """
    lo, hi = bounds(elements)
    mid = (lo[axis] + hi[axis]) / 2.0
    for el in elements:
        for end in ("from", "to"):
            el[end][axis] = round(mid + (el[end][axis] - mid) * factor, 5)
    return elements


def strip_rims(elements):
    """Drop the four edge faces. The held fan is seen almost head-on at 0.18 scale, where a
    one-pixel rim is sub-pixel — but it is 60 of the model's 86 quads, and the fan bakes
    6800+ models. Keeping the silhouette and the art costs a fifth of the geometry."""
    out = []
    for el in elements:
        faces = {d: f for d, f in el["faces"].items() if d in ("north", "south")}
        if faces:
            out.append({"from": el["from"], "to": el["to"], "faces": faces})
    return out


# ----------------------------------------------------------------- source loading

def load(name):
    """Read one Blockbench export, renaming its texture slots to front/back/sides.

    The exports reference textures by the numeric slot Blockbench happened to assign and
    by bare `sides` (a texture the author never saved), so the raw files cannot be dropped
    into the pack as-is.
    """
    with open(os.path.join(SRC, f"{name}.json"), encoding="utf-8") as fh:
        raw = json.load(fh)

    # Which slot is the face art? Anything that is not the shared back and not the rim.
    role = {}
    for slot, path in raw["textures"].items():
        if slot == "particle":
            continue
        leaf = path.rsplit("/", 1)[-1]
        role[slot] = SIDES if path == SIDES else (BACK if leaf == BACK else "front")

    elements = json.loads(json.dumps(raw["elements"]))
    for el in elements:
        for face in el["faces"].values():
            face["texture"] = "#" + role[face["texture"][1:]]
    return elements


def textures(front):
    return {
        "front": f"{TEX_NS}/{front}",
        "back": f"{TEX_NS}/{BACK}",
        "sides": f"{TEX_NS}/{SIDES}",
        "particle": f"{TEX_NS}/{front}",
    }


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(data, fh, separators=(",", ":"))


def item_def(name, model):
    write(os.path.join(ITEMS, f"{name}.json"),
          {"model": {"type": "minecraft:model", "model": f"uno:item/{model}"}})


# ----------------------------------------------------------------- output

def main():
    if not os.path.isdir(SRC):
        raise SystemExit(f"No card geometry at {SRC} — that folder holds the Blockbench "
                         f"exports this script derives every model from.")

    names = sorted(os.path.splitext(f)[0] for f in os.listdir(SRC) if f.endswith(".json"))
    cards = [n for n in names if n not in DECKS]
    if not cards:
        raise SystemExit(f"No card models found in {SRC}.")

    # Regenerate from scratch: a card removed from the source must not linger in the pack.
    for d in ("cards", "cards_flat", "cards_held"):
        shutil.rmtree(os.path.join(MODELS, d), ignore_errors=True)
    for f in os.listdir(ITEMS):
        stem = os.path.splitext(f)[0]
        if stem in DECKS or stem.startswith(("flat_", "down_")) or stem in names or stem == BACK:
            os.remove(os.path.join(ITEMS, f))

    # `back` has art but no geometry of its own — it is a card whose face is the reverse.
    # PileRenderer and the debug commands both expect it to exist.
    geometry = {name: load(name) for name in names}
    geometry[BACK] = load(cards[0])
    for name in DECKS:
        # The exports carry a leftover wild_draw4 on the face. A draw pile shows its back
        # on both the top and the bottom of the stack.
        for el in geometry[name]:
            for face in el["faces"].values():
                if face["texture"] == "#front":
                    face["texture"] = "#back"

    for name in cards + [BACK]:
        # Upright: 180 about Y puts the face on +Z (the plugin's convention), then the
        # bottom-centre goes to the origin so an ItemDisplay can roll the card around Z.
        up = rotate_model(geometry[name], RY180)
        centre_on(up, (0, 0, 0), axes=(0, 2))
        translate(up, (0, -bounds(up)[0][1], 0))
        write(os.path.join(MODELS, "cards", f"{name}.json"), {
            "texture_size": [128, 192],
            "textures": textures(name),
            "elements": up,
            "display": {
                "gui": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
                "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
            },
        })
        item_def(name, f"cards/{name}")

        # Held: same facing, but pivoting on (8, 8, 8) — the point display transforms turn
        # about — so the fan radiates from the bottom edge of every card. Nobody holds the
        # reverse of a card, so `back` gets no fan model.
        if name != BACK:
            held = rotate_model(geometry[name], RY180)
            centre_on(held, (8, 0, 8), axes=(0, 2))
            translate(held, (0, 8 - bounds(held)[0][1], 0))
            write(os.path.join(MODELS, "cards_held", f"{name}.json"),
                  {"texture_size": [128, 192], "textures": textures(name),
                   "elements": strip_rims(held)})

        # Flat: +90 about X lands the face upward, -90 lands it downward. Centred on
        # (8, 8, 8) so the card lies exactly where the ItemDisplay is spawned.
        for prefix, matrix in (("", RX90), ("down_", RXM90)):
            flat = rotate_model(geometry[name], matrix)
            thin(flat, 1, FLAT_THICKNESS)  # Y is the thickness once the card is lying down
            centre_on(flat, (8, 8, 8))
            write(os.path.join(MODELS, "cards_flat", f"{prefix}{name}.json"),
                  {"texture_size": [128, 192], "textures": textures(name), "elements": flat})
            item_def(f"{'down' if prefix else 'flat'}_{name}", f"cards_flat/{prefix}{name}")

    for name in DECKS:
        # The deck sits ON the felt rather than straddling it, so its underside — not its
        # centre — lines up with where a single flat card would lie.
        deck = rotate_model(geometry[name], RX90)
        centre_on(deck, (8, 8, 8), axes=(0, 2))
        translate(deck, (0, 8 - bounds(deck)[0][1], 0))
        write(os.path.join(MODELS, "cards_flat", f"{name}.json"),
              {"texture_size": [128, 192], "textures": textures(BACK), "elements": deck})
        item_def(name, f"cards_flat/{name}")

    print(f"Cards: {len(cards)} (+back)  Decks: {len(DECKS)}")
    print(f"Models: {len(cards + [BACK]) * 4 + len(DECKS)}  Items: {len(cards + [BACK]) * 3 + len(DECKS)}")


if __name__ == "__main__":
    main()
