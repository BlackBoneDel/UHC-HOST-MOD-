package com.blackbone.uhccore.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;

/** One bounded DB worker; SQLite uses a single connection, MySQL is transparent to callers. */
public final class Database implements AutoCloseable {
    private final Logger logger;
    private final HikariDataSource pool;
    private final ExecutorService worker;
    private final String activeDriver;

    public Database(File folder, Logger logger) {
        this.logger = logger;
        File configFile = new File(folder, "database.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        HikariDataSource candidate = null;
        String driver = config.getString("driver", "sqlite");
        if (driver.equalsIgnoreCase("mysql")) {
            try {
                candidate = new HikariDataSource(mysql(config));
                try (Connection ignored = candidate.getConnection()) {
                    logger.info("MySQL connected");
                }
            } catch (Exception exception) {
                if (candidate != null) candidate.close();
                candidate = null;
                logger.log(Level.WARNING, "MySQL unavailable: falling back to local SQLite", exception);
            }
        } else if (!driver.equalsIgnoreCase("sqlite")) {
            logger.warning("Unknown database driver: " + driver + "; using SQLite");
        }
        if (candidate == null) {
            File data = new File(folder, "data");
            if (!data.isDirectory() && !data.mkdirs()) throw new IllegalStateException("Cannot create database directory");
            candidate = new HikariDataSource(sqlite(new File(data, "uhc.db")));
            driver = "sqlite";
        } else {
            driver = "mysql";
        }
        pool = candidate;
        activeDriver = driver;
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4096), task -> {
                Thread thread = new Thread(task, "uhccore-db");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        migrate();
    }

    public String driver() { return activeDriver; }
    public Connection connection() throws SQLException { return pool.getConnection(); }

    @FunctionalInterface public interface Query<T> { T run(Connection connection) throws Exception; }

    public <T> CompletableFuture<T> async(Query<T> query) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            worker.execute(() -> {
                try (Connection connection = connection()) {
                    result.complete(query.run(connection));
                } catch (Exception exception) {
                    logger.log(Level.SEVERE, "Database operation failed", exception);
                    result.completeExceptionally(exception);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            logger.log(Level.SEVERE, "Database queue full: rejecting write instead of blocking server tick", exception);
            result.completeExceptionally(exception);
        }
        return result;
    }

    private void migrate() {
        String id = activeDriver.equals("mysql") ? "BIGINT PRIMARY KEY AUTO_INCREMENT" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            if (activeDriver.equals("sqlite")) sql.execute("PRAGMA journal_mode=WAL");
            sql.executeUpdate("CREATE TABLE IF NOT EXISTS players (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(32) NOT NULL, lang VARCHAR(2), rank_name VARCHAR(32), kills INTEGER NOT NULL DEFAULT 0, deaths INTEGER NOT NULL DEFAULT 0, assists INTEGER NOT NULL DEFAULT 0, wins INTEGER NOT NULL DEFAULT 0, played INTEGER NOT NULL DEFAULT 0, elo INTEGER NOT NULL DEFAULT 1000)");
            sql.executeUpdate("CREATE TABLE IF NOT EXISTS reports (id " + id + ", reporter VARCHAR(36) NOT NULL, target VARCHAR(36) NOT NULL, reason VARCHAR(255) NOT NULL, created_ms BIGINT NOT NULL, resolved_ms BIGINT)");
            sql.executeUpdate("CREATE TABLE IF NOT EXISTS generation (id INTEGER PRIMARY KEY, world_name VARCHAR(80) NOT NULL, radius INTEGER NOT NULL, next_index INTEGER NOT NULL, generated INTEGER NOT NULL, state VARCHAR(16) NOT NULL, started_ms BIGINT NOT NULL)");
            sql.executeUpdate("CREATE TABLE IF NOT EXISTS matches (id VARCHAR(36) PRIMARY KEY, started_ms BIGINT NOT NULL, ended_ms BIGINT NOT NULL, winner_uuid VARCHAR(36), ranking TEXT NOT NULL)");
        } catch (SQLException ex) {
            pool.close();
            worker.shutdownNow();
            throw new IllegalStateException("Database migration failed", ex);
        }
    }

    private static HikariConfig sqlite(File path) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("UhcCore-SQLite");
        config.setDriverClassName(com.blackbone.uhccore.libs.sqlite.SQLiteDriver.class.getName());
        config.setJdbcUrl("jdbc:sqlite:" + path.getAbsolutePath());
        config.setMaximumPoolSize(1);
        config.setConnectionInitSql("PRAGMA foreign_keys=ON");
        return config;
    }

    private static HikariConfig mysql(YamlConfiguration yaml) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("UhcCore-MySQL");
        config.setDriverClassName(com.mysql.cj.jdbc.Driver.class.getName());
        String host = yaml.getString("mysql.host", "localhost");
        int port = yaml.getInt("mysql.port", 3306);
        String name = yaml.getString("mysql.database", "uhccore");
        if (!host.matches("[A-Za-z0-9.:-]+") || !name.matches("[A-Za-z0-9_]+") || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid MySQL address/database");
        }
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + name
            + "?useSSL=" + yaml.getBoolean("mysql.use-ssl", true) + "&serverTimezone=UTC");
        config.setUsername(yaml.getString("mysql.username", "uhccore"));
        config.setPassword(yaml.getString("mysql.password", ""));
        config.setConnectionTimeout(5000);
        config.setValidationTimeout(2500);
        config.setMaximumPoolSize(4);
        return config;
    }

    @Override public void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("Timed out waiting for pending DB writes");
                worker.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
        pool.close();
    }
}
