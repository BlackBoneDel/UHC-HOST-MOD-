package com.blackbone.uhccore.gui;

import com.blackbone.uhccore.resource.NegativeSpace;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

/** All UI text uses Adventure; item identities come from slot/holder, never display names. */
public final class GuiItems {
    public static final TextColor GOLD = TextColor.fromHexString("#F7C955");
    public static final TextColor CYAN = TextColor.fromHexString("#57D9E8");
    public static final TextColor MUTED = TextColor.fromHexString("#8B96A8");
    private GuiItems() { }

    public static ItemStack item(Material material, Component name, Component... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(List.of(lore));
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack head(Player player, Component name, Component... lore) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        meta.setPlayerProfile(player.getPlayerProfile());
        meta.displayName(name);
        meta.lore(List.of(lore));
        skull.setItemMeta(meta);
        return skull;
    }

    public static void background(Inventory inventory) {
        ItemStack fill = item(Material.GRAY_STAINED_GLASS_PANE, Component.empty());
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, fill);
    }

    public static Component title(Component name) { return NegativeSpace.shift(-8).append(name); }
}
