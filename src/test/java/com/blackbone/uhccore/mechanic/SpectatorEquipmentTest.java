package com.blackbone.uhccore.mechanic;

import static org.junit.jupiter.api.Assertions.*;
import com.blackbone.uhccore.i18n.LocaleManager;
import java.nio.file.Path;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
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
}
