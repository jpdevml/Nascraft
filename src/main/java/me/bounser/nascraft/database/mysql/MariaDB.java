package me.bounser.nascraft.database.mysql;

import me.bounser.nascraft.database.DatabaseExecutor;
import me.bounser.nascraft.database.commands.ItemProperties;
import me.bounser.nascraft.database.sqlite.SQLite;
import me.bounser.nascraft.market.Port;
import me.bounser.nascraft.market.MarketManager;
import me.bounser.nascraft.market.unit.Item;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** Shared MariaDB storage. SQLite's JDBC operations are reused, but never its schema or snapshot writes. */
public final class MariaDB extends SQLite {
    @Override public void connect() {
        createTables();
        connected = true;
    }

    @Override public void createTables() {
        try (Connection db = DatabaseExecutor.getInstance().getConnection()) {
            initializeSchema(db);
        } catch (SQLException ex) { throw new IllegalStateException("MariaDB schema initialization failed", ex); }
    }

    /** Exposed for integration tests against a real MariaDB instance. */
    public static void initializeSchema(Connection db) throws SQLException {
        try (Statement sql = db.createStatement()) {
            sql.execute("CREATE TABLE IF NOT EXISTS nascraft_schema (version INT NOT NULL PRIMARY KEY)");
            try (ResultSet rows = sql.executeQuery("SELECT version FROM nascraft_schema LIMIT 1")) {
                if (rows.next() && rows.getInt(1) != 1) throw new SQLException("Unsupported Nascraft schema version");
            }
            sql.execute("INSERT IGNORE INTO nascraft_schema(version) VALUES(1)");
            sql.execute("CREATE TABLE IF NOT EXISTS port_items (port_id VARCHAR(128) NOT NULL, identifier VARCHAR(128) NOT NULL, lastprice DOUBLE, lowest DOUBLE, highest DOUBLE, price_stock DOUBLE, stock INT, taxes DOUBLE, PRIMARY KEY(port_id,identifier))");
            for (String table : new String[]{"prices_day", "prices_month", "prices_history"})
                sql.execute("CREATE TABLE IF NOT EXISTS " + table + " (id BIGINT AUTO_INCREMENT PRIMARY KEY, port_id VARCHAR(128), day INT, date VARCHAR(64), identifier VARCHAR(128), price DOUBLE, volume INT, INDEX port_history(port_id,identifier,id), INDEX history_day(day))");
            sql.execute("CREATE TABLE IF NOT EXISTS trade_log (id BIGINT AUTO_INCREMENT PRIMARY KEY, port_id VARCHAR(128), uuid VARCHAR(36), day INT, date VARCHAR(64), identifier VARCHAR(128), amount INT, value VARCHAR(64), buy BOOLEAN, discord BOOLEAN, INDEX player_log(uuid,id), INDEX log_day(day))");
            sql.execute("CREATE TABLE IF NOT EXISTS bazaar_orders (id BIGINT AUTO_INCREMENT PRIMARY KEY, market_id VARCHAR(128) NOT NULL, identifier VARCHAR(128) NOT NULL, owner VARCHAR(36) NOT NULL, side VARCHAR(4) NOT NULL, price_cents BIGINT NOT NULL, remaining INT NOT NULL CHECK(remaining >= 0), item_data LONGTEXT NOT NULL, INDEX bazaar_match(market_id,identifier,side,price_cents,id), INDEX bazaar_owner(owner,remaining))");
            sql.execute("CREATE TABLE IF NOT EXISTS bazaar_claims (id BIGINT AUTO_INCREMENT PRIMARY KEY, owner VARCHAR(36) NOT NULL, kind VARCHAR(8) NOT NULL, amount BIGINT NOT NULL CHECK(amount > 0), item_data LONGTEXT, status VARCHAR(16) NOT NULL DEFAULT 'READY', INDEX bazaar_claim_owner(owner,status))");
            sql.execute("CREATE TABLE IF NOT EXISTS bazaar_fills (id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL, taker VARCHAR(36) NOT NULL, quantity INT NOT NULL, price_cents BIGINT NOT NULL)");
            sql.execute("CREATE TABLE IF NOT EXISTS discord_links (userid VARCHAR(20) PRIMARY KEY, uuid VARCHAR(36), nickname TEXT, INDEX link_uuid(uuid))");
            sql.execute("CREATE TABLE IF NOT EXISTS user_names (uuid VARCHAR(36) PRIMARY KEY, name TEXT)");
            sql.execute("CREATE TABLE IF NOT EXISTS market_schedule (task_id VARCHAR(160) PRIMARY KEY, next_run BIGINT NOT NULL)");
            sql.execute("CREATE TABLE IF NOT EXISTS market_mutex (market_id VARCHAR(128) NOT NULL, identifier VARCHAR(128) NOT NULL, PRIMARY KEY(market_id,identifier))");
            sql.execute("CREATE TABLE IF NOT EXISTS market_control (setting VARCHAR(64) PRIMARY KEY, value INT NOT NULL)");
            sql.execute("INSERT IGNORE INTO market_control(setting,value) VALUES('paused',0)");
        }
    }

    /** Propagates admin/settlement halts to every node. */
    public void setPaused(boolean paused) {
        try (Connection db = DatabaseExecutor.getInstance().getConnection();
             PreparedStatement sql = db.prepareStatement("UPDATE market_control SET value=? WHERE setting='paused'")) {
            sql.setInt(1, paused ? 1 : 0);
            if (sql.executeUpdate() != 1) throw new SQLException("Missing shared pause setting");
        } catch (SQLException ex) { throw new IllegalStateException("Cannot update shared trading status", ex); }
    }

    public boolean isPaused() {
        try (Connection db = DatabaseExecutor.getInstance().getConnection();
             Statement sql = db.createStatement();
             ResultSet row = sql.executeQuery("SELECT value FROM market_control WHERE setting='paused'")) {
            if (!row.next()) throw new SQLException("Missing shared pause setting");
            return row.getInt(1) != 0;
        } catch (SQLException ex) { throw new IllegalStateException("Cannot read shared trading status", ex); }
    }

    /** Never flush a local snapshot over updates performed on other servers. */
    @Override public void saveEverything() { }
    @Override public void saveItem(Item item) { }

    @Override public void retrieveItem(Item item) {
        if (item.isPlayerOnly()) return;
        try (Connection db = DatabaseExecutor.getInstance().getConnection()) {
            // First boot: exactly one server creates the initial stock row.
            try (PreparedStatement insert = db.prepareStatement("INSERT IGNORE INTO port_items(port_id,identifier,lastprice,lowest,highest,price_stock,stock,taxes) VALUES(?,?,?,?,?,?,?,?)")) {
                bindItem(insert, item); insert.executeUpdate();
            }
            ItemProperties.retrieveItem(db, item);
        } catch (SQLException ex) { throw new IllegalStateException("Could not load shared market state", ex); }
    }

    private static void bindItem(PreparedStatement sql, Item item) throws SQLException {
        sql.setString(1, item.getPort().getId()); sql.setString(2, item.getIdentifier());
        sql.setDouble(3, item.getPrice().getValue()); sql.setDouble(4, item.getPrice().getHistoricalLow());
        sql.setDouble(5, item.getPrice().getHistoricalHigh()); sql.setDouble(6, item.getPrice().getStock());
        sql.setInt(7, item.getStock()); sql.setDouble(8, item.getCollectedTaxes());
    }

    /** Lock the authoritative good, calculate on its latest state, and commit the resulting state. */
    public double trade(Item item, Callable<Double> action) {
        Item parent = item.getParent() == null ? item : item.getParent();
        try (Connection db = DatabaseExecutor.getInstance().getConnection()) {
            db.setAutoCommit(false);
            try {
                lockItem(db, parent);
                double result = action.call();
                if (result > 0) ItemProperties.saveItem(db, parent);
                db.commit();
                return result;
            } catch (Exception ex) {
                db.rollback();
                throw ex;
            }
        } catch (Exception ex) {
            // A failed commit following a Vault/inventory transfer has an unknown outcome.
            MarketManager.getInstance().stop();
            me.bounser.nascraft.Nascraft.getInstance().getLogger().severe("Shared trade outcome uncertain for " + parent.getPort().getId() + "/" + parent.getIdentifier() + "; trading stopped. Reconcile inventory, Vault and DB: " + ex);
            return -2;
        }
    }

    private void lockItem(Connection db, Item item) throws SQLException {
        try (PreparedStatement lock = db.prepareStatement("SELECT stock FROM port_items WHERE port_id=? AND identifier=? FOR UPDATE")) {
            lock.setString(1, item.getPort().getId()); lock.setString(2, item.getIdentifier());
            try (ResultSet rows = lock.executeQuery()) {
                if (!rows.next()) throw new SQLException("Market item row missing");
            }
        }
        ItemProperties.retrieveItem(db, item);
    }

    /** Single winner per scheduled tick throughout the network (database clock). */
    public boolean due(String task, long periodSeconds) {
        try (Connection db = DatabaseExecutor.getInstance().getConnection()) {
            db.setAutoCommit(false);
            try {
                try (PreparedStatement insert = db.prepareStatement("INSERT IGNORE INTO market_schedule(task_id,next_run) VALUES(?, UNIX_TIMESTAMP())")) {
                    insert.setString(1, task); insert.executeUpdate();
                }
                boolean run;
                try (PreparedStatement lock = db.prepareStatement("SELECT next_run, UNIX_TIMESTAMP() FROM market_schedule WHERE task_id=? FOR UPDATE")) {
                    lock.setString(1, task);
                    try (ResultSet row = lock.executeQuery()) { row.next(); run = row.getLong(1) <= row.getLong(2); }
                }
                if (run) try (PreparedStatement update = db.prepareStatement("UPDATE market_schedule SET next_run=UNIX_TIMESTAMP()+? WHERE task_id=?")) {
                    update.setLong(1, periodSeconds); update.setString(2, task); update.executeUpdate();
                }
                db.commit(); return run;
            } catch (SQLException ex) { db.rollback(); throw ex; }
        } catch (SQLException ex) {
            MarketManager.getInstance().stop();
            throw new IllegalStateException("Shared scheduler unavailable", ex);
        }
    }

    public void refresh(Item item) {
        try (Connection db = DatabaseExecutor.getInstance().getConnection()) {
            ItemProperties.retrieveItem(db, item);
        } catch (SQLException ex) {
            MarketManager.getInstance().stopLocally();
            throw new IllegalStateException("Shared state refresh failed", ex);
        }
    }

    public void noise(Item item) {
        trade(item, () -> {
            item.getPrice().applyNoise();
            return 1.0;
        });
    }

    /** All goods at a port restock as one transaction. A failed tick is never partially applied. */
    public void restock(Port port) {
        List<Item> goods = new ArrayList<>(port.getParentItems());
        goods.removeIf(Item::isPlayerOnly);
        goods.sort(java.util.Comparator.comparing(Item::getIdentifier));
        try (Connection db = DatabaseExecutor.getInstance().getConnection()) {
            db.setAutoCommit(false);
            try {
                for (Item item : goods) {
                    lockItem(db, item);
                    item.addStock(item.getRestockAmount());
                    ItemProperties.saveItem(db, item);
                }
                db.commit();
            } catch (Exception ex) { db.rollback(); throw ex; }
        } catch (Exception ex) {
            MarketManager.getInstance().stop();
            throw new IllegalStateException("Restock failed; trading stopped", ex);
        }
    }
}
