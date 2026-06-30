package me.bounser.nascraft.inventorygui;

import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.config.lang.Lang;
import me.bounser.nascraft.config.lang.Message;
import me.bounser.nascraft.market.MarketManager;
import me.bounser.nascraft.market.Port;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Chest GUI listing every port. Read-only: it exists so players can see, at a
 * glance, where they can trade and what each port deals in. Stateless, like
 * {@link PortMenu}.
 */
public final class DirectoryMenu {

    private DirectoryMenu() { }

    /** Ports sorted alphabetically by display name. */
    public static List<Port> sortedPorts() {

        List<Port> ports = new ArrayList<>(MarketManager.getInstance().getPorts());

        ports.sort((a, b) -> a.getPlainDisplayName().compareToIgnoreCase(b.getPlainDisplayName()));

        return ports;
    }

    public static void populate(Inventory gui, int page) {

        Config config = Config.getInstance();
        MarketMenuManager manager = MarketMenuManager.getInstance();

        // Fillers

        String fillerName = manager.legacy(Lang.get().message(Message.GUI_FILLERS_NAME));

        HashMap<Material, List<Integer>> fillers = config.getDirectoryMenuFillers();

        for (Material material : fillers.keySet()) {
            ItemStack filler = manager.generateItemStack(material, fillerName);
            for (int slot : fillers.get(material))
                if (slot >= 0 && slot < gui.getSize()) gui.setItem(slot, filler);
        }

        // Ports

        List<Port> ports = sortedPorts();
        List<Integer> slots = config.getDirectoryMenuPortSlots();

        int offset = page * slots.size();

        for (int i = 0; i < slots.size(); i++) {

            int slot = slots.get(i);

            if (slot < 0 || slot >= gui.getSize()) continue;

            int index = offset + i;

            if (index < ports.size()) {
                gui.setItem(slot, buildEntry(ports.get(index)));
            } else {
                gui.setItem(slot, null);
            }
        }

        // Navigation

        Material navMaterial = config.getDirectoryMenuNavMaterial();

        int backSlot = config.getDirectoryMenuBackSlot();
        int nextSlot = config.getDirectoryMenuNextSlot();

        if (backSlot >= 0 && backSlot < gui.getSize()) {
            if (page > 0) {
                gui.setItem(backSlot, manager.generateItemStack(navMaterial,
                        manager.legacy(Lang.get().message(Message.GUI_CATEGORY_PREVIOUS_NAME))));
            } else {
                gui.setItem(backSlot, manager.generateItemStack(Material.GRAY_STAINED_GLASS_PANE, fillerName));
            }
        }

        if (nextSlot >= 0 && nextSlot < gui.getSize()) {
            if (offset + slots.size() < ports.size()) {
                gui.setItem(nextSlot, manager.generateItemStack(navMaterial,
                        manager.legacy(Lang.get().message(Message.GUI_CATEGORY_NEXT_NAME))));
            } else {
                gui.setItem(nextSlot, manager.generateItemStack(Material.GRAY_STAINED_GLASS_PANE, fillerName));
            }
        }
    }

    private static ItemStack buildEntry(Port port) {

        Config config = Config.getInstance();
        MarketMenuManager manager = MarketMenuManager.getInstance();

        Material material = config.getDirectoryMenuEntryMaterial();
        int modelData = config.getDirectoryMenuEntryModelData();

        String name = manager.legacy(Lang.get().message(Message.PORT_DIRECTORY_ENTRY_NAME)
                .replace("[PORT]", port.getDisplayName()));

        String lore = Lang.get().message(Message.PORT_DIRECTORY_ENTRY_LORE)
                .replace("[PORT]", port.getDisplayName())
                .replace("[GOODS]", String.valueOf(port.getParentItems().size()))
                .replace("[WORLD]", port.getWorldName())
                .replace("[X]", String.valueOf((int) port.getCenterX()))
                .replace("[Z]", String.valueOf((int) port.getCenterZ()));

        return manager.generateItemStack(material, modelData, name, manager.legacyLines(lore));
    }
}
