package com.blackbone.uhccore.stats;

import com.blackbone.uhccore.db.Database;
import com.blackbone.uhccore.domain.PlayerStats;
import com.blackbone.uhccore.domain.RatingMath;
import com.blackbone.uhccore.i18n.LocaleManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Cache is changed on the server thread; DB writes are serialized on a bounded worker. */
public final class StatsManager {
    private final Database database;
    private final Plugin plugin;
    private final LocaleManager locales;
    // Working scores are used for consecutive kills; only committed scores are exposed to GUIs.
    private final Map<UUID, PlayerStats> cache = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerStats> committed = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> pending = new ConcurrentHashMap<>();
    private final Set<UUID> failed = ConcurrentHashMap.newKeySet();
    private final Set<UUID> languageUnconfirmed = ConcurrentHashMap.newKeySet();
    private final Set<UUID> ready = ConcurrentHashMap.newKeySet();
    private final Function<List<PlayerStats>, CompletableFuture<Void>> writer;

    public StatsManager(Database database, Plugin plugin, LocaleManager locales) {
        this(database, plugin, locales, null);
    }

    // A failed future can be injected by tests without replacing the production SQL transaction.
    StatsManager(Database database, Plugin plugin, LocaleManager locales,
                 Function<List<PlayerStats>, CompletableFuture<Void>> writer) {
        this.database = database;
        this.plugin = plugin;
        this.locales = locales;
        this.writer = writer == null ? this::writeToDatabase : writer;
    }

    // Callers (including the profile GUI and match admission) must not present
    // or rely on a profile while its ranked writes are still unconfirmed.
    public boolean ready(UUID uuid) { return ready.contains(uuid) && confirmed(uuid); }
    public boolean confirmed(UUID uuid) {
        return committed.containsKey(uuid) && !pending.containsKey(uuid) && !failed.contains(uuid);
    }
    public boolean languageConfirmed(UUID uuid) { return !languageUnconfirmed.contains(uuid); }

    public void leave(Player player, boolean retainForProxy) {
        UUID uuid = player.getUniqueId();
        ready.remove(uuid);
        if (!retainForProxy) cache.remove(uuid);
    }

    public void discardOffline() {
        cache.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
        committed.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null && !pending.containsKey(uuid));
    }

    public record Profile(PlayerStats stats, String language, String rank) { }

    public void join(Player player, Consumer<Profile> loaded) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        ready.remove(uuid);
        database.async(connection -> {
            ensure(connection, uuid, name);
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM players WHERE uuid=?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw new SQLException("Profile insert failed");
                    return new Profile(fromRow(rows), rows.getString("lang"), rows.getString("rank_name"));
                }
            }
        }).whenComplete((profile, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "Unable to load player profile " + uuid, error);
                    player.kick(locales.text(player, "match.profile-unavailable"));
                    return;
                }
                try {
                    // A HUD query before the async SELECT must not insert a fresh
                    // 1000-Elo profile and later overwrite historical match data.
                    // Retained proxy scores are newer than a concurrent DB read.
                    cache.merge(uuid, profile.stats(), (current, ignored) -> current.renamed(name));
                    committed.merge(uuid, profile.stats(), (current, ignored) -> current.renamed(name));
                    loaded.accept(profile);
                    ready.add(uuid);
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.SEVERE, "Unable to initialize player profile " + uuid, ex);
                    player.kick(locales.text(player, "match.profile-unavailable"));
                }
            });
        });
    }

    public PlayerStats get(UUID uuid, String name) {
        PlayerStats current = committed.get(uuid);
        return current == null ? PlayerStats.fresh(uuid, name) : current;
    }

    private PlayerStats working(UUID uuid, String name) {
        PlayerStats current = cache.get(uuid);
        return current == null ? PlayerStats.fresh(uuid, name) : current;
    }

    public CompletableFuture<Void> participation(Collection<Player> players) {
        List<PlayerStats> updates = new ArrayList<>();
        for (Player player : players) {
            PlayerStats stats = working(player.getUniqueId(), player.getName()).participation();
            cache.put(player.getUniqueId(), stats);
            updates.add(stats);
        }
        return persist("participation", updates);
    }

    public CompletableFuture<Void> death(UUID victimId, String victimName, UUID killerId, String killerName,
                                         Collection<UUID> assists, int bonusElo) {
        PlayerStats victim = working(victimId, victimName);
        List<PlayerStats> updates = new ArrayList<>();
        if (killerId != null && !killerId.equals(victimId)) {
            PlayerStats killer = working(killerId, killerName);
            RatingMath.Result rating = RatingMath.duel(killer.elo(), victim.elo());
            PlayerStats awarded = killer.kill(rating.winner() + bonusElo);
            victim = victim.death(rating.loser());
            cache.put(killerId, awarded);
            updates.add(awarded);
        } else {
            victim = victim.death(victim.elo());
        }
        cache.put(victimId, victim);
        updates.add(victim);
        for (UUID id : assists) {
            if (id.equals(victimId) || id.equals(killerId)) continue;
            PlayerStats assisted = cache.get(id);
            if (assisted != null) {
                assisted = assisted.assist();
                cache.put(id, assisted);
                updates.add(assisted);
            }
        }
        return persist("death " + victimId, updates);
    }

    public CompletableFuture<Void> victory(UUID uuid, String name) {
        PlayerStats stats = working(uuid, name).victory();
        cache.put(uuid, stats);
        return persist("victory " + uuid, List.of(stats));
    }

    public CompletableFuture<Void> language(Player player, String language) {
        UUID id = player.getUniqueId();
        String name = player.getName();
        languageUnconfirmed.add(id);
        return database.<Void>async(connection -> {
            ensure(connection, id, name);
            try (PreparedStatement statement = connection.prepareStatement("UPDATE players SET lang=? WHERE uuid=?")) {
                statement.setString(1, language);
                statement.setString(2, id.toString());
                statement.executeUpdate();
            }
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) plugin.getLogger().log(Level.SEVERE,
                "Language write not confirmed for player " + id + " (" + language + ")", error);
            else languageUnconfirmed.remove(id);
        });
    }

    public CompletableFuture<List<PlayerStats>> leaderboard(int limit) {
        int count = Math.clamp(limit, 1, 100);
        return database.async(connection -> {
            List<PlayerStats> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM players ORDER BY elo DESC, wins DESC LIMIT ?")) {
                statement.setInt(1, count);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) result.add(fromRow(rows));
                }
            }
            return result;
        });
    }

    private CompletableFuture<Void> persist(String operation, List<PlayerStats> updates) {
        if (updates.isEmpty()) return CompletableFuture.completedFuture(null);
        List<PlayerStats> snapshot = List.copyOf(updates);
        for (PlayerStats stats : snapshot) pending.merge(stats.uuid(), 1, Integer::sum);
        CompletableFuture<Void> write;
        try {
            write = writer.apply(snapshot);
        } catch (RuntimeException exception) {
            write = CompletableFuture.failedFuture(exception);
        }
        return write.whenComplete((ignored, error) -> {
            for (PlayerStats stats : snapshot) {
                if (error == null) {
                    committed.put(stats.uuid(), stats);
                    failed.remove(stats.uuid());
                } else failed.add(stats.uuid());
                pending.computeIfPresent(stats.uuid(), (id, count) -> count == 1 ? null : count - 1);
            }
            if (error != null) plugin.getLogger().log(Level.SEVERE,
                "Ranked stats " + operation + " NOT CONFIRMED for "
                    + snapshot.stream().map(row -> row.uuid().toString()).toList(), error);
        });
    }

    private CompletableFuture<Void> writeToDatabase(List<PlayerStats> updates) {
        return database.async(connection -> {
            connection.setAutoCommit(false);
            try {
                for (PlayerStats stats : updates) {
                    ensure(connection, stats.uuid(), stats.name());
                    try (PreparedStatement stmt = connection.prepareStatement("UPDATE players SET kills=?,deaths=?,assists=?,wins=?,played=?,elo=?,name=? WHERE uuid=?")) {
                        stmt.setInt(1, stats.kills()); stmt.setInt(2, stats.deaths()); stmt.setInt(3, stats.assists());
                        stmt.setInt(4, stats.wins()); stmt.setInt(5, stats.played()); stmt.setInt(6, stats.elo());
                        stmt.setString(7, stats.name()); stmt.setString(8, stats.uuid().toString());
                        stmt.executeUpdate();
                    }
                }
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
            return null;
        });
    }

    private void ensure(Connection connection, UUID uuid, String name) throws SQLException {
        String sql = database.driver().equals("mysql")
            ? "INSERT INTO players(uuid,name) VALUES(?,?) ON DUPLICATE KEY UPDATE name=VALUES(name)"
            : "INSERT INTO players(uuid,name) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString()); statement.setString(2, name);
            statement.executeUpdate();
        }
    }

    private static PlayerStats fromRow(ResultSet row) throws SQLException {
        return new PlayerStats(UUID.fromString(row.getString("uuid")), row.getString("name"), row.getInt("kills"),
            row.getInt("deaths"), row.getInt("assists"), row.getInt("wins"), row.getInt("played"), row.getInt("elo"));
    }
}
