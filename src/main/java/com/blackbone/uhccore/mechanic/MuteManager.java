package com.blackbone.uhccore.mechanic;

import com.blackbone.uhccore.i18n.LocaleManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/** Staff mute is memory-only. Chat events may run off-thread: only a concurrent set is read there. */
public final class MuteManager implements Listener {
    private final Set<UUID> muted = ConcurrentHashMap.newKeySet();
    private final Plugin plugin;
    private final LocaleManager locales;
    public MuteManager(Plugin plugin, LocaleManager locales) { this.plugin = plugin; this.locales = locales; }

    public boolean toggle(UUID id) { if (muted.remove(id)) return false; muted.add(id); return true; }
    public boolean isMuted(UUID id) { return muted.contains(id); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void chat(AsyncChatEvent event) {
        if (!isMuted(event.getPlayer().getUniqueId())) return;
        event.setCancelled(true);
        var player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> { if (player.isOnline()) locales.send(player, "staff.muted-message"); });
    }
}
