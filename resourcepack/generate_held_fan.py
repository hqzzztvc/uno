#!/usr/bin/env python3
"""
Generate the DYNAMIC held "hand of cards" item (uno:held) for the main hand.

The fan shows ALL the cards the player holds at once. As the hand grows it COMPRESSES
(cards tilt closer together / overlap more) so everything fits on screen — like holding
a thick stack of real cards. This is done with discrete "density" tiers: each tier is a
per-card angular step; the plugin picks the tier from the card count and sets it (plus
the card and whether it's selected) into custom_model_data per slot.

custom_model_data string per slot k:
    "<card>_<tier>"        normal card in that slot at that density tier
    "<card>_<tier>_sel"    the selected card (rendered front-most, in place)
    ""                     empty slot

Fan tilt comes from each model's DISPLAY-TRANSFORM rotation (fine angles), so the cards
radiate from one bottom pivot. No world entities -> no lag, no clipping.
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CARD_DIR = os.path.join(HERE, "assets/minecraft/textures/item/cards")
MODELS = os.path.join(HERE, "assets/uno/models/item/held")
ITEM = os.path.join(HERE, "assets/uno/items/held.json")
HAND_MANAGER = os.path.join(
    HERE, "..", "src", "main", "java", "com", "unoplugin", "hand", "HandManager.java")


def java_constants():
    """Read MAX_SLOTS and DENSITIES straight out of HandManager.java.

    These two values have to agree exactly between the plugin and this generator: the
    plugin writes "<card>_<tier>" into custom_model_data and the pack has to have a model
    at that name. Keeping a second copy here meant changing one and silently getting empty
    or wrong-angle cards, so the Java file is the single source of truth and this script
    parses it.
    """
    try:
        with open(HAND_MANAGER, encoding="utf-8") as fh:
            src = fh.read()
    except OSError as exc:
        sys.exit(f"Cannot read {HAND_MANAGER}: {exc}\n"
                 "Run this script from inside the repo — it reads the plugin's constants.")

    slots = re.search(r"MAX_SLOTS\s*=\s*(\d+)", src)
    densities = re.search(r"DENSITIES\s*=\s*\{([^}]*)\}", src)
    if not slots or not densities:
        sys.exit("Could not find MAX_SLOTS / DENSITIES in HandManager.java — if they were "
                 "renamed, update java_constants() here to match.")

    values = [float(v) for v in densities.group(1).replace(" ", "").split(",") if v]
    return int(slots.group(1)), values


MAX_SLOTS, DENSITIES = java_constants()
S = (MAX_SLOTS - 1) // 2
print(f"HandManager.java: MAX_SLOTS={MAX_SLOTS} DENSITIES={DENSITIES}")
BASE_TILT_Z = -12.0          # lean the whole fan slightly right
DEPTH_STEP = 0.04            # SMALL monotonic step -> subtle left-front overlap, no giant card
SEL_FRONT = 0.5             # selected sits at this fixed front depth -> always on top, readable

# Locked first-person placement (fanpos 4, slightly lowered).
FP_TILT_X = 0
FP_TRANS = [-2.0, 2.0, 0.5]
FP_SCALE = 0.29
TP_TRANS = [0.0, 1.0, 1.5]
TP_SCALE = 0.42

EXCLUDE = {"back", "blank"}


def card_textures(card):
    return {
        "front": f"minecraft:item/cards/{card}",
        "back": "minecraft:item/cards/back",
        "particle": f"minecraft:item/cards/{card}",
    }


def slot_depth(k):
    # Monotonic: a higher slot (further LEFT on screen) is further forward, so the
    # left-most card is in front and each card to its right sits behind it (1<2<3<4<5).
    # Centred on the middle slot so the fan's overall size doesn't change with count.
    return (k - S) * DEPTH_STEP


def display(theta, depth):
    fp_tr = [FP_TRANS[0], FP_TRANS[1], FP_TRANS[2] + depth]
    tp_tr = [TP_TRANS[0], TP_TRANS[1], TP_TRANS[2] + depth]
    return {
        "firstperson_righthand": {"rotation": [FP_TILT_X, 0, theta], "translation": fp_tr, "scale": [FP_SCALE, FP_SCALE, FP_SCALE]},
        "thirdperson_righthand": {"rotation": [0, 0, theta], "translation": tp_tr, "scale": [TP_SCALE, TP_SCALE, TP_SCALE]},
        "gui": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
    }


def card_model(card, theta, depth):
    elem = {
        "from": [3, 8, 7.5],
        "to": [13, 23, 8.5],
        "faces": {
            "south": {"uv": [0, 0, 16, 16], "texture": "#front"},
            "north": {"uv": [0, 0, 16, 16], "texture": "#back"},
        },
    }
    return {"textures": card_textures(card), "elements": [elem], "display": display(theta, depth)}


def main():
    cards = sorted(
        os.path.splitext(f)[0]
        for f in os.listdir(CARD_DIR)
        if f.endswith(".png") and os.path.splitext(f)[0] not in EXCLUDE
    )

    os.makedirs(MODELS, exist_ok=True)
    with open(os.path.join(MODELS, "empty.json"), "w") as f:
        json.dump({"elements": []}, f)

    for di, step in enumerate(DENSITIES):
        for k in range(MAX_SLOTS):
            theta = (k - S) * step + BASE_TILT_Z
            depth = slot_depth(k)
            d = os.path.join(MODELS, f"d{di}", f"slot{k}")
            os.makedirs(d, exist_ok=True)
            for card in cards:
                with open(os.path.join(d, f"{card}.json"), "w") as f:
                    json.dump(card_model(card, theta, depth), f)
                with open(os.path.join(d, f"{card}_sel.json"), "w") as f:
                    # selected sits at a fixed front depth -> always on top, consistent size
                    json.dump(card_model(card, theta, SEL_FRONT), f)

    empty_ref = {"type": "minecraft:model", "model": "uno:item/held/empty"}

    def select(k):
        cases = []
        for di in range(len(DENSITIES)):
            for card in cards:
                base = f"uno:item/held/d{di}/slot{k}"
                cases.append({"when": f"{card}_{di}", "model": {"type": "minecraft:model", "model": f"{base}/{card}"}})
                cases.append({"when": f"{card}_{di}_sel", "model": {"type": "minecraft:model", "model": f"{base}/{card}_sel"}})
        return {"type": "minecraft:select", "property": "minecraft:custom_model_data", "index": k, "fallback": empty_ref, "cases": cases}

    item = {"model": {"type": "minecraft:composite", "models": [select(k) for k in range(MAX_SLOTS)]}}
    os.makedirs(os.path.dirname(ITEM), exist_ok=True)
    with open(ITEM, "w") as f:
        json.dump(item, f)

    total = len(DENSITIES) * MAX_SLOTS * len(cards) * 2 + 1
    print(f"Slots: {MAX_SLOTS}  Tiers: {DENSITIES}  Cards: {len(cards)}  Models: {total}")


if __name__ == "__main__":
    main()
