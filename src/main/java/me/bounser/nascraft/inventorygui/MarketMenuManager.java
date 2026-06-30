package me.bounser.nascraft.inventorygui;

import me.bounser.nascraft.commands.sell.sellinv.SellInvListener;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.config.lang.Lang;
import me.bounser.nascraft.config.lang.Message;
import me.bounser.nascraft.formatter.Formatter;
import me.bounser.nascraft.formatter.RoundUtils;
import me.bounser.nascraft.formatter.Style;
import me.bounser.nascraft.market.Port;
import me.bounser.nascraft.market.unit.Item;
import net.kyori.adventure.platform.bukkit.BukkitComponentSerializer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks which plugin GUI each player has open and which port it is bound to.
 *
 * Sessions are keyed by UUID and only store identifiers (port id, item
 * identifier, slot-to-identifier maps). Ports and items are re-resolved
 * through {@link me.bounser.nascraft.market.MarketManager} on every click so
 * a /nascraft reload can never leave a menu operating on stale objects.
 *
 * Sessions are set AFTER Player#openInventory returns: opening a new
 * inventory fires InventoryCloseEvent for the previous one, which clears the
 * session. Setting it afterwards avoids that race.
 */
public class MarketMenuManager {

    public enum MenuType { PORT, BUY_SELL, DIRECTORY }

    public static final class MenuSession {

        private final String portId;
        private final MenuType type;

        private int page;
        private String itemIdentifier;
        private Map<Integer, String> variantSlots = new HashMap<>();

        public MenuSession(String portId, MenuType type) {
            this.portId = portId;
            this.type = type;
        }

        public String getPortId() { return portId; }

        public MenuType getType() { return type; }

        public int getPage() { return page; }

        public void setPage(int page) { this.page = page; }

        public String getItemIdentifier() { return itemIdentifier; }

        public void setItemIdentifier(String itemIdentifier) { this.itemIdentifier = itemIdentifier; }

        public Map<Integer, String> getVariantSlots() { return variantSlots; }

        public void setVariantSlots(Map<Integer, String> variantSlots) {
            this.variantSlots = variantSlots == null ? new HashMap<>() : variantSlots;
        }
    }

    private static MarketMenuManager instance;

    private final Map<UUID, MenuSession> sessions = new HashMap<>();

    public static MarketMenuManager getInstance() { return instance == null ? instance = new MarketMenuManager() : instance; }

    /** Returns the instance or null, without lazy-creating it. Used in onDisable. */
    public static MarketMenuManager getInstanceIfPresent() { return instance; }

    public MenuSession getSession(UUID uuid) { return sessions.get(uuid); }

    /** Removes only the menu session. Called whenever a plugin GUI closes. */
    public void removeSession(Player player) { sessions.remove(player.getUniqueId()); }

    /** Full cleanup for a player. Called on quit: also returns any items held in the sell menu. */
    public void clearSession(Player player) {

        sessions.remove(player.getUniqueId());

        SellInvListener sellInvListener = SellInvListener.getInstanceIfPresent();
        if (sellInvListener != null) sellInvListener.returnHeldItems(player);
    }

    /**
     * Closes every open plugin GUI. Items held inside sell menus are handed
     * back to their owners before the inventories are closed, so the close
     * events that follow find nothing left to return.
     */
    public void closeAllMenus() {

        SellInvListener sellInvListener = SellInvListener.getInstanceIfPresent();
        if (sellInvListener != null) sellInvListener.returnAllHeldItems();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (sessions.containsKey(player.getUniqueId())
                    || player.hasMetadata("NascraftSell")
                    || player.hasMetadata("NascraftLogInventory")) {
                player.closeInventory();
            }
        }

        sessions.clear();
    }

    // Menu openers:

    public void openPortMenu(Player player, Port port) { openPortMenu(player, port, 0); }

    public void openPortMenu(Player player, Port port, int page) {

        Config config = Config.getInstance();

        String title = legacy(Lang.get().message(Message.PORT_MENU_TITLE)
                .replace("[PORT]", port.getPlainDisplayName()));

        Inventory gui = Bukkit.createInventory(null, config.getPortMenuSize(), title);

        PortMenu.populate(gui, port, page);

        player.openInventory(gui);

        MenuSession session = new MenuSession(port.getId(), MenuType.PORT);
        session.setPage(page);
        sessions.put(player.getUniqueId(), session);
    }

    public void openDirectory(Player player) { openDirectory(player, 0); }

    public void openDirectory(Player player, int page) {

        Config config = Config.getInstance();

        String title = legacy(Lang.get().message(Message.PORT_DIRECTORY_TITLE));

        Inventory gui = Bukkit.createInventory(null, config.getDirectoryMenuSize(), title);

        DirectoryMenu.populate(gui, page);

        player.openInventory(gui);

        MenuSession session = new MenuSession(null, MenuType.DIRECTORY);
        session.setPage(page);
        sessions.put(player.getUniqueId(), session);
    }

    public void openBuySellMenu(Player player, Port port, Item item) {

        Config config = Config.getInstance();

        Inventory gui = Bukkit.createInventory(null, config.getBuySellMenuSize(), item.getFormattedName());

        Map<Integer, String> variantSlots = BuySellMenu.populate(gui, item);

        player.openInventory(gui);

        MenuSession session = new MenuSession(port.getId(), MenuType.BUY_SELL);
        session.setItemIdentifier(item.getIdentifier());
        session.setVariantSlots(variantSlots);
        sessions.put(player.getUniqueId(), session);
    }

    // Shared GUI helpers:

    public String legacy(String miniMessage) {
        return BukkitComponentSerializer.legacy().serialize(MiniMessage.miniMessage().deserialize(miniMessage));
    }

    public List<String> legacyLines(String miniMessage) {

        List<String> lines = new ArrayList<>();

        for (String line : miniMessage.split("\\n"))
            lines.add(legacy(line));

        return lines;
    }

    public ItemStack generateItemStack(Material material, String name, List<String> lore) {
        return generateItemStack(material, 0, name, lore);
    }

    /**
     * Build a menu icon. When {@code modelData > 0} the item is given that custom-model-data so the
     * resource pack can swap in a real texture (instead of the generated vanilla {@code material}
     * icon). Model data is written to BOTH the float and string CMD lists so it matches
     * range_dispatch and select packs alike — same convention as the trade-good pack items.
     */
    public ItemStack generateItemStack(Material material, int modelData, String name, List<String> lore) {

        ItemStack itemStack = new ItemStack(material);

        ItemMeta meta = itemStack.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            meta.setAttributeModifiers(null);
            if (modelData > 0) {
                org.bukkit.inventory.meta.components.CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
                cmd.setFloats(java.util.Collections.singletonList((float) modelData));
                cmd.setStrings(java.util.Collections.singletonList(String.valueOf(modelData)));
                meta.setCustomModelDataComponent(cmd);
            }
            itemStack.setItemMeta(meta);
        }

        return itemStack;
    }

    public ItemStack generateItemStack(Material material, String name) {
        return generateItemStack(material, name, null);
    }

    /**
     * Builds the price lore of an item: current/buy/sell price, change over
     * the last hour and the port's stock of the good.
     */
    public List<String> getLoreFromItem(Item item, String lore) {

        double valueAnHourAgo = item.getPrice().getValueAnHourAgo();

        float change = valueAnHourAgo == 0 ? 0 :
                RoundUtils.roundToOne((float) (-100 + item.getPrice().getValue() * 100 / valueAnHourAgo));

        String itemLore = lore
                .replace("[PRICE]", Formatter.format(item.getCurrency(), item.getPrice().getValue(), Style.ROUND_BASIC))
                .replace("[SELL-PRICE]", Formatter.format(item.getCurrency(), item.getPrice().getSellPrice(), Style.ROUND_BASIC))
                .replace("[BUY-PRICE]", Formatter.format(item.getCurrency(), item.getPrice().getBuyPrice(), Style.ROUND_BASIC));

        String changeFormatted;

        if (change == 0) changeFormatted = Lang.get().message(Message.GUI_NO_CHANGE);
        else if (change < 0) changeFormatted = Lang.get().message(Message.GUI_NEGATIVE_CHANGE);
        else changeFormatted = Lang.get().message(Message.GUI_POSITIVE_CHANGE);

        itemLore = itemLore
                .replace("[CHANGE]", changeFormatted)
                .replace("[PERCENTAGE]", String.valueOf(change));

        List<String> itemLoreLines = new ArrayList<>(legacyLines(itemLore));

        itemLoreLines.add(legacy(Lang.get().message(Message.GUI_STOCK_DISPLAY)
                .replace("[STOCK]", String.valueOf(item.getStock()))));

        return itemLoreLines;
    }
}
