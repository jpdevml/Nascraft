package me.bounser.nascraft.inventorygui;

import me.bounser.nascraft.commands.sell.sellinv.SellInvListener;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.config.lang.Lang;
import me.bounser.nascraft.config.lang.Message;
import me.bounser.nascraft.formatter.Formatter;
import me.bounser.nascraft.formatter.RoundUtils;
import me.bounser.nascraft.formatter.Style;
import me.bounser.nascraft.market.Port;
import me.bounser.nascraft.market.OrderBook;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which plugin GUI each player has open and which port it is bound to.
 *
 * Sessions are keyed by UUID and store identifiers, paging and uncommitted
 * form input (never a port/item object). Ports and items are re-resolved
 * through {@link me.bounser.nascraft.market.MarketManager} on every click so
 * a /nascraft reload can never leave a menu operating on stale objects.
 *
 * Sessions are set AFTER Player#openInventory returns: opening a new
 * inventory fires InventoryCloseEvent for the previous one, which clears the
 * session. Setting it afterwards avoids that race.
 */
public class MarketMenuManager {

    public enum MenuType { PORT, BUY_SELL, DIRECTORY, CATEGORIES, GOODS, ORDERS, CLAIMS, ORDER_EDITOR, ORDER_CANCEL }

    public static final class MenuSession {

        private final String portId;
        private final MenuType type;

        private int page;
        private String itemIdentifier;
        private Map<Integer, String> variantSlots = new HashMap<>();
        private Inventory inventory;
        private String categoryId;
        private int categoryPage;
        private boolean buyOrder;
        private int quantity;
        private long priceCents;
        private long orderId;
        private Map<Integer, Long> orderSlots = new HashMap<>();

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

        public Inventory getInventory() { return inventory; }
        public String getCategoryId() { return categoryId; }
        public void setCategoryId(String id) { categoryId = id; }
        public int getCategoryPage() { return categoryPage; }
        public void setCategoryPage(int value) { categoryPage = value; }
        public boolean isBuyOrder() { return buyOrder; }
        public void setBuyOrder(boolean value) { buyOrder = value; }
        public int getQuantity() { return quantity; }
        public void setQuantity(int value) { quantity = value; }
        public long getPriceCents() { return priceCents; }
        public void setPriceCents(long value) { priceCents = value; }
        public long getOrderId() { return orderId; }
        public void setOrderId(long value) { orderId = value; }
        public Map<Integer, Long> getOrderSlots() { return orderSlots; }
        public void setOrderSlots(Map<Integer, Long> value) { orderSlots = value; }

        public void copyContext(MenuSession from) {
            if (from == null) return;
            categoryId = from.categoryId;
            categoryPage = from.categoryPage;
            page = from.page;
            itemIdentifier = from.itemIdentifier;
            buyOrder = from.buyOrder;
            quantity = from.quantity;
            priceCents = from.priceCents;
        }
    }

    private static volatile MarketMenuManager instance;

    private final Map<UUID, MenuSession> sessions = new HashMap<>();
    // Chat events can run asynchronously. Only the reference lookup is done off-thread;
    // all mutation, validation and Bukkit calls happen on the server thread.
    private final Map<UUID, PendingInput> pendingInputs = new ConcurrentHashMap<>();
    private long menuGeneration;

    public static final class PendingInput {
        private final MenuSession draft;
        private final boolean price;
        private final long generation;
        // Only changed on the server thread; onOpen must not treat our own close
        // transition as the player opening another menu.
        private boolean closingEditor;

        private PendingInput(MenuSession draft, boolean price, long generation) {
            this.draft = draft;
            this.price = price;
            this.generation = generation;
        }

        public MenuSession draft() { return draft; }
        public boolean price() { return price; }
        public long generation() { return generation; }
        public boolean closingEditor() { return closingEditor; }
        public void setClosingEditor(boolean closing) { closingEditor = closing; }
    }

    public PendingInput getPendingInput(UUID uuid) { return pendingInputs.get(uuid); }

    public PendingInput beginChatInput(Player player, MenuSession draft, boolean price) {
        PendingInput pending = new PendingInput(draft, price, menuGeneration);
        pendingInputs.put(player.getUniqueId(), pending);
        return pending;
    }

    public void removePendingInput(UUID uuid, PendingInput pending) { pendingInputs.remove(uuid, pending); }
    public void cancelPendingInput(UUID uuid) { pendingInputs.remove(uuid); }

    public long getMenuGeneration() { return menuGeneration; }

    public static MarketMenuManager getInstance() { return instance == null ? instance = new MarketMenuManager() : instance; }

    /** Returns the instance or null, without lazy-creating it. Used in onDisable. */
    public static MarketMenuManager getInstanceIfPresent() { return instance; }

    public MenuSession getSession(UUID uuid) { return sessions.get(uuid); }

    /** Removes only the menu session. Called whenever a plugin GUI closes. */
    public void removeSession(Player player) { sessions.remove(player.getUniqueId()); }

    /** Install after opening: opening fires a close event for the old inventory. */
    public void open(Player player, Inventory gui, MenuSession session) {
        cancelPendingInput(player.getUniqueId());
        player.openInventory(gui);
        trackOpened(player, gui, session);
    }

    /** Register the newly opened inventory after the previous inventory has closed. */
    public void trackOpened(Player player, Inventory gui, MenuSession session) {
        session.inventory = gui;
        sessions.put(player.getUniqueId(), session);
    }

    /** Full cleanup for a player. Called on quit: also returns any items held in the sell menu. */
    public void clearSession(Player player) {

        // A single player quitting must not invalidate other players' chat drafts.
        // Their scheduled callbacks already check the pending input by identity.
        sessions.remove(player.getUniqueId());
        cancelPendingInput(player.getUniqueId());

        SellInvListener sellInvListener = SellInvListener.getInstanceIfPresent();
        if (sellInvListener != null) sellInvListener.returnHeldItems(player);
    }

    /**
     * Closes every open plugin GUI. Items held inside sell menus are handed
     * back to their owners before the inventories are closed, so the close
     * events that follow find nothing left to return.
     */
    public void closeAllMenus() {

        menuGeneration++;
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
        pendingInputs.clear();
    }

    // Menu openers:

    public void openPortMenu(Player player, Port port) { openPortMenu(player, port, 0); }

    public void openPortMenu(Player player, Port port, int page) {

        Config config = Config.getInstance();

        String title = legacy(Lang.get().message(Message.PORT_MENU_TITLE)
                .replace("[PORT]", port.getPlainDisplayName()));

        Inventory gui = Bukkit.createInventory(null, config.getPortMenuSize(), title);

        PortMenu.populate(gui, port, page);

        MenuSession session = new MenuSession(port.getId(), MenuType.PORT);
        session.setPage(page);
        open(player, gui, session);
    }

    public void openDirectory(Player player) { openDirectory(player, 0); }

    public void openDirectory(Player player, int page) {

        Config config = Config.getInstance();

        String title = legacy(Lang.get().message(Message.PORT_DIRECTORY_TITLE));

        Inventory gui = Bukkit.createInventory(null, config.getDirectoryMenuSize(), title);

        DirectoryMenu.populate(gui, page);

        MenuSession session = new MenuSession(null, MenuType.DIRECTORY);
        session.setPage(page);
        open(player, gui, session);
    }

    public void openBuySellMenu(Player player, Port port, Item item) {

        Config config = Config.getInstance();

        Inventory gui = Bukkit.createInventory(null, config.getBuySellMenuSize(), item.getFormattedName());

        Map<Integer, String> variantSlots = BuySellMenu.populate(gui, item, player);

        MenuSession session = new MenuSession(port.getId(), MenuType.BUY_SELL);
        session.setItemIdentifier(item.getIdentifier());
        session.setVariantSlots(variantSlots);
        open(player, gui, session);
        BuySellMenu.purse(gui, item, player);
    }

    public void openBuySellMenu(Player player, Port port, Item item, MenuSession previous) {
        openBuySellMenu(player, port, item);
        MenuSession session = getSession(player.getUniqueId());
        session.setCategoryId(previous.getCategoryId());
        session.setCategoryPage(previous.getCategoryPage());
        session.setPage(previous.getPage());
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
    public List<String> getLoreFromItem(Item item, String lore) { return getLoreFromItem(item, lore, null); }

    public List<String> getLoreFromItem(Item item, String lore, Player viewer) {

        OrderBook.BookView book = item.isPlayerOnly() ? OrderBook.get().book(item) : null;
        double playerBuy = book == null ? 0 : viewer == null ? book.bestAskCents() / 100.0 : OrderBook.get().quote(item, 1, true, viewer.getUniqueId());
        double playerSell = book == null ? 0 : viewer == null ? book.bestBidCents() / 100.0 : OrderBook.get().quote(item, 1, false, viewer.getUniqueId());
        double valueAnHourAgo = item.getPrice().getValueAnHourAgo();

        float change = item.isPlayerOnly() || valueAnHourAgo == 0 ? 0 :
                RoundUtils.roundToOne((float) (-100 + item.getPrice().getValue() * 100 / valueAnHourAgo));

        if (item.isPlayerOnly()) lore = Lang.get().message(Message.GUI_BAZAAR_ORDER_ITEM_LORE);
        String itemLore = lore
                .replace("[PRICE]", item.isPlayerOnly() ? "Player orders" : Formatter.format(item.getCurrency(), item.getPrice().getValue(), Style.ROUND_BASIC))
                .replace("[SELL-PRICE]", item.isPlayerOnly() && playerSell == 0 ? "No matching orders" : Formatter.format(item.getCurrency(), item.isPlayerOnly() ? playerSell : item.sellPrice(1), Style.ROUND_BASIC))
                .replace("[BUY-PRICE]", item.isPlayerOnly() && playerBuy == 0 ? "No matching orders" : Formatter.format(item.getCurrency(), item.isPlayerOnly() ? playerBuy : item.buyPrice(1), Style.ROUND_BASIC));

        String changeFormatted;

        if (change == 0) changeFormatted = Lang.get().message(Message.GUI_NO_CHANGE);
        else if (change < 0) changeFormatted = Lang.get().message(Message.GUI_NEGATIVE_CHANGE);
        else changeFormatted = Lang.get().message(Message.GUI_POSITIVE_CHANGE);

        itemLore = itemLore
                .replace("[CHANGE]", changeFormatted)
                .replace("[PERCENTAGE]", String.valueOf(change));

        List<String> itemLoreLines = new ArrayList<>(legacyLines(itemLore));
        if (item.isPlayerOnly()) {
            itemLoreLines.add(legacy("<yellow>Player orders only — no generated stock</yellow>"));
            itemLoreLines.add(legacy("<gray>For sale: " + book.sellVolume() + " | Wanted: " + book.buyVolume() + "</gray>"));
            itemLoreLines.add(legacy("<green>Best ask: " + (book.bestAskCents() == 0 ? "None" : Formatter.format(item.getCurrency(), book.bestAskCents() / 100.0, Style.ROUND_BASIC)) + "</green>"));
            itemLoreLines.add(legacy("<red>Best bid: " + (book.bestBidCents() == 0 ? "None" : Formatter.format(item.getCurrency(), book.bestBidCents() / 100.0, Style.ROUND_BASIC)) + "</red>"));
        } else itemLoreLines.add(legacy(Lang.get().message(Message.GUI_STOCK_DISPLAY)
                .replace("[STOCK]", String.valueOf(item.getStock()))));

        return itemLoreLines;
    }
}
