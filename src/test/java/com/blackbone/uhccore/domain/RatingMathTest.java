package com.blackbone.uhccore.domain;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RatingMathTest {
    @Test void equalRatingsTransferSixteenPoints() {
        var result = RatingMath.duel(1000, 1000);
        assertEquals(1016, result.winner());
        assertEquals(984, result.loser());
    }

    @Test void upsetGainsMoreThanExpectedWin() {
        var upset = RatingMath.duel(800, 1200);
        var expected = RatingMath.duel(1200, 800);
        assertTrue(upset.winner() - 800 > expected.winner() - 1200);
        assertTrue(upset.loser() >= 0);
    }

    @Test void ratingNeverBecomesNegative() {
        assertEquals(0, RatingMath.duel(1000, 0).loser());
        assertThrows(IllegalArgumentException.class, () -> RatingMath.duel(-1, 1000));
        assertThrows(IllegalArgumentException.class, () -> RatingMath.duel(1000, -1));
    }
}
