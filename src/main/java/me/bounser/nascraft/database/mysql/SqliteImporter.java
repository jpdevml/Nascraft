package me.bounser.nascraft.database.mysql;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/** Offline one-time import; stop every Nascraft server and back up sqlite.db first. */
public final class SqliteImporter {
    private SqliteImporter() { }

    private static final Map<String, String> TABLES = new LinkedHashMap<>();
    static {
        TABLES.put("port_items", "port_id,identifier,lastprice,lowest,highest,price_stock,stock,taxes");
        for (String table : new String[]{"prices_day", "prices_month", "prices_history"})
            TABLES.put(table, "id,port_id,day,date,identifier,price,volume");
        TABLES.put("trade_log", "id,port_id,uuid,day,date,identifier,amount,value,buy,discord");
        TABLES.put("bazaar_orders", "id,market_id,identifier,owner,side,price_cents,remaining,item_data");
        TABLES.put("bazaar_claims", "id,owner,kind,amount,item_data,status");
        TABLES.put("bazaar_fills", "id,order_id,taker,quantity,price_cents");
        TABLES.put("discord_links", "userid,uuid,nickname");
        TABLES.put("user_names", "uuid,name");
    }

    public static void importDatabase(Connection sqlite, Connection maria) throws SQLException {
        MariaDB.initializeSchema(maria);
        // Refuse a partial/second import; do not overwrite orders or live market state.
        try (Statement check = maria.createStatement()) {
            for (String table : TABLES.keySet()) {
                try (ResultSet rows = check.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    rows.next();
                    if (rows.getLong(1) != 0) throw new SQLException("Target is not empty: " + table);
                }
            }
        }
        maria.setAutoCommit(false);
        try {
            DatabaseMetaData metadata = sqlite.getMetaData();
            for (Map.Entry<String, String> table : TABLES.entrySet()) {
                try (ResultSet found = metadata.getTables(null, null, table.getKey(), new String[]{"TABLE"})) {
                    if (!found.next()) continue; // installations predating player orders
                }
                String columns = table.getValue();
                int size = columns.split(",").length;
                String placeholders = String.join(",", java.util.Collections.nCopies(size, "?"));
                long count = 0;
                try (Statement read = sqlite.createStatement();
                     ResultSet rows = read.executeQuery("SELECT " + columns + " FROM " + table.getKey());
                     PreparedStatement insert = maria.prepareStatement("INSERT INTO " + table.getKey()
                             + " (" + columns + ") VALUES(" + placeholders + ")")) {
                    while (rows.next()) {
                        for (int i = 1; i <= size; i++) insert.setObject(i, rows.getObject(i));
                        insert.addBatch();
                        count++;
                        if (count % 500 == 0) insert.executeBatch();
                    }
                    insert.executeBatch();
                }
                try (Statement verify = maria.createStatement();
                     ResultSet rows = verify.executeQuery("SELECT COUNT(*) FROM " + table.getKey())) {
                    rows.next();
                    if (rows.getLong(1) != count) throw new SQLException("Row count mismatch: " + table.getKey());
                }
                System.out.println(table.getKey() + ": " + count + " rows");
            }
            maria.commit();
        } catch (Exception ex) {
            maria.rollback();
            if (ex instanceof SQLException sql) throw sql;
            throw new SQLException("Import failed", ex);
        } finally { maria.setAutoCommit(true); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: java -cp Nascraft.jar me.bounser.nascraft.database.mysql.SqliteImporter <sqlite.db> <jdbc:mariadb://host:3306/empty_database> <user>\nPassword: NASCRAFT_MIGRATION_PASSWORD environment variable. Stop all Nascraft servers and back up SQLite first.");
            System.exit(2);
        }
        String password = System.getenv("NASCRAFT_MIGRATION_PASSWORD");
        if (password == null) throw new IllegalArgumentException("Set NASCRAFT_MIGRATION_PASSWORD");
        java.io.File source = new java.io.File(args[0]);
        if (!source.isFile()) throw new IllegalArgumentException("SQLite file not found: " + source);
        if (!args[1].startsWith("jdbc:mariadb://")) throw new IllegalArgumentException("Expected a MariaDB JDBC URL");
        try (Connection sqlite = DriverManager.getConnection("jdbc:sqlite:file:" + source.getAbsolutePath() + "?mode=ro");
             Connection maria = DriverManager.getConnection(args[1], args[2], password)) {
            importDatabase(sqlite, maria);
        }
        System.out.println("Import complete; verify claims/orders before starting the network.");
    }
}
