package dev.thedocruby.resounding.raycast;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Frustum LOD step schedule: footprint {@code = 1 + growthPerBlock * distance}, steps
 * {@code 1,1,2,2,4,4,8,8,16,16} then capped at 16. Schedule tests below fix
 * {@code growthPerBlock = 0.5} to isolate the quantization logic from the ray-count-derived rate,
 * which is covered separately in {@code growthPerBlockDerivedFromRayCount}.
 */
class CastEffectiveStepSizeTest {

    private static final double GROWTH = 0.5;

    @Test
    void distanceScheduleMatchesOneOneTwoTwoPattern() {
        assertEquals(1, FrustumLod.stepForDistance(0.0, GROWTH));
        assertEquals(1, FrustumLod.stepForDistance(1.9, GROWTH));
        assertEquals(1, FrustumLod.stepForDistance(2.0, GROWTH));
        assertEquals(1, FrustumLod.stepForDistance(3.9, GROWTH));
        assertEquals(2, FrustumLod.stepForDistance(4.0, GROWTH));
        assertEquals(2, FrustumLod.stepForDistance(7.9, GROWTH));
        assertEquals(4, FrustumLod.stepForDistance(8.0, GROWTH));
        assertEquals(8, FrustumLod.stepForDistance(12.0, GROWTH));
        assertEquals(16, FrustumLod.stepForDistance(16.0, GROWTH));
        assertEquals(16, FrustumLod.stepForDistance(100.0, GROWTH), "cap at 16");
    }

    @Test
    void footprintGrowsHalfWidthPerBlock() {
        assertEquals(1.0, FrustumLod.footprintAt(0.0, GROWTH), 1e-9);
        assertEquals(2.0, FrustumLod.footprintAt(2.0, GROWTH), 1e-9);
        assertEquals(5.0, FrustumLod.footprintAt(8.0, GROWTH), 1e-9);
    }

    @Test
    void effectiveStepSizeIsCappedByBranch() {
        assertEquals(1, Cast.effectiveStepSize(16, 0.0, GROWTH));
        assertEquals(2, Cast.effectiveStepSize(16, 4.0, GROWTH));
        assertEquals(1, Cast.effectiveStepSize(1, 20.0, GROWTH), "branchSize=1 cannot coarsen upward");
        assertEquals(4, Cast.effectiveStepSize(4, 20.0, GROWTH), "min(4,16)=4");
    }

    @Test
    void growthPerBlockDerivedFromRayCount() {
        // More rays -> narrower per-ray solid angle -> slower footprint growth.
        assertEquals(0.3172, FrustumLod.growthPerBlock(128), 1e-3);
        assertEquals(0.4543, FrustumLod.growthPerBlock(64), 1e-3);
        assertEquals(0.1191, FrustumLod.growthPerBlock(890), 1e-3);
        assertTrue(FrustumLod.growthPerBlock(24) > FrustumLod.growthPerBlock(128),
                "fewer rays must yield a faster growth rate");
    }
}
