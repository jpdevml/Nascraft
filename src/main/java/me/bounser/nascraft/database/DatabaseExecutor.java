package me.bounser.nascraft.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.config.Config;

import java.io.File;

public class DatabaseExecutor {

    private static DatabaseExecutor instance;

    public static void shutdownIfPresent() {
        if (instance != null) instance.shutdown();
    }

    private final ExecutorService executor;
    private final HikariDataSource dataSource;
    private final ConcurrentHashMap<String, Long> transactionIds;
    private final AtomicLong idCounter;

    private static final int MAX_RETRIES = 3;
    private static final int BASE_DELAY_MS = 50;

    public static DatabaseExecutor getInstance() {
        if (instance == null) {
            instance = new DatabaseExecutor();
        }
        return instance;
    }

    private DatabaseExecutor() {
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Nascraft-DB");
            t.setDaemon(true);
            return t;
        });
        this.transactionIds = new ConcurrentHashMap<>();
        this.idCounter = new AtomicLong(0);

        Config settings = Config.getInstance();
        HikariConfig config = new HikariConfig();
        if (settings.getDatabaseType().equals("sqlite")) {
            File dataDir = new File(Nascraft.getInstance().getDataFolder(), "data");
            if (!dataDir.exists() && !dataDir.mkdirs()) throw new IllegalStateException("Could not create database directory");
            config.setJdbcUrl("jdbc:sqlite:" + new File(dataDir, "sqlite.db"));
            config.setMaximumPoolSize(2);
            config.addDataSourceProperty("journal_mode", "WAL");
            config.addDataSourceProperty("busy_timeout", "30000");
        } else {
            String host = settings.getMysqlHost(), name = settings.getMysqlName();
            if (!host.matches("[a-zA-Z0-9.:-]+") || !name.matches("[a-zA-Z0-9_]+")
                    || settings.getMysqlPort() < 1 || settings.getMysqlPort() > 65535)
                throw new IllegalArgumentException("Invalid database.mysql host, port or name");
            config.setJdbcUrl("jdbc:mariadb://" + host + ":" + settings.getMysqlPort() + "/" + name
                    + "?sslMode=" + (settings.getMysqlSsl() ? "verify-full" : "disable"));
            config.setUsername(settings.getMysqlUser());
            config.setPassword(settings.getMysqlPassword());
            config.setMaximumPoolSize(Math.max(2, Math.min(32, settings.getMysqlPoolSize())));
            config.setConnectionInitSql("SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED");
        }
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5000);
        config.setValidationTimeout(3000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        this.dataSource = new HikariDataSource(config);
    }

    public void execute(Runnable task) {
        executor.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                Nascraft.getInstance().getLogger().warning("DB task failed: " + e.getMessage());
            }
        });
    }

    public void executeWithRetry(Consumer<Connection> task) {
        executor.submit(() -> runWithRetry(task));
    }

    public void executeIdempotent(String transactionKey, Consumer<Connection> task) {
        long currentId = idCounter.incrementAndGet();

        Long existingId = transactionIds.putIfAbsent(transactionKey, currentId);
        if (existingId != null) {
            return;
        }

        executor.submit(() -> {
            try {
                runWithRetry(task);
            } finally {
                transactionIds.remove(transactionKey);
            }
        });
    }

    /**
     * Submits an empty task and waits for it: since the executor is a
     * single FIFO thread, returning means every previously queued write
     * has completed. Used before reload re-reads item state.
     */
    public boolean flush(int timeoutSeconds) {
        try {
            executor.submit(() -> { }).get(timeoutSeconds, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void runWithRetry(Consumer<Connection> task) {
        int attempt = 0;
        while (attempt < MAX_RETRIES) {
            try (Connection conn = dataSource.getConnection()) {
                task.accept(conn);
                return;
            } catch (RuntimeException e) {
                Nascraft.getInstance().getLogger().severe("Unexpected error in DB task: " + e);
                return;
            } catch (SQLException e) {
                String msg = e.getMessage();
                if (msg != null && (msg.contains("SQLITE_BUSY") || msg.contains("database is locked"))) {
                    attempt++;
                    if (attempt < MAX_RETRIES) {
                        int delay = BASE_DELAY_MS * (1 << attempt);
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                } else {
                    Nascraft.getInstance().getLogger().severe("DB error: " + msg);
                    if (!Config.getInstance().getDatabaseType().equals("sqlite")
                            && me.bounser.nascraft.market.MarketManager.getInstanceIfPresent() != null)
                        me.bounser.nascraft.market.MarketManager.getInstanceIfPresent().stop();
                    return;
                }
            }
        }
        Nascraft.getInstance().getLogger().warning("DB operation failed after " + MAX_RETRIES + " retries");
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                Nascraft.getInstance().getLogger().severe("Database writes did not finish within 10s; pending writes were dropped.");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
