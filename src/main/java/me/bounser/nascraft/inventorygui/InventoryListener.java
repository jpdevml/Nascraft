package me.bounser.nascraft.inventorygui;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.config.lang.Lang;
import me.bounser.nascraft.config.lang.Message;
import me.bounser.nascraft.inventorygui.MarketMenuManager.MenuSession;
import me.bounser.nascraft.market.MarketManager;
import me.bounser.nascraft.market.Port;
import me.bounser.nascraft.market.unit.Item;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * Handles clicks inside the port and buy/sell menus.
 *
 * Security notes:
 * - Every click is cancelled unconditionally while a menu session is active,
 *   including clicks (and shift-clicks) inside the player's own inventory,
 *   and drags are cancelled too: nothing can be inserted or extracted.
 * - Port and item are re-resolved by identifier on every click; if either is
 *   gone (e.g. after a reload) the menu is closed and the session cleared.
 * - Buy/sell clicks re-verify that the player is still inside the port
 *   (bypassable with 'nascraft.ports.bypass').
 * - After a trade the open inventory is repainted in place instead of being
 *   closed and reopened, avoiding session races.
 */
public class InventoryListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent event) {

        if (!(event.getWhoClicked() instanceof Player)) return;

        Player player = (Player) event.getWhoClicked();

        MarketMenuManager manager = MarketMenuManager.getInstance();

        MenuSession session = manager.getSession(player.getUniqueId());

        if (session == null || (session.getInventory() != null && session.getInventory() != event.getView().getTopInventory())) return;

        event.setCancelled(true);

        if (Config.getInstance().getMarketPermissionRequirement() && !player.hasPermission("nascraft.market")) return;

        // The directory has no bound port: it is a read-only overview with paging only.
        if (session.getType() == MarketMenuManager.MenuType.DIRECTORY) {
            handleDirectoryClick(player, session, event);
            return;
        }

        Port port = MarketManager.getInstance().getPort(session.getPortId());

        if (port == null) {
            endSession(player);
            return;
        }

        int rawSlot = event.getRawSlot();

        // Clicks outside the top inventory (own inventory, dropped outside) are only cancelled.
        if (rawSlot < 0 || rawSlot >= event.getView().getTopInventory().getSize()) return;

        ItemStack clicked = event.getCurrentItem();

        if (clicked == null || clicked.getType().isAir()) return;

        if (session.getType() != MarketMenuManager.MenuType.PORT && session.getType() != MarketMenuManager.MenuType.BUY_SELL) {
            try { BazaarMenus.click(player, port, session, rawSlot); }
            catch (IllegalStateException ex) {
                Nascraft.getInstance().getLogger().warning("Bazaar GUI unavailable: " + ex);
                BazaarMenus.feedback(player, "Market data unavailable. Try again later.", false);
            }
            return;
        }

        if (session.getType() == MarketMenuManager.MenuType.PORT) {
            handlePortMenuClick(player, port, session, event);
            return;
        }

        if (session.getType() == MarketMenuManager.MenuType.BUY_SELL) {
            handleBuySellMenuClick(player, port, session, event);
        }
    }

    private void handleDirectoryClick(Player player, MenuSession session, InventoryClickEvent event) {

        Config config = Config.getInstance();

        int rawSlot = event.getRawSlot();

        if (rawSlot < 0 || rawSlot >= event.getView().getTopInventory().getSize()) return;

        int perPage = config.getDirectoryMenuPortSlots().size();
        int total = DirectoryMenu.sortedPorts().size();

        if (rawSlot == config.getDirectoryMenuBackSlot() && session.getPage() > 0) {
            session.setPage(session.getPage() - 1);
            DirectoryMenu.populate(event.getView().getTopInventory(), session.getPage());
            return;
        }

        if (rawSlot == config.getDirectoryMenuNextSlot()
                && (session.getPage() + 1) * perPage < total) {
            session.setPage(session.getPage() + 1);
            DirectoryMenu.populate(event.getView().getTopInventory(), session.getPage());
        }
    }

    private void handlePortMenuClick(Player player, Port port, MenuSession session, InventoryClickEvent event) {

        Config config = Config.getInstance();

        int slot = event.getRawSlot();

        List<Integer> itemSlots = config.getPortMenuItemSlots();
        List<Item> parents = port.getParentItemsInAlphabeticalOrder();

        if (itemSlots.isEmpty()) return;

        if (slot == config.getPortMenuBackSlot()) {

            if (session.getPage() > 0) {
                session.setPage(session.getPage() - 1);
                PortMenu.populate(event.getView().getTopInventory(), port, session.getPage());
            }
            return;
        }

        if (slot == config.getPortMenuNextSlot()) {

            if ((session.getPage() + 1) * itemSlots.size() < parents.size()) {
                session.setPage(session.getPage() + 1);
                PortMenu.populate(event.getView().getTopInventory(), port, session.getPage());
            }
            return;
        }

        int slotIndex = itemSlots.indexOf(slot);

        if (slotIndex == -1) return;

        int index = slotIndex + session.getPage() * itemSlots.size();

        if (index >= parents.size()) return;

        Item item = parents.get(index);

        if (item == null) return;

        MarketMenuManager.getInstance().openBuySellMenu(player, port, item);
    }

    private void handleBuySellMenuClick(Player player, Port port, MenuSession session, InventoryClickEvent event) {

        Config config = Config.getInstance();

        int slot = event.getRawSlot();

        Item item = session.getItemIdentifier() == null ? null : port.getItem(session.getItemIdentifier());

        if (item == null) {
            endSession(player);
            return;
        }

        // Back to the port overview.

        if (config.getBuySellBackEnabled() && slot == config.getBuySellBackSlot()) {
            if (session.getCategoryId() != null) BazaarMenus.goods(player, port, session.getCategoryId(), session.getPage(), session.getCategoryPage());
            else BazaarMenus.categories(player, port, 0);
            return;
        }

        if (slot == config.getBazaarOrdersSlot() && BuySellMenu.navigationAvailable(config, slot, event.getView().getTopInventory().getSize())) {
            BazaarMenus.orders(player, port, 0); return;
        }
        if (slot == config.getBazaarClaimsSlot() && BuySellMenu.navigationAvailable(config, slot, event.getView().getTopInventory().getSize())) {
            BazaarMenus.claims(player, port, 0); return;
        }

        // Switch to a displayed variant (child or parent of the family).

        String variantIdentifier = session.getVariantSlots().get(slot);

        if (variantIdentifier != null) {

            Item variant = port.getItem(variantIdentifier);

            if (variant == null) {
                endSession(player);
                return;
            }

            MarketMenuManager.getInstance().openBuySellMenu(player, port, variant, session);
            return;
        }

        if (item.isPlayerOnly() && (slot == config.getBuyOrderSlot() || slot == config.getSellOrderSlot())) {
            if (!ensureInsidePort(player, port)) return;
            session.setBuyOrder(slot == config.getBuyOrderSlot());
            session.setQuantity(0);
            session.setPriceCents(0);
            BazaarMenus.editor(player, port, session);
            return;
        }

        // Buy buttons.

        Integer buyAmount = amountAtSlot(config.getBuySellBuySlots(), slot);

        if (buyAmount != null) {

            if (!ensureInsidePort(player, port)) return;

            double worth = item.buy(buyAmount, player.getUniqueId(), true);
            player.playSound(player.getLocation(), config.bazaarSound(worth > 0 ? "success" : "error",
                    worth > 0 ? org.bukkit.Sound.ENTITY_PLAYER_LEVELUP : org.bukkit.Sound.ENTITY_VILLAGER_NO), .6f, 1.1f);

            session.setVariantSlots(BuySellMenu.populate(event.getView().getTopInventory(), item));
            return;
        }

        // Sell buttons.

        Integer sellAmount = amountAtSlot(config.getBuySellSellSlots(), slot);

        if (sellAmount != null) {

            if (!ensureInsidePort(player, port)) return;

            double worth = item.sell(sellAmount, player.getUniqueId(), true);
            player.playSound(player.getLocation(), config.bazaarSound(worth > 0 ? "success" : "error",
                    worth > 0 ? org.bukkit.Sound.ENTITY_PLAYER_LEVELUP : org.bukkit.Sound.ENTITY_VILLAGER_NO), .6f, 1.1f);

            session.setVariantSlots(BuySellMenu.populate(event.getView().getTopInventory(), item));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        MarketMenuManager manager = MarketMenuManager.getInstance();
        MarketMenuManager.PendingInput pending = manager.getPendingInput(event.getPlayer().getUniqueId());
        if (pending == null) return;
        event.setCancelled(true); // Never broadcast a draft price or quantity.
        String message = event.getMessage();
        Player player = event.getPlayer();
        if (event.isAsynchronous())
            Bukkit.getScheduler().runTask(Nascraft.getInstance(), () -> BazaarMenus.handleChatInput(player, pending, message));
        else BazaarMenus.handleChatInput(player, pending, message);
    }

    @EventHandler
    public void onOpen(InventoryOpenEvent event) {
        // Closing a GUI can transition to the player's own inventory. That is the
        // expected state while typing, not a reason to discard the pending draft.
        MarketMenuManager manager = MarketMenuManager.getInstance();
        MarketMenuManager.PendingInput pending = manager.getPendingInput(event.getPlayer().getUniqueId());
        if (pending != null && !pending.closingEditor()
                && !BazaarMenus.isNormalInventoryType(event.getInventory().getType()))
            manager.cancelPendingInput(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {

        if (!(event.getWhoClicked() instanceof Player)) return;

        MenuSession session = MarketMenuManager.getInstance().getSession(event.getWhoClicked().getUniqueId());
        if (session != null && (session.getInventory() == null || session.getInventory() == event.getView().getTopInventory()))
            event.setCancelled(true);
    }

    /**
     * Sessions do not survive a closed inventory: the menu session is cleared
     * entirely (no port context is kept; reopening re-resolves the port from
     * the player's location, which is cheap).
     */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {

        if (!(event.getPlayer() instanceof Player)) return;

        MarketMenuManager manager = MarketMenuManager.getInstanceIfPresent();

        if (manager != null) {
            MenuSession session = manager.getSession(event.getPlayer().getUniqueId());
            if (session != null && (session.getInventory() == null || session.getInventory() == event.getInventory()))
                manager.removeSession((Player) event.getPlayer());
        }
    }

    // Helpers:

    private Integer amountAtSlot(Map<Integer, Integer> buttons, int slot) {

        for (Map.Entry<Integer, Integer> entry : buttons.entrySet())
            if (entry.getValue() == slot) return entry.getKey();

        return null;
    }

    private boolean ensureInsidePort(Player player, Port port) {

        if (player.hasPermission("nascraft.ports.bypass")) return true;

        if (!port.isInside(player.getLocation())) {
            Lang.get().message(player, Message.NOT_IN_PORT);
            scheduleClose(player);
            return false;
        }

        return true;
    }

    private void endSession(Player player) {
        // Keep the session until InventoryCloseEvent so intervening clicks remain cancelled.
        scheduleClose(player);
    }

    private void scheduleClose(Player player) {
        Bukkit.getScheduler().runTask(Nascraft.getInstance(), player::closeInventory);
    }
}
