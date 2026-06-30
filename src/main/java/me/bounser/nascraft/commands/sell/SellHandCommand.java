package me.bounser.nascraft.commands.sell;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.commands.Command;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.config.lang.Lang;
import me.bounser.nascraft.config.lang.Message;
import me.bounser.nascraft.formatter.Formatter;
import me.bounser.nascraft.formatter.Style;
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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sells the held stack to the port at the player's location. Requires a
 * port context, so the bypass permission does not apply: outside a port the
 * command always fails.
 */
public class SellHandCommand extends Command {

    private final Map<UUID, ItemStack> pendingConfirmations = new HashMap<>();

    public SellHandCommand() {
        super(
                "sellhand",
                new String[]{Config.getInstance().getCommandAlias("sellhand")},
                "Sell the held item to the local port",
                "nascraft.sellhand"
        );
    }

    @Override
    public void execute(CommandSender sender, String[] args) {

        if (!(sender instanceof Player)) {
            Nascraft.getInstance().getLogger().info("Command not available through console.");
            return;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("nascraft.sellhand")) {
            Lang.get().message(player, Message.NO_PERMISSION);
            return;
        }

        Port port = MarketManager.getInstance().getPortAt(player.getLocation());

        if (port == null) {
            Lang.get().message(player, Message.NOT_IN_PORT);
            return;
        }

        ItemStack handItem = player.getInventory().getItemInMainHand();

        if (handItem.getType().isAir()) {
            Lang.get().message(player, Message.SELLHAND_INVALID);
            return;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("confirm")) {

            ItemStack snapshot = pendingConfirmations.remove(player.getUniqueId());

            if (snapshot == null || !handItem.equals(snapshot)) {
                Lang.get().message(player, Message.SELLHAND_ERROR_HAND);
                return;
            }

            Item item = port.getItem(handItem);

            if (item == null) {
                Lang.get().message(player, Message.SELLHAND_INVALID);
                return;
            }

            item.sell(handItem.getAmount(), player.getUniqueId(), true);
            return;
        }

        Item item = port.getItem(handItem);

        if (item == null) {
            Lang.get().message(player, Message.SELLHAND_INVALID);
            return;
        }

        TextComponent component = (TextComponent) MiniMessage.miniMessage().deserialize(
                Lang.get().message(Message.CLICK_TO_CONFIRM));

        Component hoverText = MiniMessage.miniMessage().deserialize(
                Lang.get().message(Message.SELLHAND_ESTIMATED_VALUE) +
                        Lang.get().message(Message.LIST_SEGMENT,
                                Formatter.format(item.getCurrency(), item.sellPrice(handItem.getAmount()), Style.ROUND_BASIC),
                                String.valueOf(handItem.getAmount()),
                                item.getName()));

        component = component.hoverEvent(HoverEvent.showText(hoverText))
                .clickEvent(ClickEvent.runCommand("/" + Config.getInstance().getCommandAlias("sellhand") + " confirm"));

        Lang.get().getAudience().player(player).sendMessage(component);

        pendingConfirmations.put(player.getUniqueId(), handItem.clone());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        return Collections.emptyList();
    }
}
