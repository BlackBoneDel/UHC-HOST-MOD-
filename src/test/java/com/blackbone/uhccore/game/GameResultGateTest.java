package com.blackbone.uhccore.game;

import static org.junit.jupiter.api.Assertions.*;
import com.blackbone.uhccore.domain.MatchClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class GameResultGateTest {
    @BeforeEach void setup() { MockBukkit.mock(); }
    @AfterEach void cleanup() { MockBukkit.unmock(); }

    @Test void finishedMatchCannotResetBeforeTheResultHasBeenWritten() throws Exception {
        var game = new UhcGameManager(MockBukkit.createMockPlugin(), null, null, null,
            null, null, null, null, null, null, null, null);
        var field = UhcGameManager.class.getDeclaredField("clock");
        field.setAccessible(true);
        MatchClock clock = (MatchClock) field.get(game);
        assertTrue(clock.begin(0, 0));
        clock.start(1);
        clock.finish();
        assertFalse(game.resultConfirmed());
        assertThrows(IllegalStateException.class, game::reset);
        assertEquals(MatchClock.State.FINISHED, game.state());
    }
}
