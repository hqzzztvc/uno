# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Before starting any session

**Two developers collaborate on this repo, so the local checkout is routinely stale. Pull before
doing anything else** — reading, planning, or editing code that is already behind `origin/main`
wastes the session and produces conflicting work.

```bash
git fetch origin && git status -sb   # see how far behind/ahead main is
git pull --rebase origin main        # main tracks origin/main (https://github.com/hqzzztvc/uno.git)
```

If the working tree is dirty, stash or commit first, then pull — don't rebase over uncommitted work.
If the pull brings in conflicts or someone else has touched the files the task is about, say so
before writing code. Push finished work promptly for the same reason, and re-pull before resuming a
session that has been idle.

## What this is

A Paper server plugin (`com.unoplugin`) that implements a fully playable multiplayer UNO game inside
Minecraft: a placeable table with stair seats in nine themes (or any an admin builds), a card fan
held in the player's hand, in-world draw/discard piles, and a wagering mode ("Let It Ride") that
stakes items, Vault currency, or both.

Two halves that must stay in sync:

- `src/` — the Java plugin (Maven, Java 25, Paper 26.2 API).
- `resourcepack/` — the client-side resource pack that supplies every model the plugin references by
  `NamespacedKey("uno", …)`. Most of it is **generated**, not hand-written.

## Build & dev loop

Requires **JDK 25** (Paper 26.2 is class-file v69) and Maven. Both are installed via scoop
(`temurin25-jdk`, `maven`); scoop puts the JDK on PATH directly rather than shimming it, so if
`java` isn't found, source this:

```bash
export JAVA_HOME="$HOME/scoop/apps/temurin25-jdk/current"
export PATH="$JAVA_HOME/bin:$HOME/scoop/apps/maven/current/bin:$PATH"
```

```bash
mvn clean package          # -> target/uno-1.0.jar   (paper-api is `provided`, not bundled)
mvn test                   # rules-layer tests only
mvn -q -o clean package    # offline, quiet
```

`Card`, `Deck` and `UnoGame` are pure Java (no Bukkit imports) and covered by JUnit in
`src/test/java`. The load-bearing test is `totalCards()`: **a game always holds exactly 108 cards**,
wherever they are. Both historical card-accounting bugs — `start()` discarding the action cards it
flipped past, and `Deck.draw()` rebuilding itself while players held cards — show up as that count
drifting. Add to those tests before touching the rules.

There is no linter. Everything outside the rules layer needs a Paper 26.2 server to verify:

```bash
cp target/uno-1.0.jar server/plugins/uno-1.0.jar
cd resourcepack && zip -qr ../UNO-pack.zip pack.mcmeta assets -x '*.DS_Store'
# then copy UNO-pack.zip into the *client's* resourcepacks folder and enable it
```

The plugin does **not** host the pack. It will *offer* one if `resource-pack.url.link` points at a zip
you host yourself (`ResourcePackSender`); otherwise the zip is installed client-side by hand.

**Every key in `config.yml` is read**, all of it through `util/Settings` — that class is the only
place in the plugin that touches `getConfig()`, so "is this key wired up?" is answerable by reading
one file. If you add a key, add it there too; if you delete code, delete the key. `/uno reload`
re-reads both `config.yml` and `messages.yml`.

Every sound and particle goes through `util/Fx` the same way, so "what does this plugin sound
like?" is one file rather than a grep for `playSound`. Call sites name the *event*
(`fx.cardPlayed(...)`), never the sound. Everything in there is bounded — no loops, nothing that
scales with the number of players at the table — and `effects.sounds` / `effects.particles` /
`effects.volume` in config.yml turn it down or off.

Player-facing strings all live in `src/main/resources/messages.yml` and go through `util/Messages`
(MiniMessage). There are **no `§` codes and no `sendMessage(String)` calls** left in the source —
keep it that way. The jar's copy of `messages.yml` is registered as the defaults, so an admin's file
only needs the keys they changed. Placeholder values are inserted unparsed, so a player named
`<red>oops` can't inject formatting into a broadcast.

## Regenerating pack assets

There are **two** hand-authored sources, and everything under `assets/uno/` is derived from them:

- **art** — texture PNGs (Aseprite) under the **minecraft** namespace at
  `resourcepack/assets/minecraft/textures/item/cards/<card>.png`.
- **geometry** — Blockbench exports at `uno_json/<card>.json` (repo root). One upright card: a
  rounded-corner slab 16 wide × 24 tall × 1 thick built from 17 boxes, card face on `north`,
  `back.png` on `south`, white rims. `uno_json/deck_{10,50,100}.json` are the same slab thickened
  to 1/5/10 units and stand in for the draw pile.

Neither `assets/uno/` nor the pack's `sides.png` is hand-edited — regenerate instead:

```bash
cd resourcepack
python3 generate_card_models.py   # all 4 card families from uno_json/ + uno:<card> item defs
python3 generate_held_fan.py      # the ~6800-file uno:held composite fan (models/item/held/)
python3 generate_table_mat.py     # uno:letitride_mat, the flat "Let It Ride" table mat
```

- `generate_card_models.py` **bakes the rotations into the geometry** rather than rotating at
  render time (see the PileRenderer note below), which means rewriting every face's UV as it
  moves to a new direction. `FRAME` is the table of per-direction (u, v) axes that makes that
  correct; `remap_uv` handles axis flips exactly and falls back to a face `rotation` when the
  axes swap — which in this pack only ever happens on the uniform white rims.
- The Blockbench exports reference a texture called `sides` that was never saved, and the deck
  exports carry a leftover `wild_draw4` on their face. The generator resolves both: rims point at
  the generated `item/cards/sides.png`, and a deck shows `back.png` on top and bottom.
- `generate_card_models.py` and `generate_held_fan.py` are independent — the fan reads the card
  PNGs directly and owes nothing to `uno_json/`. Order only mattered while the fan parented the
  3D card; see the fan note below.

- `generate_held_fan.py` **reads `MAX_SLOTS` and `DENSITIES` out of `HandManager.java`** (see
  `java_constants()`) — the Java file is the single source of truth and the two can no longer drift.
  Change them in the Java, then re-run the generator. If those fields are ever renamed, update the
  regexes in `java_constants()` or the script exits with a clear message rather than emitting
  wrong-angle cards.
- The three density tiers all earn their place: `chooseTier` picks tier 0 for hands up to 8 cards,
  tier 1 for 9-14 and tier 2 for 15+. Dropping one either overflows the screen on big hands or
  cramps small ones. `HandFanTest` pins those boundaries and holds every hand size inside
  `MAX_SPAN`, because the pack is baked from these numbers and a bad one only shows up in-game.
- **The fan has to stay inside the viewport, and the numbers that keep it there are not
  obvious.** Vanilla holds a first-person item ~0.56 blocks right of the eye and ~0.72 in front;
  display translations are 1/16 block, applied *before* rotation and never scaled by the
  transform's own scale. So `FP_TRANS[0]` has to pull the fan back left, `MAX_SPAN` has to keep
  the outermost card's 0.27-block reach from leaning off the right edge, and — the one that bit
  — a forward `translation` on the *selected* card moves it toward the camera, which magnifies
  every screen offset in the fan. A `SEL_FRONT` of 3.3875 (0.21 blocks, on an item 0.72 from the
  eye) magnified the selected card ~1.33× and threw it off the side of the screen. `SEL_FRONT` is
  now **0.5** — barely in front of the front-most slot, so the selected card reads as lifted
  without leaving the fan. Raising it is the fastest way to break first person again.
- `generate_hand_model.sh` builds the static 7-card `uno:hand` item used only by the `/uno hand` debug
  command; it is not part of gameplay. All five `CardTester`/fan debug commands require both
  `uno.admin` **and** `debug: true` in config.yml — they spawn per-tick display entities and have no
  business on a live server.
- `generate_table_mat.py` is the **one exception to "assets/uno/ is never hand-authored"**, at
  both ends. Its source, `assets/uno/models/item/letitride.json`, is a Blockbench export of the
  logo that lives in the pack rather than in `uno_json/` (the other dev put it there, and
  `generate_card_models.py` would treat anything in `uno_json/` as a card). Its output,
  `uno:letitride_mat`, is that quad **baked lying flat** — X/Z centred on 8 and the *underside*
  at y=8, the same trick the deck models play, so the mat spawns flush on the felt with no
  rotation and at any scale. `TableMat.THICKNESS` is derived from that 0.5-unit thickness and
  the display scale, and is what the card piles and the pot are lifted by; don't re-type the
  number anywhere else. The +90° about X also leaves the logo's top edge pointing at local +Z,
  which is the table's far side — so it reads right way up from seat 0, the side the table was
  placed from. It imports the rotation helpers from `generate_card_models.py`, so run that
  script's own regeneration first if `sides.png` has moved.
- `generate_card_font.py` is **dead code** — an abandoned HUD-font approach. Its outputs
  (`assets/uno/font/`, `assets/uno/textures/font/`) are not in the pack and `HandFont.java` was deleted.
- **The fan's card is a flat two-face quad, and that is deliberate.** The fan briefly parented the
  3D Blockbench card (`uno:item/cards_held/`); 21 cards with real thickness sharing one pivot
  intersect and show their cross-sections, and the only fix — a depth step wider than the card is
  thick — threw the selected card far off the tuned spot. Two faces have no thickness to clear, so
  the fan inlines its own quad and keeps `DEPTH_STEP = 0.04` / `SEL_FRONT = 0.5`. Re-pointing it at
  `cards_held` means re-tuning both and checking the result in first person.
- `assets/uno/models/item/cards_held/` is still generated but **nothing references it** now that the
  fan inlines its quad. It is kept as the ready-made 3D card if the fan is ever taken back that way.
- `cards_textures_backup/` at the repo root is a stale copy of the card art, not used by the build.
  It is **untracked and gitignored** — still on disk, no longer in the repo. Same for `.DS_Store`.

## Card naming is the universal ID

`Card.name()` (`red_5`, `green_skip`, `wild_draw4`) is simultaneously the texture name, the item-model
key, and the string passed around between subsystems. Every card exists in four model families,
all generated from the one Blockbench export in `uno_json/`:

| Key | Model | Used by |
|---|---|---|
| `uno:<card>` | upright, face on +Z, bottom-centre at the origin | `CardTester` debug fan (`debug: true` only) |
| `uno:flat_<card>` / `uno:down_<card>` | lying flat, face-up / face-down | `PileRenderer` (discard pile) |
| `uno:deck_10` / `deck_50` / `deck_100` | the draw pile as one solid block of cards | `PileRenderer` (draw pile) |
| `uno:letitride_mat` | the flat 32×32 mat, underside at y=8 | `TableMat` (a table playing for stakes) |
| *(no item)* `uno:item/cards_held/<card>` | upright, pivot on (8,8,8), rims stripped | nothing — see the fan note above |
| `uno:held` | one composite item, 21 select-slots | `HandManager` (the held fan; inlines its own flat quad) |

The held fan is a *single* PAPER item whose `custom_model_data` **strings** drive the composite: slot
`k` gets `"<card>_<tier>"`, `"<card>_<tier>_sel"` for the highlighted card, or `""` for empty.
`HandManager.updateItem` builds that 21-string list every time the hand changes.

## Architecture

`UnoPlugin.onEnable` constructs the managers and wires them with setter injection, because the
dependencies are circular:

```
TableManager ──────────────► GameManager ──────────────► BetManager
 (tables, seats, visuals)     (games, bots, rendering)    (pots, escrow)
      ▲                             ▲                          │
      │      HandManager ───────────┘  (CardActions)           │
      │       (held fan, input)   ◄── GameListener ────────────┘
      └── BusyCheck: "is a hand or a pot running at this table?" ──┘

util/  Settings (all config)  Messages (all text)  Fx (all sound + particles)
       NameCache (UUID→name, never blocks)
command/  UnoCommand (routing + permissions + tab completion), GambleCommand
```

`BusyCheck` is why a table can't be removed out from under a running hand: `UnoPlugin` wires it to
`gameManager.hasGameAtTable(id) || betManager.hasSessionAtTable(id)`.

**Every table plays both ways.** There is no casino table and no `UnoTable.Kind` any more — the
choice between a friendly hand and one for stakes belongs to the four people sitting down, in the
ten seconds before the deal, not to the admin who placed the furniture. See "Sitting down, and
what happens next" below.

- **`UnoGame`** is pure rules — hands, deck, turn order, direction, active colour, legality, win.
  **Zero Bukkit imports** (that's what makes it testable); it resolves names through an injected
  `namer`. All rendering, chat, bots and scheduling live in `GameManager`. Its `id()` is the *game*
  id, not a table id — `GameManager.tableOfGame()` maps a game to the table it's being played at.
- **A card still does what it says when it is the one that goes out.** `UnoGame.resolve` deals a
  winning `+2`/`+4`'s penalty to the next player *before* declaring the win, because once `over`
  is set there is no turn left to move and nobody to hand it to. Checking the empty hand first
  reads more naturally and is why the penalty used to vanish; `UnoGameTest` pins both cards.
- **`Deck.draw()` returns `null` when there is genuinely nothing left.** It must never rebuild
  itself: players are holding cards from the current deck, so a rebuild puts a second copy of every
  one of them into play. Callers treat null as "no card — the turn just passes".
- **`GameManager`** implements `HandManager.CardActions` (input → rules) and owns the bossbar, the
  wild-colour inventory GUI, the `PileRenderer`, and the bots. Bots are plain random `UUID`s in the
  `bots` set with no `Player` behind them — every loop that touches players must skip them.
- **`BetManager`** implements `GameManager.GameListener` and sits *on top of* a normal hand. It never
  touches game rules; it reacts to `onGameEnd` / `onForfeit`.
- Order matters at shutdown: `BetManager.shutdown()` refunds before games and tables tear down.

### House rules

UNO has no single agreed rulebook, so the variants people actually play are toggles in
`rules.*` (config.yml) collected into a `RuleSet` and handed to `UnoGame` at construction.
**Every rule defaults to off** — upgrading must never silently change the game a server is
already running. `RuleSet` is a plain record with no Bukkit in it, so a test constructs any
combination directly instead of going through config.

Rules are read when a hand is **dealt**, so `/uno reload` lands on the next hand, not mid-game.

- **`canPlay` is the single gate.** Every caller — `play`, `jumpIn`, `legalIndices`,
  `firstLegalIndex`, the bots, the hint — goes through it. That is what stops a bot cheerfully
  playing a green 5 to escape a +8: while a stack is pending, a matching colour counts for
  nothing and only another draw card is legal.
- **Stacking defers the penalty rather than dealing it.** `pendingDraw` accumulates and
  `draw()` is what finally collects it. `finishWin` adds any pending stack to the last card's
  own penalty, so winning off the back of a stack still hands the pile to the next player.
- **`resolve` takes a LIST of cards, always.** Single plays call it with a one-element list.
  Effects add up rather than replace: each Skip/+2/+4 moves the turn one further on, each
  Reverse flips the direction (or acts as a skip with two players left), and draw penalties
  total. It is the only reading where one Skip and two Skips stay consistent.
- **`colorBeforePlay` is captured in `commitPlay`, never reconstructed.** A +4 is a wild, so by
  the time a challenge is set up its player has already picked a new colour and `activeColor`
  is that choice; the card underneath may itself be a wild whose colour says nothing. Reading
  it back off the discard pile convicts the innocent. Same reason `draw4Hand` is a snapshot:
  by the time anyone challenges, the accused may have drawn from a stack or been swapped.
- **An upheld challenge advances by 1, a declined one by `1 + extraSkips`.** `resolve` hands
  the challenge back *without* advancing, so the turn is still sitting on the +4's player.
  Being right means the challenger isn't skipped — they get the turn.
- **Seven-O reads the rank, not the count.** Laying three 7s is still one swap. `rotateHands`
  takes from the seat the direction came *from* so hands travel with play, and both it and
  `chooseSwap` call `recheckUnoCalls` — a swap can hand somebody their last card, or take one
  away, and a stale "called UNO" would either shield or expose the wrong player.
- **`forfeit` must drop every pending prompt the leaver owed an answer to** (colour, swap,
  challenge), or the table waits forever on somebody who isn't there.

### Calling UNO

With `rules.uno-callout.enabled` off, the plugin announces UNO for the player, as it always
did (`unoEffects`). With it on, `checkExposure` takes over: reaching one card opens a timed
window, the player gets a click-to-run **[ CALL UNO! ]** button and everyone else gets
**[ CALL THEM OUT ]**.

- Buttons are built in Java with `ClickEvent.runCommand` and passed to `Messages` as a
  **Component** placeholder. That matters: `Messages` inserts plain values *unparsed*, so a
  player called `<red>oops` can't smuggle formatting — but a Component placeholder is inserted
  as-is, which is exactly what a button needs. The command string is assembled in Java, never
  from player text.
- `checkExposure` runs on every move and **must stay idempotent**: a player already inside
  their window must not be re-prompted, and the window has to close the moment their hand
  stops being one card — including because seven-O swapped it away from them.
- `closeExposure(player, gameId)` with a non-null id means the countdown ran out, and marks
  them as having called so they aren't prompted again on the same card. Null means the window
  is being torn down for another reason (caught, called, hand changed) and no grace is given.
- `endGame` calls `clearExposures`: a scheduled closer must not outlive its game.
- **Bots call their own UNO at a random point inside the window**, not instantly and not on a
  "bots sometimes forget" constant. Beating one to the call-out is then a real race won by
  paying attention rather than a coin flip.

### In-world entities

All visuals are `ItemDisplay` / `BlockDisplay` / `TextDisplay` entities spawned with
`setPersistent(false)` and tagged in their PDC with the plugin's `NamespacedKey`s. They vanish with
the chunk; `TableManager` respawns them on `ChunkLoadEvent` and clears `entityIds` on unload. Only
logical state is persisted:

- `plugins/UNO/tables.yml` — table id, kind, theme id, world, x/y/z, yaw. A table whose world isn't loaded
  when the plugin enables (the Multiverse case) is held in `TableManager.pending` and **written back
  verbatim on save**, then built if `WorldLoadEvent` brings its world up. Dropping it from the map
  instead means the next `save()` erases it from disk forever.
- `plugins/UNO/escrow/<uuid>.yml` — staked items, one file per owner, so a stake costs one small
  synchronous write rather than re-serialising everything the server holds. A legacy single-file
  `escrow.yml` is imported on first run and renamed to `escrow.yml.imported`.
- `plugins/UNO/bets.log` — append-only audit trail (`BetLog`), written on a background thread and
  drained on disable. Evidence, not state; escrow is the source of truth.

The `cards_flat` / `deck_*` models come out of the generator *already lying flat and centred on
(8, 8, 8)* precisely so `PileRenderer` can spawn them with **no rotation**; a render-time `rotateX`
swings the card off its spawn point and drops it under the table. Don't "fix" the missing rotation —
change the baked rotation in `generate_card_models.py` instead. The table surface height lives once,
in `UnoTable.SURFACE_Y` — `1.0`, because a table is exactly one block tall.

The draw pile is **one** `ItemDisplay` showing `deck_10` / `deck_50` / `deck_100`, picked from the
remaining count, not a stack of card entities. The deck order is settled once when `Deck` is
constructed and never re-derived, so a draw only shrinks a number — the pile just swaps its item
when the count crosses 50 or 10. Three swaps a game, instead of an entity removal per draw.

`TableManager` indexes tables by packed chunk coordinate (`byChunk`), because chunk load/unload is one
of the hottest events on a busy server and scanning every table on each one is pure waste. Keep the
index in step in `register()` / `remove()`.

The discard heap is a rolling window of the last `DISCARD_MAX` (5) plays: at capacity the bottom card
leaves, the rest slide down a step and the new card lands on top, so the pile holds a steady height
instead of blinking out and restarting. It does that **without spawning or removing anything** — the
display freed from the bottom is the one that becomes the new top card, so a play costs five
teleports and one item swap. Don't "optimise" it back into a clear-and-rebuild: watching the pile
vanish every five plays is the bug that shape was hiding.

### Tables

A table's **looks are data, not code**. There is one shape — a 3×3 top with a stair pulled up to
the middle of each side — and a `TableTheme` says which block goes in each of the nine cells and
each of the four seats. `ThemeStore` loads them from `plugins/UNO/themes.yml`, which is also
written back by the in-game editor. The nine themes in `ThemeStore.builtIns()` are seeded into that
file on first run and are **not special-cased anywhere** — they carry a `built-in` flag only so
`/uno theme delete` refuses to leave a server with no themes.

The shipped nine were **drawn in the in-game editor and moved into the Java**, not authored there,
which is why each is named after its own id — that is what `/uno theme create` writes. They are all
the same chequer (primary wood on the corners and centre, secondary on the four edges) and none of
them writes an `[axis=y]`, because `ringsUp` stands logs on end as it lays them.

**`migrateLegacyId` maps on the palette, not the name, and that matters because the rename crosses
over.** The four ids the first data-driven build shipped were renamed onto a light/dark axis, and
two of the old names are still in use for different woods: what `cherry` used to mean now ships as
`lightcherry`, and what `spruce` used to mean now ships as `darkspruce`. An identity mapping would
quietly hand every legacy table a different wood. `TableThemeTest` reads `builtIns()` directly
rather than a copy of the id list, so renaming a shipped theme without fixing the migration fails
the build instead of retexturing somebody's table.

`UnoTable` carries a **theme id string** and nothing else about what it is for. The `Kind`
(`CASUAL` / `CASINO`) that used to sit beside it is **gone**: it put the casual-vs-stakes decision
in the wrong place (an admin, at placement) and made it permanent, when it is really a per-hand
decision made by whoever is sitting there. The theme is held **by id, not by resolved theme**,
because `themes.yml` is re-read on `/uno reload` and an edited theme has to reach the tables
already standing.

- **The table is REAL BLOCKS set into the world, not display entities.** `TableManager.buildBlocks`
  lays nine top blocks and four stairs. This is the whole reason the chunk-index machinery is gone:
  a display is an extra entity every nearby player has to track, it renders at whatever scale it
  was given rather than as a block, and it dies with its chunk so the plugin has to babysit
  `ChunkLoadEvent`/`ChunkUnloadEvent` to put it back. Real blocks save with the chunk, cost nothing
  to render, and light and occlude correctly. **Don't put the table back on `BlockDisplay`.**
- **The top IS rotated now, and that is a deliberate reversal.** It used to be world-aligned on the
  grounds that a 3×3 chequer is symmetric under 90° steps, so rotating was a no-op. Author-drawn
  themes are not symmetric, and a pattern someone designed has to come out the same way round
  however the table is placed. `cellLocation` maps table space (row 0 = far, col 0 = left) through
  the snapped yaw; a 90° step permutes the nine positions exactly, so nothing lands off the grid.
  `footprint()` deliberately does **not** rotate — a square maps onto itself, so "is this block
  ours?" needs no theme and stays cheap.
- Because the top is one layer of whole blocks, a theme's sides are whatever the block's own sides
  look like. That is the whole difference between `lightcherry` and `cherry`: **stripped** cherry is
  pink on every face, **unstripped** cherry keeps pink rings on top with dark bark down the sides.
  The dark frame in the original mock-up was that bark, not a separate block. It is also why the
  light/dark axis in the shipped theme names is about stripping rather than about the wood.
- A log laid flat shows bark on its top face, so `buildBlocks` stands `Orientable` blocks on end
  (`ringsUp`). A theme can still override that by writing the axis into its block state.
- Stairs carry their full-height side on the face they *face*, so a seat faces **outward** — that
  puts the tall half behind the sitter as a backrest with the low step toward the table. Anything
  that isn't `Directional` is left exactly as the theme wrote it.
- **Block specs, not `Material`.** `BlockSpec` parses `minecraft:cherry_log[axis=y]`, a bare
  `CHERRY_LOG` (what old configs held), or `itemsadder:marble|minecraft:quartz_block`. The part
  after the pipe is the fallback, and it is what makes a theme built around a custom-block plugin
  still placeable on a server that doesn't run one. **Always give custom ids a fallback** — a
  missing block leaves a hole where the card piles sit.
- `CustomBlocks` bridges ItemsAdder / Oraxen / Nexo **reflectively**, resolved once at startup.
  They aren't on any Maven repo this build pulls from, so compiling against them would make the
  plugin unbuildable for everyone else; and a server running none of them must not pay a
  `NoClassDefFoundError` at table-building time. Every call is wrapped and every failure degrades
  to the fallback and logs **once**. This is the least verifiable code in the repo — it can only
  be exercised with those plugins actually installed.
- `repairIfNeeded` checks **every cell**, not a sample. Sampling one or two let a retheme through
  whenever the new theme reused the old material in the sampled spot, and with author-written
  themes there is no "primary/secondary" pair left to sample. Thirteen block reads only happen on a
  chunk that actually holds a table; the `byChunk` lookup misses on virtually every chunk first.
- `clearBlocks` tries `CustomBlocks.removeAt` **before** matching materials: on those plugins the
  world block is a note block or similar, so matching on `Material` alone would either miss it or
  clear a real one.
- `tables.yml` rows written by older builds say `type: CASINO`. Load reads `theme` first and falls
  back to `type` through `ThemeStore.migrateLegacyId`, and the row is rewritten with a theme id on
  the next save. Rejecting unknown names instead strands them in `pending` and leaves a dead entry
  on disk that no command can reach. A `kind:` key from the Kind era is read and dropped the same
  way, and stops being written.
- `register()` re-lays a table's blocks **only if they are missing**. That is what makes the
  entity→block migration and a bulldozed table both self-healing, and it is why a normal restart
  rebuilds nothing.
- **The placeable table item is back**, and it is how tables are made now: `/uno give <theme>`
  hands over an item, and right-clicking the ground with it calls `placeAt` on the block
  above the one clicked. Where they aim, not where they stand — a 3×3 with seats 2 out centred on
  the placer buries them in their own furniture, and an aimed item is the only way to line a table
  up with a room already built. The item is an `OAK_PRESSURE_PLATE`, so `onInteract` **must** cancel
  the event before anything else or a real pressure plate goes down beside the table. The theme id
  lives in the item's PDC, so a stack that has been through a chest still builds what it says; a
  theme deleted since then is caught at placement rather than quietly building something else. An
  item stamped `casual/<theme>` by the Kind-era build still places — `onInteract` takes the theme
  off the back of the tag — and `/uno give casual oak` is still accepted, because an admin's
  muscle memory shouldn't cost them a table. `/uno createtable` is **gone**.
- `onBlockBreak` protects a live table's 13 positions from everyone without `uno.admin`, since the
  blocks are now real and mineable. Admins are deliberately let through — which also means an admin
  testing will mine their own table and see it come back on the next restart.

### The "Let It Ride" mat

A table with a live pot wears `TableMat`: one non-persistent `ItemDisplay` showing
`uno:letitride_mat`, laid **2.9 blocks across a 3-block top** — inset a tenth of a block so it
reads as something lying *on* the table rather than as a retexture of it, and so its edge doesn't
overhang thin air when you look from the side.

- **`TableManager` owns the mats, not the bet layer.** They have to come up when a table is
  removed and go back when a chunk reloads, and both of those are the table layer's job. The bet
  layer only says *when*: `showMat` on opening an ante, `hideMat` in `close()`. `showMat`/`spawn()`
  are idempotent, which is what lets `onChunkLoad` call it blind — on a table that still has its
  mat it costs one `isValid()`.
- **Everything on a wagered table is lifted by `TableMat.THICKNESS`.** `GameManager.launch` asks
  `tableManager.matLift(tableId)` for the card piles and `BetManager.potLocation` asks for the pot;
  neither has its own number. The constant is derived from the model's 0.5-unit thickness and the
  display scale, so moving the geometry moves the piles with it.
- **Order matters in `BetManager.open`:** the mat goes down *before* the `PotRenderer` is built,
  because `potLocation` adds the lift. Build the renderer first and the pot sits inside the mat.
- `open()` refuses at a table with a hand already running (`bet.table-busy`). Not the same check as
  "are you in a game" — a player who sat in a free seat mid-hand isn't in it, and letting them open
  an ante would slide the mat under piles already placed at the bare felt's height.

### Sitting down, and what happens next

The whole front door is two buttons. `TableManager.sit` sends `table.choose` —
**[ READY ]** and **[ FOR STAKES ]** — and `promptModeAt` sends `table.again` to everyone still
seated when a hand finishes. Nothing in `TableManager` knows what a game or a pot is: the buttons
run commands, and `UnoCommand` routes them.

- **`/uno ready` means "I'm in", whichever kind of hand this is.** `UnoCommand.ready` sends it to
  `BetManager` if there is a session at the table and to `GameManager` otherwise. That routing lives
  in the command layer because it is the one place that already holds both managers, and because
  there is no such thing as being casually ready at a table with a pot on it.
- **A casual hand deals itself.** `GameManager.ready` collects a per-table set and deals the moment
  it matches the seated list and clears `minPlayers`. That deletes the step where everyone waited
  for the one person who knew to type `/uno start`. `/uno start` still exists as the deal-now
  override for a table that won't wait for an AFK friend, and is what the `[ DEAL NOW ]` button in
  `game.ready-waiting` runs.
- `ready()` reconciles the set against `seatedPlayersAt` on every call, so a stale entry from
  someone who stood up can't trip the deal; standing up also clears it outright, through
  `GameManager.onStandUp` (the table's stand-up hook, which now does the ready withdrawal *and*
  the forfeit).
- **Opening a bet clears the casual readiness at that table** (`games.clearReady`). Consent to a
  friendly game is not consent to a wager — everyone has to say yes again now that there is money
  on it.
- `endGame` drops the table's ready set and re-prompts, **unless the hand was wagered**. It asks
  `hasPotAtTable` *before* telling the bet layer the hand is over, because that is the last moment
  the pot still exists; a wagered table is prompted by `BetManager.close()` instead, once the pot
  has actually settled rather than while its winner is deciding whether to let it ride.
- `close(s, false)` is the shutdown path: no "play again" offer on a server that is stopping.

### Theme editor

`/uno theme create <id>` opens `ThemeEditor`, a 54-slot chest laid out like the table seen from
above: the 3×3 top in the middle, one seat slot centred on each edge, save and cancel on the
bottom row.

- It is a **real inventory players drop real blocks into**, not a click-to-cycle picker, and that
  is the point. A picker has to enumerate its options, so it can only offer what the plugin was
  compiled knowing about; an inventory takes anything a player can hold, including a custom block.
- **Blocks are borrowed, never taken.** `returnContents` runs on close *and* on quit, and anything
  that no longer fits is dropped rather than deleted. Designing a theme must never cost a player a
  stack.
- Built-in themes are **not editable in place** — editing one would leave the server no way back to
  the look it shipped with. Copy it under a new name.
- `EditorHolder.getInventory()` returns the inventory rather than throwing. Bukkit and other
  plugins are entitled to call it on any holder they are handed, and a marker that throws turns an
  unrelated plugin's inventory sweep into a stack trace.

### Seating

**Seating is `/uno join`, not clicking a seat.** There are no `Interaction` entities on a table any
more: `joinNearest` finds the nearest table within `tables.join-radius` and takes the free seat
nearest the player — the one they would have clicked. Comparison is X/Z only, so standing on the
table or in a hole beside it doesn't change which side of it you are on. Every refusal messages the
player, because "nothing happened" is indistinguishable from a broken command.

Sitting still works by mounting an invisible `ArmorStand`, so `/uno leave` ejects and lets
`onDismount` route into `leaveSeat` — the bookkeeping and the message then happen in exactly one
place whether the player typed the command or just pressed shift.

**Standing up leaves the hand.** `leaveSeat` fires `TableManager.StandUpHook`, wired in
`UnoPlugin` to `GameManager.forfeit`, so walking away costs exactly what `/uno quit` costs —
staked pot included, because it *is* that. The hook exists for the same reason `BusyCheck` does:
tables are wired below games and must not know what a game is. Seat bookkeeping finishes before
the hook fires, since the forfeit broadcasts to the table and can end the hand outright, and both
walk `seated`.

### Leaving a hand

Two commands (plus standing up, which is `/uno quit` by another name), and the difference
between them is the wagering rule:

- **`/uno quit`** forfeits — you drop out, everyone else plays on. It routes through the same
  `GameManager.forfeit` as disconnecting, deliberately: typing it has to cost exactly what pulling
  the plug costs, or "I'm losing" becomes a reason to alt-F4. On a wagered hand your stake stays in
  the pot either way.
- **`/uno stop`** ends the hand for everyone at the table, and is **refused while a pot is live**
  (`GameListener.hasPotAtTable`). Ending a hand refunds the pot, so a player who is behind could
  otherwise use it as a free undo on a losing bet. With money up, players get `/uno quit` and admins
  get `/uno end`.

### Input ownership

While a player has an active fan, `HandManager` takes over their controls and cancels the vanilla
behaviour: A/D scroll the selection, scroll wheel too (`PlayerItemHeldEvent` cancelled to pin the
fan to one hotbar slot; a number key jumps several slots at once, so only a ±1 move counts as a
scroll), left-click/Q = play, right-click/F = draw, block break/place blocked, and
`EntityDamageByEntityEvent` cancelled (that left-click is also a punch — without this, playing a
card hits whoever is in front of you). Left-click fires as both an animation and an interact and
repeats while held, hence the 250 ms play/draw debounce.

**A/D is edge-driven, not tick-driven.** `PlayerInputEvent` arrives when the key changes state, so
a tap lands even when the server is running at 5 TPS — polling `getCurrentInput()` once a tick
samples whatever the key happens to be *now* and silently eats taps during a lag spike. The
per-tick poll that remains does the three things the event can't: key-repeat while A/D is held,
a fallback edge check (`applyInput` is idempotent — only a change from the stored state is a
press), and `resync`, which puts the fan back in the player's hand after a respawn, a desync or
another plugin. `updateItem` also skips the slot packet when the model strings didn't actually
change, which is most of the time: every game event re-renders every hand at the table.

**The fan must never cost a player an item.** `claimSlot()` prefers an empty hotbar slot, then moves
the held stack into free inventory space, and only holds it in `Hand.displaced` when the inventory is
completely full — which `hide()`, `onQuit()`, `onDeath()` and `shutdown()` all give back. `onDeath`
also strips the fan from the drop list (else it lies on the ground as a pickup-able 21-card item) and
adds any displaced stack to the drops, so death behaves exactly as if the fan had never moved it.

Two rules keep that true, and both are easy to undo by accident:

- **`updateItem` may only ever write into a slot that is empty or already holds a fan**
  (`ensureSlot`). It runs several times a second while a player scrolls, so writing blind
  destroys whatever landed in that slot meanwhile — a respawn, an escrow return, another plugin
  — and an item destroyed that way is gone for good.
- **`restoreDisplaced` puts the stack back in the slot it came out of.** That slot is exactly the
  room it needs once the fan leaves, so a full inventory costs nothing; `addItem` (and the drop
  on the ground it falls back to) is only for a slot that has since been taken.

`InventoryClickEvent` / `InventoryDragEvent` cancel anything touching a fan item anywhere — including
number-key and offhand swaps, and including stray fans from a finished game. Without it a player can
shift-click their hand into a chest.

`show()` only re-sweeps the inventory for stray fans when the *cards* changed, not on every A/D press
— that sweep walks all 41 slots.

Event-priority coupling: `BetManager.onDrop` runs at `HIGH, ignoreCancelled = true` specifically so
`HandManager`'s NORMAL-priority cancel of Q-to-play is seen first — otherwise a player would stake
their own cards. `isPluginItem()` is the second line of defence.

### Wagering invariants

- **A stake is one value, not two.** `Stake` (items + money) is what gets refunded, forfeited,
  re-attributed and paid out, because every rule here treats the two the same and two parallel
  maps would eventually drift. A refund that hands back the diamonds and quietly keeps the cash is
  indistinguishable from theft, and money is the half with nothing on the table to notice its
  absence — which is why `BetSessionTest` pins the money side of withdraw, forfeit and
  `reattributeTo` even though staking an item needs a live server.
- **escrow is the source of truth.** A staked item leaves the inventory and staked money leaves the
  balance, so `EscrowStore` writes to disk synchronously on every stake, refund and payout. Never
  let a stake live only in RAM.
- **Money is withdrawn BEFORE the stake is recorded, in that order.** The reverse would have a
  crash in the gap hand back money that was never actually paid; minting currency out of a power
  cut is a worse failure than the vanishing chance of losing one stake to it.
- **Vault is reached reflectively** (`VaultEconomy`), for exactly the reasons `CustomBlocks` is: it
  is on no Maven repo this build pulls from, and a server without it must not pay a
  `NoClassDefFoundError` the first time somebody types `/gamble 500`. Every call is wrapped; a
  refused transaction returns false, which the callers already read as "it didn't happen" — a stake
  is refused, and a payout stays in escrow for the next login rather than evaporating.
  `moneyAllowed()` is `settings.moneyEnabled() && economy.available()`; both halves matter.
- **Every message that quotes a pot goes through `potLabel`/`stakeLabel`.** Half the wagering
  messages predate money, and the way they would have gone wrong is by carrying on saying
  "8 items" about a pot that is mostly cash — technically true, and a lie about what the player is
  playing for. `bets.log` gets the raw figure to two decimal places instead, never the economy's
  formatting: it is evidence, and "$1.2k" is unreadable as a ledger.
- The pot draws money as **one `GOLD_NUGGET`** among the staked items. Without it a pure-money pot
  renders as nothing at all on the felt, which reads as a broken table rather than as a wager.
- **`shutdown()` deliberately does not refund.** Mutating player inventories while the server is
  stopping races the save that persists them, so whether the items survive is down to timing. Doing
  nothing leaves them in escrow, which is the path a `kill -9` takes anyway: returned on next join.
- **Every refusal in `onDrop` must cancel the event.** Messaging the player and returning lets the
  item really hit the ground, where it despawns or gets picked up by a passer-by.
- A challenge window that expires must always leave the session *resolved* — refund whoever never
  locked in, then either deal or pay the rider. Leaving it parked in `ANTE` with no timer armed holds
  everyone's items until the server restarts (`resolveChallenge`).
- **Quitting mid-hand is forfeiting**, not a refund — the stake stays in the pot (`BetSession.forfeit`
  removes you from `live` but deliberately leaves attribution in `stakes`, so a crash still refunds).
  Quitting during the ante, before the deal, does refund.
- The pot renders as `ItemDisplay`s, never real dropped `Item` entities (those despawn, get hoovered
  by hoppers, and can be grabbed by passers-by).
- Bots never collect: a bot win is a push and everyone is refunded.

## Paper version notes

`pom.xml` targets `paper-api 26.2.build.111-stable` and `plugin.yml` declares `api-version: '26.2'`
to match — an older declaration makes Paper apply legacy-material compatibility this doesn't want.
Bump both together.

There is a pending 26.3 migration marked by `TODO(26.3)` in `TableManager`: swap `SEAT_CUSHION` from
`Material.RED_WOOL` to the real `Material.CUSHION` and bump the pom, once that drop ships.
