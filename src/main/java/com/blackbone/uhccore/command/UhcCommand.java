package com.blackbone.uhccore.command;

import com.blackbone.uhccore.UhcCorePlugin;
import com.blackbone.uhccore.config.Settings;
import com.blackbone.uhccore.game.UhcGameManager;
import com.blackbone.uhccore.i18n.LocaleManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Paper lifecycle command registration, not legacy plugin.yml command maps. */
public final class UhcCommand implements BasicCommand {
    private record PendingBorder(int radius, int seconds, long expiresAt) { }
    private static final UUID CONSOLE_ID = UUID.nameUUIDFromBytes("uhccore:console".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private final Map<String, PendingBorder> confirmations = new ConcurrentHashMap<>();
    private final UhcCorePlugin plugin;

    public UhcCommand(UhcCorePlugin plugin) { this.plugin = plugin; }

    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
            event -> event.registrar().register("uhc", "Competitive UHC core", List.of("uhccore"), this));
    }

    @Override public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        LocaleManager lang = plugin.locales();
        if (args.length == 0) { lang.send(sender, "command.help"); return; }
        String action = args[0].toLowerCase(java.util.Locale.ROOT);
        try {
            switch (action) {
                case "help" -> lang.send(sender, "command.help");
                case "info" -> { Player p = player(sender); if (p != null) plugin.infoPanel().open(p, 0); }
                case "config" -> { if (permission(sender, "uhccore.admin")) { Player p = player(sender); if (p != null) plugin.adminPanel().open(p, 0, 0); } }
                case "staff" -> { if (permission(sender, "uhccore.staff")) { Player p = player(sender); if (p != null) plugin.staffPanel().hub(p, 0); } }
                case "reports" -> { if (permission(sender, "uhccore.staff")) { Player p = player(sender); if (p != null) plugin.staffPanel().reportQueue(p); } }
                case "lang" -> language(sender, args);
                case "start" -> { if (permission(sender, "uhccore.admin")) {
                    if (!plugin.generation().ready()) lang.send(sender, "match.restoring");
                    else if (plugin.generation().progress() != null
                        && (plugin.generation().progress().state() == com.blackbone.uhccore.world.PregenerationManager.State.RUNNING
                            || plugin.generation().progress().state() == com.blackbone.uhccore.world.PregenerationManager.State.PAUSED)) {
                        lang.send(sender, "match.generating");
                    } else if (!plugin.game().begin()) lang.send(sender, "match.already-active");
                } }
                case "reset" -> { if (permission(sender, "uhccore.admin")) {
                    plugin.game().reset(); lang.send(sender, "match.reset");
                } }
                case "freeze" -> freeze(sender, args, true);
                case "unfreeze" -> freeze(sender, args, false);
                case "mute" -> mute(sender, args);
                case "border" -> border(sender, args);
                case "pregenerate" -> pregenerate(sender, args);
                case "seed" -> seed(sender, args);
                case "report" -> report(sender, args);
                case "top" -> top(sender);
                case "rank" -> rank(sender, args);
                case "lobby" -> { Player p = player(sender); if (p != null && !plugin.proxy().sendToLobby(p)) lang.send(sender, "proxy.standalone"); }
                default -> lang.send(sender, "command.help");
            }
        } catch (UhcGameManager.ProfilesLoadingException ex) {
            lang.send(sender, "match.profiles-loading");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            lang.send(sender, "command.invalid", "reason", ex.getMessage() == null ? "-" : ex.getMessage());
        }
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player player) return player;
        plugin.locales().send(sender, "error.player-only");
        return null;
    }

    private boolean permission(CommandSender sender, String node) {
        if (sender.hasPermission(node)) return true;
        plugin.locales().send(sender, "error.permission"); return false;
    }

    private void language(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) return;
        if (args.length < 2 || (!args[1].equals("es") && !args[1].equals("en"))) {
            plugin.locales().send(sender, "command.lang-usage"); return;
        }
        if (!plugin.stats().ready(player.getUniqueId())) {
            plugin.locales().send(sender, "match.profiles-loading"); return;
        }
        plugin.locales().select(player, args[1]);
        notifyWhenDone(player, plugin.stats().language(player, args[1]), "command.lang-set");
    }

    private void freeze(CommandSender sender, String[] args, boolean apply) {
        if (!permission(sender, "uhccore.staff") || args.length < 2) {
            if (args.length < 2) plugin.locales().send(sender, "command.freeze-usage");
            return;
        }
        if (apply) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) { plugin.locales().send(sender, "staff.offline"); return; }
            UUID staffId = sender instanceof Player staff ? staff.getUniqueId() : CONSOLE_ID;
            if (!plugin.freeze().freeze(target, staffId)) {
                plugin.locales().send(sender, "staff.already-frozen", "player", target.getName());
                return;
            }
            plugin.locales().send(sender, "staff.frozen-confirm", "player", target.getName());
            plugin.locales().send(target, "staff.frozen-self");
        } else {
            UUID id = plugin.freeze().snapshot().entrySet().stream()
                .filter(entry -> entry.getKey().toString().equalsIgnoreCase(args[1])
                    || entry.getValue().name().equalsIgnoreCase(args[1]))
                .map(Map.Entry::getKey).findFirst().orElse(null);
            if (id == null || !plugin.freeze().unfreeze(id)) { plugin.locales().send(sender, "staff.not-frozen-error"); return; }
            plugin.locales().send(sender, "staff.unfrozen", "player", args[1]);
            Player target = Bukkit.getPlayer(id);
            if (target != null) plugin.locales().send(target, "staff.unfrozen-self");
        }
    }

    private void mute(CommandSender sender, String[] args) {
        if (!permission(sender, "uhccore.staff")) return;
        Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : null;
        if (target == null) { plugin.locales().send(sender, "staff.offline"); return; }
        boolean muted = plugin.mutes().toggle(target.getUniqueId());
        plugin.locales().send(sender, muted ? "staff.muted" : "staff.unmuted", "player", target.getName());
    }

    private void border(CommandSender sender, String[] args) {
        if (!permission(sender, "uhccore.staff")) return;
        if (args.length >= 2 && args[1].equalsIgnoreCase("confirm")) {
            PendingBorder pending = confirmations.remove(sender.getName());
            if (pending == null || pending.expiresAt() < System.currentTimeMillis()) {
                plugin.locales().send(sender, "command.confirm-expired"); return;
            }
            plugin.game().manualBorder(pending.radius(), pending.seconds());
            plugin.locales().send(sender, "command.border-set", "radius", String.valueOf(pending.radius()));
            return;
        }
        if (args.length != 3) { plugin.locales().send(sender, "command.border-usage"); return; }
        int radius = Integer.parseInt(args[1]); int seconds = Integer.parseInt(args[2]);
        if (!plugin.game().running() || radius < 5 || seconds < 1 || radius >= plugin.worlds().ensureWorld().getWorldBorder().getSize() / 2) {
            throw new IllegalArgumentException("Border must shrink during a running match");
        }
        confirmations.put(sender.getName(), new PendingBorder(radius, seconds, System.currentTimeMillis() + 10000));
        plugin.locales().send(sender, "command.border-confirm", "radius", args[1], "seconds", args[2]);
    }

    private void pregenerate(CommandSender sender, String[] args) {
        if (!permission(sender, "uhccore.admin")) return;
        if (!plugin.game().editable()) { plugin.locales().send(sender, "config.locked"); return; }
        if (args.length < 2) { plugin.locales().send(sender, "command.gen-usage"); return; }
        CompletableFuture<Void> write = switch (args[1]) {
            case "start" -> plugin.generation().start(plugin.settings().integer("mundo.radio"));
            case "pause" -> plugin.generation().pause();
            case "resume" -> plugin.generation().resume();
            case "cancel" -> plugin.generation().cancel();
            default -> null;
        };
        if (write == null) { plugin.locales().send(sender, "command.gen-usage"); return; }
        notifyWhenDone(sender, write, "command.gen-action", "action", args[1]);
    }

    private void seed(CommandSender sender, String[] args) {
        if (!permission(sender, "uhccore.admin")) return;
        if (!plugin.game().editable()) { plugin.locales().send(sender, "config.locked"); return; }
        if (args.length != 2) { plugin.locales().send(sender, "command.seed-usage"); return; }
        long seed = Long.parseLong(args[1]);
        plugin.settings().set("mundo.semilla", seed);
        plugin.locales().send(sender, "command.seed-set", "seed", String.valueOf(seed));
    }

    private void report(CommandSender sender, String[] args) {
        Player reporter = player(sender);
        if (reporter == null) return;
        if (args.length < 3) { plugin.locales().send(sender, "command.report-usage"); return; }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) { plugin.locales().send(sender, "staff.offline"); return; }
        String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        if (reason.length() < 5 || reason.length() > 255 || reporter.getUniqueId().equals(target.getUniqueId())) {
            throw new IllegalArgumentException("Report requires another player and a reason of 5-255 characters");
        }
        notifyWhenDone(reporter, plugin.reports().submit(reporter.getUniqueId(), target.getUniqueId(), reason),
            "command.report-sent");
    }

    private void top(CommandSender sender) {
        plugin.stats().leaderboard(10).whenComplete((list, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (sender instanceof Player player && !player.isOnline()) return;
                if (error != null) { plugin.locales().send(sender, "command.persistence-error"); return; }
                plugin.locales().send(sender, "command.top-title");
                for (int i = 0; i < list.size(); i++) {
                    var profile = list.get(i);
                    plugin.locales().send(sender, "command.top-entry", "position", String.valueOf(i + 1),
                        "player", profile.name(), "elo", String.valueOf(profile.elo()));
                }
            });
        });
    }

    private void notifyWhenDone(CommandSender sender, CompletableFuture<?> action, String success,
                                String... replacements) {
        action.whenComplete((ignored, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (sender instanceof Player player && !player.isOnline()) return;
                if (error != null) plugin.locales().send(sender, "command.persistence-error");
                else plugin.locales().send(sender, success, replacements);
            });
        });
    }

    private void rank(CommandSender sender, String[] args) {
        if (!permission(sender, "uhccore.admin")) return;
        if (args.length != 3) { plugin.locales().send(sender, "command.rank-usage"); return; }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) { plugin.locales().send(sender, "staff.offline"); return; }
        if (!plugin.stats().ready(target.getUniqueId())) {
            plugin.locales().send(sender, "match.profiles-loading"); return;
        }
        notifyWhenDone(sender, plugin.ranks().setRank(target, args[2]), "command.rank-set",
            "player", target.getName(), "rank", args[2]);
    }

    @Override public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length == 1) {
            return List.of("help", "info", "config", "staff", "reports", "lang", "start", "reset",
                "freeze", "unfreeze", "mute", "border", "pregenerate", "seed", "report", "top", "rank", "lobby");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("lang")) return List.of("es", "en");
        if (args.length == 2 && args[0].equalsIgnoreCase("pregenerate")) return List.of("start", "pause", "resume", "cancel");
        if (args.length == 2 && List.of("freeze", "mute", "report", "rank").contains(args[0].toLowerCase(java.util.Locale.ROOT))) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return List.of();
    }
}
