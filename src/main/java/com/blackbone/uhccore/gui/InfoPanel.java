package com.blackbone.uhccore.gui;

import com.blackbone.uhccore.config.Settings;
import com.blackbone.uhccore.domain.BorderPhase;
import com.blackbone.uhccore.domain.PlayerStats;
import com.blackbone.uhccore.game.UhcGameManager;
import com.blackbone.uhccore.i18n.LocaleManager;
import com.blackbone.uhccore.stats.StatsManager;
import com.blackbone.uhccore.world.WorldManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

/** Public, read-only rules / border / personal stats. */
public final class InfoPanel implements Listener {
    private static final class Holder implements UhcMenu {
        final int page;
        Inventory inventory;
        Holder(int page) { this.page = page; }
        @Override public Inventory getInventory() { return inventory; }
    }
    private final Settings settings;
    private final LocaleManager locales;
    private final UhcGameManager game;
    private final WorldManager worlds;
    private final StatsManager stats;

    public InfoPanel(Settings settings, LocaleManager locales, UhcGameManager game, WorldManager worlds, StatsManager stats) {
        this.settings = settings; this.locales = locales; this.game = game; this.worlds = worlds; this.stats = stats;
    }

    public void open(Player player, int page) {
        int clamped = Math.clamp(page, 0, 2);
        Holder holder = new Holder(clamped);
        Inventory inventory = Bukkit.createInventory(holder, 54, GuiItems.title(locales.text(player, "info.title")));
        holder.inventory = inventory;
        GuiItems.background(inventory);
        switch (clamped) {
            case 0 -> rules(player, inventory);
            case 1 -> border(player, inventory);
            case 2 -> personal(player, inventory);
            default -> throw new IllegalStateException("Invalid info page");
        }
        if (clamped > 0) inventory.setItem(45, GuiItems.item(Material.ARROW, locales.text(player, "menu.previous")));
        if (clamped < 2) inventory.setItem(53, GuiItems.item(Material.ARROW, locales.text(player, "menu.next")));
        player.openInventory(inventory);
    }

    private void rules(Player player, Inventory inventory) {
        inventory.setItem(10, GuiItems.item(Material.BOOK, locales.text(player, "info.rules")));
        String[] toggles = {"random-killer", "timebomb", "golden-heads", "scatter-seguro"};
        String[] keys = {"config.random", "config.timebomb", "config.heads", "config.scatter"};
        for (int i = 0; i < toggles.length; i++) {
            boolean on = settings.bool("mecanicas." + toggles[i]);
            inventory.setItem(19 + i, GuiItems.item(on ? Material.LIME_DYE : Material.GRAY_DYE,
                locales.text(player, keys[i]), locales.text(player, on ? "menu.enabled" : "menu.disabled")));
        }
        inventory.setItem(25, GuiItems.item(Material.SHIELD, locales.text(player, "config.combat-window")));
    }

    private void border(Player player, Inventory inventory) {
        double radius = worlds.world() == null ? settings.integer("mundo.radio") : worlds.world().getWorldBorder().getSize() / 2;
        inventory.setItem(10, GuiItems.item(Material.MAP, locales.text(player, "info.border-radius", "radius", String.valueOf((int) Math.ceil(radius)))));
        inventory.setItem(12, GuiItems.item(Material.CLOCK, locales.text(player, "info.elapsed", "seconds", String.valueOf(game.elapsed()))));
        inventory.setItem(14, GuiItems.item(Material.COMPASS, locales.text(player, "info.phase", "index", String.valueOf(game.borderPhase() + 1))));
        int slot = 19;
        for (BorderPhase phase : game.schedule().phases()) {
            if (slot >= 45) break;
            inventory.setItem(slot++, GuiItems.item(Material.REDSTONE,
                locales.text(player, "config.phase", "index", String.valueOf(slot - 19)),
                locales.text(player, "config.phase-detail", "start", String.valueOf(phase.startSeconds()),
                    "duration", String.valueOf(phase.durationSeconds()), "radius", String.valueOf(phase.targetRadius()))));
        }
    }

    private void personal(Player player, Inventory inventory) {
        inventory.setItem(10, GuiItems.head(player, locales.text(player, "info.profile", "player", player.getName())));
        if (!stats.ready(player.getUniqueId())) {
            inventory.setItem(22, GuiItems.item(Material.CLOCK, locales.text(player, "info.profile-loading")));
            return;
        }
        PlayerStats profile = stats.get(player.getUniqueId(), player.getName());
        inventory.setItem(19, GuiItems.item(Material.IRON_SWORD, locales.text(player, "info.kills", "value", String.valueOf(profile.kills()))));
        inventory.setItem(20, GuiItems.item(Material.SKELETON_SKULL, locales.text(player, "info.deaths", "value", String.valueOf(profile.deaths()))));
        inventory.setItem(21, GuiItems.item(Material.BOW, locales.text(player, "info.assists", "value", String.valueOf(profile.assists()))));
        inventory.setItem(22, GuiItems.item(Material.NETHER_STAR, locales.text(player, "info.wins", "value", String.valueOf(profile.wins()))));
        inventory.setItem(23, GuiItems.item(Material.CLOCK, locales.text(player, "info.played", "value", String.valueOf(profile.played()))));
        inventory.setItem(24, GuiItems.item(Material.GOLD_INGOT, locales.text(player, "info.elo", "value", String.valueOf(profile.elo()))));
    }

    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getRawSlot() == 45 && holder.page > 0) open(player, holder.page - 1);
        if (event.getRawSlot() == 53 && holder.page < 2) open(player, holder.page + 1);
    }
}
