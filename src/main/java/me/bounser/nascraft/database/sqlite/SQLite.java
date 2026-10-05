package me.bounser.nascraft.database.sqlite;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.database.Database;
import me.bounser.nascraft.database.DatabaseExecutor;
import me.bounser.nascraft.database.commands.DiscordLink;
import me.bounser.nascraft.database.commands.HistorialData;
import me.bounser.nascraft.database.commands.ItemProperties;
import me.bounser.nascraft.database.commands.TradesLog;
import me.bounser.nascraft.database.commands.UserNames;
import me.bounser.nascraft.database.commands.resources.Trade;
import me.bounser.nascraft.market.MarketManager;
import me.bounser.nascraft.market.Port;
import me.bounser.nascraft.market.unit.Item;
import me.bounser.nascraft.market.unit.stats.Instant;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * SQLite persistence. All item state is scoped per port: rows are keyed
 * by (port_id, identifier). Connections come from the shared HikariCP
 * pool in {@link DatabaseExecutor}; writes are dispatched asynchronously
 * to its single DB thread, reads run synchronously on the caller thread.
 */
public class SQLite implements Database {

    private static SQLite instance;

    private boolean connected = false;

    public static SQLite getInstance() { return instance == null ? instance = new SQLite() : instance; }

    @Override
    public void connect() {

        File dataDir = new File(Nascraft.getInstance().getDataFolder(), "data");
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            Nascraft.getInstance().getLogger().warning("Failed to create the data directory for the database.");
        }

        createTables();

        connected = true;
    }

    @Override
    public void disconnect() {
        // The pool and the executor are owned by DatabaseExecutor and are
        // shut down elsewhere. Nothing to do here besides the flag.
        connected = false;
    }

    @Override
    public boolean isConnected() { return connected; }

    @Override
    public void createTables() {

        try (Connection connection = DatabaseExecutor.getInstance().getConnection()) {

            createTable(connection, "port_items",
                    "port_id TEXT NOT NULL, " +
                            "identifier TEXT NOT NULL, " +
                            "lastprice DOUBLE, " +
                            "lowest DOUBLE, " +
                            "highest DOUBLE, " +
                            "price_stock DOUBLE, " +
                            "stock INTEGER, " +
                            "taxes DOUBLE, " +
                            "PRIMARY KEY(port_id, identifier)");

            createTable(connection, "prices_day",
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "port_id TEXT, " +
                            "day INTEGER, " +
                            "date TEXT, " +
                            "identifier TEXT, " +
                            "price DOUBLE, " +
                            "volume INTEGER");

            createTable(connection, "prices_month",
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "port_id TEXT, " +
                            "day INTEGER, " +
                            "date TEXT, " +
                            "identifier TEXT, " +
                            "price DOUBLE, " +
                            "volume INTEGER");

            createTable(connection, "prices_history",
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "port_id TEXT, " +
                            "day INTEGER, " +
                            "date TEXT, " +
                            "identifier TEXT, " +
                            "price DOUBLE, " +
                            "volume INTEGER");

            createTable(connection, "trade_log",
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "port_id TEXT, " +
                            "uuid VARCHAR(36), " +
                            "day INTEGER, " +
                            "date TEXT, " +
                            "identifier TEXT, " +
                            "amount INTEGER, " +
                            "value TEXT, " +
                            "buy INTEGER, " +
                            "discord INTEGER");

            // Durable, player-backed order book. Never use port_items as an inventory ledger.
            createTable(connection, "bazaar_orders",
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, market_id TEXT NOT NULL, identifier TEXT NOT NULL, " +
                            "owner TEXT NOT NULL, side TEXT NOT NULL, price_cents INTEGER NOT NULL, " +
                            "remaining INTEGER NOT NULL CHECK(remaining >= 0), item_data TEXT NOT NULL");
            createTable(connection, "bazaar_claims",
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT NOT NULL, kind TEXT NOT NULL, " +
                            "amount INTEGER NOT NULL CHECK(amount > 0), item_data TEXT, status TEXT NOT NULL DEFAULT 'READY'");
            createTable(connection, "bazaar_fills",
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER NOT NULL, taker TEXT NOT NULL, " +
                            "quantity INTEGER NOT NULL, price_cents INTEGER NOT NULL");
            try (Statement index = connection.createStatement()) {
                index.execute("CREATE INDEX IF NOT EXISTS bazaar_match ON bazaar_orders(market_id, identifier, side, price_cents, id)");
                index.execute("CREATE INDEX IF NOT EXISTS bazaar_owner ON bazaar_orders(owner, remaining)");
                index.execute("CREATE INDEX IF NOT EXISTS bazaar_claim_owner ON bazaar_claims(owner, status)");
            }

            createTable(connection, "discord_links",
                    "userid VARCHAR(20) PRIMARY KEY, " +
                            "uuid VARCHAR(36), " +
                            "nickname TEXT");

            createTable(connection, "user_names",
                    "uuid VARCHAR(36) PRIMARY KEY, " +
                            "name TEXT");

        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning("Failed to create tables: " + e.getMessage());
        }
    }

    private void createTable(Connection connection, String tableName, String columns) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + tableName + " (" + columns + ");");
        }
    }

    // Per-port item state

    @Override
    public void saveEverything() {

        MarketManager marketManager = MarketManager.getInstanceIfPresent();

        if (marketManager == null) return;

        // Snapshot on the calling thread: the live ports map may be cleared
        // and rebuilt (reload) before the DB thread runs this task.
        List<Item> snapshot = new java.util.ArrayList<>();
        for (Port port : marketManager.getPorts())
            for (Item item : port.getParentItems()) if (!item.isPlayerOnly()) snapshot.add(item);
        if (marketManager.getGlobalMarket() != null)
            for (Item item : marketManager.getGlobalMarket().getParentItems())
                if (!item.isPlayerOnly()) snapshot.add(item);

        DatabaseExecutor.getInstance().executeWithRetry(connection -> {
            try {
                for (Item item : snapshot)
                    ItemProperties.saveItem(connection, item);
            } catch (SQLException e) {
                Nascraft.getInstance().getLogger().warning("Failed to save items: " + e.getMessage());
            }
        });
    }

    @Override
    public void saveItem(Item item) {
        DatabaseExecutor.getInstance().executeWithRetry(connection -> {
            try {
                ItemProperties.saveItem(connection, item);
            } catch (SQLException e) {
                Nascraft.getInstance().getLogger().warning("Failed to save item " + item.getIdentifier() + ": " + e.getMessage());
            }
        });
    }

    @Override
    public void retrieveItem(Item item) {
        try (Connection connection = DatabaseExecutor.getInstance().getConnection()) {
            ItemProperties.retrieveItem(connection, item);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public float retrieveLastPrice(Item item) {
        try (Connection connection = DatabaseExecutor.getInstance().getConnection()) {
            return ItemProperties.retrieveLastPrice(connection, item);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return (float) item.getPrice().getValue();
        }
    }

    // Price history

    @Override
    public void saveDayPrice(Item item, Instant instant) {
        DatabaseExecutor.getInstance().executeWithRetry(connection -> {
            try {
                HistorialData.saveDayPrice(connection, item, instant);
            } catch (SQLException e) {
                Nascraft.getInstance().getLogger().warning(e.getMessage());
            }
        });
    }

    @Override
    public void saveMonthPrice(Item item, Instant instant) {
        DatabaseExecutor.getInstance().executeWithRetry(connection -> {
            try {
                HistorialData.saveMonthPrice(connection, item, instant);
            } catch (SQLException e) {
                Nascraft.getInstance().getLogger().warning(e.getMessage());
            }
        });
    }

    @Override
    public void saveHistoryPrices(Item item, Instant instant) {
        DatabaseExecutor.getInstance().executeWithRetry(connection -> {
            try {
                HistorialData.saveHistoryPrices(connection, item, instant);
            } catch (SQLException e) {
                Nascraft.getInstance().getLogger().warning(e.getMessage());
            }
        });
    }

    @Override
    public List<Instant> getDayPrices(Item item) {
        try (Connection connection = DatabaseExecutor.getInstance().getConnection()) {
            return HistorialData.getDayPrices(connection, item);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<Instant> getMonthPrices(Item item) {
        try (Connection connection = DatabaseExecutor.getInstance().getConnection()) {
            return HistorialData.getMonthPrices(connection, item);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<Instant> getYearPrices(Item item) {
        try (Connection connection = DatabaseExecutor.getInstance().getConnection()) {
            return HistorialData.getYearPrices(connection, item);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<Instant> getAllPrices(Item item) {
        try (Connection connection = DatabaseExecutor.getInstance().getConnection()) {
            return HistorialData.getAllPrices(connection, item);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return Collections.emptyList();
        }
    }

    // Trades

    @Override
    public void saveTrade(Trade trade) {
        DatabaseExecutor.getInstance().executeWithRetry(connection -> {
            try {
                TradesLog.saveTrade(connection, trade);
            } catch (SQLException e) {
                Nascraft.getInstance().getLogger().warning("Failed to save trade: " + e.getMessage());
            }
        });
    }

    @Override
    public List<Trade> retrieveTrades(UUID uuid, int offset, int limit) {
        try (Connection connection = DatabaseExecutor.getInstance().getConnection()) {
            return TradesLog.retrieveTrades(connection, uuid, offset, limit);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<Trade> retrieveTrades(int offset, int limit) {
        try (Connection connection = DatabaseExecutor.getInstance().getConnection()) {
            return TradesLog.retrieveLastTrades(connection, offset, limit);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public void purgeHistory() {
        DatabaseExecutor.getInstance().executeWithRetry(connection -> {
            try {
                TradesLog.purgeHistory(connection);
            } catch (SQLException e) {
                Nascraft.getInstance().getLogger().warning(e.getMessage());
            }
        });
    }

    // Discord links

    @Override
    public void saveLink(String userId, UUID uuid, String nickname) {
        DatabaseExecutor.getInstance().executeWithRetry(connection -> {
            try {
                DiscordLink.saveLink(connection, userId, uuid, nickname);
            } catch (SQLException e) {
                Nascraft.getInstance().getLogger().warning(e.getMessage());
            }
        });
    }

    @Override
    public void removeLink(String userId) {
        DatabaseExecutor.getInstance().executeWithRetry(connection -> {
            try {
                DiscordLink.removeLink(connection, userId);
            } catch (SQLException e) {
                Nascraft.getInstance().getLogger().warning(e.getMessage());
            }
        });
    }

    @Override
    public UUID getUUID(String userId) {
        try (Connection connection = DatabaseExecutor.getInstance().getConnection()) {
            return DiscordLink.getUUID(connection, userId);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return null;
        }
    }

    @Override
    public String getNickname(String userId) {
        try (Connection connection = DatabaseExecutor.getInstance().getConnection()) {
            return DiscordLink.getNickname(connection, userId);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return null;
        }
    }

    @Override
    public String getUserId(UUID uuid) {
        try (Connection connection = DatabaseExecutor.getInstance().getConnection()) {
            return DiscordLink.getUserId(connection, uuid);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return null;
        }
    }

    // Player names

    @Override
    public String getNameByUUID(UUID uuid) {
        try (Connection connection = DatabaseExecutor.getInstance().getConnection()) {
            return UserNames.getNameByUUID(connection, uuid);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return " ";
        }
    }

    @Override
    public void saveOrUpdateName(UUID uuid, String name) {
        DatabaseExecutor.getInstance().executeWithRetry(connection -> {
            try {
                UserNames.saveOrUpdateNick(connection, uuid, name);
            } catch (SQLException e) {
                Nascraft.getInstance().getLogger().warning(e.getMessage());
            }
        });
    }

}
