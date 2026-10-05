package me.bounser.nascraft.inventorygui;

import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class BazaarChatFlowTest {
    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "isOnline" -> false; // Allows testing cancellation without a live server.
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test public void closingEditorKeepsPendingDraftUntilChatIsHandled() {
        MarketMenuManager menus = new MarketMenuManager();
        Player player = player(UUID.randomUUID());
        MarketMenuManager.MenuSession draft = new MarketMenuManager.MenuSession("port", MarketMenuManager.MenuType.ORDER_EDITOR);
        draft.setQuantity(16);
        menus.trackOpened(player, null, draft);
        MarketMenuManager.PendingInput pending = menus.beginChatInput(player, draft, false);
        menus.removeSession(player); // InventoryCloseEvent must not discard chat input.
        assertSame(pending, menus.getPendingInput(player.getUniqueId()));
        assertEquals(16, pending.draft().getQuantity());
        menus.removePendingInput(player.getUniqueId(), pending);
        assertNull(menus.getPendingInput(player.getUniqueId()));
    }

    @Test public void anotherPlayerQuittingDoesNotInvalidatePendingInput() {
        MarketMenuManager menus = new MarketMenuManager();
        Player leaving = player(UUID.randomUUID());
        Player typing = player(UUID.randomUUID());
        MarketMenuManager.MenuSession draft = new MarketMenuManager.MenuSession("port", MarketMenuManager.MenuType.ORDER_EDITOR);
        MarketMenuManager.PendingInput pending = menus.beginChatInput(typing, draft, true);
        menus.clearSession(leaving);
        assertSame(pending, menus.getPendingInput(typing.getUniqueId()));
        assertEquals(pending.generation(), menus.getMenuGeneration());
    }

    @Test public void pendingChatIsCancelledEvenIfThePlayerDisconnectsBeforeProcessing() {
        MarketMenuManager menus = MarketMenuManager.getInstance();
        Player player = player(UUID.randomUUID());
        MarketMenuManager.MenuSession draft = new MarketMenuManager.MenuSession("port", MarketMenuManager.MenuType.ORDER_EDITOR);
        menus.beginChatInput(player, draft, false);
        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(false, player, "42", new HashSet<>());
        new InventoryListener().onChat(event);
        assertTrue(event.isCancelled());
        assertNull(menus.getPendingInput(player.getUniqueId()));

        AsyncPlayerChatEvent ordinaryChat = new AsyncPlayerChatEvent(false, player, "hello", new HashSet<>());
        new InventoryListener().onChat(ordinaryChat);
        assertFalse(ordinaryChat.isCancelled());
    }
}
