package com.blackbone.uhccore.mechanic;

import static org.junit.jupiter.api.Assertions.*;
import com.blackbone.uhccore.config.Settings;
import com.blackbone.uhccore.i18n.LocaleManager;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class RandomKillerManagerTest {
    @TempDir Path directory;
    private ServerMock server;
    private RandomKillerManager hunter;
    private Settings settings;

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock();
        Files.copy(Path.of("src/main/resources/settings.yml"), directory.resolve("settings.yml"));
        settings = new Settings(directory.resolve("settings.yml").toFile(), Logger.getLogger("test"));
        hunter = new RandomKillerManager(settings, new LocaleManager(new File("src/main/resources/lang")));
    }
    @AfterEach void cleanup() { MockBukkit.unmock(); }

    @Test void picksOnlyALivingPlayerAtTheConfiguredPeriodAndAwardsOneBounty() {
        var first = server.addPlayer("First");
        var second = server.addPlayer("Second");
        Set<java.util.UUID> alive = Set.of(first.getUniqueId(), second.getUniqueId());
        hunter.tick(899, alive);
        assertNull(hunter.target());
        hunter.tick(900, alive);
        java.util.UUID target = hunter.target();
        assertNotNull(target);
        assertTrue(alive.contains(target));
        hunter.tick(900, alive);
        assertEquals(target, hunter.target());
        var attacker = target.equals(first.getUniqueId()) ? second : first;
        assertEquals(settings.integer("mecanicas.random-killer-recompensa-elo"), hunter.bounty(target, attacker.getUniqueId()));
        assertNull(hunter.target());
        hunter.reset();
        hunter.tick(900, alive);
        assertNotNull(hunter.target());
    }

    @Test void noRewardOnUncreditedDeath() {
        var first = server.addPlayer("First");
        var second = server.addPlayer("Second");
        hunter.tick(900, Set.of(first.getUniqueId(), second.getUniqueId()));
        assertEquals(0, hunter.bounty(hunter.target(), null));
        assertNull(hunter.target());
    }
}
