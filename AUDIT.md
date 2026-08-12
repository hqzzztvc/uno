# Code Audit — resolved

A full review of the UNO plugin at commit `187332f`: every Java file in `src/main/java`, the
resource-pack generators, `plugin.yml` and `config.yml`. 33 findings.

**All 33 are now fixed.** Each entry below keeps the original problem statement and records what
changed and how far it was verified. Line references point at the *old* code; use
`git show 187332f` to read it.

## How much of this was actually tested

| | |
|---|---|
| **Unit tested** | 71 JUnit tests in `src/test/java`. Both card-accounting bugs (10, 11) were reproduced by reintroducing them and watching the suite fail, then fixed again. |
| **Server verified** | Run against a real Paper 26.2 (build 111) server: plugin lifecycle, every command, permissions, the debug gate, live `/uno reload`, table persistence, escrow migration. Finding 3 was reproduced on a baseline build (1 of 2 tables destroyed) and confirmed fixed (2 of 2 retained). |
| **Code only** | Anything needing a connected player: the held fan, death, inventory drags, staking, the turn timer. `minecraft-protocol` tops out at 1.21.11 and cannot speak the 26.x protocol, so no headless client could be driven against this server. These are implemented and reviewed but not exercised at runtime. |

**Contents**

- [P0 — Item and data loss](#p0--item-and-data-loss) (4)
- [P1 — Stuck states and stability](#p1--stuck-states-and-stability) (5)
- [P2 — Correctness](#p2--correctness) (5)
- [P3 — Weight and smoothness](#p3--weight-and-smoothness) (6)
- [P4 — Admin experience](#p4--admin-experience) (7)
- [P5 — Maintainability](#p5--maintainability) (6)

---

## P0 — Item and data loss

### 1. The hand fan destroys whatever is in the player's hotbar slot

`HandManager.java:114` set `fanSlot` to the slot the player was holding and wrote the fan straight
over it. Sit down holding a netherite sword, `/uno start`, sword gone.

**Fixed.** `HandManager.claimSlot()` prefers the held slot if empty, then any empty hotbar slot, then
moves the occupant into free inventory space; only a completely full inventory falls back to holding
the stack in `Hand.displaced`. `hide()`, `onQuit()`, `onDeath()` and `shutdown()` all give it back.
The slot is chosen once, when the hand is created, instead of being re-read on every render.
*Code only.*

### 2. The fan item drops on death

No `PlayerDeathEvent` handler existed, so dying mid-hand left a 21-card fan on the ground for anyone
to pick up.

**Fixed.** `HandManager.onDeath` strips fan items from the drop list; `onRespawn` re-claims a slot and
re-renders next tick. A displaced stack is added to the drops when `keepInventory` is off, so death
behaves exactly as if the fan had never moved it — no accidental keep-on-death exploit. *Code only.*

### 3. Tables are silently and permanently deleted when their world isn't loaded

`TableManager.load()` skipped any table whose world resolved to null — the normal case with
Multiverse — and `save()` then rewrote `tables.yml` from that same map. The next table anyone placed
erased every skipped table from disk forever.

**Fixed.** Unresolved entries go into `TableManager.pending` and are written back verbatim on save.
`WorldLoadEvent` materialises them when their world turns up, and `/uno list` reports them rather than
hiding them. *Server verified* — seeded a table in a non-existent world alongside a real one: the
baseline build kept 1 of 2 across a load/save cycle, the fixed build keeps 2 of 2.

### 4. Shutdown hands items back during plugin disable

`BetManager.shutdown()` called `refundAll`, mutating player inventories while the server was stopping.
Whether those inventories were still persisted afterwards was timing-dependent.

**Fixed.** `shutdown()` now cancels timers, removes the pot displays and logs the pot — and returns
nothing. Every stake is already in escrow under its owner, so items come back on next join via
`deliverPending`, the same path a `kill -9` exercises. *Code only.*

---

## P1 — Stuck states and stability

### 5. A challenged "let it ride" pot can hang forever

`BetManager.ride()` armed a timer that only paid out when `live().size() < 2`. A challenger who
staked but never ran `/gamble ready` left the session parked in `ANTE` holding everyone's items, with
no further timer ever scheduled.

**Fixed.** `resolveChallenge()` always leaves the session resolved: refund anyone who never locked in,
then either deal the challenge hand (if two or more are still live and ready) or pay the rider out.
*Code only.*

### 6. Nothing stops an AFK player stalling a hand

`game.turn-timeout-seconds` existed in config and was read by no code.

**Fixed and now on by default (60s).** `GameManager.armTurnTimer()` schedules a warning
(`game.turn-warning-seconds`, default 10s) and a deadline for whoever the game is waiting on. On
expiry it makes the smallest legal move: pick a colour if a wild is pending, otherwise draw, which
passes the turn. `UnoGame.moveCount()` is a monotonic counter the timer compares against, so a player
who moves just in time never gets played for. Bots are exempt — they move on their own schedule.
*Code only.*

### 7. Admins have no way to clean up

There was no `/uno end`, `/uno refund` or `/uno list`. The only remedy for a stuck state was a server
restart.

**Fixed.** `/uno end <player|all>` force-ends a hand — with no winner, so any pot refunds rather than
being handed to someone. `/uno refund <player|all>` settles a stuck pot back to its stakers.
`/uno list`, `/uno info` and `/uno tp <id>` cover visibility. *Server verified.*

### 8. Removing a table mid-game strands everything attached to it

Seat mounts were never registered in `entityIds()`, the `seated` map kept pointing at a dead table id,
and running pile/pot renderers kept floating in mid-air.

**Fixed.** `TableManager.BusyCheck` — wired in `UnoPlugin` to
`hasGameAtTable(id) || hasSessionAtTable(id)` — refuses removal while a hand or pot is live. The seat
`ArmorStand` is registered in `entityIds()` (and de-registered on `leaveSeat`), `remove()` stands
everyone up first, and `despawnVisuals` ejects riders before removing entities. *Code only.*

### 9. `getOfflinePlayer(uuid).getName()` runs on the main thread

Three call sites, one of them reached by `updateBar()` on **every move**. For an uncached UUID this
blocks on a Mojang API request.

**Fixed.** `util/NameCache` answers instantly from a cache (seeded from online players and
`PlayerJoinEvent`) and schedules a one-shot async lookup for anything it doesn't know. `UnoGame` no
longer imports Bukkit at all — it resolves names through an injected `namer`. *Code only.*

---

## P2 — Correctness

### 10. `UnoGame.start()` silently destroys cards

The loop that drew until it found a NUMBER card to flip threw away every action card on the way —
neither discarded nor returned. The deck shrank every hand.

**Fixed.** `flipStartingCard()` collects the rejected cards and `Deck.returnCards()` shuffles them
back in. `dealSize()` also clamps the configured hand size so the deal always leaves more than the
deck's 32 non-number cards behind, guaranteeing the flip finds a number.
*Unit tested* — `startConservesTheDeck`, `oversizedHandsAreClamped`. Reintroducing the bug produced
`expected: <108> but was: <105>`.

### 11. `Deck.draw()` can duplicate cards in play

When the draw pile emptied with a one-card discard, it called `reset()` — building a fresh 108-card
deck while players still held cards from the old one.

**Fixed.** `draw()` returns `null` and callers treat it as "no card available"; `UnoGame.draw()`
passes the turn, `drawTo()` stops early and reports how many it actually dealt.
*Unit tested* — `exhaustedDeckReturnsNull`, `exhaustedDeckPassesTheTurn`. With the bug reintroduced
the deck becomes infinite and the suite exhausts the heap.

### 12. The fan item is not protected from inventory manipulation

Nothing stopped a player shift-clicking their hand into a chest.

**Fixed.** `HandManager.onInventoryClick` / `onInventoryDrag` cancel anything touching a fan item
anywhere — including number-key and offhand swaps, and including stray fans from a finished game.
*Code only.*

### 13. Left-clicking to play a card also swings at what's in front of you

`onLeftClickPlay` read the arm-swing animation but nothing cancelled the resulting attack.

**Fixed.** `onAttackWhileHolding` cancels `EntityDamageByEntityEvent` when the damager has an active
fan. *Code only.*

### 14. Two `onDrop` early-returns leave the item on the ground

The "plugin item" and "stake already locked in" paths messaged the player and returned without
cancelling, so the item really dropped as a world entity.

**Fixed.** Both cancel, as do the three new refusal paths added for finding 27. *Code only.*

---

## P3 — Weight and smoothness

### 15. Chunk events scan every table

`onChunkLoad`/`onChunkUnload` iterated the entire table map on every chunk load.

**Fixed.** `TableManager.byChunk` indexes tables by packed chunk coordinate; both handlers are now a
map lookup. *Code only.*

### 16. The discard pile teleports itself on every play

Ten entity teleport packets to every nearby player, per card played, to re-base the pile.

**Fixed.** At `DISCARD_MAX` the pile is cleared and the new card starts a fresh stack — ten removals
once per ten plays instead of ten teleports every play, and the top card (the live game state) is
always the visible one. *Code only.*

### 17. Escrow rewrites the whole file synchronously on the main thread

Every stake re-serialised every item the server was holding for everyone.

**Fixed.** One file per owner: `plugins/UNO/escrow/<uuid>.yml`. A stake touches one small file and
the synchronous-durability guarantee is unchanged. A legacy `escrow.yml` is imported on first run and
renamed to `escrow.yml.imported`. *Server verified* — seeded a legacy file, confirmed the per-owner
file was written with the item intact and the old file renamed.

### 18. The debug card tester ships in the production jar

`CardTester` ran a 1-tick task per player teleporting seven or more display entities.

**Fixed.** All five debug commands (`fan`, `fanclear`, `hand`, `testcards`, `cleartest`) require
`uno.admin` **and** `debug: true`, and are hidden from tab completion otherwise. *Server verified* —
refused with `debug: false`, allowed after flipping it and running `/uno reload`.

### 19. The whole inventory is scanned on every selection change

`clearStrayFans()` walked all 41 slots on every A/D keypress.

**Fixed.** `updateItem(hand, sweep)` only sweeps when the card list actually changed. *Code only.*

### 20. The resource pack is 6,805 model files

**Partly fixed, deliberately.** The unused `blank` and `picnic_mat` assets are gone (11 files, source
PNGs included). The three density tiers stay: `chooseTier` uses tier 0 for hands up to 8 cards, tier 1
for 9-14 and tier 2 for 15+, so dropping one either overflows the screen on big hands or needlessly
cramps small ones. The file count is inherent to the composite-fan approach — 21 slots × 3 tiers ×
54 cards × 2 variants — and changing it means redesigning the fan, not trimming assets.

---

## P4 — Admin experience

### 21. Twelve documented config keys do nothing

**Fixed.** Every key in `config.yml` is now read, all through `util/Settings` — the only class in the
plugin that touches `getConfig()`. Implemented: `game.starting-hand-size`, `turn-timeout-seconds`
(+ a new `turn-warning-seconds`), `tables.casino.min/max-players`, `tables.max-per-world`,
`dealer.enabled`, `debug`, and `resource-pack.url.*` / `require` / `prompt` (offered on join by
`ResourcePackSender`; the plugin still never hosts the pack). Deleted rather than faked:
`reconnect-grace-seconds` (contradicts the forfeit-on-quit rule), `uno-penalty-cards` and
`uno-call-window-seconds` (no UNO-call mechanic exists), `active-pack`, `serve-mode`, `embedded.*`,
`dealer.chat`/`titles`/`long-game-minutes`. *Server verified* — a script cross-checks that every
config key is read and that `Settings` reads nothing that isn't in the file.

### 22. Every player-facing string is hardcoded

**Fixed.** All text lives in `messages.yml` and renders through `util/Messages` with MiniMessage. The
jar's copy is registered as defaults, so an admin's file only needs the keys they changed and an
upgrade that adds keys can't leave holes. Placeholder values are inserted unparsed — a player named
`<red>oops` cannot inject formatting into a broadcast. A script cross-checks that every key used in
code exists and every key defined is used. *Server verified* — reworded a message on disk,
`/uno reload`, new wording shown.

### 23. No tab completion

**Fixed.** `UnoCommand` and `GambleCommand` implement `TabCompleter`. Admin subcommands are hidden
from players without `uno.admin` and debug ones unless `debug: true`; second-argument completion
covers table ids, player names and `all`. *Code only* — completion can't be driven from a console.

### 24. No audit log

**Fixed.** `bet/BetLog` appends every stake, payout, refund, forfeit, deal, ride, cancel and shutdown
to `plugins/UNO/bets.log` with a UTC timestamp, table id, player name **and** UUID, and the stacks
involved. Written on a single background thread and drained on disable — it is evidence, not state,
so it must never cost a disk write mid-tick. Toggle with `gambling.audit-log`. *Code only.*

### 25. No visibility commands

**Fixed.** `/uno list` (id, world, coordinates, occupancy, plus tables waiting on an unloaded world),
`/uno info` (running hands and open pots with state and pot size), `/uno tp <id>`. *Server verified.*

### 26. `api-version` mismatch

**Fixed.** `plugin.yml` declares `api-version: '26.2'`, matching the paper-api in `pom.xml`.
*Server verified* — loads on Paper 26.2 with no compatibility warning.

### 27. No safety limits

**Fixed.** `tables.max-per-world`, `gambling.limits.max-pot-items`, `min-ante-items`, and a
`blacklist` whose entries also match by suffix so `SHULKER_BOX` covers all 17 dyed variants. Plus
`block-containers`, which structurally refuses anything with items inside it — without it "one item"
can be a shulker box of netherite. Every refusal cancels the drop. *Code only.*

---

## P5 — Maintainability

### 28. `UnoPlugin.onCommand` is a 180-line switch

**Fixed.** Split into `command/UnoCommand` and `command/GambleCommand`, one method per subcommand,
with permission and debug gating factored into `notAdmin()` / `debugPlayer()`. `UnoPlugin` is now
just wiring. *Server verified.*

### 29. Two messaging APIs in use at once

**Fixed.** Standardised on Components/MiniMessage. There are no `§` codes and no `sendMessage(String)`
calls left in the source — both are checked by grep. *Server verified.*

### 30. Test stubs remain in production paths

The "Step 5" fallbacks in `playSelected()` / `drawCard()` dealt random cards when no game was active.

**Fixed.** Both stubs and the `DECK` field they used are gone; with no game the input is simply
ignored. `/uno fan` remains a pure rendering test. *Code only.*

### 31. No tests, despite the rules layer being pure logic

**Fixed.** 71 tests across `CardTest`, `DeckTest` and `UnoGameTest`, including a 40-run randomised
full-game fuzz that asserts the 108-card invariant, legal turn order and termination on every move.
`UnoGame` dropped its last Bukkit import to make this possible. Both findings 10 and 11 were verified
to fail the suite when reintroduced.

### 32. Duplicated constants

**Fixed.** The felt height lives once as `UnoTable.SURFACE_Y`. `generate_held_fan.py` now parses
`MAX_SLOTS` and `DENSITIES` out of `HandManager.java` (`java_constants()`) instead of keeping a second
copy — the Java is the single source of truth, and the script exits with a clear message if those
fields are ever renamed rather than silently emitting wrong-angle cards. Regeneration was confirmed
byte-identical.

### 33. Repository hygiene

**Fixed.** Twelve `.DS_Store` files and the 57-file `cards_textures_backup/` are untracked
(`git rm --cached`, files left on disk) and `.gitignore` covers them, plus `server/` for local test
servers.

---

## Known gaps

- **No runtime test of the player-facing paths.** The held fan, death handling, inventory protection,
  staking and the turn timer are implemented and reviewed but never exercised against a live client:
  `minecraft-protocol` supports up to 1.21.11 and cannot speak the 26.x protocol, so no headless bot
  could be driven against a Paper 26.2 server. These need a human with a Minecraft client and the
  resource pack installed. The P0 item-loss paths (1, 2) are the ones most worth a manual pass.
- **`generate_card_font.py` is still dead code** — an abandoned HUD-font approach whose outputs aren't
  in the pack. It was outside the audit's scope; delete it when someone is sure.
