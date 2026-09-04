package dev.thedocruby.resounding.raycast;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Frustum size: additive growth on permeate only, then energy/alignment shrink on every boundary.
 */
class FrustumGrowthGateTest {

    private static final double DELTA = 1e-9;
    private static final double GROWTH = 0.5;

    @Test
    void zeroGrowth_blendStillShrinks() {
        // growth=0, alignment=0 → blend = energy; size *= 0.5
        double next = FrustumLod.nextFrustumSize(2.0, 10.0, 0.0, 0.0, 0.5);
        assertEquals(1.0, next, DELTA, "blend*size = 0.5*2 = 1 — shrink only");
    }

    @Test
    void permeateGrowsAdditivelyThenShrinks() {
        // grown = 2 + 0.5*4 = 4; blend = lerp(0.5, 1, 0) = 0.5 → 2
        double next = FrustumLod.nextFrustumSize(2.0, 4.0, GROWTH, 0.0, 0.5);
        assertEquals(2.0, next, DELTA);
    }

    @Test
    void fullAlignmentLeavesGrownSizeUnchangedByEnergy() {
        // grown = 1 + 0.5*2 = 2; blend = 1 regardless of energy
        double next = FrustumLod.nextFrustumSize(1.0, 2.0, GROWTH, 1.0, 0.25);
        assertEquals(2.0, next, DELTA);
    }

    @Test
    void firstPermeateGrowsImmediately() {
        Cast cast = new Cast(null, null, null);
        cast.lastPolarAlignment = 1.0;
        double before = cast.frustumSize;

        cast.applyFrustumStep(2.0, GROWTH, 1.0, true);
        assertEquals(before + GROWTH * 2.0, cast.frustumSize, DELTA);
    }

    @Test
    void reflectDoesNotGrow_onlyShrinks() {
        Cast cast = new Cast(null, null, null);
        cast.lastPolarAlignment = 1.0;
        cast.frustumSize = 3.0;

        cast.applyFrustumStep(5.0, GROWTH, 1.0, false);
        assertEquals(3.0, cast.frustumSize, DELTA, "full leftover + alignment: no change");

        cast.lastPolarAlignment = 0.0;
        cast.applyFrustumStep(5.0, GROWTH, 0.5, false);
        assertEquals(1.5, cast.frustumSize, DELTA, "reflect: shrink only, growth ignored");
    }

    @Test
    void permeateAfterReflectGrowsFromShrunkenSize() {
        Cast cast = new Cast(null, null, null);
        cast.lastPolarAlignment = 0.0;
        cast.frustumSize = 4.0;

        cast.applyFrustumStep(1.0, GROWTH, 0.5, false); // → 2.0
        assertEquals(2.0, cast.frustumSize, DELTA);

        cast.lastPolarAlignment = 1.0;
        cast.applyFrustumStep(2.0, GROWTH, 1.0, true); // → 2 + 0.5*2 = 3
        assertEquals(3.0, cast.frustumSize, DELTA);
    }
}
