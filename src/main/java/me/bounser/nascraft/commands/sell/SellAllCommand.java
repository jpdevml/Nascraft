package me.bounser.nascraft.commands.sell;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.commands.Command;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.config.lang.Lang;
import me.bounser.nascraft.config.lang.Message;
import me.bounser.nascraft.formatter.Formatter;
import me.bounser.nascraft.formatter.Style;
import me.bounser.nascraft.managers.currencies.CurrenciesManager;
import me.bounser.nascraft.market.MarketManager;
import me.bounser.nascraft.market.Port;
import me.bounser.nascraft.market.unit.Item;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sells every stack in the player's inventory that the port at their
 * location trades. Requires a port context, so the bypass permission does
 * not apply: outside a port the command always fails.
 */
public class SellAllCommand extends Command {

    public SellAllCommand() {
        super(
                "sellall",
                new String[]{Config.getInstance().getCommandAlias("sellall")},
                "Sell everything the local port trades",
                "nascraft.sellall"
        );
    }

    @Override
    public void execute(CommandSender sender, String[] args) {

        if (!(sender instanceof Player)) {
            Nascraft.getInstance().getLogger().info("Command not available through console.");
            return;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("nascraft.sellall")) {
            Lang.get().message(player, Message.NO_PERMISSION);
            return;
        }

        if (!MarketManager.getInstance().getActive()) {
            Lang.get().message(player, Message.SHOP_CLOSED);
            return;
        }

        Port port = MarketManager.getInstance().getPortAt(player.getLocation());

        if (port == null) {
            Lang.get().message(player, Message.NOT_IN_PORT);
            return;
        }

        // Group the sellable inventory content per port item.

        Map<String, Item> items = new LinkedHashMap<>();
        Map<String, Integer> amounts = new LinkedHashMap<>();

        for (ItemStack itemStack : player.getInventory().getContents()) {

            if (itemStack == null || itemStack.getType().isAir()) continue;

            Item item = port.getItem(itemStack);

            if (item == null) continue;

            items.put(item.getIdentifier(), item);
            amounts.merge(item.getIdentifier(), itemStack.getAmount(), Integer::sum);
        }

        if (items.isEmpty()) {
            Lang.get().message(player, Message.SELLALL_EVERYTHING_ERROR);
            return;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("confirm")) {
            sellEverything(player, items, amounts);
        } else {
            sendEstimate(player, items, amounts);
        }
    }

    private void sellEverything(Player player, Map<String, Item> items, Map<String, Integer> amounts) {

        double total = 0;
        int amountSold = 0;

        for (Item item : items.values()) {

            int amount = amounts.get(item.getIdentifier());

            double worth = item.sell(amount, player.getUniqueId(), true);

            if (worth < 0) continue;

            total += worth;
            amountSold += amount;
        }

        if (amountSold == 0) return;

        Lang.get().message(player, Message.SELLALL_TOTAL,
                Formatter.format(CurrenciesManager.getInstance().getDefaultCurrency(), total, Style.ROUND_BASIC),
                String.valueOf(amountSold),
                "");
    }

    private void sendEstimate(Player player, Map<String, Item> items, Map<String, Integer> amounts) {

        double total = 0;
        StringBuilder segments = new StringBuilder();

        for (Item item : items.values()) {

            int amount = amounts.get(item.getIdentifier());

            double worth = item.sellPrice(amount);

            total += worth;

            segments.append(Lang.get().message(Message.LIST_SEGMENT,
                    Formatter.format(item.getCurrency(), worth, Style.ROUND_BASIC),
                    String.valueOf(amount),
                    item.getName()));
        }

        String estimate = Lang.get().message(Message.SELLALL_ESTIMATED_VALUE)
                .replace("[WORTH]", Formatter.format(CurrenciesManager.getInstance().getDefaultCurrency(), total, Style.ROUND_BASIC))
                + segments + "\n";

        TextComponent component = (TextComponent) MiniMessage.miniMessage().deserialize(
                Lang.get().message(Message.CLICK_TO_CONFIRM));

        Component hoverText = MiniMessage.miniMessage().deserialize(estimate);

        component = component.hoverEvent(HoverEvent.showText(hoverText))
                .clickEvent(ClickEvent.runCommand("/" + Config.getInstance().getCommandAlias("sellall") + " confirm"));

        Lang.get().getAudience().player(player).sendMessage(component);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {

        if (args.length == 1)
            return StringUtil.copyPartialMatches(args[0], Collections.singletonList("confirm"), new ArrayList<>());

        return Collections.emptyList();
    }
}
