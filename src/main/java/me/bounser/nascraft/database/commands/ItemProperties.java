package me.bounser.nascraft.database.commands;

import me.bounser.nascraft.market.unit.Item;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-port item state. Rows are keyed by (port_id, identifier): the same
 * good has independent price/stock state on every port. Child items share
 * state with their parent, so only parent rows are ever persisted.
 */
public class ItemProperties {

    private static Item resolveParent(Item item) {
        return item.getParent() != null ? item.getParent() : item;
    }

    private static final String SAVE = "REPLACE INTO port_items (port_id, identifier, lastprice, lowest, highest, price_stock, stock, taxes, factor, trend, trend_ticks, shock) VALUES (?,?,?,?,?,?,?,?,?,?,?,?);";

    public static void saveItem(Connection connection, Item item) throws SQLException {
        try (PreparedStatement prep = connection.prepareStatement(SAVE)) {
            bind(prep, resolveParent(item));
            prep.executeUpdate();
        }
    }

    /** Same rows as {@link #saveItem} for each item, as one batched statement (the caller owns the transaction). */
    public static void saveItems(Connection connection, List<Item> items) throws SQLException {
        if (items.isEmpty()) return;
        try (PreparedStatement prep = connection.prepareStatement(SAVE)) {
            for (Item item : items) { bind(prep, resolveParent(item)); prep.addBatch(); }
            prep.executeBatch();
        }
    }

    private static void bind(PreparedStatement prep, Item item) throws SQLException {
        prep.setString(1, item.getPort().getId());
        prep.setString(2, item.getIdentifier());
        prep.setDouble(3, item.getPrice().getValue());
        prep.setDouble(4, item.getPrice().getHistoricalLow());
        prep.setDouble(5, item.getPrice().getHistoricalHigh());
        prep.setDouble(6, item.getPrice().getStock());
        prep.setInt(7, item.getStock());
        prep.setDouble(8, item.getCollectedTaxes());
        prep.setDouble(9, item.getPrice().getFactor());
        prep.setDouble(10, item.getPrice().getTrend());
        prep.setInt(11, item.getPrice().getTrendTicks());
        prep.setDouble(12, item.getPrice().getShock());
    }

    /** One stored row, read as plain values so it can be fetched off the main thread and applied on it. */
    public record State(float lowest, float highest, double priceStock, int stock, float taxes,
                        double factor, double trend, int trendTicks, double shock) {
        static State read(ResultSet rs) throws SQLException {
            return new State(rs.getFloat("lowest"), rs.getFloat("highest"), rs.getDouble("price_stock"), rs.getInt("stock"),
                    rs.getFloat("taxes"), rs.getDouble("factor"), rs.getDouble("trend"), rs.getInt("trend_ticks"), rs.getDouble("shock"));
        }
    }

    private static final String COLUMNS = "port_id, identifier, lowest, highest, price_stock, stock, taxes, factor, trend, trend_ticks, shock";

    public static String key(String portId, String identifier) { return portId + '\0' + identifier; }

    /** Applies a row exactly as {@link #retrieveItem} does. */
    public static void apply(Item item, State st) {
        item = resolveParent(item);
        // One recompute from the stored factor and stock level (no intermediate price reaches the high/low).
        item.getPrice().setMarketStateAndStock(st.factor(), st.trend(), st.trendTicks(), st.shock(), (float) st.priceStock());
        item.getPrice().setHistoricalHigh(st.highest());
        item.getPrice().setHistoricalLow(st.lowest());
        item.setStock(st.stock());
        item.setCollectedTaxes(st.taxes());
    }

    public static void retrieveItem(Connection connection, Item item) throws SQLException {

        item = resolveParent(item);

        String sql = "SELECT " + COLUMNS + " FROM port_items WHERE port_id=? AND identifier=?;";

        try (PreparedStatement prep = connection.prepareStatement(sql)) {
            prep.setString(1, item.getPort().getId());
            prep.setString(2, item.getIdentifier());
            try (ResultSet rs = prep.executeQuery()) {
                if (rs.next()) apply(item, State.read(rs));
            }
            // No row: keep the defaults coming from config.
        }
    }

    /**
     * Every stored row in one query, keyed by {@link #key}: all markets ({@code portId == null}) or one market,
     * optionally locking the market's rows ({@code FOR UPDATE}, in primary-key order) inside the caller's transaction.
     */
    public static Map<String, State> retrieveAll(Connection connection, String portId, boolean forUpdate) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM port_items" + (portId == null ? "" : " WHERE port_id=?")
                + " ORDER BY port_id, identifier" + (forUpdate ? " FOR UPDATE" : "");
        Map<String, State> states = new HashMap<>();
        try (PreparedStatement prep = connection.prepareStatement(sql)) {
            if (portId != null) prep.setString(1, portId);
            try (ResultSet rs = prep.executeQuery()) {
                while (rs.next()) states.put(key(rs.getString("port_id"), rs.getString("identifier")), State.read(rs));
            }
        }
        return states;
    }

    public static float retrieveLastPrice(Connection connection, Item item) throws SQLException {

        item = resolveParent(item);

        String sql = "SELECT lastprice FROM port_items WHERE port_id=? AND identifier=?;";

        try (PreparedStatement prep = connection.prepareStatement(sql)) {
            prep.setString(1, item.getPort().getId());
            prep.setString(2, item.getIdentifier());
            ResultSet rs = prep.executeQuery();

            if (rs.next()) return rs.getFloat("lastprice");

            return (float) item.getPrice().getValue();
        }
    }

}
