package com.blackbone.uhccore.mechanic;

import static org.junit.jupiter.api.Assertions.*;
import com.blackbone.uhccore.i18n.LocaleManager;
import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import java.io.File;
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
        freeze = new UhcFreezeManager(new LocaleManager(new File("src/main/resources/lang")),
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
