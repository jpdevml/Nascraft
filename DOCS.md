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
| `/market [portId]` | Open the local port, or global when outside all ports. A port id opens remotely with `nascraft.ports.bypass`. |
| `/market global` | Open global from anywhere, including inside a port. |
| `/market order <buy\|sell> <good> <amount> <price> [global]` | Place an escrowed player-only order. Price is per item. |
| `/market orders`, `/market cancel <id>`, `/market claim` | View orders, cancel unfilled quantity, and collect fills or cancelled escrow. |
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
| `inventorygui.yml` | Port menu / buy-sell GUI layout (slots, fillers, navigation). |
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
noise: every item and coin must be supplied by players. Place an order with
`/market order sell iron_ingot 64 10.00 global` (deposit 64 ingots) or
`/market order buy iron_ingot 64 10.00 global` (reserve funds). The GUI buttons
fill existing opposite orders all-or-nothing. Crossing limit orders are
rejected; use an instant trade instead. Use `/market claim` to collect filled
orders or cancelled escrow. Stock shown is the sum of sell orders. Conversion
variants (e.g. iron blocks sharing ingot stock) are disabled for player-only
goods. Player-only trading currently uses two-decimal Vault prices with no tax.

Vanilla items remain supported: use `material: DIAMOND` in `items.yml` or use
the material name as the identifier. Bukkit serialized `item-stack` and
`material` + `model-data` + `display-name` remain supported. Being in `items.yml`
alone does not list a good: put it under a port or `global-market.goods`.

**Safety boundary:** SQLite, Vault, and Bukkit inventories cannot share one
atomic transaction. On ambiguous external payouts, `bazaar_claims` records
remain `DELIVERING` for manual reconciliation and must not be blindly retried.
Lossless exactly-once transfers require a transactional/idempotent economy and
inventory backend. Back up `data/sqlite.db` before reconciling claims.

Before enabling player-only goods on a production server, test on a staging
server with the real Vault provider: simultaneous buyers, repeat-clicking, order
partial fills/cancellation, inventory-full claims, reload/disconnect while the
sell GUI holds goods, failed deposits, and a forced database error. Verify that
`bazaar_orders.remaining`, `bazaar_claims`, and `bazaar_fills` reconcile; never
reset a `DELIVERING` claim without checking the player's actual balance/items.

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
