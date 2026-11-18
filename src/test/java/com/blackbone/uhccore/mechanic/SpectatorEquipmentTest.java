package com.blackbone.uhccore.mechanic;

import static org.junit.jupiter.api.Assertions.*;
import com.blackbone.uhccore.domain.MatchClock;
import com.blackbone.uhccore.game.UhcGameManager;
import com.blackbone.uhccore.i18n.LocaleManager;
import java.nio.file.Files;
import java.nio.file.Path;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class SpectatorEquipmentTest {
    private ServerMock server;

    @BeforeEach void setup() { server = MockBukkit.mock(); }
    @AfterEach void cleanup() { MockBukkit.unmock(); }

    @Test void aProxyVictimReconnectedBeforeDeathCannotKeepTheOriginalLoot() {
        var player = server.addPlayer();
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
        player.getInventory().setItem(1, new ItemStack(Material.IRON_SWORD));
        player.getEnderChest().setItem(0, new ItemStack(Material.GOLD_INGOT));
        var spectators = new SpectatorManager(MockBukkit.createMockPlugin(), null,
            new LocaleManager(Path.of("src/main/resources/lang").toFile()));

        spectators.retireConnectedParticipant(player);

        assertEquals(GameMode.SPECTATOR, player.getGameMode());
        assertEquals(Material.COMPASS, player.getInventory().getItem(0).getType());
        assertNull(player.getInventory().getItem(1));
        assertNull(player.getEnderChest().getItem(0));
    }

    @Test void lateJoinerRecoversInventoryArmorOffhandAndEnderChestAfterMatch() throws Exception {
        var plugin = MockBukkit.createMockPlugin();
        var game = new UhcGameManager(plugin, null, null, null, null, null, null,
            null, null, null, null, null);
        var field = UhcGameManager.class.getDeclaredField("clock");
        field.setAccessible(true);
        MatchClock clock = (MatchClock) field.get(game);
        assertTrue(clock.begin(0, 0));
        clock.start(1);

        var player = server.addPlayer("LateJoiner");
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
        player.getInventory().setItem(1, new ItemStack(Material.IRON_SWORD));
        player.getInventory().setArmorContents(new ItemStack[] {
            new ItemStack(Material.DIAMOND_BOOTS), null, null, new ItemStack(Material.IRON_HELMET)
        });
        player.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
        player.getEnderChest().setItem(0, new ItemStack(Material.GOLD_INGOT, 7));
        var spectators = new SpectatorManager(plugin, game,
            new LocaleManager(Path.of("src/main/resources/lang").toFile()), ignored -> { });
        spectators.join(new PlayerJoinEvent(player, Component.empty()));
        Path backup = plugin.getDataFolder().toPath().resolve("late-join-inventories")
            .resolve(player.getUniqueId() + ".dat");
        assertTrue(Files.isRegularFile(backup));
        server.getScheduler().performOneTick();
        assertEquals(GameMode.SPECTATOR, player.getGameMode());
        assertEquals(Material.COMPASS, player.getInventory().getItem(0).getType());

        clock.finish();
        spectators.restoreLateJoiners();
        assertEquals(GameMode.SURVIVAL, player.getGameMode());
        assertEquals(3, player.getInventory().getItem(0).getAmount());
        assertEquals(Material.IRON_SWORD, player.getInventory().getItem(1).getType());
        assertEquals(Material.DIAMOND_BOOTS, player.getInventory().getArmorContents()[0].getType());
        assertEquals(Material.IRON_HELMET, player.getInventory().getArmorContents()[3].getType());
        assertEquals(Material.SHIELD, player.getInventory().getItemInOffHand().getType());
        assertEquals(7, player.getEnderChest().getItem(0).getAmount());
        assertFalse(Files.exists(backup));
    }

    @Test void failedPlayerdataSaveRetainsTheBackupForARecoveryAttempt() throws Exception {
        var plugin = MockBukkit.createMockPlugin();
        var game = new UhcGameManager(plugin, null, null, null, null, null, null,
            null, null, null, null, null);
        var field = UhcGameManager.class.getDeclaredField("clock");
        field.setAccessible(true);
        MatchClock clock = (MatchClock) field.get(game);
        assertTrue(clock.begin(0, 0));
        clock.start(1);
        var player = server.addPlayer("Unsaved");
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND));
        var spectators = new SpectatorManager(plugin, game,
            new LocaleManager(Path.of("src/main/resources/lang").toFile()), ignored -> {
                throw new IllegalStateException("injected playerdata failure");
            });
        spectators.join(new PlayerJoinEvent(player, Component.empty()));
        clock.finish();
        spectators.restoreLateJoiners();
        assertTrue(Files.isRegularFile(plugin.getDataFolder().toPath().resolve("late-join-inventories")
            .resolve(player.getUniqueId() + ".dat")));
    }

    @Test void lateJoinBackupSurvivesManagerRestartAndRestoresOnIdleJoin() throws Exception {
        var plugin = MockBukkit.createMockPlugin();
        var locales = new LocaleManager(Path.of("src/main/resources/lang").toFile());
        var game = new UhcGameManager(plugin, null, null, null, null, null, null,
            null, null, null, null, null);
        var field = UhcGameManager.class.getDeclaredField("clock");
        field.setAccessible(true);
        MatchClock clock = (MatchClock) field.get(game);
        assertTrue(clock.begin(0, 0));
        clock.start(1);

        var player = server.addPlayer("Returnee");
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 4));
        player.getInventory().setArmorContents(new ItemStack[] {new ItemStack(Material.IRON_BOOTS), null, null, null});
        player.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
        player.getEnderChest().setItem(0, new ItemStack(Material.EMERALD, 2));
        var first = new SpectatorManager(plugin, game, locales, ignored -> { });
        first.join(new PlayerJoinEvent(player, Component.empty()));
        Path backup = plugin.getDataFolder().toPath().resolve("late-join-inventories")
            .resolve(player.getUniqueId() + ".dat");
        assertTrue(Files.isRegularFile(backup));
        assertEquals(GameMode.SPECTATOR, player.getGameMode());
        // Only the file survives a manager restart; the active match does not.
        var restarted = new SpectatorManager(plugin,
            new UhcGameManager(plugin, null, null, null, null, null, null, null, null, null, null, null),
            locales, ignored -> { });
        restarted.join(new PlayerJoinEvent(player, Component.empty()));
        assertEquals(GameMode.SURVIVAL, player.getGameMode());
        assertEquals(4, player.getInventory().getItem(0).getAmount());
        assertEquals(Material.IRON_BOOTS, player.getInventory().getArmorContents()[0].getType());
        assertEquals(Material.SHIELD, player.getInventory().getItemInOffHand().getType());
        assertEquals(2, player.getEnderChest().getItem(0).getAmount());
        assertFalse(Files.exists(backup));
    }
}
