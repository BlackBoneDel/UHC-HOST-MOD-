package com.blackbone.uhccore.bridge;

import com.blackbone.uhccore.config.Settings;
import com.blackbone.uhccore.domain.ProtocolPolicy;
import com.blackbone.uhccore.i18n.LocaleManager;
import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.Plugin;

/** ViaVersion classes are never linked on the path used without ViaVersion. */
public final class ViaVersionBridge implements Listener {
    private final Plugin plugin;
    private final Settings settings;
    private final LocaleManager locales;
    private final boolean via;
    private final boolean backwards;
    private final int nativeProtocol;
    private final Map<UUID, Integer> observed = new ConcurrentHashMap<>();
    private final Map<UUID, Object> pending = new ConcurrentHashMap<>();
    private ProtocolPolicy policy;

    public ViaVersionBridge(Plugin plugin, Settings settings, LocaleManager locales) {
        this(plugin, settings, locales, installed("ViaVersion"), installed("ViaBackwards"),
            Bukkit.getUnsafe().getProtocolVersion());
    }

    private static boolean installed(String name) {
        Plugin found = Bukkit.getPluginManager().getPlugin(name);
        return found != null && found.isEnabled();
    }

    // MockBukkit has no protocol version implementation; exercise the gate with the native protocol.
    ViaVersionBridge(Plugin plugin, Settings settings, LocaleManager locales,
                     boolean via, boolean backwards, int nativeProtocol) {
        this.plugin = plugin;
        this.settings = settings;
        this.locales = locales;
        this.via = via;
        this.backwards = backwards;
        this.nativeProtocol = nativeProtocol;
        if (nativeProtocol != 777) throw new IllegalStateException("Paper 26.3 (protocol 777) is required");
        ProtocolPolicy.Mode requested = ProtocolPolicy.Mode.valueOf(
            settings.string("compatibilidad.modo").toUpperCase(java.util.Locale.ROOT));
        if (!via && requested == ProtocolPolicy.Mode.FLEXIBLE) {
            plugin.getLogger().warning("ViaVersion absent: forcing ranked strict mode");
            requested = ProtocolPolicy.Mode.ESTRICTO;
        }
        if (via && !backwards && requested == ProtocolPolicy.Mode.FLEXIBLE) plugin.getLogger().warning("ViaBackwards absent: older clients cannot connect; minimum set to native protocol");
        setPolicy(requested, settings.integer("compatibilidad.protocolo-minimo"));
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public boolean available() { return via; }
    public boolean backwardsAvailable() { return backwards; }
    public int nativeProtocol() { return nativeProtocol; }
    public ProtocolPolicy policy() { return policy; }

    public void setPolicy(ProtocolPolicy.Mode mode, int floor) {
        if (mode == ProtocolPolicy.Mode.FLEXIBLE && !via) throw new IllegalStateException("ViaVersion is required");
        int clamped = Math.max(ProtocolPolicy.OLDEST_SUPPORTED, floor);
        policy = ProtocolPolicy.resolve(mode, clamped, nativeProtocol, via, backwards);
        settings.setProtocolPolicy(policy);
        for (Player player : Bukkit.getOnlinePlayers()) verify(player);
    }

    public void verifyLater(Player player, java.util.function.IntConsumer accepted) {
        UUID id = player.getUniqueId();
        Object session = new Object();
        pending.put(id, session);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                if (!player.isOnline() || pending.get(id) != session) return;
                int version = verify(player);
                if (version != -1) accepted.accept(version);
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.SEVERE, "Protocol verification failed for " + id, error);
                if (player.isOnline()) player.kick(locales.text(player, "compat.kick",
                    "minimum", versionName(policy.minimum()), "maximum", versionName(policy.maximum()),
                    "actual", "unknown"));
            } finally {
                pending.remove(id, session);
            }
        }, 4L);
    }

    private int verify(Player player) {
        int version = via ? viaVersion(player) : nativeProtocol;
        observed.put(player.getUniqueId(), version);
        if (!policy.accepts(version)) {
            player.kick(locales.text(player, "compat.kick", "minimum", versionName(policy.minimum()),
                "maximum", versionName(policy.maximum()), "actual", versionName(version)));
            return -1;
        }
        return version;
    }

    // Deliberately isolated: this class can load even when ViaVersion is not installed.
    private int viaVersion(Player player) {
        return com.viaversion.viaversion.api.Via.getAPI().getPlayerVersion(player);
    }

    public String version(Player player) {
        UUID id = player.getUniqueId();
        if (pending.containsKey(id)) return "pending";
        Integer protocol = observed.get(id);
        return protocol == null ? "unknown" : versionName(protocol);
    }

    private String versionName(int protocol) {
        if (protocol == nativeProtocol) return Bukkit.getMinecraftVersion();
        if (!via) return String.valueOf(protocol);
        return com.viaversion.viaversion.api.protocol.version.ProtocolVersion.getProtocol(protocol).getName();
    }

    /** Until Via has reported the real client protocol, deny any competitive interaction. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void move(PlayerMoveEvent event) {
        if (!pending.containsKey(event.getPlayer().getUniqueId()) || event.getTo() == null) return;
        if (event.getFrom().getX() != event.getTo().getX()
            || event.getFrom().getY() != event.getTo().getY()
            || event.getFrom().getZ() != event.getTo().getZ()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void jump(PlayerJumpEvent event) {
        if (pending.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && pending.containsKey(player.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void attack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && pending.containsKey(player.getUniqueId())) event.setCancelled(true);
        if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player player
            && pending.containsKey(player.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) { if (pending.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void placeBlock(BlockPlaceEvent event) { if (pending.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void interact(PlayerInteractEvent event) { if (pending.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void interactEntity(PlayerInteractEntityEvent event) { if (pending.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent event) { if (pending.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && pending.containsKey(player.getUniqueId())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void launch(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player player && pending.containsKey(player.getUniqueId())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void consume(PlayerItemConsumeEvent event) { if (pending.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void held(PlayerItemHeldEvent event) { if (pending.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void swap(PlayerSwapHandItemsEvent event) { if (pending.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void arrow(PlayerPickupArrowEvent event) { if (pending.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void open(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && pending.containsKey(player.getUniqueId())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void click(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && pending.containsKey(player.getUniqueId())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void drag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && pending.containsKey(player.getUniqueId())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void command(PlayerCommandPreprocessEvent event) {
        if (pending.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void chat(AsyncChatEvent event) {
        if (pending.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler public void quit(PlayerQuitEvent event) { leave(event.getPlayer()); }

    public void leave(Player player) {
        observed.remove(player.getUniqueId());
        pending.remove(player.getUniqueId());
    }
}
