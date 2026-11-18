package com.blackbone.uhccore.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class MenuProtection implements Listener {
    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof UhcMenu) event.setCancelled(true);
    }
}
