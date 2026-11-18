package com.blackbone.uhccore.game;

import com.blackbone.uhccore.config.Settings;
import com.blackbone.uhccore.db.Database;
import com.blackbone.uhccore.domain.BorderPhase;
import com.blackbone.uhccore.domain.BorderSchedule;
import com.blackbone.uhccore.domain.MatchClock;
import com.blackbone.uhccore.i18n.LocaleManager;
import com.blackbone.uhccore.mechanic.CombatLoggerManager;
import com.blackbone.uhccore.mechanic.GoldenHeadManager;
import com.blackbone.uhccore.mechanic.RandomKillerManager;
import com.blackbone.uhccore.mechanic.TimeBombManager;
import com.blackbone.uhccore.mechanic.UhcFreezeManager;
import com.blackbone.uhccore.network.DiscordAnnouncer;
import com.blackbone.uhccore.stats.StatsManager;
import com.blackbone.uhccore.world.ScatterManager;
import com.blackbone.uhccore.world.WorldManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;

/** One active match; rules and participants are snapshotted before scatter. */
public final class UhcGameManager implements Listener {
    private final Plugin plugin;
    private final Settings settings;
    private final WorldManager worlds;
    private final ScatterManager scatter;
    private final StatsManager stats;
    private final Database database;
    private final LocaleManager locales;
    private final TimeBombManager bombs;
    private final GoldenHeadManager heads;
    private final RandomKillerManager hunter;
    private final UhcFreezeManager freeze;
    private final DiscordAnnouncer discord;
    private final MatchClock clock = new MatchClock();
    private final Set<UUID> alive = new HashSet<>();
    private final Set<UUID> eliminated = new HashSet<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final List<UUID> eliminationOrder = new ArrayList<>();
    private CombatLoggerManager combat;
    private BorderSchedule border;
    private boolean scatterReady;
    private int borderPhase = -1;
    private long lastSecond = -1;
    private long startedWall;
    private UUID matchId;

    public UhcGameManager(Plugin plugin, Settings settings, WorldManager worlds, ScatterManager scatter,
                          StatsManager stats, Database database, LocaleManager locales, TimeBombManager bombs,
                          GoldenHeadManager heads, RandomKillerManager hunter, UhcFreezeManager freeze,
                          DiscordAnnouncer discord) {
        this.plugin = plugin; this.settings = settings; this.worlds = worlds; this.scatter = scatter;
        this.stats = stats; this.database = database; this.locales = locales;
        this.bombs = bombs; this.heads = heads; this.hunter = hunter; this.freeze = freeze; this.discord = discord;
    }

    public void setCombat(CombatLoggerManager combat) { this.combat = combat; }
    public MatchClock.State state() { return clock.state(); }
    public boolean running() { return clock.state() == MatchClock.State.RUNNING; }
    public boolean editable() { return clock.state() == MatchClock.State.IDLE || clock.state() == MatchClock.State.FINISHED; }
    public boolean isAlive(UUID id) { return alive.contains(id); }
    public boolean isEliminated(UUID id) { return eliminated.contains(id); }
    public Set<UUID> alive() { return Set.copyOf(alive); }
    public BorderSchedule schedule() { return border == null ? settings.borderSchedule() : border; }
    public int borderPhase() { return borderPhase; }
    public int secondsRemaining() { return clock.remaining(now()); }
    public long elapsed() { return clock.elapsed(now()); }
    public String name(UUID uuid) { return names.getOrDefault(uuid, uuid.toString()); }

    private static long now() { return System.nanoTime() / 1_000_000L; }

    public boolean begin() {
        if (clock.state() != MatchClock.State.IDLE) return false;
        List<Player> players = Bukkit.getOnlinePlayers().stream()
            .filter(player -> !freeze.isFrozen(player.getUniqueId()) && player.hasPermission("uhccore.play"))
            .toList();
        if (players.size() < 2) throw new IllegalStateException("At least two eligible players required");
        border = settings.borderSchedule();
        World world = worlds.ensureWorld();
        worlds.resetBorder();
        matchId = UUID.randomUUID();
        alive.clear(); eliminated.clear(); names.clear(); eliminationOrder.clear();
        for (Player player : players) {
            alive.add(player.getUniqueId()); names.put(player.getUniqueId(), player.getName());
            player.getInventory().clear();
            player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
            player.setGameMode(GameMode.SURVIVAL);
            player.setHealth(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
            player.setFoodLevel(20); player.setSaturation(20); player.setLevel(0); player.setExp(0);
        }
        scatterReady = false; borderPhase = -1; lastSecond = -1;
        clock.begin(settings.integer("pre-game-seconds"), now());
        int separation = settings.bool("mecanicas.scatter-seguro") ? settings.integer("mundo.min-distancia-scatter") : 16;
        scatter.scatter(players, world, settings.integer("mundo.radio"), separation)
            .whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (clock.state() != MatchClock.State.PREGAME) return;
                if (error != null) {
                    plugin.getLogger().severe("Scatter failed: " + error.getMessage());
                    clock.reset(); alive.clear(); names.clear();
                    for (Player player : Bukkit.getOnlinePlayers()) locales.send(player, "match.scatter-failed");
                    return;
                }
                scatterReady = true;
            }));
        for (Player player : Bukkit.getOnlinePlayers()) locales.send(player, "match.pregame", "seconds", String.valueOf(settings.integer("pre-game-seconds")));
        return true;
    }

    public void reset() {
        if (clock.state() != MatchClock.State.FINISHED) throw new IllegalStateException("Finish the match first");
        clock.reset(); hunter.reset();
        alive.clear(); eliminated.clear(); names.clear(); eliminationOrder.clear();
        border = null; scatterReady = false;
    }

    public void tick() {
        if (clock.state() == MatchClock.State.PREGAME && scatterReady && clock.ready(now())) {
            if (alive.size() < 2) {
                clock.reset(); alive.clear(); names.clear(); return;
            }
            clock.start(now());
            startedWall = System.currentTimeMillis();
            stats.participation(Bukkit.getOnlinePlayers().stream().filter(p -> alive.contains(p.getUniqueId())).toList());
            discord.announce("UHC started: " + alive.size() + " players. World: " + worlds.ensureWorld().getName());
            for (Player player : Bukkit.getOnlinePlayers()) locales.send(player, "match.started", "count", String.valueOf(alive.size()));
        }
        if (!running()) return;
        long seconds = elapsed();
        if (seconds == lastSecond) return;
        lastSecond = seconds;
        for (int i = borderPhase + 1; i < border.phases().size(); i++) {
            BorderPhase phase = border.phases().get(i);
            if (seconds < phase.startSeconds()) break;
            borderPhase = i;
            World world = worlds.ensureWorld();
            if (world.getWorldBorder().getSize() / 2.0 > phase.targetRadius()) {
                world.getWorldBorder().setSize(phase.targetRadius() * 2.0, phase.durationSeconds());
                for (Player player : Bukkit.getOnlinePlayers()) locales.send(player, "match.border-phase",
                    "radius", String.valueOf(phase.targetRadius()), "seconds", String.valueOf(phase.durationSeconds()));
            }
        }
        hunter.tick(seconds, alive);
    }

    public void manualBorder(int radius, int seconds) {
        if (!running() || radius < 5 || seconds < 1 || radius >= worlds.ensureWorld().getWorldBorder().getSize() / 2) {
            throw new IllegalArgumentException("Border must shrink during a running match");
        }
        worlds.ensureWorld().getWorldBorder().setSize(radius * 2.0, seconds);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void noPregameDamage(EntityDamageEvent event) {
        if (clock.state() == MatchClock.State.PREGAME && event.getEntity() instanceof Player player
            && alive.contains(player.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void death(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        if (!running() || !alive.contains(victim.getUniqueId())) return;
        UUID killer = victim.getKiller() != null ? victim.getKiller().getUniqueId() : combat.killerFor(victim.getUniqueId());
        if (killer != null && !alive.contains(killer)) killer = null;
        List<ItemStack> loot = event.getDrops().stream().map(ItemStack::clone).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        event.getDrops().clear();
        if (settings.bool("mecanicas.golden-heads")
            && ThreadLocalRandom.current().nextDouble() < settings.decimal("mecanicas.golden-heads-probabilidad")) {
            loot.add(heads.create(victim));
        }
        ItemStack skull = skull(victim);
        int experience = event.getDroppedExp();
        event.setDroppedExp(0); event.setShowDeathMessages(false);
        bombs.create(victim.getName(), victim.getLocation(), skull, loot, experience);
        eliminate(victim.getUniqueId(), victim.getName(), killer, combat.assistsFor(victim.getUniqueId(), killer));
    }

    private ItemStack skull(Player player) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setPlayerProfile(player.getPlayerProfile());
        item.setItemMeta(meta);
        return item;
    }

    public void proxyDeath(UUID id, String name, org.bukkit.Location location, List<ItemStack> loot,
                           ItemStack head, UUID killer, Set<UUID> assists) {
        if (!running() || !alive.contains(id)) return;
        bombs.create(name, location, head, loot, 0);
        eliminate(id, name, killer != null && alive.contains(killer) ? killer : null, assists);
    }

    private void eliminate(UUID id, String name, UUID killer, Set<UUID> assists) {
        if (!alive.remove(id)) return;
        eliminated.add(id); eliminationOrder.add(id);
        int bounty = hunter.bounty(id, killer);
        stats.death(id, name, killer, killer == null ? "" : name(killer), assists, bounty);
        for (Player player : Bukkit.getOnlinePlayers()) locales.send(player, "match.eliminated", "player", name,
            "alive", String.valueOf(alive.size()));
        discord.announce("UHC elimination: " + name + (killer == null ? "" : " by " + name(killer)) + ". Alive: " + alive.size());
        maybeFinish();
    }

    public void forfeit(Player player) {
        if (!running() || !alive.contains(player.getUniqueId()) || combat.hasProxy(player.getUniqueId())) return;
        List<ItemStack> loot = new ArrayList<>();
        for (ItemStack item : player.getInventory().getContents()) if (item != null && !item.getType().isAir()) loot.add(item.clone());
        bombs.create(player.getName(), player.getLocation(), skull(player), loot, 0);
        eliminate(player.getUniqueId(), player.getName(), null, Set.of());
    }

    public void recheckWinner() { maybeFinish(); }

    private void maybeFinish() {
        if (alive.size() > 1 || !running()) return;
        UUID winner = alive.stream().findFirst().orElse(null);
        if (winner != null && combat.hasProxy(winner)) return;
        clock.finish();
        if (winner != null) stats.victory(winner, name(winner));
        List<UUID> ranking = new ArrayList<>(alive);
        for (int i = eliminationOrder.size() - 1; i >= 0; i--) ranking.add(eliminationOrder.get(i));
        String standing = ranking.stream().limit(5).map(id -> name(id)).collect(java.util.stream.Collectors.joining(", "));
        discord.announce("UHC finished. Winner: " + (winner == null ? "none" : name(winner)) + ". Ranking: " + standing);
        for (Player player : Bukkit.getOnlinePlayers()) locales.send(player, "match.finished",
            "winner", winner == null ? "-" : name(winner), "ranking", standing);
        UUID winningId = winner;
        UUID completedMatchId = matchId;
        long startedAt = startedWall;
        long endedAt = System.currentTimeMillis();
        String finalRanking = ranking.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(","));
        database.async(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO matches(id,started_ms,ended_ms,winner_uuid,ranking) VALUES(?,?,?,?,?)")) {
                statement.setString(1, completedMatchId.toString()); statement.setLong(2, startedAt);
                statement.setLong(3, endedAt);
                statement.setString(4, winningId == null ? null : winningId.toString());
                statement.setString(5, finalRanking);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void quit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (clock.state() == MatchClock.State.PREGAME) {
            alive.remove(player.getUniqueId());
        } else forfeit(player);
    }

    @EventHandler public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if ((running() || state() == MatchClock.State.FINISHED)
            && (!alive.contains(player.getUniqueId()) || combat.hasProxy(player.getUniqueId()))) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.setGameMode(GameMode.SPECTATOR);
                    if (!alive.contains(player.getUniqueId())) player.getInventory().clear();
                }
            });
        }
    }
}
