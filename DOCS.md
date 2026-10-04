# Nascraft — Ports edition

A fork of Nascraft reworked from a single global market into **port-based local
markets**. Each port is a market tied to a physical location in the world, with
its **own prices and stock** for the goods it trades. Prices are driven by local
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
| `/market [portId]` | Open the market for the port you're standing in. With an id, opens it remotely (needs `nascraft.ports.bypass`). |
| `/sellhand` | Sell the item in your hand to the local port. |
| `/sellall` | Sell sellable inventory items to the local port. |
| `/sell-menu` | Open the sell GUI. |
| `/nascraft` | Admin command (reload, etc.). |
| `/link`, `/discord` | Account linking / Discord info (when Discord enabled, NATIVE linking). |

## Configuration

| File | What it defines |
|------|-----------------|
| `ports.yml` | The ports: location, radius, restock timers, and per-good overrides (initial price, stock, restock amount, tax, limits…). Ships with example ports `saltmere`, `emberfall`, `thornwick`. |
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
