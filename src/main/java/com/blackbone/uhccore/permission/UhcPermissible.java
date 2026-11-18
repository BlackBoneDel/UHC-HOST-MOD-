package com.blackbone.uhccore.permission;

import org.bukkit.entity.Player;

/** Uses Bukkit's Permissible contract, including LuckPerms when installed. */
@FunctionalInterface
public interface UhcPermissible {
    boolean hasPermission(Player player, String permission);
}
