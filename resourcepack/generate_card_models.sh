#!/usr/bin/env bash
# Generates uno: item-model definitions + 2-sided card models for every card PNG.
# Each card is a thin cuboid with a true 2:3 ratio (10 wide x 15 tall), the card
# face on the front and back.png on the reverse — so cards keep card proportions
# and have a real back you can see from behind.
# Textures stay at assets/minecraft/textures/item/cards/<name>.png (your art workflow
# is unchanged). Rerun this whenever you add new card PNGs.
set -euo pipefail
cd "$(dirname "$0")"

TEX_DIR="assets/minecraft/textures/item/cards"
ITEMS_DIR="assets/uno/items"
MODELS_DIR="assets/uno/models/item/cards"

mkdir -p "$ITEMS_DIR" "$MODELS_DIR"

count=0
for png in "$TEX_DIR"/*.png; do
  [ -e "$png" ] || continue
  name="$(basename "$png" .png)"

  # Item-model definition: uno:<name>  ->  uno:item/cards/<name>
  cat > "$ITEMS_DIR/$name.json" <<EOF
{
  "model": {
    "type": "minecraft:model",
    "model": "uno:item/cards/$name"
  }
}
EOF

  # 2-sided card model: front = this card's face, back = back.png.
  # Element 10 wide x 15 tall x 1 thick -> true 2:3 ratio, with enough depth
  # that back.png cleanly shows from behind (no front bleed-through).
  # Origin (0,0,0) sits at the card's BOTTOM-CENTRE so an ItemDisplay can roll
  # each card around Z to fan them out from a single pivot (hand of cards).
  cat > "$MODELS_DIR/$name.json" <<EOF
{
  "textures": {
    "front": "minecraft:item/cards/$name",
    "back": "minecraft:item/cards/back",
    "particle": "minecraft:item/cards/$name"
  },
  "elements": [
    {
      "from": [-5, 0, -0.5],
      "to": [5, 15, 0.5],
      "faces": {
        "south": { "uv": [0, 0, 16, 16], "texture": "#front" },
        "north": { "uv": [0, 0, 16, 16], "texture": "#back" }
      }
    }
  ],
  "display": {
    "gui":   { "rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1] },
    "fixed": { "rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1] }
  }
}
EOF

  count=$((count + 1))
done

echo "Generated item + model JSON for $count cards."
