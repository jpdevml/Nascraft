package me.bounser.nascraft.commands.sell.sellinv;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.commands.Command;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.config.lang.Lang;
import me.bounser.nascraft.config.lang.Message;
import me.bounser.nascraft.market.MarketManager;
import me.bounser.nascraft.market.Port;
import net.kyori.adventure.platform.bukkit.BukkitComponentSerializer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.util.StringUtil;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Opens the sell-deposit GUI bound to the port the player is standing in.
 * Players with 'nascraft.ports.bypass' may target a port remotely with
 * /sell &lt;portId&gt;.
 */
public class SellInvCommand extends Command {

    public SellInvCommand() {
        super(
                "sellmenu",
                new String[]{Config.getInstance().getCommandAlias("sell-menu")},
                "Sell items to the local port",
                "nascraft.sellmenu"
        );
    }

    @Override
    public void execute(CommandSender sender, String[] args) {

        Player player;
        Port port;

        if (!(sender instanceof Player)) {

            if (args.length != 1) {
                Nascraft.getInstance().getLogger().info("Wrong usage of command. /sellmenu <playerName>");
                return;
            }

            player = Bukkit.getPlayer(args[0]);

            if (player == null) {
                Nascraft.getInstance().getLogger().info("Player not found.");
                return;
            }

            port = MarketManager.getInstance().getPortAt(player.getLocation());

            if (port == null) {
                Nascraft.getInstance().getLogger().info("Player " + player.getName() + " is not inside a port.");
                return;
            }

        } else {

            player = (Player) sender;

            if (!player.hasPermission("nascraft.sellmenu")) {
                Lang.get().message(player, Message.NO_PERMISSION);
                return;
            }

            if (!MarketManager.getInstance().getActive()) {
                Lang.get().message(player, Message.SHOP_CLOSED);
                return;
            }

            if (args.length >= 1 && player.hasPermission("nascraft.ports.bypass")) {

                port = MarketManager.getInstance().getPort(args[0]);

                if (port == null) {
                    Lang.get().message(player, Message.PORT_NOT_FOUND, "[PORT]", args[0]);
                    return;
                }

            } else {

                port = MarketManager.getInstance().getPortAt(player.getLocation());

                if (port == null) {
                    Lang.get().message(player, Message.NOT_IN_PORT);
                    return;
                }
            }
        }

        open(player, port);
    }

    private void open(Player player, Port port) {

        Component title = MiniMessage.miniMessage().deserialize(Lang.get().message(Message.SELL_TITLE));

        Inventory inventory = Bukkit.createInventory(null, Config.getInstance().getGetSellMenuSize(),
                BukkitComponentSerializer.legacy().serialize(title));

        insertFillingPanes(inventory);
        insertSellButton(inventory);
        insertCloseButton(inventory);
        insertHelpHead(inventory);

        player.openInventory(inventory);

        // Set AFTER openInventory: opening fires InventoryCloseEvent for any
        // previous inventory, which would strip the metadata again.
        player.setMetadata("NascraftSell", new FixedMetadataValue(Nascraft.getInstance(), port.getId()));
    }

    private void insertFillingPanes(Inventory inventory) {

        ItemStack filler = new ItemStack(Config.getInstance().getFillerMaterial());

        ItemMeta meta = filler.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(" ");
            filler.setItemMeta(meta);
        }

        for (int i = 0; i < 9; i++)
            inventory.setItem(i, filler);

        int size = Config.getInstance().getGetSellMenuSize();

        for (int i = (size - 9); i < size; i++)
            inventory.setItem(i, filler);
    }

    private void insertSellButton(Inventory inventory) {

        ItemStack sellButton = new ItemStack(Config.getInstance().getSellButtonMaterial());

        ItemMeta meta = sellButton.getItemMeta();

        if (meta == null) return;

        Component name = MiniMessage.miniMessage().deserialize(Lang.get().message(Message.SELL_BUTTON_NAME));
        meta.setDisplayName(BukkitComponentSerializer.legacy().serialize(name));

        List<String> lore = new ArrayList<>();

        for (String line : Lang.get().message(Message.SELL_BUTTON_LORE).replace("[WORTH-LIST]", "").split("\\n")) {
            Component loreLine = MiniMessage.miniMessage().deserialize(line);
            lore.add(BukkitComponentSerializer.legacy().serialize(loreLine));
        }

        meta.setLore(lore);
        sellButton.setItemMeta(meta);

        inventory.setItem(Config.getInstance().getSellButtonSlot(), sellButton);
    }

    private void insertCloseButton(Inventory inventory) {

        if (!Config.getInstance().getCloseButtonEnabled()) return;

        ItemStack closeButton = new ItemStack(Config.getInstance().getCloseButtonMaterial());

        ItemMeta meta = closeButton.getItemMeta();

        if (meta == null) return;

        Component displayNameComponent = MiniMessage.miniMessage().deserialize(Lang.get().message(Message.SELL_CLOSE));
        meta.setDisplayName(BukkitComponentSerializer.legacy().serialize(displayNameComponent));

        closeButton.setItemMeta(meta);

        inventory.setItem(Config.getInstance().getCloseButtonSlot(), closeButton);
    }

    private void insertHelpHead(Inventory inventory) {

        if (!Config.getInstance().getHelpEnabled()) return;

        String texture = Config.getInstance().getHelpTexture();

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);

        SkullMeta meta = (SkullMeta) head.getItemMeta();

        if (meta == null) return;

        Component displayNameComponent = MiniMessage.miniMessage().deserialize(Lang.get().message(Message.SELL_HELP_TITLE));
        meta.setDisplayName(BukkitComponentSerializer.legacy().serialize(displayNameComponent));

        List<String> lore = new ArrayList<>();

        for (String line : Lang.get().message(Message.SELL_HELP_LORE).split("\\n")) {
            Component loreComponent = MiniMessage.miniMessage().deserialize(line);
            lore.add(BukkitComponentSerializer.legacy().serialize(loreComponent));
        }

        meta.setLore(lore);

        PlayerProfile profile = getProfile(texture);

        if (profile != null) meta.setOwnerProfile(profile);

        head.setItemMeta(meta);

        inventory.setItem(Config.getInstance().getHelpSlot(), head);
    }

    private static PlayerProfile getProfile(String texture) {

        if (texture == null || texture.isEmpty()) return null;

        PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID());
        PlayerTextures textures = profile.getTextures();

        URL urlObject;
        try {
            urlObject = new URL("https://textures.minecraft.net/texture/" + texture);
        } catch (MalformedURLException exception) {
            return null;
        }

        textures.setSkin(urlObject);
        profile.setTextures(textures);
        return profile;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {

        if (args.length == 1 && sender.hasPermission("nascraft.ports.bypass"))
            return StringUtil.copyPartialMatches(args[0],
                    new ArrayList<>(MarketManager.getInstance().getPortIds()), new ArrayList<>());

        return Collections.emptyList();
    }
}
