package dev.thedocruby.resounding.raycast;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Frustum growth arming: growth rate is 0 until the second permeate at the current floored LOD
 * since the last bounce; shrink via the energy/alignment blend still applies.
 */
class FrustumGrowthGateTest {

    private static final double DELTA = 1e-9;
    private static final double GROWTH = 0.5;

    @Test
    void gatedGrowthRequiresTwoPermeates() {
        assertEquals(0.0, FrustumLod.gatedGrowthPerBlock(GROWTH, 0), DELTA);
        assertEquals(0.0, FrustumLod.gatedGrowthPerBlock(GROWTH, 1), DELTA);
        assertEquals(GROWTH, FrustumLod.gatedGrowthPerBlock(GROWTH, 2), DELTA);
        assertEquals(GROWTH, FrustumLod.gatedGrowthPerBlock(GROWTH, 5), DELTA);
    }

    @Test
    void zeroGrowthRateIsNoOpOnDistanceTerm_blendStillShrinks() {
        double next = FrustumLod.nextFrustumSize(2.0, 10.0, 0.0, 0.0, 0.5);
        assertEquals(1.0, next, DELTA, "blend*size*(1+0*d) = 0.5*2 = 1 — shrink only");
    }

    @Test
    void firstPermeateDoesNotGrow_secondDoes() {
        Cast cast = new Cast(null, null, null);
        cast.lastPolarAlignment = 1.0;
        double before = cast.frustumSize;

        cast.applyFrustumStep(2.0, GROWTH, 1.0, true);
        assertEquals(before, cast.frustumSize, DELTA, "1st permeate: growth gated off");
        assertEquals(1, cast.permeatesAtFrustumLod);

        cast.applyFrustumStep(2.0, GROWTH, 1.0, true);
        assertEquals(before * (1.0 + GROWTH * 2.0), cast.frustumSize, DELTA, "2nd permeate: growth arms");
        assertEquals(2, cast.permeatesAtFrustumLod);
    }

    @Test
    void bounceResetsStreak_mustRepayTwoPermeates() {
        Cast cast = new Cast(null, null, null);
        cast.lastPolarAlignment = 1.0;
        cast.applyFrustumStep(1.0, GROWTH, 1.0, true);
        cast.applyFrustumStep(1.0, GROWTH, 1.0, true);
        double armed = cast.frustumSize;
        assertEquals(FrustumLod.BASE_FOOTPRINT * (1.0 + GROWTH), armed, DELTA);

        cast.applyFrustumStep(1.0, GROWTH, 1.0, false); // bounce
        assertEquals(0, cast.permeatesAtFrustumLod);
        assertEquals(armed, cast.frustumSize, DELTA, "bounce with full leftover + alignment does not grow");

        cast.applyFrustumStep(1.0, GROWTH, 1.0, true);
        assertEquals(armed, cast.frustumSize, DELTA, "1st permeate after bounce still gated");
        cast.applyFrustumStep(1.0, GROWTH, 1.0, true);
        assertEquals(armed * (1.0 + GROWTH), cast.frustumSize, DELTA);
    }

    @Test
    void lodStepChangeResetsStreak() {
        Cast cast = new Cast(null, null, null);
        cast.lastPolarAlignment = 1.0;
        // Force a large footprint so floored LOD is already 2, then permeate once.
        cast.frustumSize = 4.0; // stepForSize(4) = 2
        cast.frustumGrowthLod = 1;
        cast.permeatesAtFrustumLod = 5; // stale streak from lod 1

        cast.applyFrustumStep(1.0, GROWTH, 1.0, true);
        assertEquals(2, cast.frustumGrowthLod);
        assertEquals(1, cast.permeatesAtFrustumLod, "LOD change must zero the streak before counting this permeate");
        assertEquals(4.0, cast.frustumSize, DELTA, "first permeate at new LOD still gated");
    }
}
