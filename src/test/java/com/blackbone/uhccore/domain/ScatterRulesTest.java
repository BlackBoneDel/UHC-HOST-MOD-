package com.blackbone.uhccore.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScatterRulesTest {
    @Test void safeSurfaceAndSquaredSeparation() {
        var here = new ScatterRules.Spot(0, 70, 0);
        var assigned = List.of(new ScatterRules.Spot(100, 70, 0));
        assertTrue(ScatterRules.acceptable(here, assigned, 100, -64, 320, true, false));
        assertFalse(ScatterRules.acceptable(here, assigned, 101, -64, 320, true, false));
        assertFalse(ScatterRules.acceptable(here, assigned, 0, -64, 320, false, false));
        assertFalse(ScatterRules.acceptable(here, assigned, 0, -64, 320, true, true));
        assertFalse(ScatterRules.acceptable(here, assigned, -1, -64, 320, true, false));
    }

    @Test void rejectsVoidAndCeiling() {
        assertFalse(ScatterRules.acceptable(new ScatterRules.Spot(0, -63, 0), List.of(), 96, -64, 320, true, false));
        assertFalse(ScatterRules.acceptable(new ScatterRules.Spot(0, 318, 0), List.of(), 96, -64, 320, true, false));
        assertTrue(ScatterRules.acceptable(new ScatterRules.Spot(0, 0, 0), List.of(), 96, -64, 320, true, false));
    }
}
