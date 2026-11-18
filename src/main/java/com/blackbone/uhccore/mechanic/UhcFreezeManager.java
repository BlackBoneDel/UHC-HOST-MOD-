package com.blackbone.uhccore.mechanic;

import com.blackbone.uhccore.i18n.LocaleManager;
import com.blackbone.uhccore.permission.UhcPermissible;
import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
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
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.Plugin;

/** Memory-only administrative freeze. Rotation and private messages to staff remain possible. */
public final class UhcFreezeManager implements Listener {
    public record Frozen(String name, UUID staff, Instant since) { }
    private final Map<UUID, Frozen> frozen = new ConcurrentHashMap<>();
    private final Plugin plugin;
    private final LocaleManager locales;
    private final UhcPermissible permissions;

    public UhcFreezeManager(Plugin plugin, LocaleManager locales, UhcPermissible permissions) {
        this.plugin = plugin; this.locales = locales; this.permissions = permissions;
    }

    public boolean freeze(Player player, Player staff) {
        return freeze(player, staff.getUniqueId());
    }

    public boolean freeze(Player player, UUID staffId) {
        return frozen.putIfAbsent(player.getUniqueId(), new Frozen(player.getName(), staffId, Instant.now())) == null;
    }

    public boolean unfreeze(UUID id) { return frozen.remove(id) != null; }
    public boolean isFrozen(UUID id) { return frozen.containsKey(id); }
    public Map<UUID, Frozen> snapshot() { return Map.copyOf(frozen); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void move(PlayerMoveEvent event) {
        if (!isFrozen(event.getPlayer().getUniqueId()) || event.getTo() == null) return;
        if (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getY() != event.getTo().getY()
            || event.getFrom().getZ() != event.getTo().getZ()) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true) public void jump(PlayerJumpEvent e) {
        if (isFrozen(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void breakBlock(BlockBreakEvent e) { if (isFrozen(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void placeBlock(BlockPlaceEvent e) { if (isFrozen(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void drop(PlayerDropItemEvent e) { if (isFrozen(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void pickup(EntityPickupItemEvent e) { if (e.getEntity() instanceof Player p && isFrozen(p.getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void interact(PlayerInteractEvent e) { if (isFrozen(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void consume(PlayerItemConsumeEvent e) { if (isFrozen(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void held(PlayerItemHeldEvent e) { if (isFrozen(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void arrow(PlayerPickupArrowEvent e) { if (isFrozen(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void interactEntity(PlayerInteractEntityEvent e) { if (isFrozen(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void open(InventoryOpenEvent e) { if (e.getPlayer() instanceof Player p && isFrozen(p.getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void click(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p && isFrozen(p.getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void drag(InventoryDragEvent e) { if (e.getWhoClicked() instanceof Player p && isFrozen(p.getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void swap(PlayerSwapHandItemsEvent e) { if (isFrozen(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void launch(ProjectileLaunchEvent e) {
        if (e.getEntity().getShooter() instanceof Player p && isFrozen(p.getUniqueId())) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void hit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && isFrozen(p.getUniqueId())) e.setCancelled(true);
        if (e.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player p
            && isFrozen(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void command(PlayerCommandPreprocessEvent event) {
        if (!isFrozen(event.getPlayer().getUniqueId())) return;
        String[] parts = event.getMessage().trim().split("\\s+", 3);
        String name = parts[0].toLowerCase(java.util.Locale.ROOT);
        boolean messageCommand = name.equals("/msg") || name.equals("/tell") || name.equals("/w")
            || name.equals("/minecraft:msg") || name.equals("/minecraft:tell");
        Player recipient = parts.length >= 3 && messageCommand ? Bukkit.getPlayerExact(parts[1]) : null;
        if (recipient == null || !permissions.hasPermission(recipient, "uhccore.staff")) {
            event.setCancelled(true);
            locales.send(event.getPlayer(), "freeze.command-blocked");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void chat(AsyncChatEvent event) {
        if (!isFrozen(event.getPlayer().getUniqueId())) return;
        // A frozen participant must not relay tactics to the public/spectator
        // channel. AsyncChatEvent may run off-thread: resolve staff permissions
        // and deliver the private moderation channel back on the server thread.
        event.setCancelled(true);
        Player sender = event.getPlayer();
        Component message = event.message();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Component rendered = Component.text("[Frozen] ", TextColor.fromHexString("#57D9E8"))
                .append(Component.text(sender.getName() + ": ")).append(message);
            boolean echoed = false;
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (!permissions.hasPermission(staff, "uhccore.staff.notify")) continue;
                staff.sendMessage(rendered);
                if (staff.getUniqueId().equals(sender.getUniqueId())) echoed = true;
            }
            if (!echoed && sender.isOnline()) sender.sendMessage(rendered);
        });
    }

    @EventHandler public void quit(PlayerQuitEvent event) {
        Frozen flag = frozen.get(event.getPlayer().getUniqueId());
        if (flag == null) return;
        String time = Instant.now().toString();
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (permissions.hasPermission(staff, "uhccore.staff.notify")) {
                locales.send(staff, "freeze.quit-alert", "player", flag.name(), "time", time);
            }
        }
    }
}
