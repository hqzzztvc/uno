#!/usr/bin/env python3
"""
Generate a custom resource-pack FONT for rendering a player's UNO hand as a flat
2D fan on the HUD (no world entities -> no lag, no clipping).

For every playable card we pre-render the card rotated about its BOTTOM-CENTRE pivot
at a range of fan angles, each composited onto a fixed-size canvas so the pivot sits
at the SAME spot in every glyph. Drawing several of these glyphs at one screen point
(using a negative-space font to reset the cursor) therefore produces a true fan that
radiates from a single bottom pivot. A raised + enlarged "selected" variant is also
produced for the highlighted card.

Outputs:
  assets/uno/textures/font/cards/eXXXX.png   (one glyph per card+angle, + selected)
  assets/uno/font/hand.json                  (font: space provider + bitmap providers)
  ../src/main/resources/hand_font.json        (manifest the plugin reads to lay out)
"""
import json
import math
import os

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
CARD_DIR = os.path.join(HERE, "assets/minecraft/textures/item/cards")
GLYPH_DIR = os.path.join(HERE, "assets/uno/textures/font/cards")
FONT_JSON = os.path.join(HERE, "assets/uno/font/hand.json")
MANIFEST = os.path.abspath(os.path.join(HERE, "../src/main/resources/hand_font.json"))

# --- Canvas / fan geometry --------------------------------------------------
CANVAS_W, CANVAS_H = 128, 88
PIVOT = (64, 78)              # bottom-centre pivot, shared by every glyph
ANGLES = list(range(-42, 43, 6))   # -42..42 step 6  -> 15 fan slots
SEL_SCALE = 1.15             # selected card enlarged
SEL_RAISE = 14               # selected card lifted up (texture px)
SUPERSAMPLE = 3              # render big then downscale for clean rotated edges

# --- Font render size (tunable; re-run after changing) ----------------------
HEIGHT = 72                  # glyph render height in GUI px
ASCENT = 44                  # vertical placement above the baseline

EXCLUDE = {"back", "blank"}
SPACE_MAGS = [256, 128, 64, 32, 16, 8, 4, 2, 1]

os.makedirs(GLYPH_DIR, exist_ok=True)
os.makedirs(os.path.dirname(FONT_JSON), exist_ok=True)
os.makedirs(os.path.dirname(MANIFEST), exist_ok=True)

scale = HEIGHT / CANVAS_H


def advance_for(right_col):
    """GUI-px advance Minecraft uses for a glyph whose content ends at right_col."""
    return round((right_col + 1) * scale) + 1


def render(card_img, angle_deg, sel=False):
    """Return (canvas RGBA 128x88, left_col, right_col) for one rotated/selected glyph."""
    ss = SUPERSAMPLE
    big = Image.new("RGBA", (CANVAS_W * ss, CANVAS_H * ss), (0, 0, 0, 0))
    px, py = PIVOT[0] * ss, PIVOT[1] * ss

    card = card_img
    if sel:
        w = round(card.width * SEL_SCALE)
        h = round(card.height * SEL_SCALE)
        card = card.resize((w, h), Image.NEAREST)
    cw, ch = card.width * ss, card.height * ss
    card_ss = card.resize((cw, ch), Image.NEAREST)

    # paste so the card's bottom-centre lands on the pivot (selected also lifted)
    lift = SEL_RAISE * ss if sel else 0
    ox = px - cw // 2
    oy = py - ch - lift
    big.alpha_composite(card_ss, (ox, oy))

    if not sel and angle_deg != 0:
        big = big.rotate(angle_deg, resample=Image.BICUBIC, center=(px, py))

    canvas = big.resize((CANVAS_W, CANVAS_H), Image.LANCZOS)
    bbox = canvas.split()[3].getbbox()
    if bbox is None:
        return canvas, 0, 0
    left_col, _, right, _ = bbox
    return canvas, left_col, right - 1


def main():
    cards = sorted(
        os.path.splitext(f)[0]
        for f in os.listdir(CARD_DIR)
        if f.endswith(".png") and os.path.splitext(f)[0] not in EXCLUDE
    )

    providers = []
    manifest_cards = {}
    cp = 0xE000

    for card in cards:
        src = Image.open(os.path.join(CARD_DIR, card + ".png")).convert("RGBA")
        angle_entries = []
        for ang in ANGLES:
            canvas, _lc, rc = render(src, ang)
            name = f"e{cp:04x}"
            canvas.save(os.path.join(GLYPH_DIR, name + ".png"))
            ch = chr(cp)
            providers.append({
                "type": "bitmap",
                "file": f"uno:font/cards/{name}.png",
                "ascent": ASCENT,
                "height": HEIGHT,
                "chars": [ch],
            })
            angle_entries.append({"deg": ang, "char": ch, "adv": advance_for(rc)})
            cp += 1

        # selected (upright, enlarged, raised)
        canvas, _lc, rc = render(src, 0, sel=True)
        name = f"e{cp:04x}"
        canvas.save(os.path.join(GLYPH_DIR, name + ".png"))
        ch = chr(cp)
        providers.append({
            "type": "bitmap",
            "file": f"uno:font/cards/{name}.png",
            "ascent": ASCENT,
            "height": HEIGHT,
            "chars": [ch],
        })
        manifest_cards[card] = {
            "sel": {"char": ch, "adv": advance_for(rc)},
            "angles": angle_entries,
        }
        cp += 1

    # negative-space provider (cursor control)
    space_cp = 0xE800
    advances = {}
    space_map = []
    for mag in SPACE_MAGS:
        for sign in (+1, -1):
            ch = chr(space_cp)
            advances[ch] = mag * sign
            space_map.append({"adv": mag * sign, "char": ch})
            space_cp += 1
    providers.insert(0, {"type": "space", "advances": advances})

    with open(FONT_JSON, "w") as f:
        json.dump({"providers": providers}, f, indent=2)

    manifest = {
        "font": "uno:hand",
        "meta": {
            "canvasW": CANVAS_W, "canvasH": CANVAS_H,
            "pivotX": PIVOT[0], "pivotY": PIVOT[1],
            "height": HEIGHT, "ascent": ASCENT,
        },
        "space": space_map,
        "cards": manifest_cards,
    }
    with open(MANIFEST, "w") as f:
        json.dump(manifest, f, indent=2)

    print(f"Cards: {len(cards)}  Angles: {len(ANGLES)}  Glyphs: {cp - 0xE000}")
    print(f"Font:     {os.path.relpath(FONT_JSON, HERE)}")
    print(f"Manifest: {os.path.relpath(MANIFEST, HERE)}")


if __name__ == "__main__":
    main()
