package com.blackbone.uhccore.gui;

import com.blackbone.uhccore.bridge.ViaVersionBridge;
import com.blackbone.uhccore.game.UhcGameManager;
import com.blackbone.uhccore.i18n.LocaleManager;
import com.blackbone.uhccore.mechanic.MuteManager;
import com.blackbone.uhccore.mechanic.UhcFreezeManager;
import com.blackbone.uhccore.stats.ReportManager;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Player-profile skull hub; all inspection inventories are isolated copies. */
public final class StaffPanel implements Listener {
    private static final int PAGE_SIZE = 45;
    private enum View { HUB, ACTIONS, INVENTORY, ENDER, REPORTS }
    private record PendingKick(UUID target, long expiresAt) { }
    private final Map<UUID, PendingKick> kickConfirmations = new ConcurrentHashMap<>();
    private static final class Holder implements UhcMenu {
        final View view;
        final int page;
        final UUID target;
        final List<UUID> players;
        final List<ReportManager.Report> reports;
        Inventory inventory;
        Holder(View view, int page, UUID target, List<UUID> players, List<ReportManager.Report> reports) {
            this.view = view; this.page = page; this.target = target; this.players = players; this.reports = reports;
        }
        @Override public Inventory getInventory() { return inventory; }
    }

    private final Plugin plugin;
    private final LocaleManager locales;
    private final ViaVersionBridge versions;
    private final UhcFreezeManager freeze;
    private final MuteManager mutes;
    private final UhcGameManager game;
    private final ReportManager reports;

    public StaffPanel(Plugin plugin, LocaleManager locales, ViaVersionBridge versions, UhcFreezeManager freeze,
                      MuteManager mutes, UhcGameManager game, ReportManager reports) {
        this.plugin = plugin; this.locales = locales; this.versions = versions;
        this.freeze = freeze; this.mutes = mutes; this.game = game; this.reports = reports;
    }

    public void hub(Player staff, int page) {
        if (!allowed(staff)) return;
        List<UUID> ids = Bukkit.getOnlinePlayers().stream().sorted(Comparator.comparing(Player::getName))
            .map(Player::getUniqueId).toList();
        int p = Math.clamp(page, 0, Math.max(0, (ids.size() - 1) / PAGE_SIZE));
        Holder holder = holder(staff, View.HUB, 54, p, null, ids, List.of(), "staff.title");
        for (int i = 0; i < PAGE_SIZE && p * PAGE_SIZE + i < ids.size(); i++) {
            Player target = Bukkit.getPlayer(ids.get(p * PAGE_SIZE + i));
            if (target == null) continue;
            holder.inventory.setItem(i, GuiItems.head(target,
                Component.text(target.getName(), GuiItems.CYAN),
                locales.text(staff, "staff.version", "version", versions.version(target)),
                locales.text(staff, freeze.isFrozen(target.getUniqueId()) ? "staff.frozen" : "staff.not-frozen"),
                locales.text(staff, "staff.hub-controls")));
        }
        if (p > 0) holder.inventory.setItem(45, GuiItems.item(Material.ARROW, locales.text(staff, "menu.previous")));
        holder.inventory.setItem(49, GuiItems.item(Material.WRITABLE_BOOK, locales.text(staff, "staff.reports")));
        if ((p + 1) * PAGE_SIZE < ids.size()) holder.inventory.setItem(53, GuiItems.item(Material.ARROW, locales.text(staff, "menu.next")));
        staff.openInventory(holder.inventory);
    }

    private Holder holder(Player staff, View view, int size, int page, UUID target, List<UUID> ids,
                          List<ReportManager.Report> reportRows, String title) {
        Holder holder = new Holder(view, page, target, ids, reportRows);
        holder.inventory = Bukkit.createInventory(holder, size, GuiItems.title(locales.text(staff, title)));
        GuiItems.background(holder.inventory);
        return holder;
    }

    private boolean allowed(Player staff) {
        if (!staff.hasPermission("uhccore.staff")) { locales.send(staff, "error.permission"); return false; }
        return true;
    }

    private void actions(Player staff, Player target) {
        Holder holder = holder(staff, View.ACTIONS, 27, 0, target.getUniqueId(), List.of(), List.of(), "staff.actions");
        holder.inventory.setItem(10, GuiItems.item(Material.ENDER_PEARL, locales.text(staff, "staff.teleport")));
        holder.inventory.setItem(11, GuiItems.item(freeze.isFrozen(target.getUniqueId()) ? Material.PACKED_ICE : Material.ICE,
            locales.text(staff, freeze.isFrozen(target.getUniqueId()) ? "staff.unfreeze" : "staff.freeze")));
        holder.inventory.setItem(12, GuiItems.item(Material.CHEST, locales.text(staff, "staff.inspect")));
        holder.inventory.setItem(13, GuiItems.item(Material.ENDER_CHEST, locales.text(staff, "staff.ender")));
        holder.inventory.setItem(14, GuiItems.item(Material.BARRIER, locales.text(staff, "staff.kick"), locales.text(staff, "staff.kick-hint")));
        holder.inventory.setItem(15, GuiItems.item(Material.PAPER, locales.text(staff, mutes.isMuted(target.getUniqueId()) ? "staff.unmute" : "staff.mute")));
        holder.inventory.setItem(16, GuiItems.item(Material.COMPASS, locales.text(staff, "staff.version", "version", versions.version(target))));
        holder.inventory.setItem(22, GuiItems.item(Material.ARROW, locales.text(staff, "menu.back")));
        staff.openInventory(holder.inventory);
    }

    private void inspect(Player staff, Player target, boolean ender) {
        Holder holder = holder(staff, ender ? View.ENDER : View.INVENTORY, 54, 0,
            target.getUniqueId(), List.of(), List.of(), ender ? "staff.ender-title" : "staff.inspect-title");
        ItemStack[] snapshot = ender ? target.getEnderChest().getContents() : target.getInventory().getContents();
        for (int i = 0; i < snapshot.length && i < 45; i++) {
            if (snapshot[i] != null) holder.inventory.setItem(i, snapshot[i].clone());
        }
        holder.inventory.setItem(53, GuiItems.item(Material.ARROW, locales.text(staff, "menu.back")));
        staff.openInventory(holder.inventory);
    }

    public void reportQueue(Player staff) {
        if (!allowed(staff)) return;
        reports.pending().thenAccept(rows -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!staff.isOnline() || !staff.hasPermission("uhccore.staff")) return;
            Holder holder = holder(staff, View.REPORTS, 54, 0, null, List.of(), rows, "staff.reports");
            for (int i = 0; i < rows.size(); i++) {
                ReportManager.Report report = rows.get(i);
                holder.inventory.setItem(i, GuiItems.item(Material.PAPER,
                    locales.text(staff, "staff.report-entry", "id", String.valueOf(report.id())),
                    locales.text(staff, "staff.report-target", "target", report.target().toString()),
                    Component.text(report.reason(), GuiItems.MUTED), locales.text(staff, "staff.resolve")));
            }
            holder.inventory.setItem(53, GuiItems.item(Material.ARROW, locales.text(staff, "menu.back")));
            staff.openInventory(holder.inventory);
        }));
    }

    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player staff) || !allowed(staff)
            || event.getRawSlot() < 0 || event.getRawSlot() >= holder.inventory.getSize()) return;
        int slot = event.getRawSlot();
        if (holder.view == View.HUB) {
            if (slot == 45 && holder.page > 0) { hub(staff, holder.page - 1); return; }
            if (slot == 53) { hub(staff, holder.page + 1); return; }
            if (slot == 49) { reportQueue(staff); return; }
            int index = holder.page * PAGE_SIZE + slot;
            if (slot >= PAGE_SIZE || index >= holder.players.size()) return;
            Player target = Bukkit.getPlayer(holder.players.get(index));
            if (target == null) return;
            if (event.isShiftClick()) toggleFreeze(staff, target);
            else if (event.getClick().isLeftClick()) teleport(staff, target);
            else if (event.getClick() == org.bukkit.event.inventory.ClickType.MIDDLE) inspect(staff, target, false);
            else actions(staff, target);
            return;
        }
        if (holder.view == View.REPORTS) {
            if (slot == 53) { hub(staff, 0); return; }
            if (slot >= holder.reports.size()) return;
            reports.resolve(holder.reports.get(slot).id()).thenAccept(ok -> Bukkit.getScheduler().runTask(plugin,
                () -> { if (staff.isOnline()) reportQueue(staff); }));
            return;
        }
        if (slot == 53 && (holder.view == View.INVENTORY || holder.view == View.ENDER)) {
            Player target = Bukkit.getPlayer(holder.target);
            if (target != null) actions(staff, target); else hub(staff, 0);
            return;
        }
        if (holder.view != View.ACTIONS) return;
        if (slot == 22) { hub(staff, 0); return; }
        Player target = Bukkit.getPlayer(holder.target);
        if (target == null) { locales.send(staff, "staff.offline"); hub(staff, 0); return; }
        switch (slot) {
            case 10 -> teleport(staff, target);
            case 11 -> { toggleFreeze(staff, target); actions(staff, target); }
            case 12 -> inspect(staff, target, false);
            case 13 -> inspect(staff, target, true);
            case 14 -> kick(staff, target);
            case 15 -> {
                boolean muted = mutes.toggle(target.getUniqueId());
                locales.send(staff, muted ? "staff.muted" : "staff.unmuted", "player", target.getName());
                actions(staff, target);
            }
            case 16 -> locales.send(staff, "staff.version", "version", versions.version(target));
            default -> { }
        }
    }

    private void teleport(Player staff, Player target) {
        if (game.running() && game.isAlive(staff.getUniqueId()) && staff.getGameMode() != GameMode.SPECTATOR) {
            locales.send(staff, "staff.teleport-denied"); return;
        }
        staff.closeInventory();
        staff.teleportAsync(target.getLocation());
    }

    public void toggleFreeze(Player staff, Player target) {
        if (freeze.isFrozen(target.getUniqueId())) {
            freeze.unfreeze(target.getUniqueId());
            locales.send(staff, "staff.unfrozen", "player", target.getName());
            locales.send(target, "staff.unfrozen-self");
        } else {
            freeze.freeze(target, staff);
            locales.send(staff, "staff.frozen-confirm", "player", target.getName());
            locales.send(target, "staff.frozen-self");
        }
    }

    private void kick(Player staff, Player target) {
        long now = System.currentTimeMillis();
        PendingKick previous = kickConfirmations.get(staff.getUniqueId());
        if (previous == null || !previous.target.equals(target.getUniqueId()) || previous.expiresAt < now) {
            kickConfirmations.put(staff.getUniqueId(), new PendingKick(target.getUniqueId(), now + 10000));
            locales.send(staff, "staff.kick-confirm", "player", target.getName()); return;
        }
        kickConfirmations.remove(staff.getUniqueId());
        target.kick(locales.text(target, "staff.kicked"));
        locales.send(staff, "staff.kicked-confirm", "player", target.getName());
        hub(staff, 0);
    }
}
