#!/usr/bin/env bash
# Generates a single HELD "hand of cards" model (uno:hand): the given cards fanned
# into one item the player actually holds in their main hand. Uses model element
# rotations (limited to 22.5deg steps) around a bottom pivot to fan them, plus a
# per-card horizontal/depth offset so they spread and layer like a real hand.
# Display transforms place the fan in the first-person & third-person hand.
#
# Usage: ./generate_hand_model.sh [card1 card2 ...]   (defaults to a 7-card hand)
set -euo pipefail
cd "$(dirname "$0")"

ITEMS_DIR="assets/uno/items"
MODELS_DIR="assets/uno/models/item"
mkdir -p "$ITEMS_DIR" "$MODELS_DIR"

if [ "$#" -gt 0 ]; then
  CARDS=("$@")
else
  CARDS=(red_1 yellow_5 green_skip blue_9 red_draw2 wild green_3)
fi

python3 - "${CARDS[@]}" <<'PY'
import json, sys
cards = sys.argv[1:]
n = len(cards)
c = (n - 1) / 2.0

# Allowed model element rotation angles (Minecraft restricts to 22.5 steps).
ALLOWED = [-45, -22.5, 0, 22.5, 45]
def snap(a):
    return min(ALLOWED, key=lambda x: abs(x - a))

textures = {"back": "minecraft:item/cards/back", "particle": "minecraft:item/cards/" + cards[0]}
elements = []
SPREAD = 45.0  # half-fan in degrees (before snapping)
for i, name in enumerate(cards):
    key = f"c{i}"
    textures[key] = "minecraft:item/cards/" + name
    t = (c - i) / max(c, 1e-6)          # +1 (left) .. -1 (right)
    angle = snap(t * SPREAD)
    xoff = (i - c) * 0.8                 # spread bases so equal-angle cards separate
    zoff = i * 0.06                      # layer front-to-back, no z-fighting
    elements.append({
        "from": [3 + xoff, 1, 7.5 + zoff],
        "to":   [13 + xoff, 16, 8.5 + zoff],
        "rotation": {"origin": [8 + xoff, 1, 8 + zoff], "axis": "z", "angle": angle},
        "faces": {
            "south": {"uv": [0, 0, 16, 16], "texture": "#" + key},
            "north": {"uv": [0, 0, 16, 16], "texture": "#back"},
        },
    })

model = {
    "textures": textures,
    "elements": elements,
    "display": {
        # Tuned from screenshots — held fan in front of the player, lower-right.
        "firstperson_righthand": {"rotation": [10, 0, 0],  "translation": [-2.0, 5.0, 0.0], "scale": [0.4, 0.4, 0.4]},
        "thirdperson_righthand": {"rotation": [0, 0, 0],   "translation": [0, 3.0, 1.5],   "scale": [0.55, 0.55, 0.55]},
        "gui":                   {"rotation": [0, 0, 0],   "translation": [0, 0, 0],       "scale": [1, 1, 1]},
        "fixed":                 {"rotation": [0, 0, 0],   "translation": [0, 0, 0],       "scale": [1, 1, 1]},
    },
}

with open("assets/uno/models/item/hand.json", "w") as f:
    json.dump(model, f, indent=2)
with open("assets/uno/items/hand.json", "w") as f:
    json.dump({"model": {"type": "minecraft:model", "model": "uno:item/hand"}}, f, indent=2)

print(f"Generated held hand model with {n} cards: {', '.join(cards)}")
PY
