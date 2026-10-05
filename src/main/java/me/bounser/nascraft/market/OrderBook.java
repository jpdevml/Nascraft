package me.bounser.nascraft.market;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.database.DatabaseExecutor;
import me.bounser.nascraft.managers.InventoryManager;
import me.bounser.nascraft.managers.MoneyManager;
import me.bounser.nascraft.market.unit.Item;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.util.*;

/** Player-only markets: persistent escrowed limit orders and instant fills. Bukkit/Vault calls are main-thread only. */
public final class OrderBook {
    private static final OrderBook INSTANCE = new OrderBook();
    public static OrderBook get() { return INSTANCE; }
    private boolean busy;

    private static String encode(ItemStack stack) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) { out.writeObject(stack); }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static ItemStack decode(String text) throws IOException, ClassNotFoundException {
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(text)))) {
            return (ItemStack) in.readObject();
        }
    }

    public static long cents(String value) {
        BigDecimal decimal = new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
        return decimal.movePointRight(2).longValueExact();
    }

    private static double money(long cents) { return cents / 100.0; }

    private static void valid(Item item, int amount) {
        if (!item.isPlayerOnly() || !item.isParent() || amount <= 0 || amount > 1000000)
            throw new IllegalArgumentException("Invalid player-backed good or quantity");
    }

    // Keep transfers within a range where double-based Vault providers retain cents.
    private static final long MAX_TRANSFER_CENTS = 9_000_000_000_000L;
    private static long product(long price, int amount) { return Math.multiplyExact(price, amount); }

    private boolean allowed(Player player, Item item) {
        Port market = item.getPort();
        return MarketManager.getInstance().getPort(market.getId()) == market
                && (market.isInside(player.getLocation()) || player.hasPermission("nascraft.ports.bypass"));
    }

    public long available(Item item, boolean buy) {
        try (Connection db = DatabaseExecutor.getInstance().getConnection();
             PreparedStatement sql = db.prepareStatement("SELECT COALESCE(SUM(remaining),0) FROM bazaar_orders WHERE market_id=? AND identifier=? AND side=? AND remaining>0")) {
            sql.setString(1, item.getPort().getId()); sql.setString(2, item.getIdentifier());
            sql.setString(3, buy ? "SELL" : "BUY");
            try (ResultSet rows = sql.executeQuery()) { return rows.next() ? rows.getLong(1) : 0; }
        } catch (SQLException ex) { throw new IllegalStateException("Order book unavailable", ex); }
    }

    /** Quote an entire instant trade; returns 0 when liquidity is insufficient. */
    public double quote(Item item, int amount, boolean buy) {
        valid(item, amount);
        try (Connection db = DatabaseExecutor.getInstance().getConnection()) {
            List<Order> orders = matches(db, item, buy, 0);
            long cost = 0; int left = amount;
            for (Order order : orders) {
                int count = Math.min(left, order.remaining);
                cost = Math.addExact(cost, product(order.price, count));
                left -= count;
                if (left == 0) break;
            }
            return left == 0 && cost <= MAX_TRANSFER_CENTS ? money(cost) : 0;
        } catch (SQLException ex) { throw new IllegalStateException("Order book unavailable", ex); }
    }

    private record Order(long id, String owner, long price, int remaining, String data) { }

    private List<Order> matches(Connection db, Item item, boolean buy, long limit) throws SQLException {
        String side = buy ? "SELL" : "BUY";
        String compare = limit > 0 ? (buy ? " AND price_cents<=?" : " AND price_cents>=?") : "";
        String sort = buy ? "ASC" : "DESC";
        try (PreparedStatement sql = db.prepareStatement("SELECT id, owner, price_cents, remaining, item_data FROM bazaar_orders WHERE market_id=? AND identifier=? AND side=? AND remaining>0" + compare + " ORDER BY price_cents " + sort + ", id ASC")) {
            sql.setString(1, item.getPort().getId()); sql.setString(2, item.getIdentifier()); sql.setString(3, side);
            if (limit > 0) sql.setLong(4, limit);
            List<Order> result = new ArrayList<>();
            try (ResultSet rows = sql.executeQuery()) {
                while (rows.next()) {
                    String data = rows.getString(5);
                    try {
                        // Never settle old escrow against a reconfigured ItemStack.
                        if (!decode(data).isSimilar(item.getItemStack())) continue;
                    } catch (IOException | ClassNotFoundException | IllegalArgumentException ex) {
                        throw new SQLException("Invalid escrow item for order " + rows.getLong(1), ex);
                    }
                    result.add(new Order(rows.getLong(1), rows.getString(2), rows.getLong(3), rows.getInt(4), data));
                }
            }
            return result;
        }
    }

    /** Never remove by material alone: remove only the exact configured stack and verify the result. */
    private boolean takeItems(Player player, Item item, int amount) {
        ItemStack stack = item.getItemStack(); stack.setAmount(1);
        if (!player.getInventory().containsAtLeast(stack, amount)) return false;
        stack.setAmount(amount);
        Map<Integer, ItemStack> leftover = player.getInventory().removeItem(stack);
        if (leftover.isEmpty()) return true;
        // A concurrent inventory plugin may have interfered; return what was actually removed.
        int missing = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
        if (missing < amount) InventoryManager.addItemsToInventory(player, item.getItemStack(), amount - missing);
        return false;
    }

    private static void claim(Connection db, String owner, String kind, long amount, String itemData) throws SQLException {
        try (PreparedStatement sql = db.prepareStatement("INSERT INTO bazaar_claims(owner, kind, amount, item_data, status) VALUES(?,?,?,?, 'READY')")) {
            sql.setString(1, owner); sql.setString(2, kind); sql.setLong(3, amount); sql.setString(4, itemData); sql.executeUpdate();
        }
    }

    /** Price in cents, positive. Orders that cross the current best quote must be filled instantly instead. */
    public synchronized long place(Player player, Item item, int amount, long price, boolean buy) {
        valid(item, amount);
        if (price <= 0 || price > 1000000000L || busy) return -1;
        if (!MarketManager.getInstance().getActive() || !allowed(player, item)) return -1;
        busy = true;
        boolean escrowed = false;
        boolean inserting = false;
        try (Connection db = DatabaseExecutor.getInstance().getConnection()) {
            List<Order> opposite = matches(db, item, buy, 0);
            if (!opposite.isEmpty() && (buy ? price >= opposite.get(0).price : price <= opposite.get(0).price)) return -1;
            long total = product(price, amount);
            if (total > MAX_TRANSFER_CENTS) return -1;
            if (buy) escrowed = MoneyManager.getInstance().withdraw(player, item.getCurrency(), money(total));
            else escrowed = takeItems(player, item, amount);
            if (!escrowed) return -1;
            try (PreparedStatement sql = db.prepareStatement("INSERT INTO bazaar_orders(market_id, identifier, owner, side, price_cents, remaining, item_data) VALUES(?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                sql.setString(1, item.getPort().getId()); sql.setString(2, item.getIdentifier());
                sql.setString(3, player.getUniqueId().toString()); sql.setString(4, buy ? "BUY" : "SELL");
                sql.setLong(5, price); sql.setInt(6, amount); sql.setString(7, encode(item.getItemStack()));
                inserting = true;
                sql.executeUpdate();
                try (Statement idQuery = db.createStatement(); ResultSet keys = idQuery.executeQuery("SELECT last_insert_rowid()")) {
                    if (keys.next()) return keys.getLong(1);
                }
                // Insert may have succeeded; do not refund escrow on an uncertain outcome.
                throw new SQLException("Order inserted but ID lookup failed; reconcile escrow before refunding");
            }
        } catch (Exception ex) {
            Nascraft.getInstance().getLogger().severe("Escrow placement failed for " + player.getUniqueId() + ": " + ex);
            if (escrowed && !inserting) {
                if (buy && !MoneyManager.getInstance().deposit(player, item.getCurrency(), money(product(price, amount)))) return -2;
                if (!buy) InventoryManager.addItemsToInventory(player, item.getItemStack(), amount);
            } else if (escrowed) {
                Nascraft.getInstance().getLogger().severe("Order insert outcome uncertain; check database before returning escrow!");
                return -2;
            }
            return -1;
        } finally { busy = false; }
    }

    /** All-or-nothing instant fill. Credits to both parties are claims, never free-floating inventory. */
    public synchronized double fill(Player player, Item item, int amount, boolean buy, boolean inventoryEscrowed) {
        valid(item, amount);
        if (busy || !MarketManager.getInstance().getActive() || !allowed(player, item)) return -1;
        busy = true;
        boolean paid = false;
        boolean mutationStarted = false;
        try (Connection db = DatabaseExecutor.getInstance().getConnection()) {
            List<Order> orders = matches(db, item, buy, 0);
            List<Map.Entry<Order, Integer>> fills = new ArrayList<>();
            int left = amount; long total = 0;
            for (Order order : orders) {
                if (order.owner.equals(player.getUniqueId().toString())) continue; // no self-matching
                int count = Math.min(left, order.remaining);
                fills.add(Map.entry(order, count));
                total = Math.addExact(total, product(order.price, count));
                left -= count;
                if (left == 0) break;
            }
            if (left > 0 || total <= 0 || total > MAX_TRANSFER_CENTS) return -1;
            if (buy) paid = MoneyManager.getInstance().withdraw(player, item.getCurrency(), money(total));
            else paid = inventoryEscrowed || takeItems(player, item, amount);
            if (!paid) return -1;

            // If a DB operation fails, rollback order state. Never issue claims on a partial fill.
            db.setAutoCommit(false);
            try {
                mutationStarted = true;
                for (Map.Entry<Order, Integer> fill : fills) {
                    Order order = fill.getKey(); int count = fill.getValue();
                    try (PreparedStatement update = db.prepareStatement("UPDATE bazaar_orders SET remaining=remaining-? WHERE id=? AND remaining>=?")) {
                        update.setInt(1, count); update.setLong(2, order.id); update.setInt(3, count);
                        if (update.executeUpdate() != 1) throw new SQLException("Order changed while filling");
                    }
                    if (buy) {
                        claim(db, player.getUniqueId().toString(), "ITEM", count, order.data);
                        claim(db, order.owner, "MONEY", product(order.price, count), null);
                    } else {
                        claim(db, order.owner, "ITEM", count, order.data);
                        claim(db, player.getUniqueId().toString(), "MONEY", product(order.price, count), null);
                    }
                    try (PreparedStatement log = db.prepareStatement("INSERT INTO bazaar_fills(order_id, taker, quantity, price_cents) VALUES(?,?,?,?)")) {
                        log.setLong(1, order.id); log.setString(2, player.getUniqueId().toString()); log.setInt(3, count); log.setLong(4, order.price); log.executeUpdate();
                    }
                }
                db.commit();
                return money(total);
            } catch (Exception ex) {
                db.rollback();
                throw ex;
            }
        } catch (Exception ex) {
            Nascraft.getInstance().getLogger().severe("Bazaar fill failed for " + player.getUniqueId() + ": " + ex);
            if (paid && !mutationStarted && !buy && !inventoryEscrowed)
                InventoryManager.addItemsToInventory(player, item.getItemStack(), amount);
            if (paid && (mutationStarted || buy))
                Nascraft.getInstance().getLogger().severe("Check escrow and claims before refunding player " + player.getUniqueId());
            // -2 means the caller must NOT return externally escrowed items: the
            // commit outcome may be uncertain. Reconcile by fill/order/claim IDs.
            return paid && (mutationStarted || buy) ? -2 : -1;
        } finally { busy = false; }
    }

    public synchronized boolean cancel(Player player, long id) {
        if (busy) return false;
        busy = true;
        try (Connection db = DatabaseExecutor.getInstance().getConnection()) {
            db.setAutoCommit(false);
            try (PreparedStatement sql = db.prepareStatement("SELECT side, price_cents, remaining, item_data FROM bazaar_orders WHERE id=? AND owner=? AND remaining>0")) {
                sql.setLong(1, id); sql.setString(2, player.getUniqueId().toString());
                try (ResultSet row = sql.executeQuery()) {
                    if (!row.next()) return false;
                    String side = row.getString(1); long total = product(row.getLong(2), row.getInt(3));
                    claim(db, player.getUniqueId().toString(), side.equals("BUY") ? "MONEY" : "ITEM", side.equals("BUY") ? total : row.getInt(3), row.getString(4));
                    try (PreparedStatement update = db.prepareStatement("UPDATE bazaar_orders SET remaining=0 WHERE id=? AND remaining>0")) {
                        update.setLong(1, id); if (update.executeUpdate() != 1) throw new SQLException("Order already closed");
                    }
                }
                db.commit(); return true;
            } catch (Exception ex) { db.rollback(); throw ex; }
        } catch (Exception ex) { Nascraft.getInstance().getLogger().warning("Cancel failed: " + ex); return false; }
        finally { busy = false; }
    }

    public List<String> orders(Player player) {
        List<String> list = new ArrayList<>();
        try (Connection db = DatabaseExecutor.getInstance().getConnection();
             PreparedStatement sql = db.prepareStatement("SELECT id, market_id, identifier, side, remaining, price_cents FROM bazaar_orders WHERE owner=? AND remaining>0 ORDER BY id")) {
            sql.setString(1, player.getUniqueId().toString());
            try (ResultSet rows = sql.executeQuery()) {
                while (rows.next()) list.add("#" + rows.getLong(1) + " " + rows.getString(2) + "/" + rows.getString(3) + " " + rows.getString(4) + " " + rows.getInt(5) + " @ " + money(rows.getLong(6)));
            }
        } catch (SQLException ex) { Nascraft.getInstance().getLogger().warning("Order lookup failed: " + ex); }
        return list;
    }

    /** Claim is marked before delivering it. Interrupted/uncertain external payouts require manual reconciliation. */
    public synchronized int collect(Player player) {
        if (busy) return 0;
        busy = true; int done = 0;
        try (Connection db = DatabaseExecutor.getInstance().getConnection();
             PreparedStatement sql = db.prepareStatement("SELECT id, kind, amount, item_data FROM bazaar_claims WHERE owner=? AND status='READY' ORDER BY id")) {
            sql.setString(1, player.getUniqueId().toString());
            List<Object[]> claims = new ArrayList<>();
            try (ResultSet rows = sql.executeQuery()) {
                while (rows.next()) claims.add(new Object[]{rows.getLong(1), rows.getString(2), rows.getLong(3), rows.getString(4)});
            }
            for (Object[] row : claims) {
                long id = (Long) row[0], amount = (Long) row[2];
                ItemStack item = null;
                if (row[1].equals("ITEM")) {
                    item = decode((String) row[3]);
                    if (amount > Integer.MAX_VALUE || !InventoryManager.checkInventory(player, true, item, (int) amount)) continue;
                }
                try (PreparedStatement update = db.prepareStatement("UPDATE bazaar_claims SET status='DELIVERING' WHERE id=? AND status='READY'")) {
                    update.setLong(1, id); if (update.executeUpdate() != 1) continue;
                }
                boolean success;
                if (item != null) {
                    InventoryManager.addItemsToInventory(player, item, (int) amount); success = true;
                } else success = MoneyManager.getInstance().deposit(player, null, money(amount));
                if (success) {
                    try (PreparedStatement update = db.prepareStatement("UPDATE bazaar_claims SET status='DONE' WHERE id=? AND status='DELIVERING'")) {
                        update.setLong(1, id); update.executeUpdate();
                    }
                    done++;
                } else Nascraft.getInstance().getLogger().severe("Bazaar claim #" + id + " needs manual reconciliation (DELIVERING)");
            }
        } catch (Exception ex) { Nascraft.getInstance().getLogger().severe("Claim failed; inspect DELIVERING claims: " + ex); }
        finally { busy = false; }
        return done;
    }
}
