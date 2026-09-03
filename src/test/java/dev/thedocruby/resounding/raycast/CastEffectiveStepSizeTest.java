package dev.thedocruby.resounding.raycast;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase B RED tests for virtual frustum step size: homogeneous large leaves stay pruned; traversal
 * steps at {@code min(branchSize, footprintTierSize)} without allocating finer Branch leaves.
 *
 * <p>Footprint tiers match {@link BeamBudget}: {@code [1,2)→1, [2,4)→2, [4,8)→4, [8,16)→8}.
 */
class CastEffectiveStepSizeTest {

    @Test
    void footprintInTierOneMapsToStepOneCappedByBranch() {
        assertEquals(1, Cast.effectiveStepSize(16, 1.5),
                "footprint in [1,2) → tier size 1; min(16,1)=1");
        assertEquals(1, Cast.effectiveStepSize(1, 1.5),
                "branchSize=1 cannot refine below 1");
    }

    @Test
    void footprintInTierTwoMapsToStepTwo() {
        assertEquals(2, Cast.effectiveStepSize(16, 3.0),
                "footprint in [2,4) → tier size 2");
    }

    @Test
    void footprintCannotExceedBranchSize() {
        assertEquals(4, Cast.effectiveStepSize(4, 8.0),
                "footprint in [8,16) → tier 8, but min(4,8)=4");
        assertEquals(1, Cast.effectiveStepSize(1, 12.0),
                "any footprint against branchSize=1 stays at 1");
    }

    @Test
    void footprintOutsideTiersFallsBackToBranchSize() {
        // Below size 1 or at/beyond 16: no footprint tier — step equals the real branch cell.
        assertEquals(16, Cast.effectiveStepSize(16, 0.5));
        assertEquals(16, Cast.effectiveStepSize(16, 16.0));
    }
}
