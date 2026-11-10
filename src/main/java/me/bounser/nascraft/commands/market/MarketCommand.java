package me.bounser.nascraft.commands.market;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.commands.Command;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.config.lang.Lang;
import me.bounser.nascraft.config.lang.Message;
import me.bounser.nascraft.inventorygui.MarketMenuManager;
import me.bounser.nascraft.inventorygui.BazaarMenus;
import me.bounser.nascraft.market.MarketManager;
import me.bounser.nascraft.market.Port;
import me.bounser.nascraft.market.OrderBook;
import me.bounser.nascraft.market.unit.Item;
import me.bounser.nascraft.market.Portfolio;
import me.bounser.nascraft.web.ExchangePages;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * /market opens the menu of the port the player is standing in.
 * /market &lt;portId&gt; opens a port remotely (requires 'nascraft.ports.bypass').
 */
public class MarketCommand extends Command {

    public MarketCommand() {
        super(
                "market",
                new String[]{Config.getInstance().getCommandAlias("market")},
                "Open the local port or global bazaar",
                "nascraft.market"
        );
    }

    @Override
    public void execute(CommandSender sender, String[] args) {

        if (!(sender instanceof Player)) {
            Nascraft.getInstance().getLogger().info("Command not available through console.");
            return;
        }

        Player player = (Player) sender;

        if (Config.getInstance().getMarketPermissionRequirement() && !player.hasPermission("nascraft.market")) {
            Lang.get().message(player, Message.NO_PERMISSION);
            return;
        }

        if (args.length > 0 && exchange(player, args)) return;

        if (args.length > 0 && args[0].equalsIgnoreCase("claim")) {
            player.sendMessage("Claims collected: " + OrderBook.get().collect(player));
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("orders")) {
            if (ExchangePages.available()) { ExchangePages.open(player, "nascraft-orders"); return; }
            BazaarMenus.orders(player, MarketManager.getInstance().getMarketAt(player.getLocation()), 0);
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("claims")) {
            BazaarMenus.claims(player, MarketManager.getInstance().getMarketAt(player.getLocation()), 0);
            return;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("cancel")) {
            try { player.sendMessage(OrderBook.get().cancel(player, Long.parseLong(args[1])) ? "Order cancelled. /market claim to collect escrow." : "Order not found."); }
            catch (NumberFormatException ex) { player.sendMessage("Invalid order ID."); }
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("order")) {
            if (args.length != 5 && args.length != 6) {
                player.sendMessage("Usage: /market order <buy|sell> <good> <amount> <price> [global]");
                return;
            }
            if (args.length == 6 && !args[5].equalsIgnoreCase("global")) {
                player.sendMessage("Only 'global' is accepted as a market selector.");
                return;
            }
            boolean buy = args[1].equalsIgnoreCase("buy");
            if (!buy && !args[1].equalsIgnoreCase("sell")) { player.sendMessage("Choose buy or sell."); return; }
            Port target = args.length == 6 && args[5].equalsIgnoreCase("global")
                    ? MarketManager.getInstance().getGlobalMarket() : MarketManager.getInstance().getMarketAt(player.getLocation());
            Item item = target == null ? null : target.getItem(args[2]);
            if (item == null || !item.isPlayerOnly() || !item.isParent()) {
                player.sendMessage("This market does not list that player-backed good.");
                return;
            }
            try {
                int amount = Integer.parseInt(args[3]);
                long price = OrderBook.cents(args[4]);
                long id = OrderBook.get().place(player, item, amount, price, buy);
                player.sendMessage(id > 0 ? "Order #" + id + " placed. /market orders to view."
                        : id == -2 ? "Escrow outcome uncertain. Contact an administrator; do not retry until reconciled."
                        : "Order rejected: check escrow, price, or existing crossing orders.");
            } catch (IllegalArgumentException | ArithmeticException ex) { player.sendMessage("Invalid amount or price."); }
            return;
        }

        // /market global is always available, including while standing in a port.
        if (args.length >= 1 && args[0].equalsIgnoreCase("global")) {
            if (ExchangePages.available()) { ExchangePages.open(player, ExchangePages.home(MarketManager.getInstance().getGlobalMarket())); return; }
            BazaarMenus.categories(player, MarketManager.getInstance().getGlobalMarket(), 0);
            return;
        }

        // In a global-only setup there is no directory to browse.
        if (args.length >= 1 && args[0].equalsIgnoreCase("list")) {
            if (MarketManager.getInstance().getPortIds().isEmpty())
                BazaarMenus.categories(player, MarketManager.getInstance().getGlobalMarket(), 0);
            else
                MarketMenuManager.getInstance().openDirectory(player);
            return;
        }

        Port port;

        if (args.length >= 1) {

            if (!player.hasPermission("nascraft.ports.bypass")) {
                Lang.get().message(player, Message.NO_PERMISSION);
                return;
            }

            port = MarketManager.getInstance().getPort(args[0]);

            if (port == null) {
                Lang.get().message(player, Message.PORT_NOT_FOUND, "[PORT]", args[0]);
                return;
            }

        } else {

            port = MarketManager.getInstance().getMarketAt(player.getLocation());
        }

        if (port != null && ExchangePages.available()) { ExchangePages.open(player, ExchangePages.home(port)); return; }
        BazaarMenus.categories(player, port, 0);
    }

    /** True when the player may trade at {@code market} from where they stand. */
    private static boolean reachable(Player player, Port market) {
        return market != null && (market.isGlobal() || market.isInside(player.getLocation()) || player.hasPermission("nascraft.ports.bypass"));
    }

    /** The exchange pages' commands: home, view, portfolio, trade and shares. Returns false if not one of them. */
    private boolean exchange(Player player, String[] args) {
        String sub = args[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "home" -> {
                Port market = args.length > 1 ? MarketManager.getInstance().getPort(args[1]) : MarketManager.getInstance().getMarketAt(player.getLocation());
                if (market == null) market = MarketManager.getInstance().getGlobalMarket();
                if (ExchangePages.available() && market != null) ExchangePages.open(player, ExchangePages.home(market));
                else BazaarMenus.categories(player, market, 0);
                return true;
            }
            case "portfolio" -> {
                if (ExchangePages.available()) { ExchangePages.open(player, "nascraft-portfolio"); return true; }
                List<Portfolio.Position> list = Portfolio.get().list(player.getUniqueId());
                if (list.isEmpty()) player.sendMessage("You hold no shares.");
                for (Portfolio.Position p : list) {
                    Port m = MarketManager.getInstance().getPort(p.market());
                    Item item = m == null ? null : m.getItem(p.identifier());
                    double value = item == null ? 0 : Portfolio.value(item, p.shares());
                    player.sendMessage(p.shares() + "x " + (item == null ? p.identifier() : item.getName()) + " @ " + p.market()
                            + ": worth " + Portfolio.round(value) + " (" + (value >= p.cost() ? "+" : "") + Portfolio.round(value - p.cost()) + ")");
                }
                return true;
            }
            case "view" -> {
                if (args.length < 3) return true;
                Port market = MarketManager.getInstance().getPort(args[1]);
                Item item = market == null ? null : market.getItem(args[2]);
                if (item == null) { player.sendMessage("No such good."); return true; }
                if (ExchangePages.available()) ExchangePages.open(player, ExchangePages.good(item, args.length > 3 && args[3].equals("d")));
                else MarketMenuManager.getInstance().openBuySellMenu(player, market, item);
                return true;
            }
            case "trade", "shares" -> {
                if (args.length != 5) {
                    player.sendMessage("Usage: /market " + sub + " <buy|sell> <market> <good> <amount|all>");
                    return true;
                }
                boolean buy = args[1].equalsIgnoreCase("buy");
                Port market = MarketManager.getInstance().getPort(args[2]);
                Item item = market == null ? null : market.getItem(args[3]);
                if (item == null) { player.sendMessage("No such good."); return true; }
                if (!reachable(player, market)) { player.sendMessage("You have to be at " + market.getPlainDisplayName() + " to trade there."); return true; }
                if (sub.equals("shares")) {
                    long units = args[4].equalsIgnoreCase("all")
                            ? (buy ? 0 : Portfolio.get().shares(player.getUniqueId(), item)) : parse(args[4]);
                    player.sendMessage(buy ? Portfolio.get().buy(player, item, units) : Portfolio.get().sell(player, item, units));
                    return true;
                }
                int units;
                if (args[4].equalsIgnoreCase("all")) {
                    units = 0;
                    for (org.bukkit.inventory.ItemStack stack : player.getInventory().getStorageContents())
                        if (stack != null && stack.isSimilar(item.getItemStack())) units += stack.getAmount();
                    if (buy || units == 0) { player.sendMessage("You carry no " + item.getName() + "."); return true; }
                } else units = (int) Math.min(100000, parse(args[4]));
                if (units <= 0) { player.sendMessage("Choose an amount."); return true; }
                if (buy) item.buy(units, player.getUniqueId(), true);
                else item.sell(units, player.getUniqueId(), true);
                return true;
            }
            default -> { return false; }
        }
    }

    private static long parse(String text) {
        try { return Long.parseLong(text); } catch (NumberFormatException ex) { return 0; }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {

        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            if (!MarketManager.getInstance().getPortIds().isEmpty()) options.add("list");
            options.add("global");
            options.add("orders");
            options.add("claims");
            options.add("order");
            options.add("cancel");
            options.add("claim");
            options.add("home");
            options.add("portfolio");
            options.add("view");
            options.add("trade");
            options.add("shares");
            if (sender.hasPermission("nascraft.ports.bypass"))
                options.addAll(MarketManager.getInstance().getPortIds());
            return StringUtil.copyPartialMatches(args[0], options, new ArrayList<>());
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("order"))
            return StringUtil.copyPartialMatches(args[1], List.of("buy", "sell"), new ArrayList<>());
        if (args.length == 3 && args[0].equalsIgnoreCase("order") && sender instanceof Player player) {
            Port market = MarketManager.getInstance().getMarketAt(player.getLocation());
            List<String> goods = new ArrayList<>();
            if (market != null) for (Item item : market.getParentItems())
                if (item.isPlayerOnly()) goods.add(item.getIdentifier());
            return StringUtil.copyPartialMatches(args[2], goods, new ArrayList<>());
        }
        if (args.length == 6 && args[0].equalsIgnoreCase("order"))
            return StringUtil.copyPartialMatches(args[5], List.of("global"), new ArrayList<>());
        return Collections.emptyList();
    }
}
