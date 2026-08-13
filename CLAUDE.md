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
Minecraft: placeable casino tables with sittable stools and a fish dealer, a 3D card fan held in the
player's hand, in-world draw/discard piles, and an item-wagering mode ("Let It Ride").

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
```

- `generate_card_models.py` **bakes the rotations into the geometry** rather than rotating at
  render time (see the PileRenderer note below), which means rewriting every face's UV as it
  moves to a new direction. `FRAME` is the table of per-direction (u, v) axes that makes that
  correct; `remap_uv` handles axis flips exactly and falls back to a face `rotation` when the
  axes swap — which in this pack only ever happens on the uniform white rims.
- The Blockbench exports reference a texture called `sides` that was never saved, and the deck
  exports carry a leftover `wild_draw4` on their face. The generator resolves both: rims point at
  the generated `item/cards/sides.png`, and a deck shows `back.png` on top and bottom.
- Run `generate_card_models.py` **before** `generate_held_fan.py` — the fan's 6800 files are
  `parent` references to `assets/uno/models/item/cards_held/`, not copies of the geometry.
  Inlining a 17-box card 6800 times is a pack the client chokes on baking.

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
  every screen offset in the fan. The old fixed `SEL_FRONT` (11 depth steps, 0.18 blocks, on an
  item 0.72 from the eye) put the selected card up to 1.5× the half-width off the side of the
  screen. Selection is now `slot_depth(k) + SEL_POP_Z` plus a `SEL_LIFT_Y` straight up: the card
  rises out of the fan and stays where the player can see it.
- `generate_hand_model.sh` builds the static 7-card `uno:hand` item used only by the `/uno hand` debug
  command; it is not part of gameplay. All five `CardTester`/fan debug commands require both
  `uno.admin` **and** `debug: true` in config.yml — they spawn per-tick display entities and have no
  business on a live server.
- `generate_card_font.py` is **dead code** — an abandoned HUD-font approach. Its outputs
  (`assets/uno/font/`, `assets/uno/textures/font/`) are not in the pack and `HandFont.java` was deleted.
- The fan strips the four rim faces from its copy of the card (`strip_rims`). They are 60 of the
  card's 86 quads and sub-pixel at the 0.18 fan scale, and the fan bakes 6800+ models.
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
| *(no item)* `uno:item/cards_held/<card>` | upright, pivot on (8,8,8), rims stripped | parent of every `uno:held` slot model |
| `uno:held` | one composite item, 21 select-slots | `HandManager` (the held fan) |

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

- **`UnoGame`** is pure rules — hands, deck, turn order, direction, active colour, legality, win.
  **Zero Bukkit imports** (that's what makes it testable); it resolves names through an injected
  `namer`. All rendering, chat, bots and scheduling live in `GameManager`. Its `id()` is the *game*
  id, not a table id — `GameManager.tableOfGame()` maps a game to the table it's being played at.
- **`Deck.draw()` returns `null` when there is genuinely nothing left.** It must never rebuild
  itself: players are holding cards from the current deck, so a rebuild puts a second copy of every
  one of them into play. Callers treat null as "no card — the turn just passes".
- **`GameManager`** implements `HandManager.CardActions` (input → rules) and owns the bossbar, the
  wild-colour inventory GUI, the `PileRenderer`, and the bots. Bots are plain random `UUID`s in the
  `bots` set with no `Player` behind them — every loop that touches players must skip them.
- **`BetManager`** implements `GameManager.GameListener` and sits *on top of* a normal hand. It never
  touches game rules; it reacts to `onGameEnd` / `onForfeit`.
- Order matters at shutdown: `BetManager.shutdown()` refunds before games and tables tear down.

### In-world entities

All visuals are `ItemDisplay` / `BlockDisplay` / `TextDisplay` / `Interaction` entities spawned with
`setPersistent(false)` and tagged in their PDC with the plugin's `NamespacedKey`s. They vanish with
the chunk; `TableManager` respawns them on `ChunkLoadEvent` and clears `entityIds` on unload. Only
logical state is persisted:

- `plugins/UNO/tables.yml` — table id, type, world, x/y/z, yaw. A table whose world isn't loaded
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
change the baked rotation in `generate_card_models.py` instead. The felt surface height lives once,
in `UnoTable.SURFACE_Y`.

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

- **escrow is the source of truth.** A staked item leaves the inventory, so `EscrowStore` writes to
  disk synchronously on every stake, refund and payout. Never let a stake live only in RAM.
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
