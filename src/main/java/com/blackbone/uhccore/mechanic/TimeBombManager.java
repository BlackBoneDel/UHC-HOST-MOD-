package com.blackbone.uhccore.mechanic;

import com.blackbone.uhccore.config.Settings;
import com.blackbone.uhccore.i18n.LocaleManager;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;

/** Visual displays, no block edits. Loot and XP are released only after a terrain-safe explosion. */
public final class TimeBombManager {
    private record Tomb(Location location, List<ItemStack> loot, int experience, TextDisplay label,
                        ItemDisplay head, long detonatesAt, int lastAlert) { }
    private final Settings settings;
    private final LocaleManager locales;
    private final List<Tomb> tombs = new ArrayList<>();

    public TimeBombManager(Settings settings, LocaleManager locales) { this.settings = settings; this.locales = locales; }

    public void create(String name, Location location, ItemStack skull, List<ItemStack> loot, int experience) {
        List<ItemStack> copy = loot.stream().filter(item -> item != null && !item.getType().isAir()).map(ItemStack::clone).toList();
        if (!settings.bool("mecanicas.timebomb")) {
            release(location, copy, experience); return;
        }
        TextDisplay text = location.getWorld().spawn(location.clone().add(0, 2.2, 0), TextDisplay.class,
            display -> {
                display.text(Component.text("✦ " + name + " ✦", TextColor.fromHexString("#F7C955")));
                display.setPersistent(false);
                display.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            });
        ItemDisplay head = location.getWorld().spawn(location.clone().add(0, 1.2, 0), ItemDisplay.class,
            display -> { display.setItemStack(skull); display.setPersistent(false); });
        tombs.add(new Tomb(location.clone(), copy, experience, text, head,
            System.nanoTime() + settings.integer("mecanicas.timebomb-segundos") * 1_000_000_000L, -1));
    }

    public void tick() {
        long now = System.nanoTime();
        for (int i = 0; i < tombs.size(); i++) {
            Tomb tomb = tombs.get(i);
            long remaining = tomb.detonatesAt - now;
            if (remaining <= 0) {
                tomb.label.remove(); tomb.head.remove();
                tomb.location.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, tomb.location, 1);
                tomb.location.getWorld().playSound(tomb.location, Sound.ENTITY_GENERIC_EXPLODE, 1, 1);
                tomb.location.getWorld().createExplosion(tomb.location, 2.0f, false, false);
                release(tomb.location, tomb.loot, tomb.experience);
                tombs.remove(i--);
            } else {
                int seconds = (int) ((remaining + 999_999_999L) / 1_000_000_000L);
                if (seconds <= 5 && seconds != tomb.lastAlert) {
                    for (Player player : tomb.location.getWorld().getPlayers()) {
                        if (player.getLocation().distanceSquared(tomb.location) <= 256) {
                            player.sendActionBar(locales.text(player, "mechanic.timebomb-warning", "seconds", String.valueOf(seconds)));
                        }
                    }
                    tombs.set(i, new Tomb(tomb.location, tomb.loot, tomb.experience, tomb.label, tomb.head, tomb.detonatesAt, seconds));
                }
            }
        }
    }

    private void release(Location location, List<ItemStack> loot, int experience) {
        for (ItemStack stack : loot) location.getWorld().dropItemNaturally(location, stack);
        if (experience > 0) location.getWorld().spawn(location, ExperienceOrb.class, orb -> orb.setExperience(experience));
    }

    public void close() {
        for (Tomb tomb : tombs) {
            tomb.label.remove(); tomb.head.remove();
            release(tomb.location, tomb.loot, tomb.experience);
        }
        tombs.clear();
    }
}
