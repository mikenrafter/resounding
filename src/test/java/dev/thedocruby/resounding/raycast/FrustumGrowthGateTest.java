package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.config.PrecomputedConfig;
import dev.thedocruby.resounding.config.ResoundingConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Frustum size: additive growth on permeate only, then energy/alignment shrink on every boundary.
 */
class FrustumGrowthGateTest {

    private static final double DELTA = 1e-9;
    private static final double GROWTH = 0.5;

    @BeforeEach
    void activateConfig() throws CloneNotSupportedException {
        if (PrecomputedConfig.pConfig != null) {
            PrecomputedConfig.pConfig.deactivate();
        }
        PrecomputedConfig.pConfig = new PrecomputedConfig(new ResoundingConfig());
    }

    @AfterEach
    void deactivateConfig() {
        if (PrecomputedConfig.pConfig != null) {
            PrecomputedConfig.pConfig.deactivate();
            PrecomputedConfig.pConfig = null;
        }
    }

    @Test
    void zeroGrowth_blendStillShrinks() {
        // growth=0, alignment=0 → blend = energy²; size *= 0.25
        double next = FrustumLod.nextFrustumSize(2.0, 10.0, 0.0, 0.0, 0.5);
        assertEquals(0.5, next, DELTA, "blend*size = 0.25*2 = 0.5 — shrink only");
    }

    @Test
    void permeateGrowsAdditivelyThenShrinks() {
        // grown = 2 + 0.5*4 = 4; blend = lerp(0.25, 1, 0) = 0.25 → 1
        double next = FrustumLod.nextFrustumSize(2.0, 4.0, GROWTH, 0.0, 0.5);
        assertEquals(1.0, next, DELTA);
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
        assertEquals(0.75, cast.frustumSize, DELTA, "reflect: shrink only, growth ignored");
    }

    @Test
    void permeateAfterReflectGrowsFromShrunkenSize() {
        Cast cast = new Cast(null, null, null);
        cast.lastPolarAlignment = 0.0;
        cast.frustumSize = 4.0;

        cast.applyFrustumStep(1.0, GROWTH, 0.5, false); // → 1.0
        assertEquals(1.0, cast.frustumSize, DELTA);

        cast.lastPolarAlignment = 1.0;
        cast.applyFrustumStep(2.0, GROWTH, 1.0, true); // → 1 + 0.5*2 = 2
        assertEquals(2.0, cast.frustumSize, DELTA);
    }
}
