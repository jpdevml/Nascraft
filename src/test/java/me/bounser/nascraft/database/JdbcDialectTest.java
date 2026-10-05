package me.bounser.nascraft.database;

import org.junit.Test;
import java.sql.*;
import static org.junit.Assert.*;

/** Exercise the SQL shared by both JDBC dialects without requiring Bukkit. */
public class JdbcDialectTest {
    @Test public void sqliteUpsertAndGeneratedKeys() throws Exception {
        try (Connection db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement ddl = db.createStatement()) {
                ddl.execute("CREATE TABLE port_items(port_id TEXT, identifier TEXT, stock INTEGER, PRIMARY KEY(port_id,identifier))");
                ddl.execute("CREATE TABLE bazaar_orders(id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT)");
            }
            try (PreparedStatement replace = db.prepareStatement("REPLACE INTO port_items(port_id,identifier,stock) VALUES(?,?,?)")) {
                replace.setString(1, "port"); replace.setString(2, "iron");
                replace.setInt(3, 10); replace.executeUpdate();
                replace.setInt(3, 20); replace.executeUpdate();
            }
            try (Statement sql = db.createStatement(); ResultSet rs = sql.executeQuery("SELECT stock FROM port_items")) {
                assertTrue(rs.next()); assertEquals(20, rs.getInt(1)); assertFalse(rs.next());
            }
            try (PreparedStatement insert = db.prepareStatement("INSERT INTO bazaar_orders(owner) VALUES(?)")) {
                insert.setString(1, "player"); insert.executeUpdate();
            }
            try (Statement sql = db.createStatement(); ResultSet keys = sql.executeQuery("SELECT last_insert_rowid()")) {
                assertTrue(keys.next()); assertEquals(1L, keys.getLong(1));
            }
        }
    }
}
