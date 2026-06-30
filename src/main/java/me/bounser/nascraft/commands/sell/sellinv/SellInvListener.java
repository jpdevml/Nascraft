package me.bounser.nascraft.commands.sell.sellinv;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.config.lang.Lang;
import me.bounser.nascraft.config.lang.Message;
import me.bounser.nascraft.formatter.Formatter;
import me.bounser.nascraft.formatter.Style;
import me.bounser.nascraft.managers.InventoryManager;
import me.bounser.nascraft.managers.currencies.CurrenciesManager;
import me.bounser.nascraft.market.MarketManager;
import me.bounser.nascraft.market.Port;
import me.bounser.nascraft.market.unit.Item;
import net.kyori.adventure.platform.bukkit.BukkitComponentSerializer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.MetadataValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deposit GUI bound to a port (the port id travels in the "NascraftSell"
 * metadata). Players move items they want to sell into the GUI, can take
 * them back, and sell everything at once at the port's prices.
 *
 * Dupe-safety:
 * - Deposited ORIGINAL stacks are tracked per player keyed by the GUI slot
 *   they are displayed in. Withdrawals remove by slot index: an item is only
 *   handed back if Map#remove actually returned it.
 * - On close/sell the tracked map entry is removed from the registry FIRST,
 *   then the items are handed back/sold (reentrancy-safe).
 * - Every click while the GUI is open is cancelled unconditionally, and
 *   drags are cancelled too.
 */
public class SellInvListener implements Listener {

    private static SellInvListener instance;

    /** Original deposited stacks per player, keyed by the GUI slot displaying them. */
    private final Map<UUID, Map<Integer, ItemStack>> deposits = new HashMap<>();

    public SellInvListener() { instance = this; }

    public static SellInvListener getInstanceIfPresent() { return instance; }

    @EventHandler
    public void onClick(InventoryClickEvent event) {

        if (!(event.getWhoClicked() instanceof Player)) return;

        Player player = (Player) event.getWhoClicked();

        String portId = getBoundPortId(player);

        if (portId == null) return;

        event.setCancelled(true);

        Port port = MarketManager.getInstance().getPort(portId);

        if (port == null) {
            // Port disappeared (reload). Closing returns the deposits via onClose.
            scheduleClose(player);
            return;
        }

        Inventory top = event.getView().getTopInventory();

        int rawSlot = event.getRawSlot();

        if (rawSlot < 0) return;

        if (rawSlot >= top.getSize()) {
            handleDeposit(player, port, event, top);
            return;
        }

        Config config = Config.getInstance();

        if (config.getCloseButtonEnabled() && rawSlot == config.getCloseButtonSlot()) {
            scheduleClose(player);
            return;
        }

        if (rawSlot == config.getSellButtonSlot()) {
            handleSell(player, port, top);
            return;
        }

        handleWithdraw(player, port, rawSlot, top);
    }

    private void handleDeposit(Player player, Port port, InventoryClickEvent event, Inventory top) {

        if (!MarketManager.getInstance().getActive()) {
            Lang.get().message(player, Message.SHOP_CLOSED);
            return;
        }

        ItemStack clicked = event.getCurrentItem();

        if (clicked == null || clicked.getType().isAir()) return;

        Item item = port.getItem(clicked);

        if (item == null) {
            Lang.get().message(player, Message.SELL_ITEM_NOT_ALLOWED);
            return;
        }

        Map<Integer, ItemStack> playerDeposits = deposits.computeIfAbsent(player.getUniqueId(), key -> new HashMap<>());

        Inventory clickedInventory = event.getClickedInventory();

        if (clickedInventory == null) return;

        if (event.isShiftClick()) {

            boolean full = false;

            for (int i = 0; i < clickedInventory.getSize(); i++) {

                ItemStack stack = clickedInventory.getItem(i);

                if (stack == null || stack.getType().isAir() || !stack.isSimilar(clicked)) continue;

                if (!canDeposit(player, port, playerDeposits, item, stack.getAmount())) return;

                int slot = nextFreeContentSlot(playerDeposits, top.getSize());

                if (slot == -1) { full = true; break; }

                playerDeposits.put(slot, stack.clone());
                clickedInventory.setItem(i, null);
                top.setItem(slot, buildDisplayStack(stack));
            }

            if (full) Lang.get().message(player, Message.SELL_FULL);

        } else {

            if (!canDeposit(player, port, playerDeposits, item, clicked.getAmount())) return;

            int slot = nextFreeContentSlot(playerDeposits, top.getSize());

            if (slot == -1) {
                Lang.get().message(player, Message.SELL_FULL);
                return;
            }

            playerDeposits.put(slot, clicked.clone());
            clickedInventory.setItem(event.getSlot(), null);
            top.setItem(slot, buildDisplayStack(clicked));
        }

        updateSellButton(top, port, player);
    }

    /** Refuses deposits that would push a restricted good past its price floor. */
    private boolean canDeposit(Player player, Port port, Map<Integer, ItemStack> playerDeposits, Item item, int amount) {

        if (!item.isPriceRestricted()) return true;

        int alreadyDeposited = 0;

        for (ItemStack deposited : playerDeposits.values()) {

            if (deposited == null) continue;

            Item depositedItem = port.getItem(deposited);

            if (depositedItem != null && depositedItem.getIdentifier().equals(item.getIdentifier()))
                alreadyDeposited += deposited.getAmount();
        }

        if (!item.getPrice().canStockChange(alreadyDeposited + amount, false)) {
            Lang.get().message(player, Message.BOTTOM_LIMIT_REACHED);
            return false;
        }

        return true;
    }

    private void handleWithdraw(Player player, Port port, int slot, Inventory top) {

        if (slot < firstContentSlot() || slot > lastContentSlot(top.getSize())) return;

        Map<Integer, ItemStack> playerDeposits = deposits.get(player.getUniqueId());

        if (playerDeposits == null) return;

        ItemStack original = playerDeposits.remove(slot);

        // Only hand an item back if it was actually removed from the tracked map.
        if (original == null) return;

        top.setItem(slot, null);

        InventoryManager.addItemsToInventory(player, original, original.getAmount());

        updateSellButton(top, port, player);
    }

    private void handleSell(Player player, Port port, Inventory top) {

        if (!MarketManager.getInstance().getActive()) {
            Lang.get().message(player, Message.SHOP_CLOSED);
            return;
        }

        Map<Integer, ItemStack> playerDeposits = deposits.get(player.getUniqueId());

        if (playerDeposits == null || playerDeposits.isEmpty()) return;

        if (!player.hasPermission("nascraft.ports.bypass") && !port.isInside(player.getLocation())) {
            Lang.get().message(player, Message.NOT_IN_PORT);
            // Closing returns the deposits via onClose.
            scheduleClose(player);
            return;
        }

        // Take ownership of the tracked items before doing anything with them.
        deposits.remove(player.getUniqueId());

        double total = 0;

        for (ItemStack stack : playerDeposits.values()) {

            if (stack == null) continue;

            // Resolve fresh: if the port no longer trades it, give it back instead of selling.
            Item item = port.getItem(stack);

            if (item == null) {
                InventoryManager.addItemsToInventory(player, stack, stack.getAmount());
                continue;
            }

            // feedback=false: the GUI holds the items, Item#sell must not touch the inventory.
            double worth = item.sell(stack.getAmount(), player.getUniqueId(), false);

            if (worth < 0) {
                InventoryManager.addItemsToInventory(player, stack, stack.getAmount());
                continue;
            }

            total += worth;
        }

        for (int slot = firstContentSlot(); slot <= lastContentSlot(top.getSize()); slot++)
            top.setItem(slot, null);

        updateSellButton(top, port, player);

        if (total > 0) {
            Lang.get().message(player, Message.SELL_ACTION_MESSAGE,
                    Formatter.format(CurrenciesManager.getInstance().getDefaultCurrency(), total, Style.ROUND_BASIC), "", "");
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {

        if (!(event.getWhoClicked() instanceof Player)) return;

        if (getBoundPortId((Player) event.getWhoClicked()) != null) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {

        if (!(event.getPlayer() instanceof Player)) return;

        Player player = (Player) event.getPlayer();

        if (!player.hasMetadata("NascraftSell")) return;

        player.removeMetadata("NascraftSell", Nascraft.getInstance());

        returnHeldItems(player);
    }

    /**
     * Returns every tracked stack of the given player once. The map entry is
     * removed before any item is handed back, so reentrant calls are no-ops.
     */
    public void returnHeldItems(Player player) {

        Map<Integer, ItemStack> playerDeposits = deposits.remove(player.getUniqueId());

        if (playerDeposits == null) return;

        for (ItemStack stack : playerDeposits.values())
            if (stack != null) InventoryManager.addItemsToInventory(player, stack, stack.getAmount());
    }

    /** Flushes the deposits of every player. Used by MarketMenuManager#closeAllMenus (reload/disable). */
    public void returnAllHeldItems() {

        for (UUID uuid : new ArrayList<>(deposits.keySet())) {

            Map<Integer, ItemStack> playerDeposits = deposits.remove(uuid);

            if (playerDeposits == null) continue;

            Player player = Bukkit.getPlayer(uuid);

            if (player == null) {
                Nascraft.getInstance().getLogger().warning("Discarding " + playerDeposits.size() +
                        " sell-menu stack(s) of offline player " + uuid + ".");
                continue;
            }

            for (ItemStack stack : playerDeposits.values())
                if (stack != null) InventoryManager.addItemsToInventory(player, stack, stack.getAmount());
        }
    }

    public void updateSellButton(Inventory top, Port port, Player player) {

        Config config = Config.getInstance();

        int buttonSlot = config.getSellButtonSlot();

        if (buttonSlot < 0 || buttonSlot >= top.getSize()) return;

        ItemStack button = top.getItem(buttonSlot);

        if (button == null) return;

        ItemMeta meta = button.getItemMeta();

        if (meta == null) return;

        double total = getDepositsValue(port, player);

        String worth = total > 0 ?
                Formatter.format(CurrenciesManager.getInstance().getDefaultCurrency(), total, Style.ROUND_BASIC) : "";

        List<String> lore = legacyLines(Lang.get().message(Message.SELL_BUTTON_LORE).replace("[WORTH-LIST]", worth));

        meta.setLore(lore);
        button.setItemMeta(meta);

        top.setItem(buttonSlot, button);
    }

    private double getDepositsValue(Port port, Player player) {

        Map<Integer, ItemStack> playerDeposits = deposits.get(player.getUniqueId());

        if (playerDeposits == null || playerDeposits.isEmpty()) return 0;

        Map<String, Integer> amounts = new LinkedHashMap<>();
        Map<String, Item> items = new LinkedHashMap<>();

        for (ItemStack stack : playerDeposits.values()) {

            if (stack == null) continue;

            Item item = port.getItem(stack);

            if (item == null) continue;

            amounts.merge(item.getIdentifier(), stack.getAmount(), Integer::sum);
            items.put(item.getIdentifier(), item);
        }

        double total = 0;

        for (Map.Entry<String, Integer> entry : amounts.entrySet())
            total += items.get(entry.getKey()).sellPrice(entry.getValue());

        return total;
    }

    // Helpers:

    private String getBoundPortId(Player player) {

        if (!player.hasMetadata("NascraftSell")) return null;

        List<MetadataValue> values = player.getMetadata("NascraftSell");

        if (values.isEmpty()) return null;

        return values.get(0).asString();
    }

    /** Content area: everything between the top and bottom border rows. */
    private int firstContentSlot() { return 9; }

    private int lastContentSlot(int size) { return size - 10; }

    private int nextFreeContentSlot(Map<Integer, ItemStack> playerDeposits, int size) {

        for (int slot = firstContentSlot(); slot <= lastContentSlot(size); slot++)
            if (!playerDeposits.containsKey(slot)) return slot;

        return -1;
    }

    private ItemStack buildDisplayStack(ItemStack original) {

        ItemStack display = original.clone();

        ItemMeta meta = display.getItemMeta();

        if (meta == null) return display;

        String removeLine = legacy(Lang.get().message(Message.SELL_REMOVE_ITEM));

        List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();

        lore.add("");
        lore.add(removeLine);

        meta.setLore(lore);
        display.setItemMeta(meta);

        return display;
    }

    private void scheduleClose(Player player) {
        Bukkit.getScheduler().runTask(Nascraft.getInstance(), player::closeInventory);
    }

    private String legacy(String miniMessage) {
        return BukkitComponentSerializer.legacy().serialize(MiniMessage.miniMessage().deserialize(miniMessage));
    }

    private List<String> legacyLines(String miniMessage) {

        List<String> lines = new ArrayList<>();

        for (String line : miniMessage.split("\\n"))
            lines.add(legacy(line));

        return lines;
    }
}
