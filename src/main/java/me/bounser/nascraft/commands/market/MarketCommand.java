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

        if (args.length > 0 && args[0].equalsIgnoreCase("claim")) {
            player.sendMessage("Claims collected: " + OrderBook.get().collect(player));
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("orders")) {
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

        BazaarMenus.categories(player, port, 0);
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
