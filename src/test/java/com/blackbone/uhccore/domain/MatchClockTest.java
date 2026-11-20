package com.blackbone.uhccore.domain;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MatchClockTest {
    @Test void onlyOneMatchCanBeActiveAndPregameCannotBeSkipped() {
        MatchClock clock = new MatchClock();
        assertEquals(MatchClock.State.IDLE, clock.state());
        assertFalse(clock.begin(-1, 1000));
        assertTrue(clock.begin(3, 1000));
        assertFalse(clock.begin(0, 1000));
        assertEquals(3, clock.remaining(1000));
        assertEquals(2, clock.remaining(2000));
        assertFalse(clock.ready(3999));
        assertThrows(IllegalStateException.class, () -> clock.start(3999));
        assertTrue(clock.ready(4000));
        clock.start(4000);
        assertEquals(MatchClock.State.RUNNING, clock.state());
        assertEquals(0, clock.remaining(8000));
        assertEquals(4, clock.elapsed(8000));
        assertFalse(clock.begin(0, 8000));
        clock.finish();
        assertEquals(MatchClock.State.FINISHED, clock.state());
        assertFalse(clock.begin(0, 8000));
        clock.reset();
        assertTrue(clock.begin(0, 9000));
        assertTrue(clock.ready(9000));
    }

    @Test void illegalFinishAndElapsedBeforeStart() {
        MatchClock clock = new MatchClock();
        assertThrows(IllegalStateException.class, clock::finish);
        assertEquals(0, clock.elapsed(1000));
        clock.begin(0, 500);
        clock.start(500);
        assertEquals(0, clock.elapsed(0));
        clock.finish();
        assertThrows(IllegalStateException.class, clock::finish);
    }
}
