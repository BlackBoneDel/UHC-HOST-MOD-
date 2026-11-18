package com.blackbone.uhccore.gui;

import com.blackbone.uhccore.bridge.ViaVersionBridge;
import com.blackbone.uhccore.config.Settings;
import com.blackbone.uhccore.db.Database;
import com.blackbone.uhccore.domain.BorderPhase;
import com.blackbone.uhccore.domain.BorderSchedule;
import com.blackbone.uhccore.domain.ProtocolPolicy;
import com.blackbone.uhccore.game.UhcGameManager;
import com.blackbone.uhccore.i18n.LocaleManager;
import com.blackbone.uhccore.world.PregenerationManager;
import com.blackbone.uhccore.world.WorldManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

/** Four tabs. Border phases have page navigation, +/- editing and Q to delete. */
public final class AdminPanel implements Listener {
    private static final String[] TABS = {"menu.general", "menu.mechanics", "menu.network", "menu.world"};
    private final Settings settings;
    private final Database database;
    private final LocaleManager locales;
    private final ViaVersionBridge versions;
    private final UhcGameManager game;
    private final WorldManager worlds;
    private final PregenerationManager generation;

    private static final class Holder implements UhcMenu {
        final int tab;
        final int page;
        Inventory inventory;
        Holder(int tab, int page) { this.tab = tab; this.page = page; }
        @Override public Inventory getInventory() { return inventory; }
    }

    public AdminPanel(Settings settings, Database database, LocaleManager locales, ViaVersionBridge versions,
                      UhcGameManager game, WorldManager worlds, PregenerationManager generation) {
        this.settings = settings; this.database = database; this.locales = locales;
        this.versions = versions; this.game = game; this.worlds = worlds; this.generation = generation;
    }

    public void open(Player player, int tab, int page) {
        if (!player.hasPermission("uhccore.admin")) { locales.send(player, "error.permission"); return; }
        int chosen = Math.clamp(tab, 0, 3);
        Holder holder = new Holder(chosen, Math.max(0, page));
        Inventory inventory = Bukkit.createInventory(holder, 54,
            GuiItems.title(locales.text(player, "menu.admin-title")));
        holder.inventory = inventory;
        GuiItems.background(inventory);
        for (int i = 0; i < 4; i++) {
            inventory.setItem(45 + i * 2, GuiItems.item(i == chosen ? Material.SPECTRAL_ARROW : Material.ARROW,
                locales.text(player, TABS[i])));
        }
        switch (chosen) {
            case 0 -> general(player, inventory);
            case 1 -> mechanics(player, inventory, holder.page);
            case 2 -> network(player, inventory);
            case 3 -> world(player, inventory);
            default -> throw new IllegalStateException("Invalid tab");
        }
        player.openInventory(inventory);
    }

    private void general(Player player, Inventory inventory) {
        inventory.setItem(10, GuiItems.item(Material.CLOCK, locales.text(player, "config.pregame",
            "value", String.valueOf(settings.integer("pre-game-seconds"))), locales.text(player, "menu.left-right")));
        inventory.setItem(12, GuiItems.item(Material.COMPARATOR, locales.text(player, "config.topology",
            "value", settings.topology().name()), locales.text(player, "menu.click-toggle")));
        inventory.setItem(14, GuiItems.item(Material.BOOK, locales.text(player, "config.database",
            "value", database.driver()), locales.text(player, "menu.read-only")));
        inventory.setItem(16, GuiItems.item(Material.NETHER_STAR,
            locales.text(player, "config.match-state", "state", game.state().name())));
    }

    private void mechanics(Player player, Inventory inventory, int page) {
        String[] keys = {"random-killer", "timebomb", "golden-heads", "scatter-seguro"};
        String[] names = {"config.random", "config.timebomb", "config.heads", "config.scatter"};
        for (int i = 0; i < keys.length; i++) {
            boolean enabled = settings.bool("mecanicas." + keys[i]);
            inventory.setItem(10 + i, GuiItems.item(enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                locales.text(player, names[i]), locales.text(player, enabled ? "menu.enabled" : "menu.disabled")));
        }
        inventory.setItem(14, GuiItems.item(Material.IRON_SWORD, locales.text(player, "config.combat-expiry",
            "rule", settings.string("mecanicas.combat-logger-expiracion")), locales.text(player, "menu.click-toggle")));
        inventory.setItem(16, GuiItems.item(Material.SHIELD, locales.text(player, "config.combat-window"), locales.text(player, "menu.read-only")));
        inventory.setItem(19, GuiItems.item(Material.GOLDEN_APPLE, locales.text(player, "config.head-rate",
            "rate", String.format(java.util.Locale.ROOT, "%.0f%%", settings.decimal("mecanicas.golden-heads-probabilidad") * 100)),
            locales.text(player, "menu.left-right")));
        inventory.setItem(21, GuiItems.item(Material.REDSTONE, locales.text(player, "config.bomb-time",
            "seconds", String.valueOf(settings.integer("mecanicas.timebomb-segundos"))), locales.text(player, "menu.left-right")));
        List<BorderPhase> phases = settings.borderSchedule().phases();
        for (int i = 0; i < 7; i++) {
            int index = page * 7 + i;
            if (index >= phases.size()) break;
            BorderPhase phase = phases.get(index);
            inventory.setItem(27 + i, GuiItems.item(Material.BARRIER,
                locales.text(player, "config.phase", "index", String.valueOf(index + 1)),
                locales.text(player, "config.phase-detail", "start", String.valueOf(phase.startSeconds()),
                    "duration", String.valueOf(phase.durationSeconds()), "radius", String.valueOf(phase.targetRadius())),
                locales.text(player, "config.phase-controls")));
        }
        inventory.setItem(35, GuiItems.item(Material.LIME_CONCRETE, locales.text(player, "config.phase-add")));
        if (page > 0) inventory.setItem(36, GuiItems.item(Material.ARROW, locales.text(player, "menu.previous")));
        if ((page + 1) * 7 < phases.size()) inventory.setItem(44, GuiItems.item(Material.ARROW, locales.text(player, "menu.next")));
    }

    private void network(Player player, Inventory inventory) {
        inventory.setItem(10, GuiItems.item(versions.available() ? Material.COMPARATOR : Material.GRAY_DYE,
            locales.text(player, "config.protocol-mode", "mode", versions.policy().mode().name()),
            locales.text(player, versions.available() ? "menu.click-toggle" : "config.via-missing")));
        inventory.setItem(12, GuiItems.item(Material.REPEATER, locales.text(player, "config.protocol-floor",
            "floor", String.valueOf(versions.policy().minimum())), locales.text(player, "menu.left-right"),
            locales.text(player, "config.floor-limit")));
        inventory.setItem(14, GuiItems.item(versions.available() ? Material.LIME_DYE : Material.RED_DYE,
            locales.text(player, versions.available() ? "config.via-online" : "config.via-offline")));
        inventory.setItem(16, GuiItems.item(versions.backwardsAvailable() ? Material.LIME_DYE : Material.GRAY_DYE,
            locales.text(player, "config.backwards", "state", versions.backwardsAvailable() ? "ON" : "OFF")));
        inventory.setItem(22, GuiItems.item(Material.WRITABLE_BOOK, locales.text(player, "config.flex-warning")));
    }

    private void world(Player player, Inventory inventory) {
        inventory.setItem(10, GuiItems.item(Material.GRASS_BLOCK, locales.text(player, "config.world-radius",
            "radius", String.valueOf(settings.integer("mundo.radio"))), locales.text(player, "menu.left-right")));
        long seed = worlds.world() == null ? settings.seed() : worlds.world().getSeed();
        inventory.setItem(12, GuiItems.item(Material.WHEAT_SEEDS, locales.text(player, "config.seed",
            "seed", String.valueOf(seed)), locales.text(player, "config.seed-hint")));
        PregenerationManager.Progress progress = generation.progress();
        inventory.setItem(14, GuiItems.item(Material.DIAMOND_PICKAXE, locales.text(player, "config.gen-start")));
        inventory.setItem(15, GuiItems.item(Material.YELLOW_DYE, locales.text(player, "config.gen-pause")));
        inventory.setItem(16, GuiItems.item(Material.LIME_DYE, locales.text(player, "config.gen-resume")));
        inventory.setItem(23, GuiItems.item(Material.BARRIER, locales.text(player, "config.gen-cancel")));
        inventory.setItem(22, GuiItems.item(Material.MAP, locales.text(player, "config.gen-status",
            "state", progress == null ? "IDLE" : progress.state().name(),
            "done", progress == null ? "0" : String.valueOf(progress.generated()),
            "total", progress == null ? "0" : String.valueOf(progress.total()))));
    }

    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= 54) return;
        if (!player.hasPermission("uhccore.admin")) { player.closeInventory(); return; }
        int slot = event.getRawSlot();
        if (slot >= 45 && slot <= 51 && slot % 2 == 1) { open(player, (slot - 45) / 2, 0); return; }
        if (holder.tab == 1 && slot == 36 && holder.page > 0) { open(player, 1, holder.page - 1); return; }
        if (holder.tab == 1 && slot == 44) { open(player, 1, holder.page + 1); return; }
        if (!game.editable()) { locales.send(player, "config.locked"); return; }
        try {
            switch (holder.tab) {
                case 0 -> generalClick(player, slot, event.getClick());
                case 1 -> mechanicClick(player, slot, event.getClick(), holder.page);
                case 2 -> networkClick(player, slot, event.getClick());
                case 3 -> worldClick(player, slot, event.getClick());
                default -> throw new IllegalStateException("Invalid tab");
            }
        } catch (IllegalArgumentException | IllegalStateException ex) {
            locales.send(player, "menu.invalid-change", "reason", ex.getMessage());
        }
        open(player, holder.tab, holder.page);
    }

    private void generalClick(Player player, int slot, ClickType click) {
        if (slot == 10) settings.set("pre-game-seconds", Math.clamp(settings.integer("pre-game-seconds")
            + (click.isRightClick() ? 15 : -15), 0, 600));
        if (slot == 12) settings.set("topologia", settings.topology() == Settings.Topology.STANDALONE ? "PROXY" : "STANDALONE");
    }

    private void mechanicClick(Player player, int slot, ClickType click, int page) {
        String[] keys = {"random-killer", "timebomb", "golden-heads", "scatter-seguro"};
        if (slot >= 10 && slot <= 13) {
            String key = "mecanicas." + keys[slot - 10]; settings.set(key, !settings.bool(key));
        } else if (slot == 14) {
            settings.set("mecanicas.combat-logger-expiracion",
                settings.string("mecanicas.combat-logger-expiracion").equals("AUTO_DEATH") ? "FORFEIT" : "AUTO_DEATH");
        } else if (slot == 19) {
            double value = settings.decimal("mecanicas.golden-heads-probabilidad") + (click.isRightClick() ? .05 : -.05);
            settings.set("mecanicas.golden-heads-probabilidad", Math.round(Math.clamp(value, 0, 1) * 100) / 100.0);
        } else if (slot == 21) {
            settings.set("mecanicas.timebomb-segundos", Math.clamp(settings.integer("mecanicas.timebomb-segundos")
                + (click.isRightClick() ? 5 : -5), 5, 120));
        } else if (slot == 35) {
            BorderSchedule schedule = settings.borderSchedule();
            BorderPhase last = schedule.phases().isEmpty() ? null : schedule.phases().getLast();
            int oldRadius = last == null ? schedule.initialRadius() : last.targetRadius();
            if (oldRadius <= 5) throw new IllegalArgumentException("Minimum radius reached");
            int begin = last == null ? 900 : Math.toIntExact(last.endSeconds() + 600);
            settings.setPhases(schedule.withPhase(new BorderPhase(begin, 300, Math.max(5, oldRadius / 2))));
        } else if (slot >= 27 && slot <= 33) {
            int index = page * 7 + slot - 27;
            BorderSchedule schedule = settings.borderSchedule();
            if (index >= schedule.phases().size()) return;
            if (click == ClickType.DROP || click == ClickType.CONTROL_DROP) {
                settings.setPhases(schedule.withoutPhase(index)); return;
            }
            BorderPhase phase = schedule.phases().get(index);
            int direction = click.isRightClick() ? 1 : -1;
            BorderPhase changed = click.isShiftClick()
                ? new BorderPhase(Math.max(0, phase.startSeconds() + direction * 60), phase.durationSeconds(), phase.targetRadius())
                : click == ClickType.MIDDLE
                    ? new BorderPhase(phase.startSeconds(), Math.max(1, phase.durationSeconds() + 30), phase.targetRadius())
                    : new BorderPhase(phase.startSeconds(), phase.durationSeconds(), Math.max(5, phase.targetRadius() + direction * 25));
            List<BorderPhase> phases = new ArrayList<>(schedule.phases());
            phases.set(index, changed);
            settings.setPhases(new BorderSchedule(schedule.initialRadius(), phases));
        }
    }

    private void networkClick(Player player, int slot, ClickType click) {
        if (!versions.available()) { locales.send(player, "config.via-missing"); return; }
        if (slot == 10) {
            ProtocolPolicy.Mode mode = versions.policy().mode() == ProtocolPolicy.Mode.ESTRICTO
                ? ProtocolPolicy.Mode.FLEXIBLE : ProtocolPolicy.Mode.ESTRICTO;
            versions.setPolicy(mode, versions.policy().minimum());
        } else if (slot == 12 && versions.policy().mode() == ProtocolPolicy.Mode.FLEXIBLE) {
            int floor = versions.policy().minimum() + (click.isRightClick() ? 1 : -1);
            versions.setPolicy(ProtocolPolicy.Mode.FLEXIBLE, Math.clamp(floor, ProtocolPolicy.OLDEST_SUPPORTED,
                versions.nativeProtocol()));
        }
    }

    private void worldClick(Player player, int slot, ClickType click) {
        if (slot == 10) {
            int proposed = Math.clamp(settings.integer("mundo.radio") + (click.isRightClick() ? 100 : -100), 128, 20000);
            new BorderSchedule(proposed, settings.borderSchedule().phases());
            settings.set("mundo.radio", proposed);
        } else if (slot == 12) {
            settings.set("mundo.semilla", click.isShiftClick() ? 0 : ThreadLocalRandom.current().nextLong());
        } else if (slot == 14) generation.start(settings.integer("mundo.radio"));
        else if (slot == 15) generation.pause();
        else if (slot == 16) generation.resume();
        else if (slot == 23) generation.cancel();
    }
}
