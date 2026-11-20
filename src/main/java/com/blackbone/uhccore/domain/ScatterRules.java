package com.blackbone.uhccore.domain;

import java.util.Collection;

/** Snapshot-only validation; callers perform chunk and biome reads on the server thread. */
public final class ScatterRules {
    private ScatterRules() { }
    public record Spot(int x, int y, int z) { }

    public static boolean acceptable(Spot candidate, Collection<Spot> assigned, int minimumDistance,
                                     int worldMinY, int worldMaxY, boolean solidFloor, boolean hazardous) {
        if (candidate.y() <= worldMinY + 1 || candidate.y() >= worldMaxY - 2
                || !solidFloor || hazardous || minimumDistance < 0) return false;
        long minimumSquared = (long) minimumDistance * minimumDistance;
        for (Spot spot : assigned) {
            long dx = (long) candidate.x() - spot.x();
            long dz = (long) candidate.z() - spot.z();
            if (dx * dx + dz * dz < minimumSquared) return false;
        }
        return true;
    }
}
