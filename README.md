# UNO

A fully playable multiplayer UNO card game for **Paper 26.2** — a placeable table with stair seats
in nine themes (or any you build yourself), a card fan held in your hand, card piles that sit on
the table for everyone to see, and an optional "Let It Ride" mode where you wager items on a hand.

No mods. Tables are ordinary blocks; everything else is vanilla display entities plus a
client-side resource pack.

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

Build a table, sit down, deal:

```
/uno give casual <theme>   # admin — hands you a placeable table item
                           # right-click the ground with it to build the table
                           # cherry | spruce | mangrove | darkoak | …, or your own
/uno join              # stand next to a table and take the nearest free seat
/uno leave             # get up (Shift does the same)
/uno quit              # drop out of the hand you're in — others play on
/uno stop              # end the hand at your table for everyone
/uno start [bots]      # deal a hand to everyone seated at your table
```

You need at least 2 players; every table seats 4. Bots can fill the empty chairs.

### Table themes

All nine are the same table — a 3×3 chequered top of logs with a stair pulled up to each side — in
different blocks. They are **real blocks set into the world**, not display entities, so they save
with the chunk and cost nothing to render. Edit any of them, or add your own, with
`/uno theme create` or by hand in `plugins/UNO/themes.yml`.

Each is named for the wood it's made of, on a light/dark axis. Stripping is what that axis means:
**stripped** cherry is pink on every face, while unstripped cherry keeps pink rings on top and
dark bark down the sides — that dark frame is the bark, not a separate block.

| Theme | Top | Seats |
|---|---|---|
| `lightcherry` | stripped cherry & stripped pale oak | cherry stairs |
| `cherry` | cherry & pale oak (dark bark sides) | pale oak stairs |
| `darkcherry` | cherry & pale oak (dark bark sides) | nether brick stairs |
| `spruce` | stripped spruce & stripped oak | spruce stairs |
| `darkspruce` | spruce & oak | dark oak stairs |
| `darkoak` | dark oak & pale oak | deepslate tile stairs |
| `lightmangrove` | stripped mangrove & stripped pale oak | mangrove stairs |
| `mangrove` | stripped dark oak & stripped mangrove | dark oak stairs |
| `darkmangrove` | dark oak & mangrove | dark oak stairs |

`cherry` and `darkcherry` are the same top; the seats are the whole difference.

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
many cards are left in the deck. Playing a wild puts four clickable colours in chat, laid out like the
card — red and blue on top, yellow and green below; if the turn timer runs out first, you get the
colour you hold most of. As your hand grows, the fan compresses so every card stays visible.

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

## Table themes

What a table is made of is **data**, not code. Every look is a "theme" in
`plugins/UNO/themes.yml`: a 3×3 grid of blocks for the top, plus a block for each of the four
seats. The nine that ship are written into that file on first run and are not special — edit them,
or add your own.

Build one in game rather than by hand:

```
/uno theme create marble    # opens a 3×3 grid with a seat slot on each side
                            # drop blocks in, hit Save
/uno give casual marble     # hand out a table wearing it
```

Blocks placed in the editor are always given back when it closes — designing a theme costs
nothing.

Written by hand, a theme looks like this:

```yaml
themes:
  marble:
    name: Marble Table
    # First row is the FAR side of the table, first column is the LEFT.
    # The pattern turns to match the way the table is placed.
    grid:
      - ["minecraft:quartz_block", "minecraft:quartz_pillar[axis=y]", "minecraft:quartz_block"]
      - ["minecraft:quartz_pillar[axis=y]", "minecraft:sea_lantern", "minecraft:quartz_pillar[axis=y]"]
      - ["minecraft:quartz_block", "minecraft:quartz_pillar[axis=y]", "minecraft:quartz_block"]
    seats:
      near: "minecraft:quartz_stairs"
      far: "minecraft:quartz_stairs"
      left: "minecraft:quartz_stairs"
      right: "minecraft:quartz_stairs"
```

### Custom blocks

A theme may name a block from **ItemsAdder, Oraxen or Nexo**, with a vanilla block after a pipe
for servers that don't run that plugin:

```yaml
      - ["itemsadder:marble_pillar|minecraft:quartz_pillar", "nexo:felt|minecraft:green_wool", ...]
```

Those plugins are soft dependencies read reflectively, so UNO builds and runs identically without
any of them installed. **Always give a custom id a fallback** — without one, a server missing that
plugin gets a plain default block in that cell.


## House rules

UNO has no single agreed rulebook, so the popular variants are toggles in `config.yml` under
`rules:`. **All of them are off by default** — the game plays exactly by the official rules
until you turn something on. They are read when a hand is dealt, so `/uno reload` applies to
the next hand rather than the one in progress.

| Rule | Key | What it does |
|---|---|---|
| Stacking | `rules.stacking.enabled` | Answer a +2 with your own instead of drawing. The pile accumulates and lands on the first player who can't or won't add to it. Sub-toggles: `draw4-on-draw2`, `draw2-on-draw4` |
| Same-rank multi-play | `rules.multi-play.enabled` | Lay several cards of the same rank in one turn. Every card still takes effect: two Skips skip two players. `max-cards: 0` for no limit |
| Jump-in | `rules.jump-in` | Holding the exact card that's showing (same colour *and* rank) lets you play it out of turn, and play jumps to you. Just left-click it in your fan |
| Seven-O | `rules.seven-o` | A 7 swaps your hand with a player you pick; a 0 moves every hand one seat around |
| Draw to match | `rules.draw-to-match` | Keep drawing until something is playable — and keep the turn, so you can play it |
| +4 challenge | `rules.challenge-draw4` | The official rule. A +4 is only legal if its player had nothing matching the colour showing. Catch a bluff and they draw 4; challenge an honest one and you draw 6 |

### Calling UNO

By default the plugin calls UNO for you. Turn on `rules.uno-callout.enabled` and players have
to call it themselves:

```yaml
rules:
  uno-callout:
    enabled: true
    window-seconds: 5          # how long the window stays open
    penalty: 2                 # cards drawn by a player who gets caught
    false-callout-penalty: 2   # cards drawn for accusing somebody who was safe
```

The moment a hand drops to one card, that player gets a clickable **[ CALL UNO! ]** and
everyone else gets **[ CALL THEM OUT ]**. Click first and you win the exchange. Ride out the
window without being caught and you're safe until you pick cards up again. `/uno uno` and
`/uno callout <player>` do the same thing if you'd rather type.

Bots call their own UNO at a random moment inside the window, so catching one is a genuine
race rather than a coin flip.


## Commands

All subcommands tab-complete; admin ones are hidden from players who can't use them.

| Command | Permission | |
|---|---|---|
| `/uno help` | `uno.play` | Command list, filtered by what you can run |
| `/uno join` | `uno.play` | Sit at the nearest table (alias: `/uno sit`) |
| `/uno leave` | `uno.play` | Get up (alias: `/uno stand`; Shift does the same) |
| `/uno start [bots]` | `uno.play` | Deal a hand to everyone seated at your table |
| `/uno quit` | `uno.play` | Drop out of your hand; others play on (alias: `/uno forfeit`) |
| `/uno stop` | `uno.play` | End the hand at your table (refused while a pot is riding) |
| `/uno uno` | `uno.play` | Call UNO when you're down to one card (usually clicked, not typed) |
| `/uno callout <player>` | `uno.play` | Catch a player on one card who never called |
| `/uno version` | `uno.play` | Plugin version |
| `/gamble …` | `uno.gamble` | Wagering — see above (aliases: `/bet`, `/ante`, `/letitride`) |
| `/uno give <casual\|casino> <theme>` | `uno.admin` | A placeable table item; right-click the ground with it. Casino is not implemented yet |
| `/uno theme <list\|create\|edit\|delete> [id]` | `uno.admin` | Design the blocks a table is built from, in a 3×3 GUI |
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
  table/                UnoTable geometry + variant palettes, TableManager (placement, seats, visuals)
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
