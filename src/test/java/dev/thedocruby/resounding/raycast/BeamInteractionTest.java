package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.Physics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 1 RED tests for {@link BeamInteraction} — asserts that Phase 1's face-hit split/commit
 * decision <em>routes through</em> the already-implemented (Phase 0.5, GREEN) gate in
 * {@link Physics}, rather than re-deriving the notable-interaction/commit-cutoff logic. Per the
 * task brief: "test that Phase 1's beam-interaction code CALLS it correctly", not the gate's own
 * formula (that has its own coverage in {@code PhysicsPolarBlendTest}).
 *
 * <p>{@link Physics#isNotableInteraction} and {@link Physics#commitReflect} are real (already
 * implemented) on this branch, so the right-hand side of every equality below is a real value;
 * {@link BeamInteraction}'s stub throw is what makes these RED.
 */
class BeamInteractionTest {

    // --- shouldSplit must equal Physics.isNotableInteraction(contrast, splitsRemaining) -----------

    @Test
    void shouldSplitDelegatesToPhysicsNotableGate_fullBudget() {
        double contrast = 0.5;
        int splitsRemaining = 4;
        assertEquals(
                Physics.isNotableInteraction(contrast, splitsRemaining),
                BeamInteraction.shouldSplit(contrast, splitsRemaining)
        );
    }

    @Test
    void shouldSplitDelegatesToPhysicsNotableGate_depletedBudget() {
        double contrast = 0.5;
        int splitsRemaining = 0;
        assertEquals(
                Physics.isNotableInteraction(contrast, splitsRemaining),
                BeamInteraction.shouldSplit(contrast, splitsRemaining)
        );
    }

    @Test
    void shouldSplitDelegatesToPhysicsNotableGate_acrossContrastRange() {
        for (double contrast = 0.0; contrast <= 1.0; contrast += 0.25) {
            for (int splitsRemaining = 0; splitsRemaining <= 4; splitsRemaining++) {
                assertEquals(
                        Physics.isNotableInteraction(contrast, splitsRemaining),
                        BeamInteraction.shouldSplit(contrast, splitsRemaining),
                        "shouldSplit(" + contrast + ", " + splitsRemaining + ") must match Physics.isNotableInteraction exactly"
                );
            }
        }
    }

    // --- shouldCommitReflect must equal Physics.commitReflect(w) -----------------------------------

    @Test
    void shouldCommitReflectDelegatesToPhysicsFlatCutoff() {
        assertEquals(Physics.commitReflect(0.5), BeamInteraction.shouldCommitReflect(0.5));
        assertEquals(Physics.commitReflect(0.75), BeamInteraction.shouldCommitReflect(0.75));
        assertEquals(Physics.commitReflect(0.49), BeamInteraction.shouldCommitReflect(0.49));
        assertEquals(Physics.commitReflect(0.0), BeamInteraction.shouldCommitReflect(0.0));
        assertEquals(Physics.commitReflect(1.0), BeamInteraction.shouldCommitReflect(1.0));
    }

    @Test
    void shouldSplitAtZeroBudgetNeverAuthorizesEvenFullContrast() {
        assertEquals(false, BeamInteraction.shouldSplit(1.0, 0),
                "splitsRemaining=0 must refuse every contrast; gate must force commit");
        assertEquals(false, BeamInteraction.shouldSplit(0.5, 0));
        assertEquals(false, BeamInteraction.shouldSplit(0.0, 0));
    }
}
