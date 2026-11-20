package com.blackbone.uhccore.hud;

import com.blackbone.uhccore.config.Settings;
import com.blackbone.uhccore.domain.MatchClock;
import com.blackbone.uhccore.game.UhcGameManager;
import com.blackbone.uhccore.i18n.LocaleManager;
import com.blackbone.uhccore.mechanic.RandomKillerManager;
import com.blackbone.uhccore.resource.NegativeSpace;
import com.blackbone.uhccore.world.PregenerationManager;
import com.blackbone.uhccore.world.WorldManager;
import fr.mrmicky.fastboard.adventure.FastBoard;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;

/** Per-player render state, but exactly one scheduler batches all HUD + packet updates. */
public final class UhcHud {
    private final Map<UUID, FastBoard> boards = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Settings settings;
    private final LocaleManager locales;
    private final UhcGameManager game;
    private final WorldManager worlds;
    private final PregenerationManager generation;
    private final RandomKillerManager hunter;
    private final TablistPackets tablist;

    public UhcHud(Settings settings, LocaleManager locales, UhcGameManager game, WorldManager worlds,
                  PregenerationManager generation, RandomKillerManager hunter, TablistPackets tablist) {
        this.settings = settings; this.locales = locales; this.game = game;
        this.worlds = worlds; this.generation = generation; this.hunter = hunter; this.tablist = tablist;
    }

    public void join(Player player) {
        FastBoard board = new FastBoard(player);
        board.updateTitle(Component.text("UHC RANKED", TextColor.fromHexString("#F7C955")));
        boards.put(player.getUniqueId(), board);
        BossBar bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        bars.put(player.getUniqueId(), bar);
        player.showBossBar(bar);
    }

    public void leave(Player player) {
        FastBoard board = boards.remove(player.getUniqueId());
        if (board != null) board.delete();
        BossBar bar = bars.remove(player.getUniqueId());
        if (bar != null) player.hideBossBar(bar);
    }

    public void update(List<Player> players) {
        PregenerationManager.Progress progress = generation.progress();
        for (Player player : players) {
            FastBoard board = boards.get(player.getUniqueId());
            BossBar bar = bars.get(player.getUniqueId());
            if (board == null || bar == null) continue;
            long elapsed = game.elapsed();
            double radius = worlds.world() == null ? settings.integer("mundo.radio")
                : worlds.world().getWorldBorder().getSize() / 2.0;
            String time = String.format("%02d:%02d", elapsed / 60, elapsed % 60);
            board.updateLines(List.of(
                Component.empty(),
                locales.text(player, "hud.alive", "count", String.valueOf(game.alive().size())),
                locales.text(player, "hud.time", "time", time),
                locales.text(player, "hud.border", "radius", String.valueOf((int) Math.ceil(radius))),
                locales.text(player, "hud.center", "x", worlds.world() == null ? "0" : String.valueOf((int) worlds.world().getWorldBorder().getCenter().getX()),
                    "z", worlds.world() == null ? "0" : String.valueOf((int) worlds.world().getWorldBorder().getCenter().getZ())),
                Component.empty(),
                locales.text(player, "hud.footer")));
            if (progress != null && progress.state() == PregenerationManager.State.RUNNING) {
                bar.name(NegativeSpace.shift(-8).append(locales.text(player, "hud.generation",
                    "done", String.valueOf(progress.generated()), "total", String.valueOf(progress.total()),
                    "speed", String.format(java.util.Locale.ROOT, "%.1f", progress.perSecond()),
                    "eta", progress.etaSeconds() < 0 ? "-" : String.valueOf(progress.etaSeconds()))));
                bar.progress(progress.fraction()); bar.color(BossBar.Color.BLUE);
            } else if (game.state() == MatchClock.State.PREGAME) {
                bar.name(locales.text(player, "hud.pregame", "seconds", String.valueOf(game.secondsRemaining())));
                int duration = Math.max(1, settings.integer("pre-game-seconds"));
                bar.progress(Math.clamp(1f - game.secondsRemaining() / (float) duration, 0f, 1f));
                bar.color(BossBar.Color.YELLOW);
            } else if (game.running()) {
                UUID target = hunter.target();
                bar.name(locales.text(player, target == null ? "hud.border-boss" : "hud.hunter-boss",
                    "phase", String.valueOf(game.borderPhase() + 1), "radius", String.valueOf((int) Math.ceil(radius)),
                    "player", target == null ? "" : game.name(target)));
                bar.progress((float) game.schedule().progressAt(elapsed));
                bar.color(target == null ? BossBar.Color.RED : BossBar.Color.PURPLE);
            } else {
                bar.name(locales.text(player, "hud.waiting"));
                bar.progress(0f); bar.color(BossBar.Color.YELLOW);
            }
        }
        tablist.update(players);
    }

    public void close(Iterable<? extends Player> players) { for (Player player : players) leave(player); }
}
