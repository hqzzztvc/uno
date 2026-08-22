#!/usr/bin/env python3
"""
Bake the flat "Let It Ride" playing mat from the upright logo model.

`assets/uno/models/item/letitride.json` is hand-authored (Blockbench) and is the one
exception to "nothing under assets/uno/ is written by hand" — it is the logo art's own
geometry, a 32x32 quad half a unit thick with the logo on `north` and the shared card rim
texture on the four edges. This script derives the *lying-down* version the plugin actually
places on a table, and nothing else reads the upright one.

Why bake the rotation instead of rotating the ItemDisplay, as with the cards: a render-time
rotation swings the model off its spawn point (see PileRenderer). The mat is 2.9 blocks
across, so a rotation about the wrong centre doesn't nudge it — it throws it off the table
entirely.

Two conventions the plugin depends on, both set here:

  * X/Z centred on 8, so the mat lands centred on the ItemDisplay wherever it is spawned.
  * UNDERSIDE at y=8, not the centre — the same trick `generate_card_models.py` plays on the
    deck models. The display origin is model (8, 8, 8), so a mat whose bottom face is at 8
    sits flush on the felt at any scale, and its top face is exactly its thickness above the
    spawn point. `TableMat.THICKNESS` in the Java is that number; the piles are lifted by it
    so they sit ON the mat rather than through it.

The +90 about X puts the logo face upward and leaves its top edge pointing at local +Z,
which is the table's FAR side once the display is spawned at the table's yaw — so the logo
reads the right way up from seat 0, the side the table was placed from.

    py generate_table_mat.py
"""
import json
import os

from generate_card_models import (
    MODELS, RX90, bounds, centre_on, item_def, rotate_model, translate, write,
)

SOURCE = os.path.join(MODELS, "letitride.json")
NAME = "letitride_mat"


def main():
    if not os.path.isfile(SOURCE):
        raise SystemExit(f"No mat geometry at {SOURCE} — that is the hand-authored upright "
                         f"logo model this script lays flat.")

    with open(SOURCE, encoding="utf-8") as fh:
        raw = json.load(fh)

    flat = rotate_model(raw["elements"], RX90)
    centre_on(flat, (8, 0, 8), axes=(0, 2))
    translate(flat, (0, 8 - bounds(flat)[0][1], 0))

    lo, hi = bounds(flat)
    write(os.path.join(MODELS, f"{NAME}.json"), {
        "texture_size": raw.get("texture_size", [16, 16]),
        "textures": raw["textures"],
        "elements": flat,
    })
    item_def(NAME, NAME)

    print(f"uno:{NAME}  {hi[0] - lo[0]:g} x {hi[2] - lo[2]:g} units, "
          f"{hi[1] - lo[1]:g} thick, underside at y={lo[1]:g}")


if __name__ == "__main__":
    main()
