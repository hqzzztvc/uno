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

Every one of those thousands of models is nothing but a display transform on top of the
same card, so each PARENTS the shared `uno:item/cards_held/<card>` that
generate_card_models.py derives from the Blockbench source. Inlining the 3D card instead
would repeat its geometry 6800 times in a pack the client bakes in full at load.
"""
import json
import os
import re
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "uno_json")
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

# Locked first-person placement (fanpos 4, slightly lowered). The card is 24 model units
# tall where the old flat quad was 15, so both scales carry a 15/24 correction and the fan
# keeps exactly the on-screen size it was tuned to.
FP_TILT_X = 0
FP_TRANS = [-2.0, 2.0, 0.5]
FP_SCALE = 0.18125           # 0.29 * 15/24
TP_TRANS = [0.0, 1.0, 1.5]
TP_SCALE = 0.2625            # 0.42 * 15/24

# The card now has real thickness, and every card in the fan pivots on the same bottom
# point, so any depth step under one card thickness makes neighbours intersect and show
# their cross-section. Display translations are not scaled by the transform's scale, so
# a 1-unit-thick card needs a step of at least `scale` to clear the card behind it.
DEPTH_STEP = round(max(FP_SCALE, TP_SCALE), 4)
SEL_FRONT = round(DEPTH_STEP * ((MAX_SLOTS - 1) // 2 + 1), 4)  # clear of the whole fan

EXCLUDE = {"back", "blank"}
DECKS = ("deck_10", "deck_50", "deck_100")


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
    return {"parent": f"uno:item/cards_held/{card}", "display": display(theta, depth)}


def main():
    cards = sorted(
        os.path.splitext(f)[0]
        for f in os.listdir(SRC)
        if f.endswith(".json") and os.path.splitext(f)[0] not in EXCLUDE | set(DECKS)
    )
    if not cards:
        sys.exit(f"No card geometry in {SRC} — run generate_card_models.py first; the fan "
                 f"parents the models it writes to assets/uno/models/item/cards_held/.")

    # Regenerate from scratch: a slot count or tier that shrank must not leave orphans.
    shutil.rmtree(MODELS, ignore_errors=True)
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
