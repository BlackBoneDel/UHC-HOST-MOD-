package com.blackbone.uhccore.bridge;

import static org.junit.jupiter.api.Assertions.*;
import com.blackbone.uhccore.config.Settings;
import com.blackbone.uhccore.i18n.LocaleManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class ViaVersionGateTest {
    @TempDir Path folder;
    private ServerMock server;

    @BeforeEach void setup() { server = MockBukkit.mock(); }
    @AfterEach void cleanup() { MockBukkit.unmock(); }

    @Test void competitiveActionsWaitUntilFourTickProtocolVerification() throws Exception {
        var plugin = MockBukkit.createMockPlugin();
        Files.copy(Path.of("src/main/resources/settings.yml"), folder.resolve("settings.yml"));
        var settings = new Settings(folder.resolve("settings.yml").toFile(), Logger.getLogger("via-test"));
        var bridge = new ViaVersionBridge(plugin, settings,
            new LocaleManager(Path.of("src/main/resources/lang").toFile()), false, false, 777);
        var player = server.addPlayer("Waiting");
        AtomicInteger accepted = new AtomicInteger();
        bridge.verifyLater(player, accepted::set);
        assertEquals("pending", bridge.version(player));

        var from = player.getLocation();
        var movement = new PlayerMoveEvent(player, from, from.clone().add(1, 0, 0));
        bridge.move(movement);
        assertTrue(movement.isCancelled());
        var broken = new BlockBreakEvent(from.getBlock(), player);
        bridge.breakBlock(broken);
        assertTrue(broken.isCancelled());
        var command = new PlayerCommandPreprocessEvent(player, "/uhc start");
        bridge.command(command);
        assertTrue(command.isCancelled());
        Component message = Component.text("hello");
        var chat = new AsyncChatEvent(true, player, new HashSet<Audience>(),
            (source, display, content, viewer) -> content, message, message, null);
        bridge.chat(chat);
        assertTrue(chat.isCancelled());
        for (int tick = 0; tick < 3; tick++) server.getScheduler().performOneTick();
        assertEquals(0, accepted.get());
        var stillBlocked = new PlayerMoveEvent(player, from, from.clone().add(1, 0, 0));
        bridge.move(stillBlocked);
        assertTrue(stillBlocked.isCancelled());
        server.getScheduler().performOneTick();
        assertEquals(777, accepted.get());
        assertNotEquals("pending", bridge.version(player));
        var admitted = new PlayerMoveEvent(player, from, from.clone().add(1, 0, 0));
        bridge.move(admitted);
        assertFalse(admitted.isCancelled());
    }
}
