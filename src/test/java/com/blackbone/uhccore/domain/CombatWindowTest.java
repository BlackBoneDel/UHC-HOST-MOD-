package com.blackbone.uhccore.domain;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CombatWindowTest {
    @Test void exactTenSecondBoundaryIsNotTagged() {
        long hit = 90_000_000_000L;
        assertFalse(CombatWindow.isTagged(Long.MIN_VALUE, hit));
        assertFalse(CombatWindow.isTagged(hit, hit - 1));
        assertTrue(CombatWindow.isTagged(hit, hit));
        assertTrue(CombatWindow.isTagged(hit, hit + CombatWindow.NANOS - 1));
        assertFalse(CombatWindow.isTagged(hit, hit + CombatWindow.NANOS));
    }

    @Test void proxyExpiresAtTenSecondsAfterLogout() {
        long quit = 50_000_000_000L;
        assertFalse(CombatWindow.proxyExpired(quit, quit + CombatWindow.NANOS - 1));
        assertTrue(CombatWindow.proxyExpired(quit, quit + CombatWindow.NANOS));
    }
}
