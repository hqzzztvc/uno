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

   Or host the zip yourself and let the server offer it on join — set `resource-pack.url.link` (and
   `sha1`, so clients cache it) in `config.yml`. The plugin never hosts the pack itself.

> **Note:** without the pack, every card renders as a blank sheet of paper.

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
| **A** / **D**, or scroll wheel | Move the selection along the fan (hold to run along it) |
| **Left-click** or **Q** | Play the selected card |
| **Right-click** or **F** | Draw a card |

The selected card lifts out of the fan so you can see what you're about to play.

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
- **Nothing is ever held only in memory.** Staked items are written to `plugins/UNO/escrow/` the
  instant they leave your inventory, so a crash, a `kill -9` or a power cut still returns them.
  Anything owed to an offline player is handed over on their next login.
- **Every movement is logged.** Stakes, payouts, refunds and forfeits are appended to
  `plugins/UNO/bets.log` with timestamps and UUIDs, so "he took my diamonds" has an answer.
- The pot on the table is a *display*, not dropped items — it can't despawn, be hoovered by a hopper,
  or be grabbed by someone walking past.
- Bots never collect. A bot win is a push and everyone is refunded.
- UNO's own cards and table items can't be put in a pot.

## Commands

All subcommands tab-complete; admin ones are hidden from players who can't use them.

| Command | Permission | |
|---|---|---|
| `/uno help` | `uno.play` | Command list, filtered by what you can run |
| `/uno start [bots]` | `uno.play` | Deal a hand to everyone seated at your table |
| `/uno version` | `uno.play` | Plugin version |
| `/gamble …` | `uno.gamble` | Wagering — see above (aliases: `/bet`, `/ante`, `/letitride`) |
| `/uno give table` | `uno.admin` | Get a placeable Casino Table |
| `/uno remove` | `uno.admin` | Remove the nearest table within 5 blocks (refused mid-hand) |
| `/uno list` | `uno.admin` | Every placed table: id, world, coordinates, occupancy |
| `/uno info` | `uno.admin` | Running hands and open pots |
| `/uno tp <id>` | `uno.admin` | Teleport to a table |
| `/uno end <player\|all>` | `uno.admin` | Force a stuck hand to finish (any pot is refunded) |
| `/uno refund <player\|all>` | `uno.admin` | Hand a stuck pot back to its stakers |
| `/uno reload` | `uno.admin` | Re-read `config.yml` and `messages.yml` |
| `/uno play [bots]` | `uno.admin` | Solo test game against bots, no table needed |

With `debug: true` in `config.yml`, five throwaway development commands also become available:
`/uno fan`, `/uno fanclear`, `/uno hand`, `/uno testcards`, `/uno cleartest`. They spawn per-tick
display entities and are not meant for a live server.

Defaults: `uno.play` and `uno.gamble` are on for everyone, `uno.admin` is op-only.

## Configuration

`plugins/UNO/config.yml` — every key in it is read by the plugin; if setting one changes nothing,
that's a bug. `/uno reload` re-reads it live. Highlights:

```yaml
game:
  starting-hand-size: 7        # clamped so the deal can't empty the deck
  turn-timeout-seconds: 60     # idle player's turn is played for them (0 = never)
tables:
  max-per-world: 0             # cap tables per world (0 = unlimited)
gambling:
  ante-seconds: 300            # ante expires and refunds after this (0 = never)
  audit-log: true              # append every item movement to bets.log
  limits:
    max-pot-items: 0           # cap the pot (0 = unlimited)
    min-ante-items: 1          # minimum buy-in
    block-containers: true     # no staking a shulker box full of netherite
    blacklist: [SHULKER_BOX, BUNDLE]
  ride:
    window-seconds: 20         # winner's decision window (no answer = cash out)
    challenge-seconds: 90      # how long the table has to match a riding pot
effects:
  sounds: true                 # card snaps, turn chimes, the UNO call
  particles: true              # coloured card dust, fireworks over a winner
  volume: 1.0                  # multiplier on every sound (0-2)
```

Player-facing text lives in `plugins/UNO/messages.yml` (MiniMessage formatting) — reword, restyle
or translate anything. Keys you leave out fall back to the copy shipped in the jar.

State the plugin persists, in `plugins/UNO/`:

- `tables.yml` — placed tables (position, facing, type). Tables in worlds that aren't loaded yet are
  preserved verbatim, not dropped.
- `escrow/<uuid>.yml` — items held for staked or unfinished bets, one file per owner.
- `bets.log` — append-only audit trail of every stake, payout, refund and forfeit.

## Building from source

```bash
mvn clean package       # -> target/uno-1.0.jar  (runs the tests)
mvn test                # rules-layer tests only
```

Needs JDK 25 and Maven. `paper-api` is a `provided` dependency, supplied by the server at runtime.

`Card`, `Deck` and `UnoGame` are pure Java with no Bukkit imports, and are covered by JUnit tests in
`src/test/java` — including the invariant that a game always holds exactly one 108-card deck, wherever
the cards happen to be. Everything else needs a running Paper 26.2 server to verify.

### Repository layout

```
src/main/java/com/unoplugin/
  UnoPlugin.java        plugin entry point, manager wiring
  command/              UnoCommand + GambleCommand (routing, permissions, tab completion)
  game/                 UnoGame (pure rules), Card, Deck, GameManager, PileRenderer
  hand/HandManager      the held 3D card fan + all player input while it's up
  table/                UnoTable/CasinoTable geometry, TableManager (placement, seats, visuals)
  bet/                  BetManager, BetSession, EscrowStore, PotRenderer, BetLog
  util/                 Settings (config), Messages (messages.yml), Fx (sound + particles),
                        NameCache, ResourcePackSender
  debug/CardTester      throwaway visual test helpers, gated behind `debug: true`

src/test/java/com/unoplugin/       JUnit tests: the rules layer, plus the fan's density tiers

uno_json/                               the card geometry (hand-authored Blockbench exports)

resourcepack/
  assets/minecraft/textures/item/cards/   the card art (hand-authored PNGs)
  assets/uno/                             item + model definitions (all generated)
  generate_card_models.py                 upright / flat / face-down / deck models + item defs
  generate_held_fan.py                    the composite held-fan models (~6800 files)
```

Two things are authored by hand: the card **art** in `assets/minecraft/textures/item/cards/`, and
the card **geometry** in `uno_json/` — one Blockbench export per card, a rounded-corner slab with
the face on one side and `back.png` on the other, plus `deck_10/50/100` for the draw pile.
Everything under `assets/uno/` is generated from those two by the scripts above and shouldn't be
edited directly. Run `generate_card_models.py` first; the fan parents the models it writes.

`generate_held_fan.py` reads its slot count and fan density tiers straight out of
`HandManager.java`, so the two can't drift apart — change them in the Java and re-run the generator.

## Contributing

Two developers work on this repo, so **pull before you start and push when you finish**:

```bash
git pull --rebase origin main
```

See [CLAUDE.md](CLAUDE.md) for the architecture notes, invariants and version-migration TODOs that
matter when changing the code.

## License

GNU General Public License v3.0 — see [LICENSE](LICENSE).
