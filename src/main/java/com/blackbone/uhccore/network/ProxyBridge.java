package com.blackbone.uhccore.network;

import com.blackbone.uhccore.config.Settings;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** BungeeCord plugin-message protocol, understood by Velocity in compatibility mode. */
public final class ProxyBridge {
    private final Plugin plugin;
    private final Settings settings;
    public ProxyBridge(Plugin plugin, Settings settings) {
        this.plugin = plugin; this.settings = settings;
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "BungeeCord");
    }

    public boolean sendToLobby(Player player) {
        if (settings.topology() != Settings.Topology.PROXY) return false;
        String destination = settings.string("proxy-lobby");
        if (destination.isBlank()) throw new IllegalStateException("Proxy lobby is not configured");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream stream = new DataOutputStream(bytes);
            stream.writeUTF("Connect"); stream.writeUTF(destination);
            player.sendPluginMessage(plugin, "BungeeCord", bytes.toByteArray());
            return true;
        } catch (IOException exception) { throw new IllegalStateException("Proxy message failed", exception); }
    }
}
