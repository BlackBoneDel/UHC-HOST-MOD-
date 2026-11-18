package com.blackbone.uhccore.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerStatsTest {
    @Test void countersAndEloAreImmutable() {
        var initial = PlayerStats.fresh(UUID.randomUUID(), "Player");
        var played = initial.participation();
        var killer = played.kill(1016);
        var assisted = killer.assist();
        var winner = assisted.victory();
        assertEquals(0, initial.played());
        assertEquals(1000, initial.elo());
        assertEquals(1, played.played());
        assertEquals(1, winner.kills());
        assertEquals(1, winner.assists());
        assertEquals(1, winner.wins());
        assertEquals(1048, winner.elo());
        assertEquals(1, played.death(984).deaths());
        assertEquals(984, played.death(984).elo());
        var renamed = winner.renamed("CurrentName");
        assertEquals("CurrentName", renamed.name());
        assertEquals(1048, renamed.elo());
        assertEquals(1, renamed.kills());
        assertEquals("Player", winner.name());
    }

    @Test void invalidDataRejected() {
        assertThrows(IllegalArgumentException.class, () -> PlayerStats.fresh(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> new PlayerStats(UUID.randomUUID(), null, 0, 0, 0, 0, 0, 1000));
        assertThrows(IllegalArgumentException.class, () -> PlayerStats.fresh(UUID.randomUUID(), "x").death(-1));
    }
}
