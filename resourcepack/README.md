# Legally Not Uno Textures

**The cards for the Legally Not Uno plugin: a full multiplayer card game, played at a table inside Minecraft.**

This pack is the client-side half of the Legally Not Uno plugin for Paper. The server runs the game, and this
pack is what lets you see it: the cards in your hand, the pile you play onto and the deck you draw from.

---

## What's in it

- **All 54 card faces:** 0–9, Skip, Reverse and Draw Two in red, yellow, green and blue, plus Wild
  and Wild Draw Four. Each is drawn at 128 × 192, and they share one card back.
- **Your hand, held as a fan.** Your cards are spread in an arc in first person. The card you've
  selected lifts out of the fan, so you can see what you're about to play.
- **A fan that stays on screen.** Up to 21 cards show at once, and the fan tightens as your hand
  grows so it doesn't run off the edge. If you hold more than 21, the fan scrolls to follow your selection.
- **Cards on the table.** Played cards land face-up in the middle of the table, where the last five
  stay stacked in a small heap.
- **A draw pile that shrinks.** The deck sits on the table as a solid block of cards and gets
  thinner as players draw from it.
- **3D cards.** The cards on the table have thickness, rounded corners, white edges and the card
  back on the underside.

## Compatibility

| | |
|---|---|
| Minecraft | Java Edition **26.2** (pack format 88) |
| Server | Must be running the Legally Not Uno plugin |
| Replaces vanilla textures? | **No.** Everything in the pack is new, so it works alongside any other pack, in any order |
| Mods | None needed |

## Installing

**If the server offers the pack when you join:** accept it. You don't need to do anything else.

**To install it yourself:**

1. Download `Legally Not Uno Textures.zip`.
2. Put the zip in your `.minecraft/resourcepacks/` folder. Don't unzip it. To find the folder, go to
   **Options → Resource Packs → Open Pack Folder**.
3. In **Options → Resource Packs**, move **Legally Not Uno Textures** to the **Selected** column
   and click **Done**.

The pack's icon in the list is the card back.

## For server owners

The plugin doesn't host the pack, but it can offer it to players when they join. Upload the zip to
any host that serves a direct download link, then fill in `plugins/LegallyNotUno/config.yml`:

```yaml
resource-pack:
  url:
    link: "https://example.com/legally-not-uno-textures.zip"
    sha1: "paste the zip's SHA-1 here"
  require: false   # true kicks players who decline the pack
  prompt: "<yellow>Legally Not Uno needs this resource pack for the card textures. Please accept!"
```

Run `/uno reload`. Players who join after that are offered the pack.

- **Set `sha1`.** Without it, players download the pack again every time they join. `require`
  and `prompt` also have no effect until `sha1` is set.
- **Update `sha1` whenever you replace the zip.** If the hash doesn't match the file, players' clients reject the download.
- To get the hash, run:
  - Linux: `sha1sum legally-not-uno-textures.zip`
  - macOS: `shasum legally-not-uno-textures.zip`
  - Windows: `certutil -hashfile legally-not-uno-textures.zip SHA1`

## Disclaimer

Legally Not Uno Textures is a fan-made pack. It is not affiliated with, endorsed by or sponsored by
Mattel. UNO is a trademark of Mattel, Inc.

## License

GNU General Public License v3.0