package com.blackbone.uhccore.mechanic;

import static org.junit.jupiter.api.Assertions.*;
import com.blackbone.uhccore.i18n.LocaleManager;
import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.File;
import java.util.HashSet;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class UhcFreezeManagerTest {
    private ServerMock server;
    private PlayerMock staff;
    private PlayerMock victim;
    private UhcFreezeManager freeze;

    @BeforeEach void setup() {
        server = MockBukkit.mock();
        staff = server.addPlayer("Moderator");
        victim = server.addPlayer("FrozenPlayer");
        freeze = new UhcFreezeManager(MockBukkit.createMockPlugin(),
            new LocaleManager(new File("src/main/resources/lang")),
            (player, node) -> player.getUniqueId().equals(staff.getUniqueId()));
    }

    @AfterEach void cleanup() { MockBukkit.unmock(); }

    @Test void translationRotationAllowedPositionAndJumpBlocked() {
        assertTrue(freeze.freeze(victim, staff));
        assertFalse(freeze.freeze(victim, staff));
        Location start = victim.getLocation();
        Location look = start.clone(); look.setYaw(90); look.setPitch(-20);
        var turn = new PlayerMoveEvent(victim, start, look);
        freeze.move(turn);
        assertFalse(turn.isCancelled());
        var movement = new PlayerMoveEvent(victim, start, start.clone().add(0, 1, 0));
        freeze.move(movement);
        assertTrue(movement.isCancelled());
        var jump = new PlayerJumpEvent(victim, start, start.clone().add(0, 1, 0));
        freeze.jump(jump);
        assertTrue(jump.isCancelled());
        assertTrue(freeze.unfreeze(victim.getUniqueId()));
        assertFalse(freeze.unfreeze(victim.getUniqueId()));
        var walking = new PlayerMoveEvent(victim, start, start.clone().add(1, 0, 0));
        freeze.move(walking);
        assertFalse(walking.isCancelled());
    }

    @Test void frozenChatIsPrefixedAndSentToEveryOnlinePlayer() {
        PlayerMock publicPlayer = server.addPlayer("PublicPlayer");
        freeze.freeze(victim, staff);
        Component text = Component.text("please help");
        var event = new AsyncChatEvent(true, victim, new HashSet<Audience>(),
            (source, display, message, viewer) -> message, text, text, null);

        freeze.chat(event);
        assertTrue(event.isCancelled());
        server.getScheduler().performOneTick();

        Component received = staff.nextComponentMessage();
        assertNotNull(received);
        assertEquals("[Frozen] FrozenPlayer: please help",
            PlainTextComponentSerializer.plainText().serialize(received));
        assertEquals(received, victim.nextComponentMessage());
        assertEquals(received, publicPlayer.nextComponentMessage());
    }

    @Test void onlyMessagesToOnlineStaffRemainAvailable() {
        freeze.freeze(victim, staff);
        var allowed = new PlayerCommandPreprocessEvent(victim, "/msg Moderator please help");
        freeze.command(allowed);
        assertFalse(allowed.isCancelled());
        var blocked = new PlayerCommandPreprocessEvent(victim, "/spawn");
        freeze.command(blocked);
        assertTrue(blocked.isCancelled());
        var self = new PlayerCommandPreprocessEvent(victim, "/tell FrozenPlayer hi");
        freeze.command(self);
        assertTrue(self.isCancelled());
    }
}
