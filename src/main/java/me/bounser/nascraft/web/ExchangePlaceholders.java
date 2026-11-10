package me.bounser.nascraft.web;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.managers.MarketNews;
import me.bounser.nascraft.managers.MoneyManager;
import me.bounser.nascraft.market.MarketManager;
import me.bounser.nascraft.market.OrderBook;
import me.bounser.nascraft.market.Port;
import me.bounser.nascraft.market.Portfolio;
import me.bounser.nascraft.market.unit.Item;
import me.bounser.nascraft.market.unit.Price;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * {@code %nascraft_...%} for the exchange pages. Per good: {@code <key>:<market>:<good>[:<n>]}, e.g.
 * {@code %nascraft_p:global:oak_log%} (price), {@code ch} (1h change), {@code chd} (24h), {@code dir}
 * (up/down/flat), {@code reg}/{@code regc} (trend label/class), {@code buy:...:16} / {@code sell:...:16}
 * (item cost / payout), {@code sb}/{@code ss} (shares), {@code sh}/{@code shv}/{@code shpl}/{@code shdir}
 * (the viewer's position), {@code stock}, {@code hi}/{@code lo}, {@code inv} (in the viewer's inventory) and
 * for player-backed goods {@code ask}/{@code bid}/{@code askv}/{@code bidv}/{@code bidp:..:pct}/{@code askp:..:pct}.
 * Lists: {@code pos_<i>_*}, {@code ord_<i>_*}, {@code news_<i>[_dir|_state]}, plus {@code balance},
 * {@code pf_value}/{@code pf_pl}/{@code pf_dir}/{@code pf_count}, {@code ord_count} and {@code claims}.
 */
public final class ExchangePlaceholders extends PlaceholderExpansion {

    private record Cached(long at, Object value) { }
    private static final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Set<String> loading = ConcurrentHashMap.newKeySet();
    private volatile long lastPrune;

    /**
     * Database-backed values. Fresh: returned as is. Stale: the old value is returned and one reload runs off the
     * main thread (stale-while-revalidate), so page refreshes never wait on the database. Missing, or far too old
     * (the async reload keeps failing): loaded now, as before.
     */
    @SuppressWarnings("unchecked")
    private <T> T cached(String key, long ms, Supplier<T> load) {
        Cached c = cache.get(key);
        long now = System.currentTimeMillis();
        if (c != null && now - c.at() < ms) return (T) c.value();
        if (c != null && now - c.at() < ms * 10) {
            if (loading.add(key)) {
                try {
                    Bukkit.getScheduler().runTaskAsynchronously(Nascraft.getInstance(), () -> {
                        try { cache.put(key, new Cached(System.currentTimeMillis(), load.get())); }
                        catch (RuntimeException ignored) { } // keep serving the last value; retried next time
                        finally { loading.remove(key); }
                    });
                } catch (RuntimeException ex) { loading.remove(key); }
            }
            return (T) c.value();
        }
        T value = load.get();
        cache.put(key, new Cached(now, value));
        prune(now);
        return value;
    }

    /** After an order-book change by {@code player}: their orders and claims, and the good's book, reload on next use. */
    public static void invalidate(java.util.UUID player, String market, String good) {
        if (player != null) { cache.remove("orders:" + player); cache.remove("claims:" + player); }
        if (market != null && good != null) cache.remove("book:" + market + ":" + good);
    }

    /** Per-player keys would otherwise pile up for everyone who ever opened the exchange. */
    private void prune(long now) {
        if (now - lastPrune < 60_000) return;
        lastPrune = now;
        cache.entrySet().removeIf(e -> now - e.getValue().at() > 60_000);
        shared.entrySet().removeIf(e -> now - e.getValue().at() > 60_000);
    }

    /** Good keys whose text depends on the viewer; every other good key is the same for everyone. */
    private static final Set<String> PER_VIEWER = Set.of("buy", "sell", "inv", "ss", "sh", "shv", "shc", "shpl", "shdir", "shstate");
    /** Viewer-independent good keys, resolved once per second for everyone (Screens asks per viewer, twice). */
    private final Map<String, Cached> shared = new ConcurrentHashMap<>();
    private static final long SHARED_MS = 1000;

    @Override public String getIdentifier() { return "nascraft"; }
    @Override public String getAuthor() { return "Crusalis"; }
    @Override public String getVersion() { return Nascraft.getInstance().getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    public static String money(double value) {
        if (Math.abs(value) >= 100000) return String.format(Locale.ROOT, "%,.0f", value);
        if (Math.abs(value) < 1 && value != 0) return String.format(Locale.ROOT, "%.3f", value);
        return String.format(Locale.ROOT, "%,.2f", value);
    }

    static String signed(double value) { return (value >= 0 ? "+" : "") + money(value); }

    static String pct(double value) { return String.format(Locale.ROOT, "%+.1f%%", value); }

    static String dir(double value) { return value > 0.05 ? "up" : value < -0.05 ? "down" : "flat"; }

    static Item item(String market, String good) {
        Port port = MarketManager.getInstance().getPort(market);
        return port == null ? null : port.getItem(good);
    }

    /** % change over the stored hour (minute samples). */
    static double change1h(Item item) {
        double then = item.getPrice().getValueAnHourAgo();
        return then <= 0 ? 0 : 100 * (item.getPrice().getValue() / then - 1);
    }

    @Override
    public String onRequest(OfflinePlayer offline, String params) {
        try {
            String result = resolve(offline instanceof Player p ? p : null, params);
            return result == null ? "" : result;
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private String resolve(Player player, String params) {
        int colon = params.indexOf(':');
        if (colon > 0) {
            if (PER_VIEWER.contains(params.substring(0, colon))) return good(player, params.split(":"));
            long now = System.currentTimeMillis();
            Cached c = shared.get(params);
            if (c != null && now - c.at() < SHARED_MS) return (String) c.value();
            String value = good(null, params.split(":"));
            if (value != null) shared.put(params, new Cached(now, value));
            return value;
        }
        if (params.startsWith("news_")) return news(params.substring(5));
        if (player == null) return "";

        switch (params) {
            case "balance": return money(MoneyManager.getInstance().getBalance(player));
            case "pf_value": return money(portfolioValue(player));
            case "pf_cost": return money(portfolioCost(player));
            case "pf_pl": return signed(portfolioValue(player) - portfolioCost(player));
            case "pf_dir": return dir(portfolioValue(player) - portfolioCost(player));
            case "pf_count": return String.valueOf(Portfolio.get().list(player.getUniqueId()).size());
            case "ord_count": return String.valueOf(orders(player).size());
            case "claims": return String.valueOf(claims(player));
        }
        if (params.startsWith("pos_")) return position(player, params.substring(4));
        if (params.startsWith("ord_")) return order(player, params.substring(4));
        return null;
    }

    private double portfolioValue(Player player) {
        double total = 0;
        for (Portfolio.Position p : Portfolio.get().list(player.getUniqueId())) {
            Item item = item(p.market(), p.identifier());
            if (item != null) total += Portfolio.value(item, p.shares());
        }
        return total;
    }

    private double portfolioCost(Player player) {
        double total = 0;
        for (Portfolio.Position p : Portfolio.get().list(player.getUniqueId())) total += p.cost();
        return total;
    }

    private String good(Player player, String[] a) {
        if (a.length < 3) return null;
        Item item = item(a[1], a[2]);
        if (item == null) return "-";
        Price price = item.getPrice();
        String n = a.length > 3 ? a[3] : "1";
        switch (a[0]) {
            case "name": return item.getName();
            case "p": return money(item.isPlayerOnly() ? reference(item) : price.getValue());
            case "ch": return pct(change1h(item));
            case "chd": return pct(100 * item.getChangeLastDayCached());
            case "dir": return dir(change1h(item));
            case "dird": return dir(100 * item.getChangeLastDayCached());
            case "reg": return item.isPlayerOnly() ? "Player market" : price.getRegime();
            case "regc": return item.isPlayerOnly() ? "book" : price.getRegime().toLowerCase(Locale.ROOT).replace(' ', '-');
            case "stock": return item.isPlayerOnly() ? String.valueOf(book(item).sellVolume()) : String.format(Locale.ROOT, "%,d", item.getStock());
            case "hi": return money(price.getDayHigh());
            case "lo": return money(price.getDayLow());
            case "vol": return String.format(Locale.ROOT, "%.1f%%", price.getVolatility() * Math.sqrt(60) * 100);
            case "buy": {
                int units = Integer.parseInt(n);
                double cost = item.isPlayerOnly() ? snapshot(item).quote(units, true, player == null ? null : player.getUniqueId()) : item.buyPrice(units);
                return cost <= 0 ? "-" : money(cost);
            }
            case "sell": {
                int units = n.equals("all") ? Math.max(1, inventory(player, item)) : Integer.parseInt(n);
                double pay = item.isPlayerOnly() ? snapshot(item).quote(units, false, player == null ? null : player.getUniqueId()) : item.sellPrice(units);
                return pay <= 0 ? "-" : money(pay);
            }
            case "inv": return String.valueOf(inventory(player, item));
            case "sb": return Portfolio.tradable(item) ? money(Portfolio.quote(item, Long.parseLong(n), true)) : "-";
            case "ss": {
                if (!Portfolio.tradable(item)) return "-";
                long units = n.equals("all") ? shares(player, item) : Long.parseLong(n);
                return units <= 0 ? "-" : money(Portfolio.quote(item, units, false));
            }
            case "sh": return String.format(Locale.ROOT, "%,d", shares(player, item));
            case "shv": return money(Portfolio.value(item, shares(player, item)));
            case "shc": {
                Portfolio.Position p = player == null ? null : Portfolio.get().position(player.getUniqueId(), item);
                return p == null || p.shares() == 0 ? "-" : money(p.cost() / p.shares());
            }
            case "shpl": return signed(pl(player, item));
            case "shdir": return dir(pl(player, item));
            case "shstate": return shares(player, item) > 0 ? "held" : "none";
            case "ask": return cents(book(item).bestAskCents());
            case "bid": return cents(book(item).bestBidCents());
            case "askv": return String.valueOf(book(item).sellVolume());
            case "bidv": return String.valueOf(book(item).buyVolume());
            case "bidp": return plain(offset(item, true, Double.parseDouble(n)));
            case "askp": return plain(offset(item, false, Double.parseDouble(n)));
            case "bidpf": return money(offset(item, true, Double.parseDouble(n)));
            case "askpf": return money(offset(item, false, Double.parseDouble(n)));
        }
        return null;
    }

    private static String plain(double value) { return String.format(Locale.ROOT, "%.2f", Math.max(0.01, value)); }

    private static String cents(long cents) { return cents <= 0 ? "-" : money(cents / 100.0); }

    /** Price for a new limit order: the best bid (buy) or ask (sell) moved by {@code pct}, or the reference price. */
    private double offset(Item item, boolean buy, double pct) {
        OrderBook.BookView book = book(item);
        long base = buy ? book.bestBidCents() : book.bestAskCents();
        double price = base > 0 ? base / 100.0 : reference(item);
        return Math.round(price * (1 + pct / 100.0) * 100.0) / 100.0;
    }

    /** Player market reference: the mid of the book, or one side, or the configured price. */
    private double reference(Item item) {
        OrderBook.BookView book = book(item);
        if (book.bestAskCents() > 0 && book.bestBidCents() > 0) return (book.bestAskCents() + book.bestBidCents()) / 200.0;
        if (book.bestAskCents() > 0) return book.bestAskCents() / 100.0;
        if (book.bestBidCents() > 0) return book.bestBidCents() / 100.0;
        return item.getPrice().getInitialValue();
    }

    private OrderBook.BookView book(Item item) { return snapshot(item).book(); }

    /** One order-book read per good every 2 s, shared by every viewer and every buy/sell/ask/bid key. */
    private OrderBook.Snapshot snapshot(Item item) {
        return cached("book:" + item.getPort().getId() + ":" + item.getIdentifier(), 2000, () -> OrderBook.get().snapshot(item));
    }

    private static int inventory(Player player, Item item) {
        if (player == null) return 0;
        org.bukkit.inventory.ItemStack target = item.getItemStack(); // a clone: take it once, not once per slot
        int count = 0;
        for (org.bukkit.inventory.ItemStack stack : player.getInventory().getStorageContents())
            if (stack != null && stack.isSimilar(target)) count += stack.getAmount();
        return count;
    }

    private static long shares(Player player, Item item) {
        return player == null ? 0 : Portfolio.get().shares(player.getUniqueId(), item);
    }

    private static double pl(Player player, Item item) {
        if (player == null) return 0;
        Portfolio.Position p = Portfolio.get().position(player.getUniqueId(), item);
        return p == null ? 0 : Portfolio.value(item, p.shares()) - p.cost();
    }

    private String position(Player player, String rest) {
        int cut = rest.indexOf('_');
        int index = Integer.parseInt(rest.substring(0, cut)) - 1;
        String field = rest.substring(cut + 1);
        List<Portfolio.Position> list = Portfolio.get().list(player.getUniqueId());
        if (index < 0 || index >= list.size()) return field.equals("state") ? "empty" : "";
        Portfolio.Position p = list.get(index);
        Item item = item(p.market(), p.identifier());
        double value = item == null ? 0 : Portfolio.value(item, p.shares());
        return switch (field) {
            case "state" -> "held";
            case "name" -> item == null ? p.identifier() : item.getName();
            case "good" -> p.identifier();
            case "market" -> p.market();
            case "shares" -> String.format(Locale.ROOT, "%,d", p.shares());
            case "price" -> item == null ? "-" : money(item.getPrice().getValue());
            case "avg" -> money(p.cost() / p.shares());
            case "value" -> money(value);
            case "pl" -> signed(value - p.cost());
            case "plpct" -> pct(p.cost() <= 0 ? 0 : 100 * (value / p.cost() - 1));
            case "dir" -> dir(value - p.cost());
            case "ch" -> item == null ? "" : pct(change1h(item));
            default -> null;
        };
    }

    private List<OrderBook.OpenOrder> orders(Player player) {
        return cached("orders:" + player.getUniqueId(), 2000, () -> OrderBook.get().openOrders(player, 0, 11));
    }

    private int claims(Player player) {
        return cached("claims:" + player.getUniqueId(), 2000, () -> OrderBook.get().claimCount(player.getUniqueId(), 50));
    }

    private String order(Player player, String rest) {
        int cut = rest.indexOf('_');
        int index = Integer.parseInt(rest.substring(0, cut)) - 1;
        String field = rest.substring(cut + 1);
        List<OrderBook.OpenOrder> list = orders(player);
        if (index < 0 || index >= Math.min(10, list.size())) return field.equals("state") ? "empty" : "";
        OrderBook.OpenOrder o = list.get(index);
        Item item = item(o.market(), o.identifier());
        return switch (field) {
            case "state" -> o.buy() ? "buy" : "sell";
            case "id" -> String.valueOf(o.id());
            case "name" -> item == null ? o.identifier() : item.getName();
            case "side" -> o.buy() ? "Buying" : "Selling";
            case "qty" -> String.format(Locale.ROOT, "%,d", o.remaining());
            case "price" -> money(o.priceCents() / 100.0);
            case "total" -> money(o.priceCents() * (double) o.remaining() / 100.0);
            default -> null;
        };
    }

    private String news(String rest) {
        String[] parts = rest.split("_", 2);
        int index = Integer.parseInt(parts[0]) - 1;
        List<MarketNews.Story> list = MarketNews.get().recent();
        String field = parts.length > 1 ? parts[1] : "text";
        if (index < 0 || index >= list.size()) return field.equals("state") ? "empty" : field.equals("text") ? "" : "";
        MarketNews.Story s = list.get(index);
        return switch (field) {
            case "state" -> "shown";
            case "dir" -> s.move() > 0 ? "up" : "down";
            case "move" -> pct(100 * (Math.exp(s.move() * 0.5) - 1));
            case "ago" -> ago(s.time());
            default -> s.headline();
        };
    }

    private static String ago(long time) {
        long m = Math.max(0, (System.currentTimeMillis() - time) / 60000);
        if (m < 1) return "just now";
        if (m < 60) return m + "m ago";
        if (m < 1440) return (m / 60) + "h ago";
        return (m / 1440) + "d ago";
    }
}
