package me.bounser.nascraft.web;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.market.MarketManager;
import me.bounser.nascraft.market.Port;
import me.bounser.nascraft.market.unit.Item;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The exchange as CrusalisScreens pages, written into its pages folder (it live-reloads them). Prices and the
 * viewer's own numbers come from {@link ExchangePlaceholders}, re-filled every two seconds; charts and sparklines
 * are SVG files {@link ExchangeCharts} rewrites every minute. Buttons run ordinary /market commands as the player.
 *
 * <p>Pages per market {@code m}: {@code nascraft-m} (first category), {@code nascraft-m-c-<cat>}, and per good
 * {@code nascraft-m-g-<good>} (hour chart) and {@code ...-d} (day chart); then {@code nascraft-portfolio} and
 * {@code nascraft-orders}.
 */
public final class ExchangePages {

    static final String[] ART = {"crest", "wood", "baize", "coin", "satchel", "crate", "rivet"};
    private static final int PER_PAGE = 12;

    private ExchangePages() { }

    static Plugin screens() { return Bukkit.getPluginManager().getPlugin("CrusalisScreens"); }

    public static boolean available() {
        Plugin p = screens();
        return Config.getInstance().getScreensEnabled() && p != null && p.isEnabled()
                && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    public static Path dir() { return screens().getDataFolder().toPath().resolve("pages"); }

    public static void open(Player player, String page) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "screens open " + page + " " + player.getName());
    }

    public static String home(Port market) { return "nascraft-" + market.getId(); }

    public static String good(Item item, boolean day) {
        return "nascraft-" + item.getPort().getId() + "-g-" + item.getIdentifier() + (day ? "-d" : "");
    }

    /** A file operation planned on the main thread; it touches only the file system. */
    interface FileOp { void run() throws IOException; }

    private static final Object WRITING = new Object();

    /**
     * Builds every page (and the first charts) on the main thread, where the market and Bukkit are read, then
     * writes the files off it. The minute chart refresh starts once the files are down, so it can't be overwritten
     * by this older first draw.
     */
    public static void write() {
        if (!available()) return;
        Path dir = dir();
        List<FileOp> ops = new ArrayList<>();
        plan(dir, ops);
        Bukkit.getScheduler().runTaskAsynchronously(Nascraft.getInstance(), () -> {
            try {
                synchronized (WRITING) { for (FileOp op : ops) op.run(); }
                Nascraft.getInstance().getLogger().info("Exchange pages written for CrusalisScreens.");
            } catch (IOException e) {
                Nascraft.getInstance().getLogger().warning("Couldn't write the exchange pages: " + e.getMessage());
            }
            if (Nascraft.getInstance().isEnabled())
                Bukkit.getScheduler().runTask(Nascraft.getInstance(), ExchangeCharts::refresh);
        });
    }

    static List<Port> markets() {
        List<Port> list = new ArrayList<>();
        if (MarketManager.getInstance().getGlobalMarket() != null && !MarketManager.getInstance().getGlobalMarket().getParentItems().isEmpty())
            list.add(MarketManager.getInstance().getGlobalMarket());
        list.addAll(MarketManager.getInstance().getPorts());
        return list;
    }

    static void put(List<FileOp> ops, Path file, String text) {
        ops.add(() -> Files.writeString(file, text, StandardCharsets.UTF_8));
    }

    static void plan(Path dir, List<FileOp> ops) {
        ops.add(() -> Files.createDirectories(dir));
        for (String art : ART) {
            Path to = dir.resolve("nascraft-" + art + ".svg");
            ops.add(() -> copy("/web/art/" + art + ".svg", to, true));
        }
        put(ops, dir.resolve("nascraft.css"), ExchangeStyle.CSS);
        for (Port market : markets()) {
            for (Item item : market.getParentItems()) {
                Path icon = dir.resolve("nascraft-icon-" + item.getIdentifier() + ".png");
                String material = item.getItemStack().getType().getKey().getKey();
                String identifier = item.getIdentifier();
                ops.add(() -> {
                    if (!copy("/web/icons/" + material + ".png", icon, false) && !Files.exists(icon))
                        copy("/web/icons/" + identifier + ".png", icon, false);
                });
            }
            List<Config.BazaarCategory> cats = Config.getInstance().getBazaarCategories(market);
            List<String[]> tabs = new ArrayList<>(); // page name, label
            for (Config.BazaarCategory cat : cats) {
                int pages = Math.max(1, (cat.goods().size() + PER_PAGE - 1) / PER_PAGE);
                for (int p = 0; p < pages; p++)
                    tabs.add(new String[]{categoryPage(market, cat, p), cat.name() + (pages > 1 ? " " + (p + 1) : "")});
            }
            int t = 0;
            for (Config.BazaarCategory cat : cats) {
                int pages = Math.max(1, (cat.goods().size() + PER_PAGE - 1) / PER_PAGE);
                for (int p = 0; p < pages; p++, t++) {
                    List<String> goods = cat.goods().subList(p * PER_PAGE, Math.min(cat.goods().size(), (p + 1) * PER_PAGE));
                    String html = category(market, tabs, t, goods);
                    put(ops, dir.resolve(tabs.get(t)[0] + ".html"), html);
                    if (t == 0) put(ops, dir.resolve(home(market) + ".html"), html);
                }
                for (String id : cat.goods()) {
                    Item item = market.getItem(id);
                    if (item == null) continue;
                    String back = categoryPage(market, cat, cat.goods().indexOf(id) / PER_PAGE);
                    put(ops, dir.resolve(good(item, false) + ".html"), goodPage(item, back, false));
                    put(ops, dir.resolve(good(item, true) + ".html"), goodPage(item, back, true));
                }
            }
        }
        put(ops, dir.resolve("nascraft-portfolio.html"), portfolio());
        put(ops, dir.resolve("nascraft-orders.html"), orders());
        ExchangeCharts.plan(dir, ops);
    }

    private static boolean copy(String resource, Path to, boolean replace) throws IOException {
        if (!replace && Files.exists(to)) return true;
        try (InputStream in = ExchangePages.class.getResourceAsStream(resource)) {
            if (in == null) return false;
            Files.copy(in, to, StandardCopyOption.REPLACE_EXISTING);
            return true;
        }
    }

    private static String categoryPage(Port market, Config.BazaarCategory cat, int page) {
        return "nascraft-" + market.getId() + "-c-" + cat.id() + (page > 0 ? "-" + (page + 1) : "");
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String ph(String key, Item item) { return "%nascraft_" + key + ":" + item.getPort().getId() + ":" + item.getIdentifier() + "%"; }

    private static String ph(String key, Item item, Object n) {
        return "%nascraft_" + key + ":" + item.getPort().getId() + ":" + item.getIdentifier() + ":" + n + "%";
    }

    static String icon(Item item) {
        return Files.exists(dir().resolve("nascraft-icon-" + item.getIdentifier() + ".png"))
                ? "nascraft-icon-" + item.getIdentifier() + ".png" : "nascraft-crate.svg";
    }

    private static String head(String title) {
        return "<!doctype html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n<title>" + esc(title) + "</title>\n"
                + "<meta name=\"ui:overlay\" content=\"rgba(0, 0, 0, 0.2)\">\n<meta name=\"ui:height\" content=\"720\">\n"
                + "<meta name=\"ui:refresh\" content=\"2\">\n<link rel=\"stylesheet\" href=\"nascraft.css\">\n</head>\n<body>\n";
    }

    /** The brass-trimmed header: crest, title, purse and portfolio, close. */
    private static String header(String title, String sub, String before) {
        return """
                  <div class="head">%s<img class="crest" src="nascraft-crest.svg">
                    <div class="titles"><span class="title">%s</span><span class="sub">%s</span></div>
                    <span class="spacer"></span>
                    <span class="chip" title="Your purse"><img src="nascraft-coin.svg">%%nascraft_balance%%</span>
                    <a class="chip link %%nascraft_pf_dir%%" data-command="market portfolio" title="Your shares"><img src="nascraft-satchel.svg">%%nascraft_pf_value%%<em>%%nascraft_pf_pl%%</em></a>
                    <a class="btn small x" data-action="close">✕</a>
                  </div>
                """.formatted(before, esc(title), esc(sub));
    }

    private static String tape() {
        StringBuilder b = new StringBuilder("  <div class=\"tape\"><span class=\"label\">NEWS</span>");
        for (int i = 1; i <= 3; i++)
            b.append("<span class=\"story %nascraft_news_").append(i).append("_state% %nascraft_news_").append(i)
                    .append("_dir%\"><i></i>%nascraft_news_").append(i).append("% <small>%nascraft_news_").append(i).append("_ago%</small></span>");
        b.append("<span class=\"quiet\">Quiet day on the exchange.</span></div>\n");
        return b.toString();
    }

    private static String footer(String extra) {
        return """
                  <div class="foot">
                    <a class="btn" data-command="market portfolio">Portfolio</a>
                    <a class="btn" data-command="market orders">Orders &amp; claims <em>%%nascraft_claims%%</em></a>
                    %s
                    <span class="spacer"></span>
                    <span class="hint">Shares follow the price without hauling goods. A fee applies each way.</span>
                  </div>
                """.formatted(extra);
    }

    private static String category(Port market, List<String[]> tabs, int at, List<String> goods) {
        StringBuilder b = new StringBuilder(head(Config.getInstance().getScreensTitle()));
        b.append("<div class=\"window\">\n");
        b.append(header(Config.getInstance().getScreensTitle(), market.getPlainDisplayName(), ""));
        b.append(tape());
        b.append("  <div class=\"tabs\">");
        for (int i = 0; i < tabs.size(); i++)
            b.append("<a class=\"tab").append(i == at ? " on" : "").append("\" href=\"").append(tabs.get(i)[0]).append(".html\">")
                    .append(esc(tabs.get(i)[1])).append("</a>");
        b.append("</div>\n  <div class=\"board\">\n");
        for (String id : goods) {
            Item item = market.getItem(id);
            if (item == null) continue;
            b.append("""
                        <a class="card %s %s" data-command="market view %s %s" data-sound="ui.button.click 0.6 1.4">
                          <div class="top"><img class="icon" src="%s"><div class="nm"><span>%s</span><small class="reg">%s</small></div></div>
                          <div class="mid"><span class="px"><img src="nascraft-coin.svg">%s</span><span class="chg">%s</span></div>
                          <img class="spark" src="nascraft-spark-%s-%s.svg">
                        </a>
                    """.formatted(ph("dir", item), ph("regc", item), market.getId(), id, icon(item), esc(item.getName()),
                    ph("reg", item), ph("p", item), ph("ch", item), market.getId(), id));
        }
        b.append("  </div>\n");
        b.append(footer(""));
        b.append("</div>\n</body>\n</html>\n");
        return b.toString();
    }

    private static String button(String cls, String command, String label, String value) {
        return "<a class=\"btn trade " + cls + "\" data-command=\"" + command + "\" data-sound=\"entity.experience_orb.pickup 0.5 1.6\"><b>"
                + label + "</b><span>" + value + "</span></a>";
    }

    private static String goodPage(Item item, String back, boolean day) {
        String m = item.getPort().getId(), id = item.getIdentifier();
        boolean book = item.isPlayerOnly();
        StringBuilder b = new StringBuilder(head(item.getName()));
        b.append("<div class=\"window good ").append(ph("dir", item)).append(' ').append(ph("regc", item)).append("\">\n");
        b.append(header(item.getName(), item.getPort().getPlainDisplayName(),
                "<a class=\"btn small back\" href=\"" + back + ".html\">‹</a>"));
        b.append("  <div class=\"split\">\n    <div class=\"left\">\n");
        b.append("""
                      <div class="quote"><img class="icon big" src="%s">
                        <div class="qv"><span class="px"><img src="nascraft-coin.svg">%s</span>
                          <span class="chg">%s <small>1h</small></span><span class="chg day %s">%s <small>24h</small></span></div>
                        <span class="spacer"></span><span class="pill">%s</span>
                      </div>
                """.formatted(icon(item), ph("p", item), ph("ch", item), ph("dird", item), ph("chd", item), ph("reg", item)));
        if (!book) {
            b.append("      <div class=\"chart\">\n        <div class=\"ranges\"><a class=\"tab").append(day ? "" : " on")
                    .append("\" href=\"").append(good(item, false)).append(".html\">1H</a><a class=\"tab").append(day ? " on" : "")
                    .append("\" href=\"").append(good(item, true)).append(".html\">24H</a></div>\n")
                    .append("        <img class=\"plot\" src=\"nascraft-chart-").append(m).append('-').append(id).append(day ? "-d" : "-h").append(".svg\">\n      </div>\n");
            b.append("""
                          <div class="stats">
                            <div><small>Day high</small>%s</div><div><small>Day low</small>%s</div>
                            <div><small>Supply</small>%s</div><div><small>Swing/h</small>%s</div>
                            <div><small>You carry</small>%s</div>
                          </div>
                    """.formatted(ph("hi", item), ph("lo", item), ph("stock", item), ph("vol", item), ph("inv", item)));
        } else {
            b.append("""
                          <div class="bookbox">
                            <div class="side ask"><small>Lowest ask</small><b>%s</b><span>%s for sale</span></div>
                            <div class="side bid"><small>Highest bid</small><b>%s</b><span>%s wanted</span></div>
                          </div>
                          <p class="note">Players set this price. Buy or sell now against standing orders, or post your own order
                          below and it fills when someone takes it. Goods and coin from fills wait in <b>Orders &amp; claims</b>.</p>
                          <div class="stats"><div><small>You carry</small>%s</div><div><small>Your orders</small>%%nascraft_ord_count%%</div><div><small>Claims</small>%%nascraft_claims%%</div></div>
                    """.formatted(ph("ask", item), ph("askv", item), ph("bid", item), ph("bidv", item), ph("inv", item)));
        }
        b.append("    </div>\n    <div class=\"right\">\n");
        String trade = "market trade %s " + m + " " + id + " %s";
        b.append("      <div class=\"panel\"><div class=\"ph\">").append(book ? "Trade now" : "Goods").append("</div>\n        <div class=\"row\"><span class=\"lbl\">Buy</span>");
        for (int n : new int[]{1, 16, 64}) b.append(button("buy", trade.formatted("buy", n), "×" + n, ph("buy", item, n)));
        b.append("</div>\n        <div class=\"row\"><span class=\"lbl\">Sell</span>");
        for (int n : new int[]{1, 16}) b.append(button("sell", trade.formatted("sell", n), "×" + n, ph("sell", item, n)));
        b.append(button("sell", trade.formatted("sell", "all"), "All", ph("sell", item, "all")));
        b.append("</div>\n      </div>\n");
        if (!book) {
            String share = "market shares %s " + m + " " + id + " %s";
            b.append("      <div class=\"panel shares\"><div class=\"ph\">Shares</div>\n        <div class=\"row\"><span class=\"lbl\">Buy</span>");
            for (int n : new int[]{1, 10, 100}) b.append(button("buy", share.formatted("buy", n), "×" + n, ph("sb", item, n)));
            b.append("</div>\n        <div class=\"row\"><span class=\"lbl\">Sell</span>");
            for (int n : new int[]{1, 10}) b.append(button("sell", share.formatted("sell", n), "×" + n, ph("ss", item, n)));
            b.append(button("sell", share.formatted("sell", "all"), "All", ph("ss", item, "all")));
            b.append("</div>\n");
            b.append("""
                            <div class="position %s %s"><div><small>Held</small>%s</div><div><small>Avg cost</small>%s</div>
                              <div><small>Worth</small>%s</div><div class="pl"><small>P/L</small>%s</div></div>
                          </div>
                    """.formatted(ph("shstate", item), ph("shdir", item), ph("sh", item), ph("shc", item), ph("shv", item), ph("shpl", item)));
        } else {
            b.append("      <div class=\"panel orders\"><div class=\"ph\">Post an order</div>\n");
            b.append(orderBox(item));
            b.append("      </div>\n");
        }
        b.append("    </div>\n  </div>\n");
        b.append(footer(""));
        b.append("</div>\n</body>\n</html>\n");
        return b.toString();
    }

    /** Amount select plus price buttons; each button's command carries its side and price, the amount comes from the form. */
    private static String orderBox(Item item) {
        String m = item.getPort().getId(), id = item.getIdentifier();
        String g = m.equals("global") ? " global" : "";
        StringBuilder b = new StringBuilder();
        b.append("        <form>\n          <div class=\"row\"><span class=\"lbl\">Amount</span><select name=\"qty\" class=\"btn sel\">")
                .append("<option value=\"16\">16</option><option value=\"64\">64</option><option value=\"256\">256</option><option value=\"1\">1</option></select></div>\n");
        b.append("          <div class=\"row\"><span class=\"lbl\">Bid</span>");
        for (int pct : new int[]{0, 2, 5})
            b.append("<a class=\"btn trade buy\" data-command=\"market order buy ").append(id).append(" {form.qty} ")
                    .append(ph("bidp", item, pct)).append(g).append("\"><b>").append(pct == 0 ? "Best" : "+" + pct + "%")
                    .append("</b><span>").append(ph("bidpf", item, pct)).append("</span></a>");
        b.append("</div>\n          <div class=\"row\"><span class=\"lbl\">Ask</span>");
        for (int pct : new int[]{0, -2, -5})
            b.append("<a class=\"btn trade sell\" data-command=\"market order sell ").append(id).append(" {form.qty} ")
                    .append(ph("askp", item, pct)).append(g).append("\"><b>").append(pct == 0 ? "Best" : pct + "%")
                    .append("</b><span>").append(ph("askpf", item, pct)).append("</span></a>");
        b.append("</div>\n        </form>\n        <p class=\"note small\">Each unit at that price. Coin or goods are held until the order fills or you cancel.</p>\n");
        return b.toString();
    }

    private static String portfolio() {
        StringBuilder b = new StringBuilder(head("Portfolio"));
        b.append("<div class=\"window ledger\">\n");
        b.append(header("Your portfolio", "Shares held across the exchange", "<a class=\"btn small back\" data-command=\"market home\">‹</a>"));
        b.append("""
                  <div class="summary %nascraft_pf_dir%"><div><small>Worth now</small>%nascraft_pf_value%</div><div><small>Paid</small>%nascraft_pf_cost%</div>
                    <div class="pl"><small>Profit / loss</small>%nascraft_pf_pl%</div><div><small>Holdings</small>%nascraft_pf_count%</div></div>
                  <div class="table">
                    <div class="tr th"><span class="g">Good</span><span>Shares</span><span>Avg</span><span>Price</span><span>1h</span><span>Worth</span><span>P/L</span><span class="act"></span></div>
                """);
        for (int i = 1; i <= 10; i++) {
            String p = "%nascraft_pos_" + i + "_";
            b.append("    <div class=\"tr ").append(p).append("state% ").append(p).append("dir%\"><span class=\"g\">").append(p).append("name%</span><span>")
                    .append(p).append("shares%</span><span>").append(p).append("avg%</span><span>").append(p).append("price%</span><span>")
                    .append(p).append("ch%</span><span>").append(p).append("value%</span><span class=\"pl\">").append(p).append("pl%</span><span class=\"act\">")
                    .append("<a class=\"btn small\" data-command=\"market view ").append(p).append("market% ").append(p).append("good%\">View</a>")
                    .append("<a class=\"btn small red\" data-command=\"market shares sell ").append(p).append("market% ").append(p).append("good% all\">Sell</a></span></div>\n");
        }
        b.append("    <div class=\"empty-note\">No shares yet. Open a good on the exchange and buy a few.</div>\n  </div>\n");
        b.append(footer(""));
        b.append("</div>\n</body>\n</html>\n");
        return b.toString();
    }

    private static String orders() {
        StringBuilder b = new StringBuilder(head("Orders"));
        b.append("<div class=\"window ledger\">\n");
        b.append(header("Orders & claims", "Your standing orders and what's waiting for you", "<a class=\"btn small back\" data-command=\"market home\">‹</a>"));
        b.append("""
                  <div class="summary claims"><div><small>Waiting to collect</small>%nascraft_claims%</div><div><small>Open orders</small>%nascraft_ord_count%</div>
                    <span class="spacer"></span><a class="btn green" data-command="market claim" data-sound="entity.player.levelup 0.5 1.4">Collect all</a></div>
                  <div class="table">
                    <div class="tr th"><span class="g">Good</span><span>Side</span><span>Left</span><span>Price</span><span>Total</span><span class="act"></span></div>
                """);
        for (int i = 1; i <= 10; i++) {
            String o = "%nascraft_ord_" + i + "_";
            b.append("    <div class=\"tr ").append(o).append("state%\"><span class=\"g\">").append(o).append("name%</span><span class=\"side\">")
                    .append(o).append("side%</span><span>").append(o).append("qty%</span><span>").append(o).append("price%</span><span>")
                    .append(o).append("total%</span><span class=\"act\"><a class=\"btn small red\" data-command=\"market cancel ").append(o)
                    .append("id%\">Cancel</a></span></div>\n");
        }
        b.append("    <div class=\"empty-note\">No open orders. Post one from any player-traded good.</div>\n  </div>\n");
        b.append(footer(""));
        b.append("</div>\n</body>\n</html>\n");
        return b.toString();
    }

    static String lower(String s) { return s.toLowerCase(Locale.ROOT); }
}
