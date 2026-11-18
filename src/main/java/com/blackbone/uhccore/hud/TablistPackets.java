package com.blackbone.uhccore.hud;

import com.blackbone.uhccore.i18n.LocaleManager;
import com.blackbone.uhccore.permission.RankManager;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerListHeaderAndFooter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;

/** One delta packet per batch, no scoreboard Teams, retaining signed skin texture properties. */
public final class TablistPackets {
    private record Snapshot(String rank, int ping, org.bukkit.GameMode mode) { }
    private final Map<UUID, Snapshot> previous = new HashMap<>();
    private final LocaleManager locales;
    private final RankManager ranks;

    public TablistPackets(LocaleManager locales, RankManager ranks) { this.locales = locales; this.ranks = ranks; }

    public void update(List<Player> players) {
        Set<UUID> present = new HashSet<>();
        Set<UUID> newViewers = new HashSet<>();
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> added = new ArrayList<>();
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> changed = new ArrayList<>();
        for (Player player : players) {
            UUID uuid = player.getUniqueId(); present.add(uuid);
            String rank = ranks.prefix(player);
            Snapshot current = new Snapshot(rank, Math.max(0, player.getPing()), player.getGameMode());
            Snapshot before = previous.put(uuid, current);
            if (before == null) { added.add(info(player, rank)); newViewers.add(uuid); }
            else if (!before.equals(current)) changed.add(info(player, rank));
        }
        List<UUID> removed = previous.keySet().stream().filter(id -> !present.contains(id)).toList();
        removed.forEach(previous::remove);
        for (Player viewer : players) {
            // Wrappers are channel/version-specific after encoding: never reuse across viewers.
            List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> joinEntries = newViewers.contains(viewer.getUniqueId())
                ? players.stream().map(player -> info(player, ranks.prefix(player))).toList() : added;
            if (!joinEntries.isEmpty()) PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
                new WrapperPlayServerPlayerInfoUpdate(EnumSet.of(
                    WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                    WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME,
                    WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE,
                    WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY,
                    WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_HAT), joinEntries));
            if (!changed.isEmpty()) PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
                new WrapperPlayServerPlayerInfoUpdate(EnumSet.of(
                    WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME,
                    WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE,
                    WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY), changed));
            if (!removed.isEmpty()) PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
                new WrapperPlayServerPlayerInfoRemove(removed));
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
                new WrapperPlayServerPlayerListHeaderAndFooter(locales.text(viewer, "hud.tab-header"),
                    locales.text(viewer, "hud.tab-footer")));
        }
    }

    private WrapperPlayServerPlayerInfoUpdate.PlayerInfo info(Player player, String rank) {
        List<TextureProperty> textures = player.getPlayerProfile().getProperties().stream()
            .filter(property -> property.getName().equals("textures"))
            .map(property -> new TextureProperty(property.getName(), property.getValue(), property.getSignature()))
            .toList();
        UserProfile profile = new UserProfile(player.getUniqueId(), player.getName(), textures);
        Component display = locales.prefix(rank).append(Component.space())
            .append(Component.text(player.getName(), TextColor.fromHexString("#E8EDF3")));
        return new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(profile, true, Math.max(0, player.getPing()),
            com.github.retrooper.packetevents.protocol.player.GameMode.valueOf(player.getGameMode().name()),
            display, null, 0, true);
    }
}
