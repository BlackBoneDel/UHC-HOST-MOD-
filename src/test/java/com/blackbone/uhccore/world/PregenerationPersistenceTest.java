package com.blackbone.uhccore.world;

import static org.junit.jupiter.api.Assertions.*;
import com.blackbone.uhccore.config.Settings;
import com.blackbone.uhccore.db.Database;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.bukkit.plugin.Plugin;

class PregenerationPersistenceTest {
    @TempDir Path folder;
    private ServerMock server;
    private Plugin plugin;
    private WorldManager worlds;
    private Database database;

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        Files.copy(Path.of("src/main/resources/settings.yml"), folder.resolve("settings.yml"));
        Files.copy(Path.of("src/main/resources/database.yml"), folder.resolve("database.yml"));
        Settings settings = new Settings(folder.resolve("settings.yml").toFile(), Logger.getLogger("generation-test"));
        worlds = new WorldManager(settings);
        server.addSimpleWorld(settings.string("mundo.nombre"));
        database = new Database(folder.toFile(), Logger.getLogger("generation-test"));
    }

    @AfterEach void cleanup() {
        database.close();
        MockBukkit.unmock();
    }

    private void awaitRestore(PregenerationManager manager) throws InterruptedException {
        manager.restore();
        for (int i = 0; i < 500 && !manager.ready(); i++) {
            Thread.sleep(10);
            server.getScheduler().performOneTick();
        }
        assertTrue(manager.ready(), "generation checkpoint must be loaded before commands");
    }

    private void awaitConfirmation(PregenerationManager manager) throws InterruptedException {
        for (int i = 0; i < 100 && !manager.progress().confirmed(); i++) {
            Thread.sleep(5);
            server.getScheduler().performOneTick();
        }
        assertTrue(manager.progress().confirmed(), "DB write must be acknowledged on the server thread");
    }

    @Test void rejectedWritePausesAndDoesNotExposeAnUnpersistedCheckpoint() throws Exception {
        PregenerationManager manager = new PregenerationManager(plugin, database, worlds);
        awaitRestore(manager);
        database.close(); // a deterministic RejectedExecutionException from Database.async
        var rejected = manager.start(128);
        assertThrows(CompletionException.class, rejected::join);
        server.getScheduler().performOneTick();
        var progress = manager.progress();
        assertFalse(progress.confirmed());
        assertTrue(progress.failed());
        assertEquals(PregenerationManager.State.PAUSED, progress.state());
        assertEquals(0, progress.generated());
        manager.tick(); // must not request chunks after the DB rejected the initial checkpoint
        assertThrows(CompletionException.class, () -> manager.resume().join());
    }

    @Test void confirmedPauseCanBeLoadedAndResumedByANewManager() throws Exception {
        PregenerationManager first = new PregenerationManager(plugin, database, worlds);
        awaitRestore(first);
        first.start(128).join();
        awaitConfirmation(first);
        first.pause().join();
        awaitConfirmation(first);
        assertEquals(PregenerationManager.State.PAUSED, first.progress().state());
        database.close();
        database = new Database(folder.toFile(), Logger.getLogger("generation-reopen"));
        PregenerationManager restarted = new PregenerationManager(plugin, database, worlds);
        awaitRestore(restarted);
        assertEquals(PregenerationManager.State.PAUSED, restarted.progress().state());
        assertTrue(restarted.progress().confirmed());
        assertEquals(0, restarted.progress().generated());
        restarted.resume().join();
        awaitConfirmation(restarted);
        assertEquals(PregenerationManager.State.RUNNING, restarted.progress().state());
    }
}
