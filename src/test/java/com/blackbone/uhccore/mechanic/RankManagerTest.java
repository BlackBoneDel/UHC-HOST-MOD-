package com.blackbone.uhccore.mechanic;

import static org.junit.jupiter.api.Assertions.*;
import com.blackbone.uhccore.db.Database;
import com.blackbone.uhccore.permission.RankManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

class RankManagerTest {
    @TempDir Path databaseDir;
    private ServerMock server;
    private PluginMock plugin;
    private Database database;

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        Files.createDirectories(plugin.getDataFolder().toPath());
        Files.copy(Path.of("src/main/resources/ranks.yml"), plugin.getDataFolder().toPath().resolve("ranks.yml"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(Path.of("src/main/resources/database.yml"), databaseDir.resolve("database.yml"));
        database = new Database(databaseDir.toFile(), Logger.getLogger("test"));
    }
    @AfterEach void cleanup() { database.close(); MockBukkit.unmock(); }

    @Test void fallbackAttachmentsInheritAndAreRemovedOnQuit() {
        var player = server.addPlayer("Member");
        RankManager manager = new RankManager(plugin, database);
        assertTrue(manager.fallback());
        manager.join(player, "jugador");
        assertFalse(manager.hasPermission(player, "uhccore.staff"));
        manager.setRank(player, "staff");
        assertTrue(manager.hasPermission(player, "uhccore.play"));
        assertTrue(manager.hasPermission(player, "uhccore.staff.notify"));
        assertEquals("staff", database.async(connection -> {
            try (var stmt = connection.prepareStatement("SELECT rank_name FROM players WHERE uuid=?")) {
                stmt.setString(1, player.getUniqueId().toString());
                try (var rows = stmt.executeQuery()) {
                    assertTrue(rows.next());
                    return rows.getString(1);
                }
            }
        }).join());
        assertThrows(IllegalArgumentException.class, () -> manager.setRank(player, "ghost"));
        manager.leave(player);
        assertFalse(manager.hasPermission(player, "uhccore.staff.notify"));
    }
}
