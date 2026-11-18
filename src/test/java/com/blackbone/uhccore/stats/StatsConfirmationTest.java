package com.blackbone.uhccore.stats;

import static org.junit.jupiter.api.Assertions.*;
import com.blackbone.uhccore.db.Database;
import com.blackbone.uhccore.i18n.LocaleManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class StatsConfirmationTest {
    @TempDir Path folder;
    private ServerMock server;
    private Database database;

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock();
        Files.copy(Path.of("src/main/resources/database.yml"), folder.resolve("database.yml"));
        database = new Database(folder.toFile(), Logger.getLogger("confirmation-test"));
    }

    @AfterEach void cleanup() {
        database.close();
        MockBukkit.unmock();
    }

    @Test void failedFutureLeavesRankedScoreUnconfirmedAndExposesLastCommittedScore() {
        var player = server.addPlayer("Alice");
        CompletableFuture<Void> simulatedWrite = new CompletableFuture<>();
        var stats = new StatsManager(database, MockBukkit.createMockPlugin(),
            new LocaleManager(Path.of("src/main/resources/lang").toFile()), ignored -> simulatedWrite);
        var result = stats.victory(player.getUniqueId(), player.getName());
        assertFalse(result.isDone());
        assertFalse(stats.confirmed(player.getUniqueId()));
        assertEquals(1000, stats.get(player.getUniqueId(), player.getName()).elo());

        simulatedWrite.completeExceptionally(new IOException("injected disk failure"));
        assertThrows(CompletionException.class, result::join);
        assertFalse(stats.confirmed(player.getUniqueId()));
        assertEquals(1000, stats.get(player.getUniqueId(), player.getName()).elo());
    }

    @Test void onlyAnAcknowledgedWritePublishesTheNewScore() {
        var player = server.addPlayer("Bob");
        CompletableFuture<Void> simulatedWrite = new CompletableFuture<>();
        var stats = new StatsManager(database, MockBukkit.createMockPlugin(),
            new LocaleManager(Path.of("src/main/resources/lang").toFile()), ignored -> simulatedWrite);
        var result = stats.victory(player.getUniqueId(), player.getName());
        assertEquals(1000, stats.get(player.getUniqueId(), player.getName()).elo());
        simulatedWrite.complete(null);
        result.join();
        assertTrue(stats.confirmed(player.getUniqueId()));
        assertEquals(1032, stats.get(player.getUniqueId(), player.getName()).elo());
    }

    @Test void rejectedDatabaseQueuePropagatesFailureWithoutPublishingAPhantomWin() {
        var player = server.addPlayer("Carol");
        var stats = new StatsManager(database, MockBukkit.createMockPlugin(),
            new LocaleManager(Path.of("src/main/resources/lang").toFile()));
        stats.participation(List.of(player)).join();
        assertTrue(stats.confirmed(player.getUniqueId()));
        database.close(); // deterministic rejection: the DB worker is now shut down
        var rejected = stats.victory(player.getUniqueId(), player.getName());
        assertThrows(CompletionException.class, rejected::join);
        assertFalse(stats.confirmed(player.getUniqueId()));
        assertEquals(0, stats.get(player.getUniqueId(), player.getName()).wins());
        assertEquals(1000, stats.get(player.getUniqueId(), player.getName()).elo());
    }
}
