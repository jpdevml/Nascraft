package me.bounser.nascraft.database;

import me.bounser.nascraft.database.sqlite.SQLite;
import me.bounser.nascraft.database.mysql.MariaDB;
import me.bounser.nascraft.config.Config;

public class DatabaseManager {

    private final Database database;

    private static DatabaseManager instance;

    public static DatabaseManager get() { return instance == null ? instance = new DatabaseManager() : instance; }

    /** Like get but never constructs/connects: for shutdown paths. */
    public static DatabaseManager getIfPresent() { return instance; }

    public DatabaseManager() {
        String type = Config.getInstance().getDatabaseType();
        database = switch (type) {
            case "sqlite" -> SQLite.getInstance();
            case "mysql", "mariadb" -> new MariaDB();
            default -> throw new IllegalArgumentException("Unsupported database.type: " + type);
        };
        database.connect();
    }

    public Database getDatabase() {
        return database;
    }
}
