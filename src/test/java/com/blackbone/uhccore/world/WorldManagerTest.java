package com.blackbone.uhccore.world;

import static org.junit.jupiter.api.Assertions.*;
import com.blackbone.uhccore.config.Settings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.bukkit.GameRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

class WorldManagerTest {
    @TempDir Path folder;

    @Test void existingArenaEnforcesUhcRulesRatherThanInheritingVanillaRegeneration() throws Exception {
        var server = MockBukkit.mock();
        try {
            Files.copy(Path.of("src/main/resources/settings.yml"), folder.resolve("settings.yml"));
            var settings = new Settings(folder.resolve("settings.yml").toFile(), Logger.getLogger("test"));
            var world = server.addSimpleWorld(settings.string("mundo.nombre"));
            world.setGameRule(GameRules.NATURAL_HEALTH_REGENERATION, true);
            assertSame(world, new WorldManager(settings).ensureWorld());
            assertEquals(Boolean.FALSE, world.getGameRuleValue(GameRules.NATURAL_HEALTH_REGENERATION));
            assertEquals(Boolean.TRUE, world.getGameRuleValue(GameRules.PVP));
            assertEquals(Boolean.TRUE, world.getGameRuleValue(GameRules.IMMEDIATE_RESPAWN));
            assertEquals(Boolean.FALSE, world.getGameRuleValue(GameRules.KEEP_INVENTORY));
        } finally {
            MockBukkit.unmock();
        }
    }
}
