package me.bounser.nascraft.inventorygui;

import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.config.lang.Lang;
import me.bounser.nascraft.config.lang.Message;
import me.bounser.nascraft.market.Port;
import me.bounser.nascraft.market.unit.Item;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.List;

/**
 * Chest GUI listing the parent goods traded at a port, with pagination,
 * a port-information item and the configured fillers. Stateless: it only
 * paints inventories, the session state lives in {@link MarketMenuManager}.
 */
public final class PortMenu {

    private PortMenu() { }

    /** (Re)paints a port menu inventory for the given page. Safe to call on an already-open inventory. */
    public static void populate(Inventory gui, Port port, int page) {

        Config config = Config.getInstance();
        MarketMenuManager manager = MarketMenuManager.getInstance();

        // Fillers

        String fillerName = manager.legacy(Lang.get().message(Message.GUI_FILLERS_NAME));

        HashMap<Material, List<Integer>> fillers = config.getPortMenuFillers();

        for (Material material : fillers.keySet()) {

            ItemStack filler = manager.generateItemStack(material, fillerName);

            for (int slot : fillers.get(material))
                if (slot >= 0 && slot < gui.getSize()) gui.setItem(slot, filler);
        }

        // Port information

        if (config.getPortMenuInfoEnabled()) {

            String infoName = manager.legacy(Lang.get().message(Message.PORT_INFO_NAME)
                    .replace("[PORT]", port.getDisplayName()));

            List<String> infoLore = manager.legacyLines(Lang.get().message(Message.PORT_INFO_LORE)
                    .replace("[GOODS]", String.valueOf(port.getParentItems().size()))
                    .replace("[MIN]", String.valueOf(port.getRestockMinMinutes()))
                    .replace("[MAX]", String.valueOf(port.getRestockMaxMinutes())));

            int infoSlot = config.getPortMenuInfoSlot();

            if (infoSlot >= 0 && infoSlot < gui.getSize())
                gui.setItem(infoSlot, manager.generateItemStack(config.getPortMenuInfoMaterial(),
                        config.getPortMenuInfoModelData(), infoName, infoLore));
        }

        // Goods

        List<Item> parents = port.getParentItemsInAlphabeticalOrder();
        List<Integer> itemSlots = config.getPortMenuItemSlots();

        int offset = page * itemSlots.size();

        for (int i = 0; i < itemSlots.size(); i++) {

            int slot = itemSlots.get(i);

            if (slot < 0 || slot >= gui.getSize()) continue;

            int index = offset + i;

            if (index < parents.size()) {
                gui.setItem(slot, buildGoodDisplay(parents.get(index)));
            } else {
                gui.setItem(slot, null);
            }
        }

        // Navigation

        Material navMaterial = config.getPortMenuNavMaterial();

        int backSlot = config.getPortMenuBackSlot();
        int nextSlot = config.getPortMenuNextSlot();

        if (backSlot >= 0 && backSlot < gui.getSize()) {
            if (page > 0) {
                gui.setItem(backSlot, manager.generateItemStack(navMaterial,
                        manager.legacy(Lang.get().message(Message.GUI_CATEGORY_PREVIOUS_NAME))));
            } else {
                gui.setItem(backSlot, manager.generateItemStack(Material.GRAY_STAINED_GLASS_PANE, fillerName));
            }
        }

        if (nextSlot >= 0 && nextSlot < gui.getSize()) {
            if (offset + itemSlots.size() < parents.size()) {
                gui.setItem(nextSlot, manager.generateItemStack(navMaterial,
                        manager.legacy(Lang.get().message(Message.GUI_CATEGORY_NEXT_NAME))));
            } else {
                gui.setItem(nextSlot, manager.generateItemStack(Material.GRAY_STAINED_GLASS_PANE, fillerName));
            }
        }
    }

    static ItemStack buildGoodDisplay(Item good) {

        MarketMenuManager manager = MarketMenuManager.getInstance();

        ItemStack itemStack = good.getItemStack();

        ItemMeta meta = itemStack.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(good.getFormattedName());
            meta.setLore(manager.getLoreFromItem(good, Lang.get().message(Message.GUI_CATEGORY_ITEM_LORE)));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
            itemStack.setItemMeta(meta);
        }

        return itemStack;
    }
}
