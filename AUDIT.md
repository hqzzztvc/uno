# Code Audit

A full review of the UNO plugin at commit `187332f` — every Java file in `src/main/java`, the
resource-pack generators, `plugin.yml` and `config.yml`. 33 findings, ordered by the damage they do
to server admins and their players.

Every finding was verified against the source; file and line references are given throughout.

**Contents**

- [P0 — Item and data loss](#p0--item-and-data-loss) (4)
- [P1 — Stuck states and stability](#p1--stuck-states-and-stability) (5)
- [P2 — Correctness](#p2--correctness) (5)
- [P3 — Weight and smoothness](#p3--weight-and-smoothness) (6)
- [P4 — Admin experience](#p4--admin-experience) (7)
- [P5 — Maintainability](#p5--maintainability) (6)
- [Where to start](#where-to-start)

---

## P0 — Item and data loss

Silent destruction of player property or persisted state. None of these need a design decision to fix.

### 1. The hand fan destroys whatever is in the player's hotbar slot

`HandManager.java:114` sets `fanSlot` to the slot the player is currently holding, and
`HandManager.java:266` writes the fan item straight over it. Nothing saves the displaced stack, and
`hide()` (`HandManager.java:124`) nulls the slot when the hand ends.

**Failure:** sit down holding a netherite sword, run `/uno start`, and the sword is gone permanently.
Also reachable through `/uno fan` and `/uno hand`.

**Fix:** store the displaced `ItemStack` on the `Hand` record and restore it in `hide()`. Better,
prefer an empty hotbar slot and only displace as a fallback.

### 2. The fan item drops on death

There is no `PlayerDeathEvent` handler anywhere in the plugin.

**Failure:** dying mid-hand without `keepInventory` drops a paper item rendering as a 21-card fan on
the ground for anyone to pick up, while the player's real hand state is untouched.

**Fix:** `PlayerDeathEvent` → strip any `uno:held` item from the drop list; `PlayerRespawnEvent` →
re-render the hand.

### 3. Tables are silently and permanently deleted when their world isn't loaded

`TableManager.load():110-115` skips any table whose world resolves to null — the normal case with
Multiverse and similar, which load worlds *after* plugins enable — logging a warning and never adding
it to the `tables` map. `save():130-150` then rewrites `tables.yml` from that same map.

**Failure:** the next table anyone places erases every skipped table from disk forever.

**Fix:** keep unresolved entries in a pending list and write them back verbatim on save, or defer
`load()` to the first server tick when all worlds are up.

### 4. Shutdown hands items back during plugin disable

`BetManager.shutdown():572-579` calls `refundAll`, which reaches `EscrowStore.give()` and mutates
player inventories while the server is stopping. Whether those inventories are still persisted
afterwards is timing-dependent. The comment directly above the call already explains that escrow
makes the refund unnecessary.

**Fix:** delete the `refundAll` call and let `deliverPending` return items on next join — that path is
already crash-proof and is the one a hard kill exercises anyway.

---

## P1 — Stuck states and stability

States a server cannot recover from without a restart.

### 5. A challenged "let it ride" pot can hang forever

`BetManager.ride():462-467` arms a challenge timer that only pays out when `live().size() < 2`. If a
challenger stakes in but never runs `/gamble ready`, the timer fires, does nothing, and no further
timer is ever scheduled — `armAnteTimeout` is called only at session creation (`BetManager.java:127`).

**Failure:** the session sits in `ANTE` indefinitely, holding everyone's items in escrow and locking
the table.

**Fix:** re-arm the ante timeout every time a session re-enters `ANTE`, not just when it is opened.

### 6. Nothing stops an AFK player stalling a hand

`game.turn-timeout-seconds` exists in `config.yml` and is read by no code.

**Failure:** one player alt-tabbing freezes the hand, the table and the pot for everyone else,
indefinitely.

**Fix:** implement the timer — auto-draw, then auto-pass on expiry.

### 7. Admins have no way to clean up

There is no `/uno end`, no `/uno refund`, no `/uno list`. If findings 5 or 6 occur, the only remedy is
a server restart. For a plugin holding players' valuables, this is the first gap to close after the
P0 items.

**Fix:** admin subcommands to force-end a game, force-settle or refund a pot, and list active
tables/games/sessions.

### 8. Removing a table mid-game strands everything attached to it

`TableManager.removeNearest():197-216` despawns visuals and drops the table from the map, but:

- the seat mount armour stands spawned in `sit():463-476` are never registered in `table.entityIds()`,
  so they are not despawned and the rider is left mounted on an invisible entity;
- the `seated` map still points at a table id that no longer exists;
- any running game's `PileRenderer` and the session's `PotRenderer` entities keep floating in mid-air.

**Fix:** refuse removal while a game or bet session is active at that table, and register the mount in
`entityIds()` so it is torn down with everything else.

### 9. `getOfflinePlayer(uuid).getName()` runs on the main thread

Three call sites: `UnoGame.java:334`, `GameManager.java:558`, `BetManager.java:705`. For a UUID not in
the local cache this can block on a Mojang API request. `GameManager.updateBar()` reaches it on **every
move**.

**Fix:** resolve names once per game into a cache; never resolve inside a render or broadcast path.

---

## P2 — Correctness

Rules and state bugs that produce wrong behaviour rather than crashes.

### 10. `UnoGame.start()` silently destroys cards

`UnoGame.java:59-63` draws in a loop until it gets a NUMBER card to flip as the starting card. Every
action card drawn on the way is thrown away — not added to `discard`, not returned to the deck. The
deck quietly shrinks every hand.

**Fix:** collect the rejected cards and shuffle them back into the draw pile.

### 11. `Deck.draw()` can duplicate cards in play

`Deck.java:65` — when the draw pile empties and the discard has one card or fewer, it calls `reset()`,
building a fresh 108-card deck **while players still hold cards**. The same card now exists twice in
one game. The comment calls it a safety net; it is reachable with six players and heavy `+4` chains.

**Fix:** return null and have the caller treat it as "no card available — pass" rather than fabricating
a new deck.

### 12. The fan item is not protected from inventory manipulation

No `InventoryClickEvent` or `InventoryDragEvent` handler covers it — `GameManager.java:412` only guards
the wild-colour GUI. `clearStrayFans()` tidies the player's own inventory on the next update, but not a
chest.

**Failure:** a player can shift-click their hand into a chest.

**Fix:** cancel clicks and drags on any item whose item-model is `uno:held`.

### 13. Left-clicking to play a card also swings at what's in front of you

`HandManager.onLeftClickPlay():317` reads the arm-swing animation to trigger a play, but nothing
cancels the resulting attack.

**Failure:** playing a card while facing a teammate hits them.

**Fix:** cancel `EntityDamageByEntityEvent` for players with an active fan.

### 14. Two `onDrop` early-returns leave the item on the ground

`BetManager.java:286-288` (plugin item) and `BetManager.java:290-292` (stake already locked in) message
the player and return without `setCancelled(true)`, so the item really drops as a world entity.

**Fix:** cancel the event on both paths.

---

## P3 — Weight and smoothness

Wasted main-thread work, packets and client load.

### 15. Chunk events scan every table

`TableManager.onChunkLoad`/`onChunkUnload` (`:408-437`) iterate the entire table map on *every* chunk
load — one of the hottest events on a busy server.

**Fix:** index tables in a `Map<Long, List<UnoTable>>` keyed by packed chunk coordinates.

### 16. The discard pile teleports itself on every play

`PileRenderer.addToDiscard():110-114` teleports all ten discard displays down one step whenever the
pile is at capacity — ten entity teleport packets to every nearby player, per card played.

**Fix:** adjust each display's `Transformation` translation instead, or drop the re-basing entirely.

### 17. Escrow rewrites the whole file synchronously on the main thread

`EscrowStore.save():172-187` serialises and writes all held items on every stake, refund and payout.
The safety reasoning is sound and should be preserved — but the implementation isn't the only way to
get it.

**Fix:** write per-owner files so a stake touches one small file, or hand an immutable snapshot to a
single-threaded executor and fsync on disable.

### 18. The debug card tester ships in the production jar

`CardTester.java:90-101` runs a 1-tick task per player teleporting seven or more display entities. The
class documents itself as throwaway.

**Fix:** delete it, or gate it behind the (currently unused) `debug` config flag.

### 19. The whole inventory is scanned on every selection change

`HandManager.updateItem()` calls `clearStrayFans()` (`:282`), which walks all 41 inventory slots — on
every A/D keypress, not just when the hand contents change.

**Fix:** only sweep when cards are added or removed.

### 20. The resource pack is 6,805 model files

21 slots × 3 density tiers × 54 cards × 2 variants, plus a 748 KB `assets/uno/items/held.json`. It
compresses well, but the file count slows client-side pack loading.

**Fix:** drop the unused `blank` and `picnic_mat` assets, and reconsider whether all three density
tiers earn their place.

---

## P4 — Admin experience

The difference between a plugin admins tolerate and one they recommend.

### 21. Twelve documented config keys do nothing

Everything outside `gambling.*` — `game.*`, `tables.*`, `dealer.*`, `debug`, and the entire
`resource-pack` block — is read nowhere in the code. An admin who sets `starting-hand-size: 5`, sees no
change, and files a bug is behaving reasonably.

**Fix:** implement them or delete them. At minimum, mark them as not-yet-implemented in the file.

### 22. Every player-facing string is hardcoded

Legacy `§` codes throughout, no `messages.yml`. Admins cannot reword, restyle or translate anything.
For a plugin this chatty, this is the single largest admin-friendliness win available.

### 23. No tab completion

`/uno` has twelve subcommands, discoverable only by triggering the error message.

**Fix:** implement `TabCompleter` — and hide admin subcommands from players without `uno.admin`.

### 24. No audit log

A gambling plugin with no record of who staked what and who collected it cannot survive its first
"he stole my diamonds" dispute.

**Fix:** append every stake, payout, refund and forfeit to `plugins/UNO/bets.log` with timestamps and
UUIDs.

### 25. No visibility commands

Nothing shows an admin the current state of the plugin: `/uno list` (tables with coordinates and
occupancy), `/uno tp <id>`, `/uno info` (running games and open pots).

### 26. `api-version` mismatch

`plugin.yml` declares `api-version: '1.21'` while `pom.xml` builds against Paper 26.2. Paper may apply
legacy compatibility behaviour you don't want.

**Fix:** raise it to match the API actually targeted.

### 27. No safety limits

Nothing an admin can cap: tables per world, maximum pot size, minimum ante, or an item blacklist —
nothing prevents staking a shulker box full of netherite.

---

## P5 — Maintainability

### 28. `UnoPlugin.onCommand` is a 180-line switch

Routing, permission checks, argument parsing and user messaging are all interleaved. Split into
per-subcommand handlers.

### 29. Two messaging APIs in use at once

Legacy `§` strings and Adventure `Component`s are mixed within single classes (`BetManager` uses both),
and `sendMessage(String)` is deprecated in Paper. Standardise on Components/MiniMessage — this pairs
naturally with finding 22.

### 30. Test stubs remain in production paths

`HandManager.playSelected()` and `drawCard()` still contain "Step 5" fallbacks that deal random cards
when no game is active. Dead paths that can only produce confusing behaviour.

### 31. No tests, despite the rules layer being pure logic

`UnoGame`, `Deck` and `Card` have zero Bukkit dependencies and are trivially unit-testable. Findings 10
and 11 are exactly what a couple of hundred lines of JUnit would have caught. **Highest
quality-per-effort item in this document.**

### 32. Duplicated constants

- The felt surface height `0.757` appears in `GameManager.java:192` and `BetManager.java:57`.
- `MAX_SLOTS` and `DENSITIES` are duplicated between `HandManager.java` and
  `resourcepack/generate_held_fan.py` (already documented in `CLAUDE.md`).

### 33. Repository hygiene

Seven `.DS_Store` files are tracked, and `cards_textures_backup/` duplicates all 56 card PNGs already
present in the resource pack.

**Fix:** `git rm --cached` both, and extend `.gitignore`.

---

## Where to start

1. **Findings 1, 2, 3, 4** — all silent item or data destruction, none requiring a design decision.
   One focused session.
2. **Findings 5, 6, 7** — the stuck states, plus the admin escape hatch that makes any future stuck
   state survivable.
3. **Finding 31** — tests before any refactor, so everything after has a net.
4. **Findings 21, 22, 23, 24** — the admin-experience cluster, which is what turns this from a working
   plugin into one people are happy to run.
