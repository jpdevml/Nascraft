package me.bounser.nascraft.inventorygui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class BazaarInputTest {
    @Test public void acceptsExactOrderInputs() {
        assertEquals(1, BazaarMenus.quantity("1"));
        assertEquals(1_000_000, BazaarMenus.quantity("1000000"));
        assertEquals(1, BazaarMenus.price("0.01"));
        assertEquals(1001, BazaarMenus.price("10.01"));
        assertEquals(1_000_000_000L, BazaarMenus.price("10000000"));
    }

    @Test public void chatInputUpdatesOnlySelectedFieldAndPreservesDraftOnError() {
        MarketMenuManager.MenuSession draft = new MarketMenuManager.MenuSession("port", MarketMenuManager.MenuType.ORDER_EDITOR);
        draft.setQuantity(16);
        draft.setPriceCents(200);
        BazaarMenus.applyNumber(draft, "32", false);
        assertEquals(32, draft.getQuantity());
        assertEquals(200, draft.getPriceCents());
        assertThrows(IllegalArgumentException.class, () -> BazaarMenus.applyNumber(draft, "hello", true));
        assertThrows(IllegalArgumentException.class, () -> BazaarMenus.applyNumber(draft, "-3", true));
        assertEquals(32, draft.getQuantity());
        assertEquals(200, draft.getPriceCents());
        BazaarMenus.applyNumber(draft, "10.50", true);
        assertEquals(1050, draft.getPriceCents());
        MarketMenuManager.MenuSession editor = new MarketMenuManager.MenuSession("port", MarketMenuManager.MenuType.ORDER_EDITOR);
        editor.copyContext(draft);
        assertEquals(32, editor.getQuantity());
        assertEquals(1050, editor.getPriceCents());
    }

    @Test public void recognizesCancelWithoutAcceptingItAsANumber() {
        assertTrue(BazaarMenus.isCancelInput(" CANCEL "));
        assertFalse(BazaarMenus.isCancelInput("cancelled"));
        assertThrows(IllegalArgumentException.class, () -> BazaarMenus.quantity("cancel"));
        assertThrows(IllegalArgumentException.class, () -> BazaarMenus.price("word"));
    }

    @Test public void rejectsMalformedOrOutOfRangeInputs() {
        for (String quantity : new String[]{"", "0", "-1", "1.5", "1000001", "999999999999999", "1e3"})
            assertThrows(IllegalArgumentException.class, () -> BazaarMenus.quantity(quantity));
        for (String price : new String[]{"", "0", "0.00", "0.001", "-1", "10.999", "10000000.01", "1e4", ".50"})
            assertThrows(IllegalArgumentException.class, () -> BazaarMenus.price(price));
    }
}
