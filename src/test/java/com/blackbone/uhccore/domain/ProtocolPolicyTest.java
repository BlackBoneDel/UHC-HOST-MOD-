package com.blackbone.uhccore.domain;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ProtocolPolicyTest {
    @Test void strictUsesExactNativeEvenWhenViaIsPresent() {
        var rule = ProtocolPolicy.resolve(ProtocolPolicy.Mode.ESTRICTO, 755, 777, true, true);
        assertEquals(777, rule.minimum());
        assertEquals(777, rule.maximum());
        assertTrue(rule.accepts(777));
        assertFalse(rule.accepts(776));
        assertFalse(rule.accepts(778));
    }

    @Test void missingViaSilentlyKeepsNativeOnly() {
        var rule = ProtocolPolicy.resolve(ProtocolPolicy.Mode.FLEXIBLE, 755, 777, false, false);
        assertEquals(ProtocolPolicy.Mode.ESTRICTO, rule.mode());
        assertEquals(777, rule.minimum());
    }

    @Test void backwardsIsNecessaryToOpenTheLowerRange() {
        assertEquals(777, ProtocolPolicy.resolve(ProtocolPolicy.Mode.FLEXIBLE, 755, 777, true, false).minimum());
        var flexible = ProtocolPolicy.resolve(ProtocolPolicy.Mode.FLEXIBLE, 760, 777, true, true);
        assertTrue(flexible.accepts(760));
        assertFalse(flexible.accepts(759));
        assertTrue(flexible.accepts(777));
        assertFalse(flexible.accepts(778));
    }

    @Test void configuredFloorClampsToOneSeventeenAndNative() {
        assertEquals(755, ProtocolPolicy.resolve(ProtocolPolicy.Mode.FLEXIBLE, 1, 777, true, true).minimum());
        assertEquals(777, ProtocolPolicy.resolve(ProtocolPolicy.Mode.FLEXIBLE, 900, 777, true, true).minimum());
    }

    @Test void invalidPoliciesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ProtocolPolicy(ProtocolPolicy.Mode.ESTRICTO, 755, 777));
        assertThrows(IllegalArgumentException.class, () -> new ProtocolPolicy(ProtocolPolicy.Mode.FLEXIBLE, 754, 777));
        assertThrows(IllegalArgumentException.class, () -> new ProtocolPolicy(ProtocolPolicy.Mode.FLEXIBLE, 778, 777));
        assertThrows(IllegalArgumentException.class, () -> ProtocolPolicy.resolve(ProtocolPolicy.Mode.ESTRICTO, 755, 754, true, true));
    }
}
