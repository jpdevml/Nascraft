# Nascraft — Ports edition

A fork of Nascraft with **port-based local markets** and a **global bazaar**.
Each port is tied to a physical location and has its own prices and stock. Prices are driven by local
supply: plentiful goods are cheap, scarce goods are expensive. The intended loop
is mercantile arbitrage — buy where a good is produced, haul it, sell where it's
in demand.

- **Build target:** Spigot API 1.21.11, Java 21 (Maven, shaded jar)
- **Version:** `2.0.0-ports` (branch `ports-rework`)
- **Requires:** Vault + an economy plugin
- **Optional:** Discord (JDA, bundled), DiscordSRV, PlaceholderAPI

## Concept

A **port** (`market/Port.java`) has a world, an `(x, z)` center and a `radius`
(the column is covered at all heights). Players must stand inside a port to trade
there. Each port owns its own `Item` instances, so the same good can be cheap at
one port and expensive at another. Goods restock on a per-port randomized timer.

## Commands

(Names/aliases are configurable; gate with `nascraft.*` permissions.)

| Command | Purpose |
|---------|---------|
| `/market [portId]` | Open the local port's category bazaar, or global when outside all ports. A port id opens remotely with `nascraft.ports.bypass`. |
| `/market global` | Open global from anywhere, including inside a port. |
| `/market order <buy\|sell> <good> <amount> <price> [global]` | Place an escrowed player-only order. Price is per item. |
| `/market orders`, `/market claims`, `/market cancel <id>`, `/market claim` | Open order/claim GUIs or use command shortcuts to cancel/collect. All order operations are available through the GUI. |
| `/sellhand` | Sell the item in your hand to the local port. |
| `/sellall` | Sell sellable inventory items to the local port. |
| `/sell-menu` | Open the sell GUI. |
| `/nascraft` | Admin command (reload, etc.). |
| `/link`, `/discord` | Account linking / Discord info (when Discord enabled, NATIVE linking). |

## Configuration

| File | What it defines |
|------|-----------------|
| `ports.yml` | Port definitions, the global catalog, and per-good `liquidity: managed\|player-only`. Ships with example ports. |
| `items.yml` | The catalog of tradeable goods (materials, aliases, price params). |
| `config.yml` | General settings + `discord-bot` section (token, link-method, trade log channel). |
| `inventorygui.yml` | Port menu / buy-sell GUI layout (slots, fillers, bazaar navigation). |
| `langs/*.yml` | Messages (en_US is the reference). |

### Defining a port (ports.yml)

```yaml
ports:
  saltmere:
    display-name: '<gradient:#4fc3f7:#81d4fa>Saltmere Harbor</gradient>'
    location: { world: 'world', x: 0, z: 0, radius: 50 }
    restock: { min-minutes: 40, max-minutes: 80 }
    goods:
      cod:    { initial-price: 3, starting-stock: 800, restock-amount: 256 }
      iron_ingot: { initial-price: 16, starting-stock: 40, restock-amount: 12 }
```

Rule of thumb: a port that **produces** a good → low price, high stock, high
restock; a port that **demands** it → high price, low stock, low restock.

### Global / player-only goods

`global-market.goods` in `ports.yml` explicitly selects goods from `items.yml`.
Existing installations must add this section to their existing `ports.yml`;
updated bundled defaults do not overwrite existing files.
`liquidity: player-only` turns off starting stock, admin restocks, and price
noise: every item and coin must be supplied by players. Players can create, price,
review and cancel orders, and collect claims through `/market` without commands;
click the quantity or price button to enter a value in chat (type `cancel` to return to the editor; invalid input can be retried). The existing command
shortcuts remain available. Configure category icons/identifiers under
`bazaar-categories` in `items.yml`; only goods selected in `ports.yml` appear.
Existing installations without this section use the bundled categories, with
unmatched goods under Other. No five-minute price timer is shown for player-only
goods: best bids and asks come from player orders. Place an order with
`/market order sell iron_ingot 64 10.00 global` (deposit 64 ingots) or
`/market order buy iron_ingot 64 10.00 global` (reserve funds). The GUI instant-trade buttons
fill existing opposite orders all-or-nothing. Crossing limit orders are
rejected; use an instant trade instead. Use the GUI Claims button (or `/market claim`) to collect filled
orders or cancelled escrow. READY claims can be collected individually or in
bulk; DELIVERING claims are never retried automatically. Stock shown is the sum of sell orders. Conversion
variants (e.g. iron blocks sharing ingot stock) are disabled for player-only
goods. Player-only trading currently uses two-decimal Vault prices with no tax.

Vanilla items remain supported: use `material: DIAMOND` in `items.yml` or use
the material name as the identifier. Bukkit serialized `item-stack` and
`material` + `model-data` + `display-name` remain supported. Being in `items.yml`
alone does not list a good: put it under a port or `global-market.goods`.

**Safety boundary:** SQL, Vault, and Bukkit inventories cannot share one
atomic transaction. On ambiguous external payouts, `bazaar_claims` records
remain `DELIVERING` for manual reconciliation and must not be blindly retried.
Lossless exactly-once transfers require a transactional/idempotent economy and
inventory backend. Back up the active database before reconciling claims.

Before enabling player-only goods on a production server, test on a staging
server with the real Vault provider: simultaneous buyers, repeat-clicking, order
partial fills/cancellation, inventory-full claims, reload/disconnect while the
sell GUI holds goods, failed deposits, and a forced database error. Verify that
`bazaar_orders.remaining`, `bazaar_claims`, and `bazaar_fills` reconcile; never
reset a `DELIVERING` claim without checking the player's actual balance/items.

## Shared MariaDB network

Set `database.type: mysql` and fill in `database.mysql` in `config.yml` on **every** server. The bundled MariaDB JDBC driver connects to MariaDB over TCP. The `plugin.yml` dependency on Vault is static; Bukkit cannot declare a conditional dependency based on storage mode, and MariaDB is not a Bukkit plugin. The plugin refuses to enable if the selected database is unreachable. Changing the backend or its connection settings requires a restart, not `/nascraft reload`. Never put the DB password into version control; restrict DB access and enable TLS for untrusted networks.

Every server must use the **same** `ports.yml`, `items.yml`, Vault economy and shared player balances/inventories (and compatible item serialization). Database sharing does not itself synchronize Vault balances or Minecraft inventories. Run all servers on the same Nascraft version; IDs must agree across servers. Managed trades lock their item rows before checking price/stock; order placement and fills lock the book in MariaDB. A single database schedule elects the winner for automated restocks and noise. Display state refreshes approximately every 10 seconds; a displayed quote may change before the trade is committed. The five-minute snapshot writer is disabled in MySQL mode, so another server cannot overwrite committed state at shutdown.

**Migration from SQLite:** Stop *all* Nascraft instances. Back up `plugins/Nascraft/data/sqlite.db`, player inventories and economy balances. Create a **new, empty** MariaDB database and a user with schema creation/read/write privileges. Run the offline importer from the plugin jar (before setting `database.type: mysql`):

```bash
export NASCRAFT_MIGRATION_PASSWORD='your-db-password'
java -cp target/Nascraft-2.0.0-ports.jar \
  me.bounser.nascraft.database.mysql.SqliteImporter \
  plugins/Nascraft/data/sqlite.db \
  jdbc:mariadb://db.example.com:3306/nascraft nascraft
```

The importer refuses an already populated target, copies IDs (including order and claim IDs) and validates per-table row counts; it does not touch the SQLite source. Confirm claim statuses, order quantities and stock against the backup, then switch *all* servers to MySQL mode. Do not start SQLite-mode and MySQL-mode servers simultaneously against the same economy. Keep backups for rollback; reverting after new MariaDB trades requires reconciling them, not merely changing the config.

**Operational limits:** Failed/uncertain Vault or inventory transfers still require manual reconciliation. A SQL transaction cannot make external money/items exactly-once; in particular do not automatically retry `DELIVERING` claims. Database errors during managed settlement halt trading locally and attempt to pause the network; if the database itself is unreachable, stop other servers manually until reconciled. A shared pause is polled every two seconds; the server that reported an uncertain settlement remains stopped until explicitly resumed there. SQL queries and the shared-state refresh currently run on the Bukkit main thread and can stall a tick during DB latency/outage; deploy a low-latency MariaDB server and monitor its availability. Historical volume sampling follows the elected server's in-memory counters, so it does not yet represent total network volume. `/nascraft stop`/`resume` updates the network pause, subject to polling delay. Do not use this mode for a network requiring cross-server exact-once inventory/economy settlement or network-wide volume charts without implementing those backends first.

Testing: `mvn test` runs SQLite regression tests. With a dedicated empty MariaDB test database, set `NASCRAFT_TEST_MARIADB_URL`, `NASCRAFT_TEST_MARIADB_USER`, and `NASCRAFT_TEST_MARIADB_PASSWORD`, then run `mvn test` for schema/import/lock tests. Stage real two-server buy/sell, simultaneous fills/cancellations/claims, reload, restocks, and outage/ambiguous Vault payouts before production.

## Discord integration

Informational only (remote trading was removed — markets are location-locked):

- Slash commands: `/ports`, `/port <id>` (live local price table), `/balance`,
  `/link`, `/unlink`.
- Buffered **trade log** posts each trade with the port it happened at.
- Linking via **NATIVE** (in-game `/link <code>`) or **DiscordSRV**.

## Build

```bash
JAVA_HOME=/path/to/jdk-21 mvn clean package
# → target/Nascraft-2.0.0-ports.jar   (shaded, includes JDA)
```

## Notes

- Some leftover enum constants from the removed portfolio/debt and chart systems
  remain in `Message.java`; `admin-role-id` in config is currently unused.
- See `[[ports.yml]]` header comment for the full list of per-good overrides.
