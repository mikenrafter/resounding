package dev.thedocruby.resounding.raycast;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task E: {@link FrustumLod#wouldDoubleCover} — growth into already-swept space past the polarity
 * mid-plane must defer (worked 2D wall-glide example in BEAM_TRACING_TDD_PLAN.md).
 */
class FrustumLodDoubleCoverTest {

    private static final Vec3d CELL_BASE = new Vec3d(0, 0, 0);
    private static final int CELL_SIZE = 2;
    private static final Vec3d POLAR_UP = new Vec3d(0, 1, 0);

    @Test
    void triggersOnWorkedGeometryAtStarAndShiftedPositions() {
        // Left octant, wall on +Y, polar ≈ (0,+1); mid=1.0, exit y=0.5 → clearance=0.5;
        // candidate 2.0 → half 1.0 > 0.5 → true at both *OOO (x=0.9) and O*OO (x=1.5).
        assertTrue(FrustumLod.wouldDoubleCover(
                CELL_BASE, CELL_SIZE, new Vec3d(0.9, 0.5, 0), POLAR_UP, 2.0, 1.0));
        assertTrue(FrustumLod.wouldDoubleCover(
                CELL_BASE, CELL_SIZE, new Vec3d(1.5, 0.5, 0), POLAR_UP, 2.0, 1.0));
    }

    @Test
    void clearsWhenUnpolarizedOrClearanceAllowsGrowth() {
        assertFalse(FrustumLod.wouldDoubleCover(
                CELL_BASE, CELL_SIZE, new Vec3d(0.9, 0.5, 0), null, 2.0, 1.0));

        // Neighboring open octant — no polar, growth proceeds.
        assertFalse(FrustumLod.wouldDoubleCover(
                new Vec3d(2, 0, 0), CELL_SIZE, new Vec3d(2.5, 0.5, 0), null, 2.0, 1.0));

        // Polarized but clearance 0.8 > half-candidate 0.15 (current < candidate so the
        // no-growth guard does not short-circuit first).
        assertFalse(FrustumLod.wouldDoubleCover(
                CELL_BASE, CELL_SIZE, new Vec3d(0.5, 0.2, 0), POLAR_UP, 0.3, 0.2));
    }

    @Test
    void noGrowthGuardReturnsFalseEvenAtZeroClearance() {
        // Reflect leg: candidate == current → never double-cover.
        assertFalse(FrustumLod.wouldDoubleCover(
                CELL_BASE, CELL_SIZE, new Vec3d(0.9, 1.0, 0), POLAR_UP, 1.0, 1.0));
    }

    @Test
    void pastPlaneClampTriggersOnAnyPositiveGrowth() {
        // exit y already on the solid side of mid (y>1); clearance clamps to 0 → any growth triggers.
        assertTrue(FrustumLod.wouldDoubleCover(
                CELL_BASE, CELL_SIZE, new Vec3d(0.9, 1.5, 0), POLAR_UP, 1.1, 1.0));
    }
}
