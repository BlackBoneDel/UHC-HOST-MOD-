package com.blackbone.uhccore.gui;

import static org.junit.jupiter.api.Assertions.*;
import com.blackbone.uhccore.config.Settings;
import com.blackbone.uhccore.game.UhcGameManager;
import com.blackbone.uhccore.i18n.LocaleManager;
import com.blackbone.uhccore.world.WorldManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class ReadOnlyMenuTest {
    @TempDir Path folder;
    private ServerMock server;

    @BeforeEach void setup() { server = MockBukkit.mock(); }
    @AfterEach void cleanup() { MockBukkit.unmock(); }

    @Test void infoPagesCancelClicksAndMenuDragsWithoutMovingItems() throws Exception {
        Files.copy(Path.of("src/main/resources/settings.yml"), folder.resolve("settings.yml"));
        var settings = new Settings(folder.resolve("settings.yml").toFile(), Logger.getLogger("gui-test"));
        var plugin = MockBukkit.createMockPlugin();
        var player = server.addPlayer("Viewer");
        var locales = new LocaleManager(Path.of("src/main/resources/lang").toFile());
        var worlds = new WorldManager(settings);
        var game = new UhcGameManager(plugin, settings, worlds, null, null, null, locales,
            null, null, null, null, null);
        var panel = new InfoPanel(settings, locales, game, worlds, null);
        server.getPluginManager().registerEvents(panel, plugin);
        server.getPluginManager().registerEvents(new MenuProtection(), plugin);

        panel.open(player, 0);
        var original = player.getOpenInventory().getTopInventory();
        assertEquals(Material.LIME_DYE, original.getItem(19).getType());
        assertTrue(player.simulateInventoryClick(19).isCancelled());
        assertSame(original, player.getOpenInventory().getTopInventory());
        assertEquals(Material.LIME_DYE, original.getItem(19).getType());
        var drag = new InventoryDragEvent(player.getOpenInventory(), new ItemStack(Material.DIAMOND),
            new ItemStack(Material.DIAMOND, 2), false, Map.of(19, new ItemStack(Material.DIAMOND)));
        server.getPluginManager().callEvent(drag);
        assertTrue(drag.isCancelled());
        assertTrue(player.simulateInventoryClick(53).isCancelled());
        assertNotSame(original, player.getOpenInventory().getTopInventory());
    }
}
