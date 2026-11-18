package com.blackbone.uhccore.mechanic;

import com.blackbone.uhccore.config.Settings;
import com.blackbone.uhccore.i18n.LocaleManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** One marked hunter at a time; their elimination grants a deterministic Elo bounty. */
public final class RandomKillerManager {
    private final Settings settings;
    private final LocaleManager locales;
    private UUID target;
    private long lastSelection = -1;

    public RandomKillerManager(Settings settings, LocaleManager locales) { this.settings = settings; this.locales = locales; }
    public UUID target() { return target; }

    public void tick(long elapsed, Set<UUID> alive) {
        if (!settings.bool("mecanicas.random-killer") || alive.size() < 2 || elapsed == 0) return;
        int period = settings.integer("mecanicas.random-killer-periodo-segundos");
        if (elapsed % period != 0 || elapsed == lastSelection) return;
        List<UUID> candidates = new ArrayList<>();
        for (UUID id : alive) if (Bukkit.getPlayer(id) != null) candidates.add(id);
        if (candidates.isEmpty()) return;
        target = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        lastSelection = elapsed;
        Player selected = Bukkit.getPlayer(target);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendActionBar(locales.text(player, "mechanic.random-killer", "player", selected.getName()));
        }
    }

    public int bounty(UUID victim, UUID killer) {
        if (!victim.equals(target)) return 0;
        target = null;
        return killer == null ? 0 : settings.integer("mecanicas.random-killer-recompensa-elo");
    }

    public void reset() { target = null; lastSelection = -1; }
}
