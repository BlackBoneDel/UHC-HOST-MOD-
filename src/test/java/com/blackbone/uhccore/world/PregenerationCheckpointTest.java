package com.blackbone.uhccore.world;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PregenerationCheckpointTest {
    @Test void countsOnlyChunksWithinTheCircularBorder() {
        var job = new PregenerationManager.Job("uhc", 8, 0, 0,
            PregenerationManager.State.RUNNING, System.currentTimeMillis());
        int expected = 0;
        for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) {
            if (x * x + z * z <= 64) expected++;
        }
        assertEquals(expected, job.total);
        assertTrue(job.total < job.side * job.side);
        assertEquals(0, job.checkpoint);
        assertEquals(0, job.committed);
    }

    @Test void checkpointOnlyAdvancesPastContiguouslyFinishedIndices() {
        var job = new PregenerationManager.Job("uhc", 8, 0, 0,
            PregenerationManager.State.RUNNING, System.currentTimeMillis());
        for (int index = 0; index < 11; index++) {
            if (index != 4) PregenerationManager.mark(job, index);
        }
        assertEquals(4, job.checkpoint);
        assertEquals(0, job.committed);
        PregenerationManager.mark(job, 4);
        assertEquals(11, job.checkpoint);
        assertEquals(1, job.committed); // x = -8, z = 0 is the only inside cell of this row
    }

    @Test void stalledChunkCannotGrowThePendingCheckpointSetWithoutBound() {
        var job = new PregenerationManager.Job("uhc", 64, 0, 0,
            PregenerationManager.State.RUNNING, System.currentTimeMillis());
        job.cursor = PregenerationManager.MAX_PENDING_INDICES;
        // The first request is stalled; later completions cannot advance the
        // contiguous checkpoint, and no more than 2048 cells may be scanned.
        for (int index = 1; index < job.cursor; index++) PregenerationManager.mark(job, index);
        assertEquals(0, job.checkpoint);
        assertEquals(PregenerationManager.MAX_PENDING_INDICES - 1, job.done.size());
        assertFalse(PregenerationManager.hasCheckpointRoom(job));
        PregenerationManager.mark(job, 0);
        assertEquals(PregenerationManager.MAX_PENDING_INDICES, job.checkpoint);
        assertTrue(job.done.isEmpty());
        assertTrue(PregenerationManager.hasCheckpointRoom(job));
    }

    @Test void corruptOrIncompleteSavedCheckpointsAreRejected() {
        long now = System.currentTimeMillis();
        assertThrows(IllegalArgumentException.class, () -> new PregenerationManager.Job("uhc", 7, 0, 0,
            PregenerationManager.State.RUNNING, now));
        assertThrows(IllegalArgumentException.class, () -> new PregenerationManager.Job("uhc", 8, -1, 0,
            PregenerationManager.State.RUNNING, now));
        assertThrows(IllegalArgumentException.class, () -> new PregenerationManager.Job("uhc", 8, 1, 2,
            PregenerationManager.State.RUNNING, now));
        assertThrows(IllegalArgumentException.class, () -> new PregenerationManager.Job("uhc", 8, 0, 0,
            PregenerationManager.State.COMPLETED, now));
    }
}
