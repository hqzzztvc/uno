# Legally Not Uno

A multiplayer card game for Paper servers. You sit at a real table, hold your
cards in a fan in first person, and play onto a pile that everyone at the table can see. Up to four
players per table, with bots to fill empty seats if you're short.

<!-- SCREENSHOT: a full table mid-game, four players seated, piles visible -->

---

## Requirements

- Paper **26.2** (Folia isn't supported)
- Java **25**
- The **[Legally Not Uno Textures](https://modrinth.com/resourcepack/legally-not-uno-textures)**
  resource pack on every player's client. Without it the cards show up as blank paper.
- Optional: **Vault** plus an economy plugin, if you want players to bet money as well as items

## Installation

1. Put the plugin jar in your server's `plugins/` folder.
2. Start the server once so it creates `plugins/LegallyNotUno/`.
3. Set up the resource pack (below).
4. Give yourself a table with `/uno give lightcherry` and right-click the ground where you want it.

### Resource pack

Download the pack from **[Modrinth](https://modrinth.com/resourcepack/legally-not-uno-textures)**.
The easiest way to get it to players is to have the server send it when they join. Open
`server.properties` and fill in these lines:

```properties
resource-pack=https://cdn.modrinth.com/data/.../Legally%20Not%20Uno%20Textures.zip
resource-pack-sha1=<the zip's SHA-1>
require-resource-pack=false
resource-pack-prompt=You'll need this pack to see the cards.
```

- For `resource-pack`, go to the pack's Modrinth page, open the version you want, right-click the
  download button and copy the link. It has to be the direct file link.
- `resource-pack-sha1` is optional but worth setting, otherwise players re-download the pack every
  time they join. You can get the hash by downloading the zip and running
  `certutil -hashfile "Legally Not Uno Textures.zip" SHA1` on Windows or
  `sha1sum "Legally Not Uno Textures.zip"` on Linux/macOS.
- Set `require-resource-pack=true` if you'd rather kick players who decline it.

Restart the server after editing `server.properties`.

If you already use `server.properties` for another pack, the plugin can send this one instead. Put
the same link and hash under `resource-pack` in `plugins/LegallyNotUno/config.yml` and run `/uno reload`.

Players can also just download the pack themselves and add it through
**Options → Resource Packs**.

## How to play

Walk up to a table and type `/uno join` to sit down. You'll get two buttons in chat:

- **[ READY ]** for a normal game. The hand deals as soon as everyone seated has clicked it.
- **[ FOR STAKES ]** to play for items or money (see [Let It Ride](#let-it-ride)).

Tables seat four and need at least two players. If you're on your own, `/uno start 3` deals you in
against three bots.

<!-- SCREENSHOT: first-person view of the card fan in hand -->

### Controls

While you're holding your cards, the plugin takes over your controls, so you can't break or place
blocks until the hand ends.

| Input | Action |
|---|---|
| **A** / **D** or scroll wheel | Move along your hand |
| **Left-click** or **Q** | Play the selected card |
| **Right-click** or **F** | Draw a card |

The bossbar at the top shows the card in play, the current colour, whose turn it is and how many
cards are left in the deck. When you play a wild, pick the colour from the buttons in chat.

Standing up (Shift or `/uno leave`) takes you out of the hand. So does `/uno quit`. Everyone else
keeps playing.

<!-- SCREENSHOT: the discard pile and draw pile on the table -->

## House rules

Every group plays a little differently, so the common variants are in `config.yml` under
`rules:`. They're all **off** by default, which gives you the official rules.

| Rule | What it does |
|---|---|
| `stacking` | Answer a +2 with another +2 instead of drawing. The penalty keeps growing until someone can't add to it. |
| `multi-play` | Play several cards of the same number or symbol in one turn. |
| `jump-in` | If you're holding the exact card on top of the pile, play it out of turn. |
| `seven-o` | A 7 swaps hands with a player you choose. A 0 passes every hand along one seat. |
| `draw-to-match` | Keep drawing until you get a card you can play. |
| `challenge-draw4` | Challenge a Wild +4 you think was played illegally. Guess right and they draw 4, guess wrong and you draw 6. |
| `uno-callout` | Players have to call UNO themselves, and anyone can catch a player who forgets. |

Rule changes apply from the next hand dealt after `/uno reload`.

## Let It Ride

Any table can be played for stakes. Click **[ FOR STAKES ]** when you sit down, then put something
in the pot: drop items onto the table, or type `/gamble <amount>` to bet money if the server has
Vault set up. Click ready once you're happy with your stake. The pot sits on the table while
everyone antes up.

Whoever wins the hand takes the whole pot. They can cash out, or let it ride and leave it on the
table for another hand. Anyone who wants to challenge has to match it.

<!-- SCREENSHOT: a pot of items sitting on the table -->

A few things worth knowing:

- Leaving during a hand counts as a loss. Your stake stays in the pot. Leaving before the cards are
  dealt gets you a full refund.
- Everything staked is saved to disk straight away. If the server crashes, players get their items
  and money back, and anyone who was offline gets theirs the next time they log in.
- Every stake, payout and refund is written to `plugins/LegallyNotUno/bets.log`, so you can check what
  happened if someone says they were robbed.
- If a bot wins, nobody loses anything. Everyone gets their stake back.
- Shulker boxes and bundles can't be staked by default. You can change the blacklist in
  `config.yml`.

| Command | |
|---|---|
| `/gamble <amount>` | Bet money |
| `/gamble ready` | Lock in your stake |
| `/gamble pot` | See what's in the pot and who's playing |
| `/gamble out` | Take your stake back (before the deal only) |
| `/gamble go` | Deal now and refund anyone who hasn't locked in (whoever opened the bet) |
| `/gamble cancel` | Call it off and refund everyone (whoever opened the bet) |

## Table themes

There are nine tables to choose from, all built from wood:

`lightcherry`, `cherry`, `darkcherry`, `spruce`, `darkspruce`, `darkoak`, `lightmangrove`,
`mangrove`, `darkmangrove`

<!-- SCREENSHOT: a row of tables in different themes -->

You can also design your own in game. `/uno theme create <name>` opens a grid laid out like the
table seen from above. Drop blocks into the nine top slots and the four seat slots (seats should be
stairs), then hit Save. You get your blocks back when the editor closes. `/uno give <name>` then
hands out a table using your design.

Themes are stored in `plugins/LegallyNotUno/themes.yml` if you'd rather edit them by hand. Blocks from
ItemsAdder, Oraxen and Nexo work too. Write the custom block, a `|`, and a vanilla block to use if
that plugin isn't installed:

```yaml
- ["itemsadder:marble_pillar|minecraft:quartz_pillar", ...]
```

Tables are made of real blocks, so they save with the world. Only admins can break them.

## Commands

| Command | Who | |
|---|---|---|
| `/uno join` | Everyone | Sit at the nearest table |
| `/uno leave` | Everyone | Stand up |
| `/uno ready` | Everyone | Ready up (same as the button) |
| `/uno start [bots]` | Everyone | Deal now, optionally adding bots |
| `/uno quit` | Everyone | Drop out of your hand |
| `/uno stop` | Everyone | End the hand at your table (not allowed while there's a pot) |
| `/uno help` | Everyone | List the commands you can use |
| `/gamble` | Everyone | Betting, see [Let It Ride](#let-it-ride) |
| `/uno give <theme>` | Admin | Get a placeable table |
| `/uno theme <list\|create\|edit\|delete>` | Admin | Manage table themes |
| `/uno remove` | Admin | Remove the nearest table |
| `/uno list` | Admin | List every table |
| `/uno tp <id>` | Admin | Teleport to a table |
| `/uno info` | Admin | Show running games and pots |
| `/uno end <player\|all>` | Admin | Force a game to end and refund any pot |
| `/uno refund <player\|all>` | Admin | Refund a stuck pot |
| `/uno reload` | Admin | Reload `config.yml`, `messages.yml` and `themes.yml` |

### Permissions

| Permission | Default | |
|---|---|---|
| `legallynotuno.play` | Everyone | Sit and play |
| `legallynotuno.gamble` | Everyone | Place bets |
| `legallynotuno.admin` | Op | Admin commands |

## Configuration

Everything is in `plugins/LegallyNotUno/config.yml`, and each setting has a comment explaining it. The ones
you're most likely to want:

- `game.turn-timeout-seconds`: how long a player can sit on their turn before they automatically
  draw and pass (default 60).
- `game.starting-hand-size`: cards dealt to each player (default 7).
- `gambling.enabled`: turn betting off entirely.
- `gambling.money.enabled`: allow or block money bets when Vault is installed.
- `effects.sounds` / `effects.particles`: turn the effects off if you don't want them.

All chat messages are in `plugins/LegallyNotUno/messages.yml` if you want to reword or translate them.

## Building from source

You need JDK 25 and Maven.

```bash
mvn clean package    # builds target/legallynotuno-1.0.jar and runs the tests
```

Architecture notes for contributors are in [CLAUDE.md](CLAUDE.md).

## Disclaimer

Legally Not Uno is a fan project. It isn't affiliated with, endorsed by or sponsored by Mattel.
UNO is a trademark of Mattel, Inc.

## License

GNU General Public License v3.0. See [LICENSE](LICENSE).
