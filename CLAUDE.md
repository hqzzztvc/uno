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

Requires **JDK 25** (Paper 26.2 is class-file v69) and Maven. Neither is currently installed on this
machine — check before assuming a build will run.

```bash
mvn clean package          # -> target/uno-1.0.jar   (paper-api is `provided`, not bundled)
mvn -q -o clean package    # offline, quiet
```

There is **no test suite and no linter**. Verification means running a Paper 26.2 server:

```bash
cp target/uno-1.0.jar server/plugins/uno-1.0.jar
cd resourcepack && zip -qr ../UNO-pack.zip pack.mcmeta assets -x '*.DS_Store'
# then copy UNO-pack.zip into the *client's* resourcepacks folder and enable it
```

The plugin does **not** serve the pack. `resource-pack.serve-mode` in `config.yml` is logged on enable
and otherwise ignored — the zip is installed client-side by hand. Same for most of `config.yml`: only
the `gambling.*` keys are actually read (`BetManager`). `game.*`, `tables.*`, `dealer.*` and `debug`
are aspirational and have no code behind them; don't assume changing them does anything.

## Regenerating pack assets

Texture PNGs are authored by hand (Aseprite/Blockbench) and live under the **minecraft** namespace at
`resourcepack/assets/minecraft/textures/item/cards/<card>.png`. Everything under `assets/uno/` is
generated from those PNGs and should not be hand-edited:

```bash
cd resourcepack
./generate_card_models.sh   # upright 2-sided card models + uno:<card> item defs (one per PNG)
python3 generate_held_fan.py  # the ~6800-file uno:held composite fan (assets/uno/models/item/held/)
```

- `generate_held_fan.py` **must stay in lockstep with `HandManager`**: `MAX_SLOTS = 21` and
  `DENSITIES = {19.0, 10.0, 5.0}` are duplicated in both files. Changing one without the other
  silently produces empty or wrong-angle cards.
- `generate_hand_model.sh` builds the static 7-card `uno:hand` item used only by the `/uno hand` debug
  command; it is not part of gameplay.
- `generate_card_font.py` is **dead code** — an abandoned HUD-font approach. Its outputs
  (`assets/uno/font/`, `assets/uno/textures/font/`) are not in the pack and `HandFont.java` was deleted.
- The flat table-pile models (`assets/uno/models/item/cards_flat/`) have no checked-in generator; they
  were produced ad hoc. Add new ones by copying an existing pair.
- `cards_textures_backup/` at the repo root is a stale copy of the card art, not used by the build.

## Card naming is the universal ID

`Card.name()` (`red_5`, `green_skip`, `wild_draw4`) is simultaneously the texture name, the item-model
key, and the string passed around between subsystems. Every card exists in three model families:

| Key | Model | Used by |
|---|---|---|
| `uno:<card>` | upright, front + `back.png` reverse | `CardTester` debug fan |
| `uno:flat_<card>` / `uno:down_<card>` | lying flat, face-up / face-down | `PileRenderer` (table piles) |
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
                                    ▲                          │
        HandManager ────────────────┘  (CardActions)           │
         (held fan, input)      ◄── GameListener ──────────────┘
```

- **`UnoGame`** is pure rules — hands, deck, turn order, direction, active colour, legality, win. No
  Bukkit calls except name lookup. All rendering, chat, bots and scheduling live in `GameManager`.
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

- `plugins/UNO/tables.yml` — table id, type, world, x/y/z, yaw.
- `plugins/UNO/escrow.yml` — staked items, keyed by owner.

Card models are authored *already lying flat* precisely so `PileRenderer` can spawn them with **no
rotation**; a render-time `rotateX` swings the card off its spawn point and drops it under the table.
Don't "fix" the missing rotation. The felt surface constant `0.757` is duplicated in `GameManager`
(`surfaceY`) and `BetManager` (`SURFACE_Y`).

### Input ownership

While a player has an active fan, `HandManager` takes over their controls and cancels the vanilla
behaviour: A/D polled every tick via `Player.getCurrentInput()`, scroll wheel (`PlayerItemHeldEvent`
cancelled to pin the fan to one hotbar slot), left-click/Q = play, right-click/F = draw, block
break/place blocked. Left-click and interact events double-fire, hence the 150 ms debounce.

Event-priority coupling: `BetManager.onDrop` runs at `HIGH, ignoreCancelled = true` specifically so
`HandManager`'s NORMAL-priority cancel of Q-to-play is seen first — otherwise a player would stake
their own cards. `isPluginItem()` is the second line of defence.

### Wagering invariants

- **escrow.yml is the source of truth.** A staked item leaves the inventory, so `EscrowStore` writes
  the file synchronously on every stake, refund and payout. Never let a stake live only in RAM.
- **Quitting mid-hand is forfeiting**, not a refund — the stake stays in the pot (`BetSession.forfeit`
  removes you from `live` but deliberately leaves attribution in `stakes`, so a crash still refunds).
  Quitting during the ante, before the deal, does refund.
- The pot renders as `ItemDisplay`s, never real dropped `Item` entities (those despawn, get hoovered
  by hoppers, and can be grabbed by passers-by).
- Bots never collect: a bot win is a push and everyone is refunded.

## Paper version notes

`pom.xml` targets `paper-api 26.2.build.111-stable` while `plugin.yml` declares `api-version: '1.21'`.
There is a pending 26.3 migration marked by `TODO(26.3)` in `TableManager`: swap `SEAT_CUSHION` from
`Material.RED_WOOL` to the real `Material.CUSHION` and bump the pom, once that drop ships.
