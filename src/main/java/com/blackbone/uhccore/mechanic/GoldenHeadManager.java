package com.blackbone.uhccore.mechanic;

import com.blackbone.uhccore.config.Settings;
import com.blackbone.uhccore.i18n.LocaleManager;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Identified by PDC, not by name or texture; safe against renamed vanilla heads. */
public final class GoldenHeadManager implements Listener {
    private final Settings settings;
    private final LocaleManager locales;
    private final UhcFreezeManager freeze;
    private final NamespacedKey key;
    private final Set<UUID> onGround = new HashSet<>();

    public GoldenHeadManager(Plugin plugin, Settings settings, LocaleManager locales, UhcFreezeManager freeze) {
        this.settings = settings; this.locales = locales; this.freeze = freeze;
        key = new NamespacedKey(plugin, "golden_head");
    }

    public ItemStack create(Player victim) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setPlayerProfile(victim.getPlayerProfile());
        meta.displayName(locales.text(victim, "mechanic.golden-head", "player", victim.getName()));
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        head.setItemMeta(meta);
        head.setData(DataComponentTypes.ITEM_MODEL, Key.key("uhccore", "golden_head"));
        head.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addString("golden_head"));
        return head;
    }

    public boolean isGolden(ItemStack item) {
        return item != null && item.getType() == Material.PLAYER_HEAD && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @EventHandler(ignoreCancelled = true)
    public void consume(PlayerInteractEvent event) {
        if (!settings.bool("mecanicas.golden-heads") || freeze.isFrozen(event.getPlayer().getUniqueId())
            || event.getItem() == null || !isGolden(event.getItem())) return;
        if (!event.getAction().isRightClick()) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        EquipmentSlot hand = event.getHand();
        if (hand == null) return;
        ItemStack item = hand == EquipmentSlot.OFF_HAND ? player.getInventory().getItemInOffHand()
            : player.getInventory().getItemInMainHand();
        if (!isGolden(item)) return;
        if (item.getAmount() <= 1) {
            if (hand == EquipmentSlot.OFF_HAND) player.getInventory().setItemInOffHand(null);
            else player.getInventory().setItemInMainHand(null);
        } else item.setAmount(item.getAmount() - 1);
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 200, 1));
        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 2400, 0));
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_BURP, 1, 1);
    }

    @EventHandler public void ground(ItemSpawnEvent event) {
        if (isGolden(event.getEntity().getItemStack())) onGround.add(event.getEntity().getUniqueId());
    }
    @EventHandler public void picked(EntityPickupItemEvent event) { onGround.remove(event.getItem().getUniqueId()); }
    @EventHandler public void despawn(ItemDespawnEvent event) { onGround.remove(event.getEntity().getUniqueId()); }

    public void alertNearby() {
        onGround.removeIf(id -> Bukkit.getEntity(id) == null);
        for (UUID id : onGround) {
            if (!(Bukkit.getEntity(id) instanceof Item item)) continue;
            for (Player player : item.getWorld().getPlayers()) {
                if (player.getLocation().distanceSquared(item.getLocation()) < 144) {
                    player.sendActionBar(locales.text(player, "mechanic.head-nearby"));
                }
            }
        }
    }
}
