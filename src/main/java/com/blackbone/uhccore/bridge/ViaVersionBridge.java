package com.blackbone.uhccore.bridge;

import com.blackbone.uhccore.config.Settings;
import com.blackbone.uhccore.domain.ProtocolPolicy;
import com.blackbone.uhccore.i18n.LocaleManager;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** ViaVersion classes are never linked on the path used without ViaVersion. */
public final class ViaVersionBridge {
    private final Plugin plugin;
    private final Settings settings;
    private final LocaleManager locales;
    private final boolean via;
    private final boolean backwards;
    private final int nativeProtocol;
    private final Map<UUID, Integer> observed = new ConcurrentHashMap<>();
    private ProtocolPolicy policy;

    public ViaVersionBridge(Plugin plugin, Settings settings, LocaleManager locales) {
        this.plugin = plugin;
        this.settings = settings;
        this.locales = locales;
        via = Bukkit.getPluginManager().getPlugin("ViaVersion") != null;
        backwards = Bukkit.getPluginManager().getPlugin("ViaBackwards") != null;
        nativeProtocol = Bukkit.getUnsafe().getProtocolVersion();
        if (nativeProtocol < 777) throw new IllegalStateException("Paper 26.3 (protocol 777) is required");
        ProtocolPolicy.Mode requested = ProtocolPolicy.Mode.valueOf(settings.string("compatibilidad.modo"));
        if (!via && requested == ProtocolPolicy.Mode.FLEXIBLE) {
            plugin.getLogger().warning("ViaVersion absent: forcing ranked strict mode");
            requested = ProtocolPolicy.Mode.ESTRICTO;
        }
        if (via && !backwards && requested == ProtocolPolicy.Mode.FLEXIBLE) plugin.getLogger().warning("ViaBackwards absent: older clients cannot connect; minimum set to native protocol");
        setPolicy(requested, settings.integer("compatibilidad.protocolo-minimo"));
    }

    public boolean available() { return via; }
    public boolean backwardsAvailable() { return backwards; }
    public int nativeProtocol() { return nativeProtocol; }
    public ProtocolPolicy policy() { return policy; }

    public void setPolicy(ProtocolPolicy.Mode mode, int floor) {
        if (mode == ProtocolPolicy.Mode.FLEXIBLE && !via) throw new IllegalStateException("ViaVersion is required");
        int clamped = Math.max(ProtocolPolicy.OLDEST_SUPPORTED, floor);
        policy = ProtocolPolicy.resolve(mode, clamped, nativeProtocol, via, backwards);
        settings.setProtocolPolicy(policy);
        for (Player player : Bukkit.getOnlinePlayers()) verify(player);
    }

    public void verifyLater(Player player, java.util.function.IntConsumer accepted) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            int version = verify(player);
            if (version != -1) accepted.accept(version);
        }, 4L);
    }

    private int verify(Player player) {
        int version = via ? viaVersion(player) : nativeProtocol;
        observed.put(player.getUniqueId(), version);
        if (!policy.accepts(version)) {
            player.kick(locales.text(player, "compat.kick", "minimum", versionName(policy.minimum()),
                "maximum", versionName(policy.maximum()), "actual", versionName(version)));
            return -1;
        }
        return version;
    }

    // Deliberately isolated: this class can load even when ViaVersion is not installed.
    private int viaVersion(Player player) {
        return com.viaversion.viaversion.api.Via.getAPI().getPlayerVersion(player);
    }

    public String version(Player player) {
        return versionName(observed.getOrDefault(player.getUniqueId(), nativeProtocol));
    }

    private String versionName(int protocol) {
        if (protocol == nativeProtocol) return Bukkit.getMinecraftVersion();
        if (!via) return String.valueOf(protocol);
        return com.viaversion.viaversion.api.protocol.version.ProtocolVersion.getProtocol(protocol).getName();
    }

    public void leave(Player player) { observed.remove(player.getUniqueId()); }
}
