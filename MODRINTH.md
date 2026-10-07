<!-- BANNER: wide shot of a table with players seated -->

**Legally Not Uno** brings the card game to your Paper server. Sit down at a table with your
friends, hold your hand as a fan of cards in first person, and play onto a pile in the middle of
the table that everyone can see.

<!-- SCREENSHOT: first-person view of the card fan -->

## Features

- **Real tables.** Place one anywhere and it's built from normal blocks, with four stair seats.
  There are nine wood themes to pick from, or you can design your own in game.
- **Cards in your hand.** Your hand is held as a fan. Scroll through it with A/D or the mouse wheel,
  left-click to play and right-click to draw.
- **Cards on the table.** Played cards land face-up on the discard pile and the draw pile shrinks as
  people draw from it.
- **Bots.** Short on players? Bots can fill the empty seats.
- **House rules.** Stacking, jump-in, Seven-O, draw to match, +4 challenges and calling UNO are all
  there. Turn on the ones your group plays with.
- **Play for stakes.** Any table can be played for items, or for money if you run Vault. Winner
  takes the pot, and can risk it again on the next hand.

<!-- SCREENSHOT: discard and draw piles on the table mid-game -->

## Let It Ride

When you sit down you can choose to play a normal game or play for stakes. To bet, drop items onto
the table or use `/gamble <amount>` for money. Whoever wins the hand takes everything, and then
decides whether to cash out or let it ride for another round.

Stakes are saved the moment they're placed, so a crash or restart won't lose anyone's items. Every
bet and payout is also logged, so admins can check what happened if there's ever a dispute.

<!-- SCREENSHOT: a pot of items on the table -->

## Custom tables

`/uno theme create <name>` opens a grid shaped like the table. Drop in the blocks you want for the
top and the seats and save it. Blocks from ItemsAdder, Oraxen and Nexo work as well.

<!-- SCREENSHOT: the theme editor, and a few tables side by side -->

## Installation

1. Download the plugin and put it in your server's `plugins/` folder.
2. Download the **[Legally Not Uno Textures](https://modrinth.com/resourcepack/legally-not-uno-textures)**
   resource pack. It's required, otherwise the cards show up as blank paper.
3. Add the pack to your `server.properties` so players get it when they join:
   ```properties
   resource-pack=<direct download link from the pack's Modrinth page>
   resource-pack-sha1=<the zip's SHA-1, optional but recommended>
   ```
   To get the link, right-click the download button on the pack's version page and copy it.
4. Restart the server.
5. Run `/uno give lightcherry` and right-click the ground to place a table.

Players sit down with `/uno join` and click **[ READY ]** in chat. The game deals once everyone at
the table is ready.

Full setup instructions, commands and config options are on the
[GitHub page](https://github.com/hqzzztvc/uno).

## Requirements

- Paper 1.21.4 to 26.3 (one jar for all of them)
- Java 21 or newer: 21 for 1.21.x, 25 for 26.x
- Vault and an economy plugin, only if you want money bets

---

*Legally Not Uno is a fan project and isn't affiliated with or endorsed by Mattel. UNO is a
trademark of Mattel, Inc.*
