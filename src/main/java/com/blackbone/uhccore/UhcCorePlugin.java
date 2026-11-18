package com.blackbone.uhccore;

import com.blackbone.uhccore.bridge.ViaVersionBridge;
import com.blackbone.uhccore.command.UhcCommand;
import com.blackbone.uhccore.config.Settings;
import com.blackbone.uhccore.db.Database;
import com.blackbone.uhccore.game.UhcGameManager;
import com.blackbone.uhccore.gui.AdminPanel;
import com.blackbone.uhccore.gui.InfoPanel;
import com.blackbone.uhccore.gui.MenuProtection;
import com.blackbone.uhccore.gui.StaffPanel;
import com.blackbone.uhccore.hud.TablistPackets;
import com.blackbone.uhccore.hud.UhcHud;
import com.blackbone.uhccore.i18n.LocaleManager;
import com.blackbone.uhccore.mechanic.CombatLoggerManager;
import com.blackbone.uhccore.mechanic.GoldenHeadManager;
import com.blackbone.uhccore.mechanic.MuteManager;
import com.blackbone.uhccore.mechanic.RandomKillerManager;
import com.blackbone.uhccore.mechanic.SpectatorManager;
import com.blackbone.uhccore.mechanic.TimeBombManager;
import com.blackbone.uhccore.mechanic.UhcFreezeManager;
import com.blackbone.uhccore.network.DiscordAnnouncer;
import com.blackbone.uhccore.network.ProxyBridge;
import com.blackbone.uhccore.permission.RankManager;
import com.blackbone.uhccore.resource.PackHost;
import com.blackbone.uhccore.stats.ReportManager;
import com.blackbone.uhccore.stats.StatsManager;
import com.blackbone.uhccore.world.PregenerationManager;
import com.blackbone.uhccore.world.ScatterManager;
import com.blackbone.uhccore.world.WorldManager;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Composition root: Bukkit access is confined to the main thread except documented async I/O. */
public final class UhcCorePlugin extends JavaPlugin implements Listener {
    private Settings settings;
    private Database database;
    private LocaleManager locales;
    private StatsManager stats;
    private ReportManager reports;
    private RankManager ranks;
    private ViaVersionBridge versions;
    private WorldManager worlds;
    private PregenerationManager generation;
    private UhcFreezeManager freeze;
    private MuteManager mutes;
    private GoldenHeadManager heads;
    private TimeBombManager bombs;
    private RandomKillerManager hunter;
    private UhcGameManager game;
    private CombatLoggerManager combat;
    private SpectatorManager spectators;
    private AdminPanel adminPanel;
    private InfoPanel infoPanel;
    private StaffPanel staffPanel;
    private UhcHud hud;
    private PackHost packs;
    private DiscordAnnouncer discord;
    private ProxyBridge proxy;
    private long ticks;

    @Override public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
    }

    @Override public void onEnable() {
        try {
            saveResource("settings.yml", false);
            saveResource("database.yml", false);
            saveResource("ranks.yml", false);
            settings = new Settings(new java.io.File(getDataFolder(), "settings.yml"), getLogger());
            settings.validate();
            locales = new LocaleManager(this);
            database = new Database(getDataFolder(), getLogger());
            stats = new StatsManager(database, this);
            reports = new ReportManager(database);
            ranks = new RankManager(this, database);
            versions = new ViaVersionBridge(this, settings, locales);
            worlds = new WorldManager(settings);
            generation = new PregenerationManager(this, database, worlds);
            freeze = new UhcFreezeManager(locales, ranks);
            mutes = new MuteManager(this, locales);
            heads = new GoldenHeadManager(this, settings, locales, freeze);
            bombs = new TimeBombManager(settings, locales);
            hunter = new RandomKillerManager(settings, locales);
            discord = new DiscordAnnouncer(settings, getLogger());
            var scatter = new ScatterManager(this);
            game = new UhcGameManager(this, settings, worlds, scatter, stats, database, locales, bombs,
                heads, hunter, freeze, discord);
            combat = new CombatLoggerManager(this, settings, game, heads);
            game.setCombat(combat);
            spectators = new SpectatorManager(this, game, locales);
            adminPanel = new AdminPanel(settings, database, locales, versions, game, worlds, generation);
            infoPanel = new InfoPanel(settings, locales, game, worlds, stats);
            staffPanel = new StaffPanel(this, locales, versions, freeze, mutes, game, reports);
            proxy = new ProxyBridge(this, settings);
            packs = new PackHost(this, settings, locales);
            PacketEvents.getAPI().init();
            hud = new UhcHud(settings, locales, game, worlds, generation, hunter,
                new TablistPackets(locales, ranks));

            for (Listener listener : List.of(this, freeze, mutes, heads, game, combat, spectators,
                    adminPanel, infoPanel, staffPanel, new MenuProtection())) {
                Bukkit.getPluginManager().registerEvents(listener, this);
            }
            new UhcCommand(this).register();
            generation.restore();
            for (Player player : Bukkit.getOnlinePlayers()) join(player);
            // Exactly one repeating scheduler drives match, combat, generation, tombs and batched HUD.
            Bukkit.getScheduler().runTaskTimer(this, task -> tick(), 1L, 1L);
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Cannot initialize UhcCore; disabling rather than running partial rules", ex);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    private void tick() {
        ticks++;
        generation.tick();
        game.tick();
        combat.tick();
        bombs.tick();
        if (ticks % 20 == 0) {
            List<Player> online = List.copyOf(Bukkit.getOnlinePlayers());
            hud.update(online);
            heads.alertNearby();
        }
    }

    @EventHandler(priority = EventPriority.LOW) public void joined(PlayerJoinEvent event) { join(event.getPlayer()); }

    private void join(Player player) {
        locales.detect(player);
        stats.join(player, profile -> {
            if (profile.language() != null) locales.select(player, profile.language());
            ranks.join(player, profile.rank());
        });
        hud.join(player);
        versions.verifyLater(player, protocol -> {
            try {
                packs.send(player, protocol);
                combat.reclaim(player);
            } catch (RuntimeException exception) {
                getLogger().log(Level.SEVERE, "Mandatory resource pack cannot be delivered", exception);
                player.kick(locales.text(player, "pack.unavailable"));
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR) public void left(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        hud.leave(player); ranks.leave(player); locales.leave(player); versions.leave(player);
    }

    @Override public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        if (generation != null) generation.flush();
        if (combat != null) combat.close();
        if (bombs != null) bombs.close();
        if (hud != null) hud.close(Bukkit.getOnlinePlayers());
        if (packs != null) packs.close();
        if (discord != null) discord.close();
        if (database != null) database.close();
        if (PacketEvents.getAPI() != null) PacketEvents.getAPI().terminate();
    }

    public Settings settings() { return settings; }
    public Database database() { return database; }
    public LocaleManager locales() { return locales; }
    public StatsManager stats() { return stats; }
    public ReportManager reports() { return reports; }
    public RankManager ranks() { return ranks; }
    public ViaVersionBridge versions() { return versions; }
    public WorldManager worlds() { return worlds; }
    public PregenerationManager generation() { return generation; }
    public UhcFreezeManager freeze() { return freeze; }
    public MuteManager mutes() { return mutes; }
    public UhcGameManager game() { return game; }
    public AdminPanel adminPanel() { return adminPanel; }
    public InfoPanel infoPanel() { return infoPanel; }
    public StaffPanel staffPanel() { return staffPanel; }
    public ProxyBridge proxy() { return proxy; }
}
