package me.bounser.nascraft.inventorygui;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.market.MarketManager;
import me.bounser.nascraft.market.Port;
import me.bounser.nascraft.market.unit.Item;
import me.bounser.nascraft.managers.MoneyManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.event.inventory.InventoryType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.HashSet;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** A real MockBukkit click, close and scheduler tick; parsing-only tests miss this handoff. */
public class BazaarMockBukkitTest {
    private ServerMock server;
    private org.bukkit.plugin.Plugin plugin;
    private PlayerMock player;
    private MarketMenuManager menus;
    private Object previousConfig, previousMarket, previousPlugin, previousMoney;
    private Port port;

    private static Object setStatic(Class<?> owner, String name, Object value) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        Object old = field.get(null);
        field.set(null, value);
        return old;
    }

    @Before public void setUp() throws ReflectiveOperationException {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("Nascraft");
        player = server.addPlayer();
        menus = MarketMenuManager.getInstance();
        Config config = Mockito.mock(Config.class);
        when(config.getMarketPermissionRequirement()).thenReturn(false);
        when(config.getCurrencyFormat()).thenReturn("[AMOUNT]$");
        when(config.bazaarSound(anyString(), any(Sound.class))).thenAnswer(call -> call.getArgument(1));
        previousConfig = setStatic(Config.class, "instance", config);
        MarketManager market = Mockito.mock(MarketManager.class);
        port = Mockito.mock(Port.class);
        when(port.getId()).thenReturn("global");
        when(port.isInside(any())).thenReturn(true);
        Item item = Mockito.mock(Item.class);
        when(item.isPlayerOnly()).thenReturn(true);
        when(item.isParent()).thenReturn(true);
        when(item.getItemStack()).thenReturn(new ItemStack(Material.DIAMOND));
        when(item.getFormattedName()).thenReturn("Diamond");
        when(port.getItem("diamond")).thenReturn(item);
        when(market.getPort("global")).thenReturn(port);
        previousMarket = setStatic(MarketManager.class, "instance", market);
        previousPlugin = setStatic(Nascraft.class, "main", Mockito.mock(Nascraft.class));
        MoneyManager money = Mockito.mock(MoneyManager.class);
        previousMoney = setStatic(MoneyManager.class, "instance", money);
        Bukkit.getPluginManager().registerEvents(new InventoryListener(), plugin);
    }

    @After public void tearDown() throws ReflectiveOperationException {
        setStatic(Config.class, "instance", previousConfig);
        setStatic(MarketManager.class, "instance", previousMarket);
        setStatic(Nascraft.class, "main", previousPlugin);
        setStatic(MoneyManager.class, "instance", previousMoney);
        MockBukkit.unmock();
    }

    private void openEditor() {
        Inventory gui = Bukkit.createInventory(null, 54);
        gui.setItem(20, new ItemStack(Material.CHEST));
        gui.setItem(22, new ItemStack(Material.GOLD_INGOT));
        var draft = new MarketMenuManager.MenuSession("global", MarketMenuManager.MenuType.ORDER_EDITOR);
        draft.setItemIdentifier("diamond");
        menus.open(player, gui, draft);
    }

    @Test public void clickingQuantityClosesGuiAndPromptsForPrivateChat() {
        openEditor();
        player.simulateInventoryClick(20);
        assertNotNull("Click must arm pending input", menus.getPendingInput(player.getUniqueId()));
        server.getScheduler().performOneTick();
        assertNotNull("Closing editor must not discard pending input", menus.getPendingInput(player.getUniqueId()));
        assertNull("Closing editor must clear its menu session", menus.getSession(player.getUniqueId()));
        assertTrue("Player must receive prompt after close", player.nextComponentMessage().toString().contains("quantity"));
    }

    @Test public void delayedCloseMustRecoverFromStaleMenuSessionAndStillPrompt() {
        openEditor();
        // Some inventory implementations expose a different wrapper in the close
        // event, leaving the old session present after the menu itself is closed.
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.MONITOR)
            public void restoreStaleSession(InventoryCloseEvent event) {
                MarketMenuManager.MenuSession old = menus.getSession(player.getUniqueId());
                if (old == null) {
                    MarketMenuManager.MenuSession stale = new MarketMenuManager.MenuSession("global", MarketMenuManager.MenuType.ORDER_EDITOR);
                    stale.setItemIdentifier("diamond");
                    menus.trackOpened(player, event.getInventory(), stale);
                }
            }
        }, plugin);
        player.simulateInventoryClick(20);
        server.getScheduler().performOneTick();
        assertNotNull(menus.getPendingInput(player.getUniqueId()));
        assertNull(menus.getSession(player.getUniqueId()));
        assertTrue(player.nextComponentMessage().toString().contains("quantity"));
    }

    @Test public void transientOpenEventDuringCloseDoesNotEatPrompt() {
        openEditor();
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.MONITOR)
            public void simulateTransition(InventoryCloseEvent event) {
                InventoryView view = Mockito.mock(InventoryView.class);
                Inventory temporary = Mockito.mock(Inventory.class);
                when(temporary.getType()).thenReturn(InventoryType.CHEST);
                when(view.getTopInventory()).thenReturn(temporary);
                when(view.getPlayer()).thenReturn(player);
                server.getPluginManager().callEvent(new InventoryOpenEvent(view));
            }
        }, plugin);
        player.simulateInventoryClick(20);
        server.getScheduler().performOneTick();
        assertNotNull(menus.getPendingInput(player.getUniqueId()));
        assertTrue(player.nextComponentMessage().toString().contains("quantity"));
    }

    @Test public void chatIsPrivateAndReopensEditorWithUpdatedQuantity() {
        openEditor();
        player.simulateInventoryClick(20);
        server.getScheduler().performOneTick();
        assertTrue(player.nextComponentMessage().toString().contains("quantity"));
        AsyncPlayerChatEvent chat = new AsyncPlayerChatEvent(false, player, "32", new HashSet<>());
        server.getPluginManager().callEvent(chat);
        assertTrue("Input must not be broadcast", chat.isCancelled());
        assertNull(menus.getPendingInput(player.getUniqueId()));
        assertEquals(32, menus.getSession(player.getUniqueId()).getQuantity());
        assertSame(menus.getSession(player.getUniqueId()).getInventory(), player.getOpenInventory().getTopInventory());
    }

    @Test public void asynchronousChatIsCancelledBeforeMainThreadReopen() {
        openEditor();
        player.simulateInventoryClick(20);
        server.getScheduler().performOneTick();
        assertTrue(player.nextComponentMessage().toString().contains("quantity"));
        AsyncPlayerChatEvent chat = new AsyncPlayerChatEvent(true, player, "64", new HashSet<>());
        java.util.concurrent.CompletableFuture.runAsync(() -> server.getPluginManager().callEvent(chat)).join();
        assertTrue(chat.isCancelled());
        assertNotNull("Pending input remains until the sync task runs", menus.getPendingInput(player.getUniqueId()));
        server.getScheduler().performOneTick();
        assertEquals(64, menus.getSession(player.getUniqueId()).getQuantity());
        assertNull(menus.getPendingInput(player.getUniqueId()));
    }

    @Test public void creativePlayerCanEnterQuantity() {
        player.setGameMode(GameMode.CREATIVE);
        openEditor();
        player.simulateInventoryClick(20);
        server.getScheduler().performOneTick();
        assertTrue(player.nextComponentMessage().toString().contains("quantity"));
        AsyncPlayerChatEvent chat = new AsyncPlayerChatEvent(false, player, "17", new HashSet<>());
        server.getPluginManager().callEvent(chat);
        assertTrue(chat.isCancelled());
        assertEquals(17, menus.getSession(player.getUniqueId()).getQuantity());
    }

    @Test public void priceAcceptsRetryThenReopensWithParsedCents() {
        openEditor();
        player.simulateInventoryClick(22);
        server.getScheduler().performOneTick();
        assertTrue(player.nextComponentMessage().toString().contains("price"));
        AsyncPlayerChatEvent invalid = new AsyncPlayerChatEvent(false, player, "invalid", new HashSet<>());
        server.getPluginManager().callEvent(invalid);
        assertTrue(invalid.isCancelled());
        assertNotNull(menus.getPendingInput(player.getUniqueId()));
        assertTrue(player.nextComponentMessage().toString().contains("Try again"));
        AsyncPlayerChatEvent price = new AsyncPlayerChatEvent(false, player, "10.50", new HashSet<>());
        server.getPluginManager().callEvent(price);
        assertTrue(price.isCancelled());
        assertEquals(1050, menus.getSession(player.getUniqueId()).getPriceCents());
        assertNull(menus.getPendingInput(player.getUniqueId()));
    }

    @Test public void openingAnotherInventoryDiscardsPendingInput() {
        openEditor();
        player.simulateInventoryClick(20);
        server.getScheduler().performOneTick();
        assertNotNull(menus.getPendingInput(player.getUniqueId()));
        player.openInventory(Bukkit.createInventory(null, 9));
        assertNull(menus.getPendingInput(player.getUniqueId()));
    }

    @Test public void invalidInputCanRetryAndCancelRestoresEditor() {
        openEditor();
        player.simulateInventoryClick(22);
        server.getScheduler().performOneTick();
        assertTrue(player.nextComponentMessage().toString().contains("price"));
        AsyncPlayerChatEvent invalid = new AsyncPlayerChatEvent(false, player, "oops", new HashSet<>());
        server.getPluginManager().callEvent(invalid);
        assertTrue(invalid.isCancelled());
        assertNotNull(menus.getPendingInput(player.getUniqueId()));
        assertNull(menus.getSession(player.getUniqueId()));
        assertTrue(player.nextComponentMessage().toString().contains("Try again"));
        AsyncPlayerChatEvent cancel = new AsyncPlayerChatEvent(false, player, "cancel", new HashSet<>());
        server.getPluginManager().callEvent(cancel);
        assertTrue(cancel.isCancelled());
        assertEquals(0, menus.getSession(player.getUniqueId()).getPriceCents());
        assertNull(menus.getPendingInput(player.getUniqueId()));
    }
}

