package me.bounser.nascraft.inventorygui;

import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.config.lang.Lang;
import me.bounser.nascraft.config.lang.Message;
import me.bounser.nascraft.formatter.Formatter;
import me.bounser.nascraft.formatter.Style;
import me.bounser.nascraft.market.unit.Item;
import me.bounser.nascraft.managers.MoneyManager;
import me.bounser.nascraft.market.OrderBook;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chest GUI to buy/sell a specific good at a port. Stateless painter: it
 * returns the slot-to-identifier map of the displayed child variants, which
 * {@link MarketMenuManager.MenuSession} stores so clicks can be resolved by
 * identifier on the live port.
 */
public final class BuySellMenu {

    private BuySellMenu() { }

    /**
     * (Re)paints a buy/sell menu for the given item. Safe to call on an
     * already-open inventory (used to refresh prices in place after a trade).
     *
     * @return slots holding child/parent variants, mapped to their item identifiers.
     */
    public static Map<Integer, String> populate(Inventory gui, Item item) {
        Player viewer = gui.getViewers().stream().filter(Player.class::isInstance).map(Player.class::cast).findFirst().orElse(null);
        return populate(gui, item, viewer);
    }

    public static Map<Integer, String> populate(Inventory gui, Item item, Player viewer) {

        Config config = Config.getInstance();
        MarketMenuManager manager = MarketMenuManager.getInstance();

        // Fillers

        String fillerName = manager.legacy(Lang.get().message(Message.GUI_FILLERS_NAME));

        ItemStack filler = manager.generateItemStack(config.getBuySellFillersMaterial(), fillerName);

        for (int slot : config.getBuySellFillersSlots())
            if (slot >= 0 && slot < gui.getSize()) gui.setItem(slot, filler);

        // Traded item

        int itemSlot = config.getBuySellMenuItemSlot();

        if (itemSlot >= 0 && itemSlot < gui.getSize())
            gui.setItem(itemSlot, buildItemDisplay(item, Message.GUI_BUYSELL_ITEM_LORE, viewer));

        // Back button

        if (config.getBuySellBackEnabled()) {

            int backSlot = config.getBuySellBackSlot();

            if (backSlot >= 0 && backSlot < gui.getSize())
                gui.setItem(backSlot, manager.generateItemStack(
                        config.getBuySellBackMaterial(),
                        manager.legacy(Lang.get().message(Message.GUI_CATEGORY_BACK_NAME))));
        }

        // Buy buttons

        HashMap<Integer, Integer> buyButtons = config.getBuySellBuySlots();

        for (int amount : buyButtons.keySet()) {

            int slot = buyButtons.get(amount);

            if (slot < 0 || slot >= gui.getSize()) continue;

            String name = manager.legacy(Lang.get().message(Message.GUI_BUYSELL_BUY_BUTTONS_NAME)
                    .replace("[AMOUNT]", String.valueOf(amount)));

            double quote = item.isPlayerOnly() ? OrderBook.get().quote(item, amount, true, viewer == null ? null : viewer.getUniqueId()) : item.buyPrice(amount);
            List<String> lore = manager.legacyLines(Lang.get().message(Message.GUI_BUYSELL_BUY_BUTTONS_LORE)
                    .replace("[AMOUNT]", String.valueOf(amount))
                    .replace("[WORTH]", item.isPlayerOnly() && quote == 0
                            ? "No matching sell orders" : Formatter.format(item.getCurrency(), quote, Style.ROUND_BASIC)));

            if (item.isPlayerOnly()) {
                lore.add(manager.legacy("<gray>Instant fill: lowest sell orders first.</gray>"));
                lore.add(manager.legacy("<gray>Matched items are delivered via Claims.</gray>"));
            }
            ItemStack button = manager.generateItemStack(config.getBuySellBuyMaterial(), name, lore);

            button.setAmount(Math.max(1, Math.min(64, amount)));

            gui.setItem(slot, button);
        }

        // Sell buttons

        HashMap<Integer, Integer> sellButtons = config.getBuySellSellSlots();

        for (int amount : sellButtons.keySet()) {

            int slot = sellButtons.get(amount);

            if (slot < 0 || slot >= gui.getSize()) continue;

            String name = manager.legacy(Lang.get().message(Message.GUI_BUYSELL_SELL_BUTTONS_NAME)
                    .replace("[AMOUNT]", String.valueOf(amount)));

            double quote = item.isPlayerOnly() ? OrderBook.get().quote(item, amount, false, viewer == null ? null : viewer.getUniqueId()) : item.sellPrice(amount);
            List<String> lore = manager.legacyLines(Lang.get().message(Message.GUI_BUYSELL_SELL_BUTTONS_LORE)
                    .replace("[AMOUNT]", String.valueOf(amount))
                    .replace("[WORTH]", item.isPlayerOnly() && quote == 0
                            ? "No matching buy orders" : Formatter.format(item.getCurrency(), quote, Style.ROUND_BASIC)));

            if (item.isPlayerOnly()) {
                lore.add(manager.legacy("<gray>Instant fill: highest buy orders first.</gray>"));
                lore.add(manager.legacy("<gray>Matched proceeds are delivered via Claims.</gray>"));
            }
            ItemStack button = manager.generateItemStack(config.getBuySellSellMaterial(), name, lore);

            button.setAmount(Math.max(1, Math.min(64, amount)));

            gui.setItem(slot, button);
        }

        // Player-only order form. These buttons never invoke or suggest commands.
        if (item.isPlayerOnly()) {
            int buySlot = config.getBuyOrderSlot(), sellSlot = config.getSellOrderSlot();
            if (buySlot >= 0 && buySlot < gui.getSize())
                gui.setItem(buySlot, manager.generateItemStack(org.bukkit.Material.EMERALD,
                        manager.legacy("<green>Create buy order</green>"),
                        manager.legacyLines("<gray>Set quantity and price in the GUI.</gray>")));
            if (sellSlot >= 0 && sellSlot < gui.getSize())
                gui.setItem(sellSlot, manager.generateItemStack(org.bukkit.Material.CHEST,
                        manager.legacy("<red>Create sell order</red>"),
                        manager.legacyLines("<gray>Set quantity and price in the GUI.</gray>")));
        }

        if (navigationAvailable(config, config.getBazaarOrdersSlot(), gui.getSize()))
            gui.setItem(config.getBazaarOrdersSlot(), manager.generateItemStack(Material.BOOK, manager.legacy("<yellow>My orders</yellow>")));
        if (navigationAvailable(config, config.getBazaarClaimsSlot(), gui.getSize()))
            gui.setItem(config.getBazaarClaimsSlot(), manager.generateItemStack(Material.CHEST, manager.legacy("<yellow>Claims</yellow>")));
        if (viewer != null) purse(gui, item, viewer);

        // Variants (parent + childs of the family, excluding the item on display)
        return placeVariants(gui, item, viewer);
    }

    static boolean navigationAvailable(Config config, int slot, int size) {
        return slot >= 0 && slot < size && slot != config.getBuySellMenuItemSlot()
                && (!config.getBuySellBackEnabled() || slot != config.getBuySellBackSlot())
                && !config.getBuySellBuySlots().containsValue(slot) && !config.getBuySellSellSlots().containsValue(slot)
                && slot != config.getBuyOrderSlot() && slot != config.getSellOrderSlot()
                && (slot != config.getBazaarOrdersSlot() || (slot != config.getBazaarClaimsSlot() && slot != config.getBazaarPurseSlot()))
                && (slot != config.getBazaarClaimsSlot() || slot != config.getBazaarPurseSlot());
    }

    static void purse(Inventory gui, Item item, Player player) {
        Config config = Config.getInstance();
        if (!navigationAvailable(config, config.getBazaarPurseSlot(), gui.getSize())) return;
        MarketMenuManager manager = MarketMenuManager.getInstance();
        gui.setItem(config.getBazaarPurseSlot(), manager.generateItemStack(Material.GOLD_INGOT,
                manager.legacy("<gold>✦ Purse</gold>"), manager.legacyLines("<gray>Balance: <green>"
                        + Formatter.format(item.getCurrency(), MoneyManager.getInstance().getBalance(player), Style.ROUND_BASIC) + "</green></gray>")));
    }

    private static Map<Integer, String> placeVariants(Inventory gui, Item item, Player viewer) {

        Config config = Config.getInstance();

        Map<Integer, String> variantSlots = new LinkedHashMap<>();

        Item parent = item.isParent() ? item : item.getParent();

        if (parent == null || parent.getChilds().isEmpty()) return variantSlots;

        List<Item> family = new ArrayList<>();
        family.add(parent);
        family.addAll(parent.getChilds());

        Set<Integer> reserved = new HashSet<>();
        reserved.add(config.getBuySellMenuItemSlot());
        if (config.getBuySellBackEnabled()) reserved.add(config.getBuySellBackSlot());
        reserved.addAll(config.getBuySellBuySlots().values());
        reserved.addAll(config.getBuySellSellSlots().values());
        reserved.add(config.getBazaarOrdersSlot());
        reserved.add(config.getBazaarClaimsSlot());
        reserved.add(config.getBazaarPurseSlot());
        if (item.isPlayerOnly()) {
            reserved.add(config.getBuyOrderSlot());
            reserved.add(config.getSellOrderSlot());
        }

        int cursor = config.getBuySellMenuItemSlot() + 1;

        for (Item variant : family) {

            if (variant.getIdentifier().equals(item.getIdentifier())) continue;

            while (cursor < gui.getSize() && reserved.contains(cursor)) cursor++;

            if (cursor >= gui.getSize()) break;

            gui.setItem(cursor, buildItemDisplay(variant, Message.GUI_CATEGORY_ITEM_LORE, viewer));
            variantSlots.put(cursor, variant.getIdentifier());

            cursor++;
        }

        return variantSlots;
    }

    private static ItemStack buildItemDisplay(Item item, Message loreKey, Player viewer) {

        MarketMenuManager manager = MarketMenuManager.getInstance();

        ItemStack itemStack = item.getItemStack();

        ItemMeta meta = itemStack.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(item.getFormattedName());
            meta.setLore(manager.getLoreFromItem(item, Lang.get().message(loreKey), viewer));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
            itemStack.setItemMeta(meta);
        }

        return itemStack;
    }
}
