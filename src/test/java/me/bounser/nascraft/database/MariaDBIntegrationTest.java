package me.bounser.nascraft.database;

import me.bounser.nascraft.database.mysql.MariaDB;
import me.bounser.nascraft.database.mysql.SqliteImporter;
import org.junit.Test;
import java.sql.*;
import java.util.UUID;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Run with NASCRAFT_TEST_MARIADB_URL=jdbc:mariadb://localhost:3306/nascraft_test. */
public class MariaDBIntegrationTest {
    @Test public void offlineImportAndRowCounts() throws Exception {
        String url = System.getenv("NASCRAFT_TEST_MARIADB_URL");
        assumeTrue("Set a dedicated empty test database URL", url != null && !url.isBlank());
        String user = System.getenv("NASCRAFT_TEST_MARIADB_USER");
        String password = System.getenv("NASCRAFT_TEST_MARIADB_PASSWORD");
        try (Connection sqlite = DriverManager.getConnection("jdbc:sqlite::memory:");
             Connection maria = DriverManager.getConnection(url, user == null ? "root" : user, password == null ? "" : password)) {
            String id = UUID.randomUUID().toString();
            try (Statement sql = sqlite.createStatement()) {
                sql.execute("CREATE TABLE port_items(port_id TEXT,identifier TEXT,lastprice DOUBLE,lowest DOUBLE,highest DOUBLE,price_stock DOUBLE,stock INTEGER,taxes DOUBLE)");
                sql.execute("INSERT INTO port_items VALUES('" + id + "','iron',3,1,4,10,42,0)");
            }
            SqliteImporter.importDatabase(sqlite, maria);
            try (PreparedStatement sql = maria.prepareStatement("SELECT stock FROM port_items WHERE port_id=?")) {
                sql.setString(1, id);
                try (ResultSet rows = sql.executeQuery()) { assertTrue(rows.next()); assertEquals(42, rows.getInt(1)); }
            }
            try (PreparedStatement sql = maria.prepareStatement("DELETE FROM port_items WHERE port_id=?")) {
                sql.setString(1, id); sql.executeUpdate();
            }
        }
    }

    @Test public void bookMutexSerializesTwoConnections() throws Exception {
        String url = System.getenv("NASCRAFT_TEST_MARIADB_URL");
        assumeTrue(url != null && !url.isBlank());
        String user = System.getenv("NASCRAFT_TEST_MARIADB_USER");
        String password = System.getenv("NASCRAFT_TEST_MARIADB_PASSWORD");
        String id = UUID.randomUUID().toString();
        try (Connection first = DriverManager.getConnection(url, user, password);
             Connection second = DriverManager.getConnection(url, user, password)) {
            MariaDB.initializeSchema(first);
            try (PreparedStatement sql = first.prepareStatement("INSERT INTO market_mutex(market_id,identifier) VALUES(?,?)")) {
                sql.setString(1, id); sql.setString(2, "iron"); sql.executeUpdate();
            }
            first.setAutoCommit(false); second.setAutoCommit(false);
            try (PreparedStatement sql = first.prepareStatement("SELECT identifier FROM market_mutex WHERE market_id=? AND identifier=? FOR UPDATE")) {
                sql.setString(1, id); sql.setString(2, "iron"); sql.executeQuery().close();
            }
            java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.Future<Boolean> blocked = workers.submit(() -> {
                    try (PreparedStatement sql = second.prepareStatement("SELECT identifier FROM market_mutex WHERE market_id=? AND identifier=? FOR UPDATE")) {
                        sql.setString(1, id); sql.setString(2, "iron");
                        try (ResultSet row = sql.executeQuery()) { return row.next(); }
                    }
                });
                Thread.sleep(150);
                assertFalse("A second server must wait for the first", blocked.isDone());
                first.commit();
                assertTrue(blocked.get(3, java.util.concurrent.TimeUnit.SECONDS));
                second.commit();
            } finally {
                first.rollback(); second.rollback();
                workers.shutdownNow();
            }
            try (PreparedStatement sql = first.prepareStatement("DELETE FROM market_mutex WHERE market_id=?")) {
                sql.setString(1, id); sql.executeUpdate();
            }
        }
    }

    @Test public void schemaAndSharedJdbcStatements() throws Exception {
        String url = System.getenv("NASCRAFT_TEST_MARIADB_URL");
        assumeTrue("Set a dedicated test database URL", url != null && !url.isBlank());
        String user = System.getenv("NASCRAFT_TEST_MARIADB_USER");
        String password = System.getenv("NASCRAFT_TEST_MARIADB_PASSWORD");
        try (Connection db = DriverManager.getConnection(url, user == null ? "root" : user, password == null ? "" : password)) {
            MariaDB.initializeSchema(db);
            MariaDB.initializeSchema(db);
            String id = UUID.randomUUID().toString();
            try (PreparedStatement insert = db.prepareStatement("REPLACE INTO port_items(port_id,identifier,lastprice,lowest,highest,price_stock,stock,taxes) VALUES(?,?,?,?,?,?,?,?)")) {
                insert.setString(1, id); insert.setString(2, "iron");
                insert.setDouble(3, 1); insert.setDouble(4, 1); insert.setDouble(5, 1);
                insert.setDouble(6, 100); insert.setInt(7, 10); insert.setDouble(8, 0);
                insert.executeUpdate(); insert.setInt(7, 20); insert.executeUpdate();
            }
            try (PreparedStatement read = db.prepareStatement("SELECT stock FROM port_items WHERE port_id=? AND identifier=? FOR UPDATE")) {
                read.setString(1, id); read.setString(2, "iron");
                try (ResultSet rows = read.executeQuery()) { assertTrue(rows.next()); assertEquals(20, rows.getInt(1)); }
            }
            try (PreparedStatement insert = db.prepareStatement("INSERT INTO bazaar_orders(market_id,identifier,owner,side,price_cents,remaining,item_data) VALUES(?,?,?,?,?,?,?)")) {
                insert.setString(1, id); insert.setString(2, "iron"); insert.setString(3, id);
                insert.setString(4, "SELL"); insert.setLong(5, 200); insert.setInt(6, 1); insert.setString(7, "test");
                insert.executeUpdate();
            }
            try (Statement sql = db.createStatement(); ResultSet rows = sql.executeQuery("SELECT LAST_INSERT_ID()")) {
                assertTrue(rows.next()); assertTrue(rows.getLong(1) > 0);
            }
            try (PreparedStatement sql = db.prepareStatement("DELETE FROM bazaar_orders WHERE market_id=?")) {
                sql.setString(1, id); sql.executeUpdate();
            }
            try (PreparedStatement sql = db.prepareStatement("DELETE FROM port_items WHERE port_id=?")) {
                sql.setString(1, id); sql.executeUpdate();
            }
        }
    }
}
