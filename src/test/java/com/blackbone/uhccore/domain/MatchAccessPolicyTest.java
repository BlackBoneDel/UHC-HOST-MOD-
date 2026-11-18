package com.blackbone.uhccore.domain;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MatchAccessPolicyTest {
    @Test void countdownBlocksAllParticipantActionsButNotOutsidersInTheLobby() {
        var state = MatchClock.State.PREGAME;
        assertTrue(MatchAccessPolicy.active(state));
        assertFalse(MatchAccessPolicy.canAct(state, true, true));
        assertFalse(MatchAccessPolicy.canAct(state, true, false));
        assertFalse(MatchAccessPolicy.canAct(state, false, true));
        assertTrue(MatchAccessPolicy.canAct(state, false, false));
        assertTrue(MatchAccessPolicy.mayTeleport(state, true, true)); // scatter
        assertFalse(MatchAccessPolicy.mayTeleport(state, true, false));
    }

    @Test void activeMatchCannotBeAffectedByOutsidersOrMaterialsFromOtherWorlds() {
        var state = MatchClock.State.RUNNING;
        assertTrue(MatchAccessPolicy.canAct(state, true, true));
        assertFalse(MatchAccessPolicy.canAct(state, true, false));
        assertFalse(MatchAccessPolicy.canAct(state, false, true));
        assertTrue(MatchAccessPolicy.canAct(state, false, false));
        assertFalse(MatchAccessPolicy.mayTeleport(state, true, false));
        assertTrue(MatchAccessPolicy.mayTeleport(state, false, false));
    }

    @Test void editingAndPostMatchAreNotConstrainedByArenaRules() {
        for (var state : new MatchClock.State[]{MatchClock.State.IDLE, MatchClock.State.FINISHED}) {
            assertFalse(MatchAccessPolicy.active(state));
            assertTrue(MatchAccessPolicy.canAct(state, false, true));
            assertTrue(MatchAccessPolicy.canAct(state, true, false));
            assertTrue(MatchAccessPolicy.mayTeleport(state, true, false));
        }
    }
}
