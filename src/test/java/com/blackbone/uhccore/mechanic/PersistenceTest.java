package com.blackbone.uhccore.mechanic;

import static org.junit.jupiter.api.Assertions.*;
import com.blackbone.uhccore.db.Database;
import com.blackbone.uhccore.stats.ReportManager;
import com.blackbone.uhccore.stats.StatsManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class PersistenceTest {
    @TempDir Path directory;
    private ServerMock server;
    private Database database;

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock();
        Files.copy(Path.of("src/main/resources/database.yml"), directory.resolve("database.yml"));
        database = new Database(directory.toFile(), Logger.getLogger("test"));
    }

    @AfterEach void cleanup() {
        database.close();
        MockBukkit.unmock();
    }

    @Test void earlyHudReadCannotReplaceAStoredProfile() throws Exception {
        var alice = server.addPlayer("Alice");
        var plugin = MockBukkit.createMockPlugin();
        var locales = new com.blackbone.uhccore.i18n.LocaleManager(Path.of("src/main/resources/lang").toFile());
        StatsManager first = new StatsManager(database, plugin, locales);
        first.participation(List.of(alice));
        first.victory(alice.getUniqueId(), alice.getName());
        first.leaderboard(10).join(); // all writes have committed before the new session

        StatsManager reconnect = new StatsManager(database, plugin, locales);
        assertEquals(1000, reconnect.get(alice.getUniqueId(), alice.getName()).elo());
        assertFalse(reconnect.ready(alice.getUniqueId()));
        reconnect.join(alice, profile -> { });
        for (int i = 0; i < 300 && !reconnect.ready(alice.getUniqueId()); i++) {
            Thread.sleep(5);
            server.getScheduler().performOneTick();
        }
        assertTrue(reconnect.ready(alice.getUniqueId()));
        assertEquals(1032, reconnect.get(alice.getUniqueId(), alice.getName()).elo());
        assertEquals(1, reconnect.get(alice.getUniqueId(), alice.getName()).played());
        reconnect.leave(alice, false);
        assertFalse(reconnect.ready(alice.getUniqueId()));
    }

    @Test void recordsStatsReportsAndRatingsWithoutARealServer() {
        var alice = server.addPlayer("Alice");
        var bob = server.addPlayer("Bob");
        StatsManager stats = new StatsManager(database, MockBukkit.createMockPlugin(),
            new com.blackbone.uhccore.i18n.LocaleManager(Path.of("src/main/resources/lang").toFile()));
        stats.participation(List.of(alice, bob));
        stats.death(bob.getUniqueId(), bob.getName(), alice.getUniqueId(), alice.getName(), Set.of(), 15);
        stats.victory(alice.getUniqueId(), alice.getName());
        var top = stats.leaderboard(10).join();
        assertEquals("Alice", top.getFirst().name());
        assertEquals(1, top.getFirst().kills());
        assertEquals(1, top.getFirst().wins());
        assertEquals(1, top.getFirst().played());
        assertEquals(1063, top.getFirst().elo());
        assertEquals(1, top.get(1).deaths());
        assertEquals(984, top.get(1).elo());

        ReportManager reports = new ReportManager(database);
        reports.submit(bob.getUniqueId(), alice.getUniqueId(), "possible unfair combat").join();
        var pending = reports.pending().join();
        assertEquals(1, pending.size());
        assertEquals(alice.getUniqueId(), pending.getFirst().target());
        assertTrue(reports.resolve(pending.getFirst().id()).join());
        assertFalse(reports.resolve(pending.getFirst().id()).join());
        assertTrue(reports.pending().join().isEmpty());
    }
}
