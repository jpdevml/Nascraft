package me.bounser.nascraft.inventorygui;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.config.Config.BazaarCategory;
import me.bounser.nascraft.inventorygui.MarketMenuManager.MenuSession;
import me.bounser.nascraft.inventorygui.MarketMenuManager.MenuType;
import me.bounser.nascraft.managers.MoneyManager;
import me.bounser.nascraft.market.MarketManager;
import me.bounser.nascraft.market.OrderBook;
import me.bounser.nascraft.market.Port;
import me.bounser.nascraft.market.unit.Item;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bazaar navigation and order forms. Only identifiers and user input live in sessions, never market objects. */
public final class BazaarMenus {
    private BazaarMenus() { }
    private static final int[] GRID = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
    private static final MarketMenuManager MENUS = MarketMenuManager.getInstance();

    private static void sound(Player player, String key, Sound fallback) {
        player.playSound(player.getLocation(), Config.getInstance().bazaarSound(key, fallback), .6f, 1.1f);
    }

    private static ItemStack icon(Material material, String name, String... lore) {
        List<String> lines = new ArrayList<>();
        for (String line : lore) lines.add(MENUS.legacy(line));
        return MENUS.generateItemStack(material, MENUS.legacy(name), lines);
    }

    private static void button(Inventory gui, int slot, Material material, String name, String... lore) {
        gui.setItem(slot, icon(material, name, lore));
    }

    private static Inventory inventory(String title) {
        Inventory gui = Bukkit.createInventory(null, 54, MENUS.legacy(title));
        ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < gui.getSize(); slot++) gui.setItem(slot, filler);
        return gui;
    }

    private static void purse(Inventory gui, Player player) {
        button(gui, 49, Material.GOLD_INGOT, "<gold>✦ Purse</gold>", "<gray>Balance: <green>" + fmt(MoneyManager.getInstance().getBalance(player)) + "</green></gray>");
    }

    private static String fmt(double value) {
        return Config.getInstance().getCurrencyFormat().replace("[AMOUNT]", String.format(Locale.ROOT, "%,.2f", value));
    }
    private static String cents(long value) { return fmt(value / 100.0); }
    private static void open(Player player, Inventory gui, MenuSession session) {
        MENUS.open(player, gui, session);
        sound(player, "open", Sound.BLOCK_CHEST_OPEN);
    }

    public static void categories(Player player, Port port, int page) {
        List<BazaarCategory> categories = Config.getInstance().getBazaarCategories(port);
        int safePage = Math.max(0, Math.min(page, categories.isEmpty() ? 0 : (categories.size() - 1) / GRID.length));
        Inventory gui = inventory("<gold>✦ " + port.getPlainDisplayName() + (port.isGlobal() ? "" : " Bazaar") + "</gold>");
        for (int i = 0; i < GRID.length && safePage * GRID.length + i < categories.size(); i++) {
            BazaarCategory category = categories.get(safePage * GRID.length + i);
            button(gui, GRID[i], category.icon(), "<yellow>✦ " + category.name() + "</yellow>",
                    "<gray>" + category.goods().size() + " goods</gray>", "<yellow>Click to browse</yellow>");
        }
        button(gui, 45, Material.ARROW, safePage > 0 ? "<yellow>← Previous page</yellow>" : "<gray>Categories</gray>");
        if (!MarketManager.getInstance().getPortIds().isEmpty())
            button(gui, 47, Material.COMPASS, "<yellow>Ports directory</yellow>", "<gray>View port locations</gray>");
        button(gui, 51, Material.BOOK, "<yellow>My orders</yellow>");
        button(gui, 52, Material.CHEST, "<yellow>Claims</yellow>");
        if ((safePage + 1) * GRID.length < categories.size()) button(gui, 53, Material.ARROW, "<yellow>Next page →</yellow>");
        purse(gui, player);
        MenuSession session = new MenuSession(port.getId(), MenuType.CATEGORIES);
        session.setPage(safePage);
        open(player, gui, session);
    }

    private static BazaarCategory category(Port port, String id) {
        return Config.getInstance().getBazaarCategories(port).stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
    }

    public static void goods(Player player, Port port, String id, int page, int categoryPage) {
        BazaarCategory category = category(port, id);
        if (category == null) { categories(player, port, categoryPage); return; }
        int safePage = Math.max(0, Math.min(page, (category.goods().size() - 1) / GRID.length));
        Inventory gui = inventory("<gold>✦ " + category.name() + "</gold>");
        Map<Integer, String> slots = new HashMap<>();
        for (int i = 0; i < GRID.length && safePage * GRID.length + i < category.goods().size(); i++) {
            String identifier = category.goods().get(safePage * GRID.length + i);
            Item item = port.getItem(identifier);
            if (item == null) continue;
            gui.setItem(GRID[i], PortMenu.buildGoodDisplay(item));
            slots.put(GRID[i], identifier);
        }
        button(gui, 45, Material.ARROW, "<yellow>← Categories</yellow>");
        if (safePage > 0) button(gui, 46, Material.ARROW, "<yellow>Previous page</yellow>");
        if ((safePage + 1) * GRID.length < category.goods().size()) button(gui, 53, Material.ARROW, "<yellow>Next page →</yellow>");
        button(gui, 51, Material.BOOK, "<yellow>My orders</yellow>");
        button(gui, 52, Material.CHEST, "<yellow>Claims</yellow>");
        purse(gui, player);
        MenuSession session = new MenuSession(port.getId(), MenuType.GOODS);
        session.setCategoryId(id);
        session.setCategoryPage(categoryPage);
        session.setPage(safePage);
        session.setVariantSlots(slots);
        open(player, gui, session);
    }

    public static void orders(Player player, Port port, int page) {
        int safePage = Math.max(0, page);
        List<OrderBook.OpenOrder> entries = OrderBook.get().openOrders(player, safePage, GRID.length + 1);
        if (safePage > 0 && entries.isEmpty()) { orders(player, port, safePage - 1); return; }
        Inventory gui = inventory("<gold>✦ My open orders</gold>");
        Map<Integer, Long> slots = new HashMap<>();
        for (int i = 0; i < GRID.length && i < entries.size(); i++) {
            OrderBook.OpenOrder order = entries.get(i);
            Port market = MarketManager.getInstance().getPort(order.market());
            Item item = market == null ? null : market.getItem(order.identifier());
            ItemStack display = item == null ? new ItemStack(Material.PAPER) : item.getItemStack();
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(MENUS.legacy((order.buy() ? "<green>BUY</green> " : "<red>SELL</red> ") + "<yellow>" + order.identifier() + " #" + order.id() + "</yellow>"));
                meta.setLore(MENUS.legacyLines("<gray>Market: " + order.market() + "</gray>\n<gray>Remaining: " + order.remaining()
                        + "</gray>\n<gray>Per item: " + cents(order.priceCents()) + "</gray>\n<yellow>Click to cancel remaining quantity</yellow>"));
                display.setItemMeta(meta);
            }
            gui.setItem(GRID[i], display);
            slots.put(GRID[i], order.id());
        }
        button(gui, 45, Material.ARROW, "<yellow>← Bazaar</yellow>");
        if (safePage > 0) button(gui, 46, Material.ARROW, "<yellow>Previous page</yellow>");
        if (entries.size() > GRID.length) button(gui, 53, Material.ARROW, "<yellow>Next page →</yellow>");
        button(gui, 52, Material.CHEST, "<yellow>Claims</yellow>");
        purse(gui, player);
        MenuSession session = new MenuSession(port.getId(), MenuType.ORDERS);
        session.setPage(safePage);
        session.setOrderSlots(slots);
        open(player, gui, session);
    }

    public static void claims(Player player, Port port, int page) {
        int safePage = Math.max(0, page);
        List<OrderBook.ClaimView> entries = OrderBook.get().claims(player, safePage, GRID.length + 1);
        if (safePage > 0 && entries.isEmpty()) { claims(player, port, safePage - 1); return; }
        Inventory gui = inventory("<gold>✦ Claims</gold>");
        Map<Integer, Long> slots = new HashMap<>();
        for (int i = 0; i < GRID.length && i < entries.size(); i++) {
            OrderBook.ClaimView claim = entries.get(i);
            String value = claim.kind().equals("MONEY") ? cents(claim.amount()) : claim.amount() + " items";
            ItemStack display = OrderBook.get().claimPreview(claim);
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(MENUS.legacy("<yellow>Claim #" + claim.id() + "</yellow>"));
                meta.setLore(MENUS.legacyLines("<gray>" + value + "</gray>\n<gray>Item: " + display.getType() + "</gray>\n" +
                        (claim.status().equals("READY") ? "<green>Ready for collection</green>" : "<red>Delivery uncertain — contact staff. Do not retry.</red>")));
                display.setItemMeta(meta);
            }
            gui.setItem(GRID[i], display);
            slots.put(GRID[i], claim.id());
        }
        button(gui, 45, Material.ARROW, "<yellow>← Bazaar</yellow>");
        if (safePage > 0) button(gui, 46, Material.ARROW, "<yellow>Previous page</yellow>");
        button(gui, 48, Material.EMERALD, "<green>Collect all ready claims</green>", "<gray>Items need inventory space.</gray>");
        if (entries.size() > GRID.length) button(gui, 53, Material.ARROW, "<yellow>Next page →</yellow>");
        button(gui, 51, Material.BOOK, "<yellow>My orders</yellow>");
        purse(gui, player);
        MenuSession session = new MenuSession(port.getId(), MenuType.CLAIMS);
        session.setPage(safePage);
        session.setOrderSlots(slots);
        open(player, gui, session);
    }

    public static void cancel(Player player, Port port, MenuSession previous, long id) {
        // Recheck ownership before even offering a confirmation; OrderBook.cancel rechecks under lock.
        OrderBook.OpenOrder order = OrderBook.get().openOrder(player, id);
        if (order == null) { orders(player, port, previous.getPage()); return; }
        Inventory gui = inventory("<gold>✦ Cancel order #" + id + "?</gold>");
        button(gui, 22, Material.PAPER, "<yellow>Order #" + id + "</yellow>",
                "<gray>" + order.market() + "/" + order.identifier() + "</gray>",
                "<gray>Remaining: " + order.remaining() + " @ " + cents(order.priceCents()) + "</gray>",
                "<yellow>Unfilled escrow becomes a claim.</yellow>");
        button(gui, 30, Material.LIME_WOOL, "<green>Confirm cancellation</green>");
        button(gui, 32, Material.RED_WOOL, "<red>Keep order</red>");
        button(gui, 45, Material.ARROW, "<yellow>← My orders</yellow>");
        MenuSession session = new MenuSession(port.getId(), MenuType.ORDER_CANCEL);
        session.setPage(previous.getPage()); session.setOrderId(id);
        open(player, gui, session);
    }

    public static void editor(Player player, Port port, MenuSession previous) {
        Inventory gui = inventory("<gold>✦ " + (previous.isBuyOrder() ? "Buy" : "Sell") + " order</gold>");
        Item item = port.getItem(previous.getItemIdentifier());
        if (item == null || !item.isPlayerOnly()) { categories(player, port, 0); return; }
        ItemStack goodIcon = item.getItemStack();
        ItemMeta goodMeta = goodIcon.getItemMeta();
        if (goodMeta != null) { goodMeta.setDisplayName(item.getFormattedName()); goodIcon.setItemMeta(goodMeta); }
        gui.setItem(13, goodIcon);
        button(gui, 20, Material.CHEST, "<yellow>Quantity: " + (previous.getQuantity() == 0 ? "Set quantity" : previous.getQuantity()) + "</yellow>", "<yellow>Click to type an amount (1–1,000,000)</yellow>");
        button(gui, 22, Material.GOLD_INGOT, "<yellow>Price per item: " + (previous.getPriceCents() == 0 ? "Set price" : cents(previous.getPriceCents())) + "</yellow>", "<yellow>Click to type a price (two decimals)</yellow>");
        button(gui, 24, Material.PAPER, "<yellow>Total escrow</yellow>",
                previous.getPriceCents() == 0 || previous.getQuantity() == 0 ? "<gray>Set quantity and price first</gray>" :
                (previous.isBuyOrder() ? "<gray>Funds: " + cents(previous.getPriceCents() * previous.getQuantity()) + "</gray>" : "<gray>Items: " + previous.getQuantity() + "</gray>"),
                "<gray>Crossing orders must be filled instantly instead.</gray>");
        button(gui, 29, Material.PAPER, "<yellow>Quantity: 1</yellow>");
        button(gui, 30, Material.PAPER, "<yellow>Quantity: 16</yellow>");
        button(gui, 31, Material.PAPER, "<yellow>Quantity: 64</yellow>");
        button(gui, 40, Material.LIME_WOOL, "<green>Confirm order</green>", "<gray>Escrow is taken on confirmation.</gray>");
        button(gui, 45, Material.ARROW, "<yellow>← Item</yellow>");
        purse(gui, player);
        MenuSession session = new MenuSession(port.getId(), MenuType.ORDER_EDITOR);
        session.copyContext(previous);
        open(player, gui, session);
    }

    public static void openNumberInput(Player player, Port port, MenuSession previous, boolean price) {
        MenuSession draft = new MenuSession(port.getId(), MenuType.ORDER_EDITOR);
        draft.copyContext(previous);
        // Arm the listener before closing so no chat can escape during the GUI-to-chat handoff.
        MarketMenuManager.PendingInput pending = MENUS.beginChatInput(player, draft, price);
        // Close on the next tick, after the inventory click has completed. The close event
        // removes the inventory session, but must leave the pending draft intact.
        Bukkit.getScheduler().runTask(Nascraft.getInstance(), () -> {
            if (MENUS.getPendingInput(player.getUniqueId()) != pending) return;
            if (!player.isOnline() || MENUS.getMenuGeneration() != pending.generation()) {
                MENUS.removePendingInput(player.getUniqueId(), pending);
                return;
            }
            MenuSession current = MENUS.getSession(player.getUniqueId());
            if (current != null && current != previous) {
                MENUS.removePendingInput(player.getUniqueId(), pending);
                feedback(player, "Chat input cancelled: another menu was opened.", false);
                return;
            }
            // Ignore inventory-open notifications generated by this close, not a new GUI
            // the player chooses to open after the handoff.
            if (current == previous) {
                pending.setClosingEditor(true);
                try { player.closeInventory(); }
                finally { pending.setClosingEditor(false); }
            }
            if (MENUS.getPendingInput(player.getUniqueId()) != pending) return;
            // Do not require a particular InventoryType immediately after closeInventory:
            // some servers still expose the old view during this same tick. onOpen will
            // cancel input if the player opens a different menu after this transition.
            // CloseEvent may have seen a different inventory wrapper. Once the editor
            // is actually closed, a leftover session must not block chat or reopening.
            if (MENUS.getSession(player.getUniqueId()) != null) MENUS.removeSession(player);
            player.sendMessage(MENUS.legacy("<yellow>Type " + (price ? "a price per item (0.01–10,000,000.00, up to two decimals)"
                    : "a whole-number quantity (1–1,000,000)")
                    + " in chat. Your message will not be sent. Type <white>cancel</white> to return to the order editor.</yellow>"));
        });
    }

    /** Called on the main thread after a pending player's chat message has been hidden. */
    public static void handleChatInput(Player player, MarketMenuManager.PendingInput pending, String input) {
        if (MENUS.getPendingInput(player.getUniqueId()) != pending) return;
        if (!player.isOnline() || MENUS.getMenuGeneration() != pending.generation()) {
            MENUS.removePendingInput(player.getUniqueId(), pending);
            return;
        }
        if (!normalInventory(player)) {
            MENUS.removePendingInput(player.getUniqueId(), pending);
            feedback(player, "Chat input cancelled: another inventory is open. Try again.", false);
            return;
        }
        // If an inventory implementation did not identify the closed editor by reference,
        // discard that stale GUI session before opening the updated one.
        if (MENUS.getSession(player.getUniqueId()) != null) MENUS.removeSession(player);
        MenuSession draft = pending.draft();
        if (Config.getInstance().getMarketPermissionRequirement() && !player.hasPermission("nascraft.market")) {
            MENUS.removePendingInput(player.getUniqueId(), pending);
            feedback(player, "Market input cancelled: permission required.", false);
            return;
        }
        Port port = MarketManager.getInstance().getPort(draft.getPortId());
        Item item = port == null ? null : port.getItem(draft.getItemIdentifier());
        if (item == null || !item.isPlayerOnly() || !item.isParent()) {
            MENUS.removePendingInput(player.getUniqueId(), pending);
            feedback(player, "Market input cancelled: item no longer available.", false);
            return;
        }
        if (!inside(player, port)) {
            MENUS.removePendingInput(player.getUniqueId(), pending);
            return;
        }
        if (!isCancelInput(input)) {
            try {
                applyNumber(draft, input.trim(), pending.price());
            } catch (IllegalArgumentException | ArithmeticException ex) {
                feedback(player, ex.getMessage() + ". Try again in chat, or type cancel to return to the order editor.", false);
                return; // Preserve the draft and allow another attempt.
            }
        }
        MENUS.removePendingInput(player.getUniqueId(), pending);
        editor(player, port, draft);
    }

    // Bukkit normally exposes the player's own inventory as CRAFTING; other
    // implementations may expose it as PLAYER or CREATIVE.
    private static boolean normalInventory(Player player) {
        InventoryType type = player.getOpenInventory().getType();
        return isNormalInventoryType(type);
    }

    static boolean isNormalInventoryType(InventoryType type) {
        return type == InventoryType.CRAFTING || type == InventoryType.PLAYER || type == InventoryType.CREATIVE;
    }

    static boolean isCancelInput(String input) { return "cancel".equalsIgnoreCase(input.trim()); }

    static void applyNumber(MenuSession session, String input, boolean price) {
        if (price) session.setPriceCents(price(input));
        else session.setQuantity(quantity(input));
    }

    public static int quantity(String input) {
        if (!input.matches("[0-9]{1,7}")) throw new IllegalArgumentException("Quantity must be a whole number");
        int amount = Integer.parseInt(input);
        if (amount < 1 || amount > 1_000_000) throw new IllegalArgumentException("Quantity must be 1–1,000,000");
        return amount;
    }

    public static long price(String input) {
        if (!input.matches("[0-9]{1,10}(\\.[0-9]{1,2})?")) throw new IllegalArgumentException("Use a positive price with at most two decimals");
        long cents = OrderBook.cents(input);
        if (cents < 1 || cents > 1_000_000_000L) throw new IllegalArgumentException("Price must be 0.01–10,000,000.00");
        return cents;
    }

    public static void feedback(Player player, String message, boolean success) {
        player.sendMessage(MENUS.legacy((success ? "<green>" : "<red>") + message + (success ? "</green>" : "</red>")));
        sound(player, success ? "success" : "error", success ? Sound.ENTITY_PLAYER_LEVELUP : Sound.ENTITY_VILLAGER_NO);
    }

    public static boolean inside(Player player, Port port) {
        if (port.isInside(player.getLocation()) || player.hasPermission("nascraft.ports.bypass")) return true;
        feedback(player, "You must be at this port to trade.", false);
        return false;
    }

    public static void click(Player player, Port port, MenuSession session, int slot) {
        sound(player, "click", Sound.UI_BUTTON_CLICK);
        switch (session.getType()) {
            case CATEGORIES -> {
                if (slot == 47 && !MarketManager.getInstance().getPortIds().isEmpty()) { MENUS.openDirectory(player); return; }
                if (slot == 51) { orders(player, port, 0); return; }
                if (slot == 52) { claims(player, port, 0); return; }
                List<BazaarCategory> cats = Config.getInstance().getBazaarCategories(port);
                if (slot == 45 && session.getPage() > 0) categories(player, port, session.getPage() - 1);
                else if (slot == 53 && (session.getPage() + 1) * GRID.length < cats.size()) categories(player, port, session.getPage() + 1);
                else {
                    int index = index(slot, session.getPage());
                    if (index >= 0 && index < cats.size()) goods(player, port, cats.get(index).id(), 0, session.getPage());
                }
            }
            case GOODS -> {
                if (slot == 45) { categories(player, port, session.getCategoryPage()); return; }
                if (slot == 46 && session.getPage() > 0) { goods(player, port, session.getCategoryId(), session.getPage() - 1, session.getCategoryPage()); return; }
                if (slot == 51) { orders(player, port, 0); return; }
                if (slot == 52) { claims(player, port, 0); return; }
                BazaarCategory cat = category(port, session.getCategoryId());
                if (cat == null) { categories(player, port, 0); return; }
                if (slot == 53 && (session.getPage() + 1) * GRID.length < cat.goods().size()) goods(player, port, cat.id(), session.getPage() + 1, session.getCategoryPage());
                else {
                    String id = session.getVariantSlots().get(slot);
                    Item item = id == null ? null : port.getItem(id);
                    if (item != null) MENUS.openBuySellMenu(player, port, item, session);
                }
            }
            case ORDERS -> {
                if (slot == 45) categories(player, port, 0);
                else if (slot == 52) claims(player, port, 0);
                else if (slot == 53 && OrderBook.get().openOrders(player, session.getPage(), GRID.length + 1).size() > GRID.length)
                    orders(player, port, session.getPage() + 1);
                else if (slot == 46 && session.getPage() > 0) orders(player, port, session.getPage() - 1);
                else {
                    Long id = session.getOrderSlots().get(slot);
                    if (id != null) cancel(player, port, session, id);
                }
            }
            case CLAIMS -> {
                if (slot == 45) categories(player, port, 0);
                else if (slot == 51) orders(player, port, 0);
                else if (slot == 53 && OrderBook.get().claims(player, session.getPage(), GRID.length + 1).size() > GRID.length)
                    claims(player, port, session.getPage() + 1);
                else if (slot == 46 && session.getPage() > 0) claims(player, port, session.getPage() - 1);
                else if (slot == 48) {
                    int collected = OrderBook.get().collect(player);
                    feedback(player, "Collected " + collected + " claim(s). Full inventories or uncertain deliveries need attention.", collected > 0);
                    claims(player, port, session.getPage());
                } else {
                    Long id = session.getOrderSlots().get(slot);
                    if (id != null) {
                        int collected = OrderBook.get().collect(player, id);
                        feedback(player, collected > 0 ? "Claim collected." : "Not ready, no inventory space, or delivery needs staff attention.", collected > 0);
                        claims(player, port, session.getPage());
                    }
                }
            }
            case ORDER_CANCEL -> {
                if (slot == 45 || slot == 32) orders(player, port, session.getPage());
                else if (slot == 30) {
                    boolean cancelled = OrderBook.get().cancel(player, session.getOrderId());
                    feedback(player, cancelled ? "Order cancelled. Collect returned escrow under Claims." : "Order already closed or cancellation failed.", cancelled);
                    orders(player, port, session.getPage());
                }
            }
            case ORDER_EDITOR -> {
                if (slot == 45) {
                    Item item = port.getItem(session.getItemIdentifier());
                    if (item != null) MENUS.openBuySellMenu(player, port, item, session);
                    else categories(player, port, 0);
                } else if (slot == 20 || slot == 22) {
                    openNumberInput(player, port, session, slot == 22);
                } else if (slot >= 29 && slot <= 31) {
                    session.setQuantity(slot == 29 ? 1 : slot == 30 ? 16 : 64);
                    editor(player, port, session);
                } else if (slot == 40) {
                    Item item = port.getItem(session.getItemIdentifier());
                    if (item == null || !item.isPlayerOnly() || !item.isParent() || !inside(player, port)) return;
                    if (session.getPriceCents() == 0 || session.getQuantity() == 0) { feedback(player, "Set a quantity and price first.", false); return; }
                    long id = OrderBook.get().place(player, item, session.getQuantity(), session.getPriceCents(), session.isBuyOrder());
                    if (id > 0) { feedback(player, "Order #" + id + " placed. Follow it under My orders.", true); orders(player, port, 0); }
                    else feedback(player, id == -2 ? "Escrow uncertain. Contact staff; do not retry." : "Order rejected. Check escrow or matching prices; crossing orders must be filled instantly.", false);
                }
            }
            default -> { }
        }
    }

    private static int index(int slot, int page) {
        for (int i = 0; i < GRID.length; i++) if (GRID[i] == slot) return page * GRID.length + i;
        return -1;
    }
}
