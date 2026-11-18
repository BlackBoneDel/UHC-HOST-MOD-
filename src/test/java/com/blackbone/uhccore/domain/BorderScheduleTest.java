package com.blackbone.uhccore.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class BorderScheduleTest {
    private final BorderSchedule schedule = new BorderSchedule(1000,
        List.of(new BorderPhase(600, 200, 500), new BorderPhase(1200, 200, 100)));

    @Test void interpolatesOnlyInsidePhases() {
        assertEquals(1000, schedule.radiusAt(0));
        assertEquals(1000, schedule.radiusAt(599));
        assertEquals(750, schedule.radiusAt(700));
        assertEquals(500, schedule.radiusAt(800));
        assertEquals(500, schedule.radiusAt(1199));
        assertEquals(300, schedule.radiusAt(1300));
        assertEquals(100, schedule.radiusAt(1400));
    }

    @Test void progressIsClampedAndIndexOnlyMovesForward() {
        assertEquals(-1, schedule.activeIndex(599));
        assertEquals(0, schedule.activeIndex(600));
        assertEquals(1, schedule.activeIndex(1200));
        assertEquals(0, schedule.progressAt(0));
        assertEquals(.5, schedule.progressAt(700));
        assertEquals(1, schedule.progressAt(1400));
    }

    @Test void rejectsExpansionOverlapAndInvalidDuration() {
        assertThrows(IllegalArgumentException.class,
            () -> new BorderSchedule(1000, List.of(new BorderPhase(600, 200, 500), new BorderPhase(799, 20, 100))));
        assertThrows(IllegalArgumentException.class, () -> new BorderSchedule(1000, List.of(new BorderPhase(0, 1, 1000))));
        assertThrows(IllegalArgumentException.class, () -> new BorderSchedule(9, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new BorderPhase(0, 0, 5));
        assertThrows(IllegalArgumentException.class, () -> new BorderPhase(-1, 1, 5));
        assertThrows(IllegalArgumentException.class, () -> new BorderPhase(1, 1, 4));
    }

    @Test void additionsAndDeletionsKeepOriginalImmutable() {
        var shorter = schedule.withoutPhase(1);
        assertEquals(1, shorter.phases().size());
        assertEquals(2, schedule.phases().size());
        assertEquals(2, shorter.withPhase(new BorderPhase(1200, 200, 50)).phases().size());
        assertThrows(IndexOutOfBoundsException.class, () -> schedule.withoutPhase(2));
        assertThrows(IllegalArgumentException.class, () -> shorter.withPhase(new BorderPhase(700, 200, 400)));
    }
}
