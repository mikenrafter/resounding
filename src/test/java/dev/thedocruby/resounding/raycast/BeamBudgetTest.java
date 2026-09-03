package dev.thedocruby.resounding.raycast;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1 RED tests for {@link BeamBudget}'s tiered subdivision cap (frustums-plan.md Phase 1 task
 * 3, "Resolved": 4 total splits per beam, one per footprint-size tier — sizes 1, 2, 4, 8; beyond
 * size 8, or once all four tiers are spent, no further splitting).
 *
 * <p>Tier-boundary interpretation is {@link BeamBudget}'s own documented design decision (half-open
 * ranges {@code [1,2) [2,4) [4,8) [8,16)}, nothing below 1 or at/above 16) — see that class's
 * javadoc for the rationale; these tests encode that same interpretation.
 */
class BeamBudgetTest {

    // --- tierIndexForFootprint: the four discrete tiers ------------------------------------------

    @Test
    void tierIndicesMatchTheFourResolvedSizes() {
        assertEquals(0, BeamBudget.tierIndexForFootprint(1.0), "size 1 -> first tier");
        assertEquals(1, BeamBudget.tierIndexForFootprint(2.0), "size 2 -> second tier");
        assertEquals(2, BeamBudget.tierIndexForFootprint(4.0), "size 4 -> third tier");
        assertEquals(3, BeamBudget.tierIndexForFootprint(8.0), "size 8 -> fourth tier");
    }

    @Test
    void withinTierRangeMapsToTheSameTierAsItsLowerBound() {
        assertEquals(0, BeamBudget.tierIndexForFootprint(1.5));
        assertEquals(1, BeamBudget.tierIndexForFootprint(3.0));
        assertEquals(2, BeamBudget.tierIndexForFootprint(6.0));
        assertEquals(3, BeamBudget.tierIndexForFootprint(12.0));
    }

    @Test
    void belowSizeOneOrAtOrBeyondSixteenHasNoTier() {
        assertEquals(-1, BeamBudget.tierIndexForFootprint(0.5), "below the first tier threshold");
        assertEquals(-1, BeamBudget.tierIndexForFootprint(0.0));
        assertEquals(-1, BeamBudget.tierIndexForFootprint(16.0), "beyond size 8's doubling range: no further splitting");
        assertEquals(-1, BeamBudget.tierIndexForFootprint(100.0));
    }

    // --- full budget: all 4 tiers available, 4 total splits ---------------------------------------

    @Test
    void freshBudgetHasAllFourSplitsRemaining() {
        assertEquals(4, BeamBudget.full().splitsRemaining());
    }

    @Test
    void freshBudgetCanSplitAtEveryTierSize() {
        BeamBudget budget = BeamBudget.full();
        assertTrue(budget.canSplitAt(1.0));
        assertTrue(budget.canSplitAt(2.0));
        assertTrue(budget.canSplitAt(4.0));
        assertTrue(budget.canSplitAt(8.0));
    }

    @Test
    void freshBudgetCannotSplitOutsideAnyTier() {
        BeamBudget budget = BeamBudget.full();
        assertFalse(budget.canSplitAt(0.5));
        assertFalse(budget.canSplitAt(16.0));
    }

    // --- consuming a tier: one split per tier, independent of the other tiers ---------------------

    @Test
    void consumingATierMakesItUnavailableButLeavesOtherTiersAlone() {
        BeamBudget afterFirstSplit = BeamBudget.full().consumeAt(1.0);
        assertFalse(afterFirstSplit.canSplitAt(1.0), "size-1 tier's single split is now spent");
        assertTrue(afterFirstSplit.canSplitAt(2.0), "size-2 tier is untouched");
        assertTrue(afterFirstSplit.canSplitAt(4.0), "size-4 tier is untouched");
        assertTrue(afterFirstSplit.canSplitAt(8.0), "size-8 tier is untouched");
        assertEquals(3, afterFirstSplit.splitsRemaining());
    }

    @Test
    void consumingAllFourTiersExhaustsTheBudget() {
        BeamBudget budget = BeamBudget.full()
                .consumeAt(1.0)
                .consumeAt(2.0)
                .consumeAt(4.0)
                .consumeAt(8.0);
        assertEquals(0, budget.splitsRemaining());
        assertFalse(budget.canSplitAt(1.0));
        assertFalse(budget.canSplitAt(2.0));
        assertFalse(budget.canSplitAt(4.0));
        assertFalse(budget.canSplitAt(8.0));
    }

    @Test
    void consumingTheSameTierTwiceDoesNotDoubleSpend() {
        BeamBudget budget = BeamBudget.full().consumeAt(1.0).consumeAt(1.0);
        assertEquals(3, budget.splitsRemaining(), "re-consuming an already-spent tier must not go negative");
    }

    @Test
    void consumeAtOutsideAnyTierDoesNotChangeSplitsRemaining() {
        BeamBudget budget = BeamBudget.full().consumeAt(0.5);
        assertEquals(4, budget.splitsRemaining(), "consuming outside every tier's range must be a no-op");
    }
}
