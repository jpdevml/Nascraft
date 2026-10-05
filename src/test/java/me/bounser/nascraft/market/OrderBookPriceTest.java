package me.bounser.nascraft.market;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class OrderBookPriceTest {
    @Test public void exactCents() {
        assertEquals(1, OrderBook.cents("0.01"));
        assertEquals(1001, OrderBook.cents("10.01"));
        assertEquals(1000, OrderBook.cents("10"));
    }

    @Test public void rejectsFractionalCentsAndOverflow() {
        assertThrows(ArithmeticException.class, () -> OrderBook.cents("0.001"));
        assertThrows(ArithmeticException.class, () -> OrderBook.cents("99999999999999999999999"));
        assertThrows(NumberFormatException.class, () -> OrderBook.cents("not a price"));
    }

    @Test public void instantFillsUseBestPriceThenOldestOrder() {
        assertEquals(" ORDER BY price_cents ASC, id ASC", OrderBook.instantFillOrder(true));
        assertEquals(" ORDER BY price_cents DESC, id ASC", OrderBook.instantFillOrder(false));
    }
}
