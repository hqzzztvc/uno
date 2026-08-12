# UNO

A fully playable multiplayer UNO card game for **Paper 26.2** — placeable casino tables with sittable
stools and a fish dealer, a real 3D card fan held in your hand, card piles that sit on the felt for
everyone to see, and an optional "Let It Ride" mode where you wager items on a hand.

No mods. Everything is built from vanilla display entities plus a client-side resource pack.

---

## Requirements

| | |
|---|---|
| Server | Paper **26.2** (Folia not supported) |
| Java | **25** |
| Client | The `UNO-pack.zip` resource pack, installed manually |

## Installing

1. Drop `uno-1.0.jar` into your server's `plugins/` folder and start the server once.
2. Build the resource pack and install it on each **client**:

   ```bash
   cd resourcepack
   zip -qr ../UNO-pack.zip pack.mcmeta assets -x '*.DS_Store'
   ```

   Copy `UNO-pack.zip` into `.minecraft/resourcepacks/` and enable it in **Options → Resource Packs**.

> **Note:** the plugin does not currently serve the pack to players. The `resource-pack` section in
> `config.yml` is logged on startup but not implemented — the zip has to reach clients some other way.
> Without the pack, every card renders as a blank sheet of paper.

## Playing

Get a table, place it, sit down, deal:

```
/uno give table        # admin — puts a Casino Table item in your inventory
                       # right-click the ground to place it (needs ~4 blocks of clearance)
                       # right-click a stool to sit, Shift to stand
/uno start [bots]      # deal a hand to everyone seated at your table
```

You need at least 2 players; a casino table seats 6. Bots can fill the empty chairs.

### Controls

Your hand appears as a fan of cards held in your main hand. While it's up, the plugin owns your
controls — you can't break or place blocks.

| Input | Action |
|---|---|
| **A** / **D**, or scroll wheel | Move the selection along the fan |
| **Left-click** or **Q** | Play the selected card |
| **Right-click** or **F** | Draw a card |

The bossbar shows the top card, the active colour, whose turn it is, the direction of play and how
many cards are left in the deck. Playing a wild opens a colour picker; closing it without choosing
picks the colour you hold most of. As your hand grows, the fan compresses so every card stays visible.

Standard rules: 108-card deck, 7 cards each, match colour / number / symbol, Skip, Reverse (a Skip in
a 2-player game), +2, Wild and Wild +4. Empty your hand to win. If you disconnect mid-hand, you drop
out and the others play on.

## Gambling — "Let It Ride"

Wager real items on a hand. **Winner takes the whole pot.**

```
/gamble            # open (or join) the ante at your table
                   # then drop items onto the table to stake them
/gamble ready      # lock your stake in — the hand deals when everyone is ready
/gamble pot        # see what's on the line and who's in
/gamble out        # pull out and take your stake back (before the deal only)
/gamble go         # host: start now, refunding anyone still deciding
/gamble cancel     # host: call it off, refund everyone
```

Win the hand and you choose: **cash out**, or **let it ride** — leave the whole pot in for another
hand and make the table match it to challenge you. No takers within the challenge window and you walk
away with it.

The rules that give the mode its teeth:

- **Quitting mid-hand is forfeiting.** Your stake stays in the pot for whoever wins — otherwise
  disconnecting would be a free undo on a losing bet. Leaving during the ante, before cards are
  dealt, refunds you normally.
- **Nothing is ever held only in memory.** Staked items are written to `plugins/UNO/escrow.yml` the
  instant they leave your inventory, so a crash, a `kill -9` or a power cut still returns them.
  Anything owed to an offline player is handed over on their next login.
- The pot on the table is a *display*, not dropped items — it can't despawn, be hoovered by a hopper,
  or be grabbed by someone walking past.
- Bots never collect. A bot win is a push and everyone is refunded.
- UNO's own cards and table items can't be put in a pot.

## Commands

| Command | Permission | |
|---|---|---|
| `/uno start [bots]` | `uno.play` | Deal a hand to everyone seated at your table |
| `/uno version` | `uno.play` | Plugin version |
| `/gamble …` | `uno.gamble` | Wagering — see above (aliases: `/bet`, `/ante`, `/letitride`) |
| `/uno give table` | `uno.admin` | Get a placeable Casino Table |
| `/uno remove` | `uno.admin` | Remove the nearest table within 5 blocks |
| `/uno reload` | `uno.admin` | Reload `config.yml` |
| `/uno play [bots]` | `uno.admin` | Solo test game against bots, no table needed |
| `/uno fan [cards…]`, `/uno fanclear` | `uno.admin` | Debug: show/hide a hand fan |
| `/uno hand` | `uno.admin` | Debug: the static 7-card hand item |
| `/uno testcards [cards…]`, `/uno cleartest` | `uno.admin` | Debug: spawn/remove a card gallery |

Defaults: `uno.play` and `uno.gamble` are on for everyone, `uno.admin` is op-only.

## Configuration

`plugins/UNO/config.yml`. **Only the `gambling.*` keys are currently implemented** — `game.*`,
`tables.*`, `dealer.*`, `debug` and `resource-pack.*` are placeholders for planned features and have
no effect yet.

```yaml
gambling:
  enabled: true
  ante-seconds: 300          # ante expires and refunds after this (0 = never)
  ride:
    enabled: true            # offer the winner cash-out vs. let-it-ride
    window-seconds: 20       # winner's decision window (no answer = cash out)
    challenge-seconds: 90    # how long the table has to match a riding pot
```

State the plugin persists, in `plugins/UNO/`:

- `tables.yml` — placed tables (position, facing, type).
- `escrow.yml` — items held for staked or unfinished bets.

## Building from source

```bash
mvn clean package       # -> target/uno-1.0.jar
```

Needs JDK 25 and Maven. `paper-api` is a `provided` dependency, supplied by the server at runtime.
There is no test suite — changes are verified by running a Paper 26.2 server.

### Repository layout

```
src/main/java/com/unoplugin/
  UnoPlugin.java        plugin entry point, command routing, manager wiring
  game/                 UnoGame (pure rules), Card, Deck, GameManager, PileRenderer
  hand/HandManager      the held 3D card fan + all player input while it's up
  table/                UnoTable/CasinoTable geometry, TableManager (placement, seats, visuals)
  bet/                  BetManager, BetSession, EscrowStore, PotRenderer
  debug/CardTester      throwaway visual test helpers

resourcepack/
  assets/minecraft/textures/item/cards/   the card art (hand-authored PNGs)
  assets/uno/                             item + model definitions (mostly generated)
  generate_card_models.sh                 upright card models, one per PNG
  generate_held_fan.py                    the composite held-fan models (~6800 files)
```

Card art is authored by hand; everything under `assets/uno/` is generated from it by the scripts
above and shouldn't be edited directly. `generate_held_fan.py` shares constants with `HandManager`
(slot count and fan density tiers) — the two have to be changed together.

## Contributing

Two developers work on this repo, so **pull before you start and push when you finish**:

```bash
git pull --rebase origin main
```

See [CLAUDE.md](CLAUDE.md) for the architecture notes, invariants and version-migration TODOs that
matter when changing the code.

## License

GNU General Public License v3.0 — see [LICENSE](LICENSE).
