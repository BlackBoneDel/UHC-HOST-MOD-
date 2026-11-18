package com.blackbone.uhccore.permission;

import com.blackbone.uhccore.db.Database;
import java.io.File;
import java.sql.PreparedStatement;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;

/** Only injects Bukkit attachments when LuckPerms is absent. No replacement of Player's permissible. */
public final class RankManager implements UhcPermissible {
    private final Plugin plugin;
    private final Database database;
    private final YamlConfiguration ranks;
    private final boolean fallback;
    private final Map<UUID, PermissionAttachment> attachments = new ConcurrentHashMap<>();
    private final Map<UUID, String> assigned = new ConcurrentHashMap<>();

    public RankManager(Plugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
        this.ranks = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "ranks.yml"));
        fallback = Bukkit.getPluginManager().getPlugin("LuckPerms") == null;
        if (ranks.getConfigurationSection("ranks") == null) throw new IllegalArgumentException("ranks.yml has no ranks");
        plugin.getLogger().info(fallback ? "Using internal ranks.yml permissions" : "LuckPerms found: delegating to Bukkit permissions");
    }

    public boolean fallback() { return fallback; }
    public Set<String> rankNames() { return ranks.getConfigurationSection("ranks").getKeys(false); }
    public String defaultRank() { return ranks.getString("default", "jugador"); }

    public void join(Player player, String storedRank) {
        if (!fallback) return;
        String rank = rankNames().contains(storedRank) ? storedRank : defaultRank();
        assigned.put(player.getUniqueId(), rank);
        PermissionAttachment attachment = player.addAttachment(plugin);
        attachments.put(player.getUniqueId(), attachment);
        apply(attachment, rank, new HashSet<>());
        player.recalculatePermissions();
    }

    public CompletableFuture<Void> setRank(Player player, String rank) {
        if (!fallback || !rankNames().contains(rank)) throw new IllegalArgumentException("Rank unavailable");
        UUID uuid = player.getUniqueId();
        PermissionAttachment old = attachments.remove(uuid);
        if (old != null) player.removeAttachment(old);
        assigned.put(uuid, rank);
        PermissionAttachment attachment = player.addAttachment(plugin);
        attachments.put(uuid, attachment);
        apply(attachment, rank, new HashSet<>());
        player.recalculatePermissions();
        String name = player.getName();
        return database.async(connection -> {
            String sql = database.driver().equals("mysql")
                ? "INSERT INTO players(uuid,name,rank_name) VALUES(?,?,?) ON DUPLICATE KEY UPDATE name=VALUES(name),rank_name=VALUES(rank_name)"
                : "INSERT INTO players(uuid,name,rank_name) VALUES(?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name,rank_name=excluded.rank_name";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid.toString()); statement.setString(2, name); statement.setString(3, rank);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private void apply(PermissionAttachment attachment, String rank, Set<String> visiting) {
        if (!visiting.add(rank)) throw new IllegalArgumentException("Cyclic rank inheritance at " + rank);
        ConfigurationSection section = ranks.getConfigurationSection("ranks." + rank);
        if (section == null) throw new IllegalArgumentException("Unknown inherited rank: " + rank);
        for (String inherited : section.getStringList("inherits")) apply(attachment, inherited, visiting);
        for (String node : section.getStringList("permissions")) attachment.setPermission(node, true);
        visiting.remove(rank);
    }

    public String prefix(Player player) {
        String rank = assigned.getOrDefault(player.getUniqueId(), defaultRank());
        if (!fallback) {
            rank = player.hasPermission("uhccore.rank.admin") ? "admin"
                : player.hasPermission("uhccore.rank.staff") ? "staff" : defaultRank();
        }
        return ranks.getString("ranks." + rank + ".prefix", "");
    }

    @Override public boolean hasPermission(Player player, String permission) {
        return player.hasPermission(permission);
    }

    public void leave(Player player) {
        assigned.remove(player.getUniqueId());
        PermissionAttachment attachment = attachments.remove(player.getUniqueId());
        if (attachment != null) player.removeAttachment(attachment);
    }
}
