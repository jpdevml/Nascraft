package me.bounser.nascraft.database.commands;

import me.bounser.nascraft.market.unit.Item;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Per-port item state. Rows are keyed by (port_id, identifier): the same
 * good has independent price/stock state on every port. Child items share
 * state with their parent, so only parent rows are ever persisted.
 */
public class ItemProperties {

    private static Item resolveParent(Item item) {
        return item.getParent() != null ? item.getParent() : item;
    }

    public static void saveItem(Connection connection, Item item) throws SQLException {

        item = resolveParent(item);

        String sql = "REPLACE INTO port_items (port_id, identifier, lastprice, lowest, highest, price_stock, stock, taxes) VALUES (?,?,?,?,?,?,?,?);";

        try (PreparedStatement prep = connection.prepareStatement(sql)) {
            prep.setString(1, item.getPort().getId());
            prep.setString(2, item.getIdentifier());
            prep.setDouble(3, item.getPrice().getValue());
            prep.setDouble(4, item.getPrice().getHistoricalLow());
            prep.setDouble(5, item.getPrice().getHistoricalHigh());
            prep.setDouble(6, item.getPrice().getStock());
            prep.setInt(7, item.getStock());
            prep.setDouble(8, item.getCollectedTaxes());
            prep.executeUpdate();
        }
    }

    public static void retrieveItem(Connection connection, Item item) throws SQLException {

        item = resolveParent(item);

        String sql = "SELECT lowest, highest, price_stock, stock, taxes FROM port_items WHERE port_id=? AND identifier=?;";

        try (PreparedStatement prep = connection.prepareStatement(sql)) {
            prep.setString(1, item.getPort().getId());
            prep.setString(2, item.getIdentifier());
            ResultSet rs = prep.executeQuery();

            if (rs.next()) {
                // setStock recomputes the price from the stored stock level.
                item.getPrice().setStock((float) rs.getDouble("price_stock"));
                item.getPrice().setHistoricalHigh(rs.getFloat("highest"));
                item.getPrice().setHistoricalLow(rs.getFloat("lowest"));
                item.setStock(rs.getInt("stock"));
                item.setCollectedTaxes(rs.getFloat("taxes"));
            }
            // No row: keep the defaults coming from config.
        }
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
