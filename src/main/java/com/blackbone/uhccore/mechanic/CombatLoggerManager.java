package com.blackbone.uhccore.mechanic;

import com.blackbone.uhccore.config.Settings;
import com.blackbone.uhccore.domain.CombatWindow;
import com.blackbone.uhccore.game.UhcGameManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;

/** 10-second monotonic tag; public-API, damageable zombie proxy (not a packet-only illusion). */
public final class CombatLoggerManager implements Listener {
    private record Tag(long lastHit, UUID attacker) { }
    private record ChunkRef(World world, int x, int z) { }
    private static final class Proxy {
        final UUID victim;
        final String name;
        final Zombie entity;
        final ItemStack[] inventory;
        final ItemStack head;
        final ItemStack goldenHead;
        final ChunkRef chunk;
        final long disconnectedAt;
        UUID lastAttacker;
        Proxy(Player player, Zombie entity, ItemStack goldenHead, UUID lastAttacker) {
            victim = player.getUniqueId(); name = player.getName(); this.entity = entity;
            inventory = player.getInventory().getContents().clone();
            for (int i = 0; i < inventory.length; i++) if (inventory[i] != null) inventory[i] = inventory[i].clone();
            head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setPlayerProfile(player.getPlayerProfile()); head.setItemMeta(meta);
            this.goldenHead = goldenHead;
            this.chunk = new ChunkRef(entity.getWorld(), entity.getLocation().getBlockX() >> 4,
                entity.getLocation().getBlockZ() >> 4);
            this.lastAttacker = lastAttacker;
            disconnectedAt = System.nanoTime();
        }
        List<ItemStack> loot() {
            List<ItemStack> list = new ArrayList<>();
            for (ItemStack item : inventory) if (item != null && !item.getType().isAir()) list.add(item.clone());
            if (goldenHead != null) list.add(goldenHead.clone());
            return list;
        }
    }

    private final Plugin plugin;
    private final Settings settings;
    private final UhcGameManager game;
    private final GoldenHeadManager heads;
    private final Map<UUID, Tag> tags = new HashMap<>();
    private final Map<UUID, Map<UUID, Long>> damageLedger = new HashMap<>();
    private final Map<UUID, Proxy> proxies = new HashMap<>();
    private final Map<UUID, UUID> entities = new HashMap<>();
    // Paper has one plugin ticket per chunk, so proxies in the same chunk must
    // share it until the last proxy is resolved or reclaimed.
    private final Map<ChunkRef, Integer> chunkTickets = new HashMap<>();

    public CombatLoggerManager(Plugin plugin, Settings settings, UhcGameManager game, GoldenHeadManager heads) {
        this.plugin = plugin; this.settings = settings; this.game = game; this.heads = heads;
    }

    public boolean hasProxy(UUID id) { return proxies.containsKey(id); }

    public void forget(UUID id) {
        tags.remove(id);
        damageLedger.remove(id);
    }

    private void retainChunk(ChunkRef chunk) {
        int holders = chunkTickets.getOrDefault(chunk, 0);
        if (holders == 0) chunk.world().addPluginChunkTicket(chunk.x(), chunk.z(), plugin);
        chunkTickets.put(chunk, holders + 1);
    }

    private void releaseChunk(ChunkRef chunk) {
        int holders = chunkTickets.getOrDefault(chunk, 0);
        if (holders <= 1) {
            chunkTickets.remove(chunk);
            chunk.world().removePluginChunkTicket(chunk.x(), chunk.z(), plugin);
        } else chunkTickets.put(chunk, holders - 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damage(EntityDamageByEntityEvent event) {
        if (!game.running() || event.getFinalDamage() <= 0) return;
        Player attacker = attacker(event);
        if (attacker == null || !game.isAlive(attacker.getUniqueId())) return;
        UUID victim;
        if (event.getEntity() instanceof Player player && game.isAlive(player.getUniqueId())) {
            victim = player.getUniqueId();
        } else {
            victim = entities.get(event.getEntity().getUniqueId());
            if (victim == null) return;
            Proxy proxy = proxies.get(victim);
            if (proxy != null) proxy.lastAttacker = attacker.getUniqueId();
        }
        if (victim.equals(attacker.getUniqueId())) return;
        long now = System.nanoTime();
        tags.put(victim, new Tag(now, attacker.getUniqueId()));
        damageLedger.computeIfAbsent(victim, key -> new HashMap<>()).put(attacker.getUniqueId(), now);
    }

    private Player attacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) return player;
        if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    public UUID killerFor(UUID victim) {
        Tag tag = tags.get(victim);
        return tag != null && System.nanoTime() - tag.lastHit < 30_000_000_000L ? tag.attacker : null;
    }

    public Set<UUID> assistsFor(UUID victim, UUID killer) {
        Map<UUID, Long> attackers = damageLedger.remove(victim);
        tags.remove(victim);
        if (attackers == null) return Set.of();
        long now = System.nanoTime();
        Set<UUID> result = new HashSet<>();
        attackers.forEach((uuid, lastAt) -> {
            if (!uuid.equals(killer) && now - lastAt <= 30_000_000_000L) result.add(uuid);
        });
        return result;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void quit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (!game.running() || !game.isAlive(player.getUniqueId()) || hasProxy(player.getUniqueId())) return;
        Tag tag = tags.get(player.getUniqueId());
        if (tag == null || !CombatWindow.isTagged(tag.lastHit, System.nanoTime())) return;
        Zombie proxy = player.getWorld().spawn(player.getLocation(), Zombie.class, zombie -> {
            zombie.setAI(false); zombie.setShouldBurnInDay(false); zombie.setSilent(true);
            zombie.setPersistent(false); zombie.setRemoveWhenFarAway(false);
            zombie.setGlowing(true);
            zombie.customName(net.kyori.adventure.text.Component.text("[Combat Logger] " + player.getName()));
            zombie.setCustomNameVisible(true);
            zombie.getAttribute(Attribute.MAX_HEALTH).setBaseValue(Math.max(20, player.getHealth()));
            zombie.setHealth(Math.max(1, player.getHealth()));
            zombie.getEquipment().setArmorContents(player.getInventory().getArmorContents());
            zombie.getEquipment().setItemInMainHand(player.getInventory().getItemInMainHand());
            zombie.getEquipment().setHelmetDropChance(0);
            zombie.getEquipment().setChestplateDropChance(0);
            zombie.getEquipment().setLeggingsDropChance(0);
            zombie.getEquipment().setBootsDropChance(0);
            zombie.getEquipment().setItemInMainHandDropChance(0);
        });
        ItemStack golden = settings.bool("mecanicas.golden-heads")
            && java.util.concurrent.ThreadLocalRandom.current().nextDouble() < settings.decimal("mecanicas.golden-heads-probabilidad")
            ? heads.create(player) : null;
        Proxy entry = new Proxy(player, proxy, golden, tag.attacker);
        retainChunk(entry.chunk);
        proxies.put(entry.victim, entry);
        entities.put(proxy.getUniqueId(), entry.victim);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void entityDeath(EntityDeathEvent event) {
        UUID victim = entities.remove(event.getEntity().getUniqueId());
        if (victim == null) return;
        Proxy proxy = proxies.remove(victim);
        event.getDrops().clear(); event.setDroppedExp(0);
        if (proxy != null) {
            releaseChunk(proxy.chunk);
            resolve(proxy, true);
        }
    }

    public void tick() {
        long now = System.nanoTime();
        for (Iterator<Proxy> iterator = proxies.values().iterator(); iterator.hasNext();) {
            Proxy proxy = iterator.next();
            if (proxy.entity.isValid() && !proxy.entity.isDead()
                && !CombatWindow.proxyExpired(proxy.disconnectedAt, now)) continue;
            iterator.remove();
            entities.remove(proxy.entity.getUniqueId());
            releaseChunk(proxy.chunk);
            resolve(proxy, settings.string("mecanicas.combat-logger-expiracion").equals("AUTO_DEATH"));
        }
    }

    private void resolve(Proxy proxy, boolean creditAttacker) {
        Location at = proxy.entity.getLocation().clone();
        if (proxy.entity.isValid()) proxy.entity.remove();
        UUID killer = creditAttacker && proxy.lastAttacker != null && game.isAlive(proxy.lastAttacker)
            ? proxy.lastAttacker : null;
        game.proxyDeath(proxy.victim, proxy.name, at, proxy.loot(), proxy.head, killer, assistsFor(proxy.victim, killer));
    }

    public void reclaim(Player player) {
        Proxy proxy = proxies.get(player.getUniqueId());
        if (proxy == null || !game.isAlive(player.getUniqueId())) return;
        if (!proxy.entity.isValid() || proxy.entity.isDead()) return;
        Location location = proxy.entity.getLocation().clone();
        player.setGameMode(GameMode.SPECTATOR);
        player.teleportAsync(location).whenComplete((success, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || error != null || !Boolean.TRUE.equals(success)
                || proxies.get(player.getUniqueId()) != proxy || !proxy.entity.isValid()) return;
            player.getInventory().setContents(proxy.inventory.clone());
            player.setHealth(Math.max(1, Math.min(player.getAttribute(Attribute.MAX_HEALTH).getValue(), proxy.entity.getHealth())));
            player.setGameMode(GameMode.SURVIVAL);
            proxy.entity.remove();
            entities.remove(proxy.entity.getUniqueId());
            proxies.remove(player.getUniqueId());
            releaseChunk(proxy.chunk);
            game.recheckWinner();
        }));
    }

    /** A tag from the previous match must never spawn a proxy in the next one. */
    public void clearRound() {
        if (!proxies.isEmpty()) throw new IllegalStateException("Cannot reset with live combat proxies");
        tags.clear();
        damageLedger.clear();
    }

    public void close() {
        for (Proxy proxy : proxies.values()) {
            if (proxy.entity.isValid()) proxy.entity.remove();
            releaseChunk(proxy.chunk);
        }
        proxies.clear(); entities.clear(); tags.clear(); damageLedger.clear();
    }
}
