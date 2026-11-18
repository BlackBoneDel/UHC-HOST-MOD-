package com.blackbone.uhccore.mechanic;

import com.blackbone.uhccore.domain.MatchAccessPolicy;
import com.blackbone.uhccore.domain.MatchClock;
import com.blackbone.uhccore.game.UhcGameManager;
import com.blackbone.uhccore.gui.UhcMenu;
import com.blackbone.uhccore.world.WorldManager;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
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
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Isolates the match from spectators, late joiners, lobby equipment and pregame head starts. */
public final class MatchProtection implements Listener {
    private final UhcGameManager game;
    private final WorldManager worlds;

    public MatchProtection(UhcGameManager game, WorldManager worlds) {
        this.game = game;
        this.worlds = worlds;
    }

    private boolean proxyOwner(UUID id) {
        return game.isAlive(id) && game.shouldSpectate(id);
    }

    private boolean canAct(Player player) {
        UUID id = player.getUniqueId();
        return MatchAccessPolicy.canAct(game.state(), game.isAlive(id),
            player.getWorld() == worlds.world(), proxyOwner(id));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        if (!canAct(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void placeBlock(BlockPlaceEvent event) {
        if (!canAct(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent event) {
        if (!canAct(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && !canAct(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void pickupArrow(PlayerPickupArrowEvent event) {
        if (!canAct(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void consume(PlayerItemConsumeEvent event) {
        if (!canAct(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void launch(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player player && !canAct(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void interact(PlayerInteractEvent event) {
        // Spectator mode cannot modify blocks, but its custom compass must still
        // receive the interaction so its read-only target menu can open.
        Player player = event.getPlayer();
        if ((player.getGameMode() != GameMode.SPECTATOR || proxyOwner(player.getUniqueId()))
            && !canAct(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void interactEntity(PlayerInteractEntityEvent event) {
        if (!canAct(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void openInventory(InventoryOpenEvent event) {
        if (!(event.getInventory().getHolder() instanceof UhcMenu)
            && event.getPlayer() instanceof Player player && !canAct(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void clickInventory(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof UhcMenu)
            && event.getWhoClicked() instanceof Player player && !canAct(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void dragInventory(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof UhcMenu)
            && event.getWhoClicked() instanceof Player player && !canAct(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void move(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent || game.state() != MatchClock.State.PREGAME
            || !game.isAlive(event.getPlayer().getUniqueId()) || event.getTo() == null) return;
        if (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getY() != event.getTo().getY()
            || event.getFrom().getZ() != event.getTo().getZ()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) {
        if (event.getTo() != null && !MatchAccessPolicy.mayTeleport(game.state(),
            game.isAlive(event.getPlayer().getUniqueId()), event.getTo().getWorld() == worlds.world())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (!MatchAccessPolicy.active(game.state()) || !(event.getEntity() instanceof Player player)) return;
        UUID id = player.getUniqueId();
        boolean participant = game.isAlive(id);
        if (proxyOwner(id) || (game.state() == MatchClock.State.PREGAME && participant)
            || (participant && player.getWorld() != worlds.world())
            || (!participant && player.getWorld() == worlds.world())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void attack(EntityDamageByEntityEvent event) {
        if (!MatchAccessPolicy.active(game.state())) return;
        Entity damager = event.getDamager();
        UUID owner = owner(damager);
        if (owner != null && ((event.getEntity().getWorld() == worlds.world() && !game.isAlive(owner))
            || !MatchAccessPolicy.canAct(game.state(), game.isAlive(owner),
                damager.getWorld() == worlds.world(), proxyOwner(owner)))) event.setCancelled(true);
        if (event.getEntity() instanceof Player victim && victim.getWorld() == worlds.world()
            && (!game.isAlive(victim.getUniqueId()) || proxyOwner(victim.getUniqueId()))) event.setCancelled(true);
    }

    private UUID owner(Entity damager) {
        if (damager instanceof Player player) return player.getUniqueId();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player.getUniqueId();
        }
        if (damager instanceof Tameable tameable) return tameable.getOwnerUniqueId();
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) return player.getUniqueId();
        return null;
    }
}
