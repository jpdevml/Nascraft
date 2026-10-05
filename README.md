# Nascraft — Global Bazaar

A Minecraft 1.21.11 market with a category-based in-game Bazaar GUI.

## How it works

- The bundled `ports.yml` configures **only the global market**. `/market` works
  anywhere; there are no location-restricted ports in the default setup.
- `items.yml` defines the goods and the **Resources**, **Building Blocks** and
  **Crops & Raw Food** GUI categories. `global-market.goods` in `ports.yml`
  decides which goods can actually be traded.
- Iron, gold, gunpowder and coal use `player-only` liquidity: players fund buy
  orders or deposit sell orders. No stock is generated for these goods.
- Blocks, crops and raw meats use `managed` liquidity with starting stock and
  periodic restocks. They use fixed prices and a 20% buy / 20% sell spread so
  buying and immediately selling back cannot be profitable. Set prices to
  suit your economy, including the four-planks-per-log conversion.
- Existing installations must update **both** `plugins/Nascraft/ports.yml` and
  `plugins/Nascraft/items.yml` manually: plugin resource files are not
  overwritten on upgrade. Remove old `ports` entries to run global-only.
- Custom location-restricted ports remain supported if explicitly configured.
- **Money is handled through Vault.** Storage is SQLite (single server) or
  MariaDB (`database.type: mysql`) for a shared network; player-backed orders
  and claims are written synchronously. See [network deployment](DOCS.md#shared-mariadb-network).
- Optional **Discord integration** (JDA): account linking, a trade-log
  channel showing which market each trade happened at, and informational
  `/ports`, `/port`, `/balance` slash commands. Remote trading from Discord is
  not supported.

## Commands

| Command | Description |
|---|---|
| `/market` (`/port`) | Open the global Bazaar (or a configured local port) |
| `/market global` | Open global from any location |
| `/market order <buy\|sell> <good> <qty> <price> [global]` | Place an escrowed player-only order |
| `/market orders`, `/market claims`, `/market cancel <id>`, `/market claim` | Open the GUI lists or use command shortcuts to cancel/collect; all are also available from the bazaar GUI |
| `/sell` | Deposit-and-sell menu at the global market (`/sell global` also works) |
| `/sellhand`, `/sellall` | Sell held item / all matching items at the current market |
| `/nascraft reload\|stop\|resume\|restock\|ports\|log` | Admin tools (`nascraft.admin`) |
| `/link`, `/discord` | Discord account linking |

Permissions: `nascraft.market` (trade), `nascraft.admin` (admin),
`nascraft.ports.bypass` (open ports remotely).

## In-game bazaar

`/market` opens categories, then a paginated list of goods and an item detail page. The GUI shows your purse and, for player-backed goods, live buy/sell order depth and available instant-fill prices. The 1/16/64 quick-buy buttons fill the cheapest sell orders first; quick-sell buttons fill the highest buy orders first, with oldest orders winning price ties. Use **Create buy/sell order** to choose a preset or type a custom quantity and price in chat after clicking the quantity or price button (type `cancel` to return to the editor; invalid input can be retried), review escrow, and confirm. **My orders** allows cancelling remaining quantities; **Claims** collects proceeds, bought items, or cancelled escrow (select a claim or collect all READY claims). Full inventories prevent item-claim collection; a claim marked `DELIVERING` requires staff reconciliation and cannot be retried. Managed goods continue using the existing 1/16/64 buttons and port stock rules.

Categories/icons are configured in `items.yml` (`bazaar-categories`) and are filtered to goods actually listed in the active market. Unknown or uncategorized goods appear under Other. An existing `items.yml` without category definitions uses the bundled categories. GUI sounds are configured in `config.yml`; item-page navigation slots are in `inventorygui.yml`. Old `/market order`, `/market cancel` and `/market claim` commands remain available.

## Removed from upstream

Web UI, AdvancedGUI layouts, portfolios, margin loans, price
alerts, sell wands, CPI/flows charts, custom command currencies, Redis.

## Building

```
mvn package
```

Requires JDK 21+. Built against Spigot API 1.21.11; runs on Paper forks
(e.g. UniverseSpigot).

## Maintainers

- [jamesperreaultdev](https://github.com/jamesperreaultdev)
- [Error11O](https://github.com/Error11O)

Original plugin by [Bounser](https://github.com/Bounser) (MIT).
