# Nascraft — Ports Edition

A heavily reworked fork of [Nascraft](https://www.spigotmc.org/resources/108216/) that turns the
single global item market into **port-based local markets** with their own
economies — Mount & Blade style trading for Minecraft 1.21.11.

## How it works

- **Ports** are local markets placed on the map (`ports.yml`): a world position
  plus a radius. Players must physically stand inside a port to trade there
  (`/market`, `/sell`, `/sellhand`, `/sellall`).
- Every port keeps its **own prices and stock** for the goods it trades.
  Prices are driven by local supply (stock-based exponential pricing): goods a
  port produces are plentiful and cheap; goods it demands are scarce and
  expensive. Buy low at one port, haul the cargo, sell high at another.
- Each port **restocks on its own randomized schedule** (a window of
  min/max minutes), optionally announced to players.
- The goods catalog lives in `items.yml`; ports pick goods from it and can
  override any economic parameter per port (price, elasticity, stock,
  restock amount, taxes, limits).
- **Money is handled through Vault.** State persists in SQLite
  (async + HikariCP connection pooling).
- Optional **Discord integration** (JDA): account linking, a trade-log
  channel showing which port each trade happened at, and informational
  `/ports`, `/port`, `/balance` slash commands. Remote trading from Discord is
  deliberately not supported — markets are location-locked.

## Commands

| Command | Description |
|---|---|
| `/market` (`/port`) | Open the market of the port you're standing in |
| `/sell` | Deposit-and-sell menu at the current port |
| `/sellhand`, `/sellall` | Sell held item / all matching items at the current port |
| `/nascraft reload\|stop\|resume\|restock\|ports\|log` | Admin tools (`nascraft.admin`) |
| `/link`, `/discord` | Discord account linking |

Permissions: `nascraft.market` (trade), `nascraft.admin` (admin),
`nascraft.ports.bypass` (open ports remotely).

## Removed from upstream

Web UI, AdvancedGUI layouts, portfolios, margin loans, limit orders, price
alerts, sell wands, CPI/flows charts, custom command currencies, MySQL/Redis.

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
