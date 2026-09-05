package dev.thedocruby.resounding;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0.5 RED tests for {@link Physics}'s polarized reflection material-selection primitives:
 * blend mechanism, permeation sign-copy bending, and the notable-interaction gate. See
 * references/research/frustums-plan.md.
 */
class PhysicsPolarBlendTest {

    private static final double DELTA = 1e-9;

    // --- dual-derived polarity alignment: position-aware blend of ray direction with the ------
    // --- offset from incident point to octant center (frustums-plan.md Task C) ------------------

    // Fixture: cellBase (0,0,0), cellSize 2 -> octant center C = (1,1,1).
    private static final Vec3d C = new Vec3d(1, 1, 1);

    @Test
    void dualDerivedAlignmentDeadCenterEntryMatchesPureAngle() {
        // P = center of -X face; C-P = (1,0,0), exactly parallel to ray, so the blend degenerates
        // to the raw ray direction and dualDerivedAlignment must match polarAlignment exactly.
        Vec3d ray = new Vec3d(1, 0, 0);
        Vec3d p = new Vec3d(0, 1, 1);

        assertEquals(Physics.polarAlignment(ray, new Vec3d(1, 0, 0)),
                Physics.dualDerivedAlignment(ray, p, C, new Vec3d(1, 0, 0)), DELTA);

        double s = Math.sqrt(2) / 2;
        Vec3d pol = new Vec3d(s, s, 0);
        assertEquals(Physics.polarAlignment(ray, pol),
                Physics.dualDerivedAlignment(ray, p, C, pol), DELTA);
    }

    @Test
    void tangentEntryWithFullyAlignedRayIsExactlyOneHalf() {
        // P = center of +Y face; C-P = (0,-1,0), perpendicular to ray. Blended = (1,-1,0)/sqrt(2);
        // dot with pol (1,0,0) = 1/sqrt(2), squared = 0.5.
        Vec3d ray = new Vec3d(1, 0, 0);
        Vec3d pol = new Vec3d(1, 0, 0);
        Vec3d p = new Vec3d(1, 2, 1);

        assertEquals(0.5, Physics.dualDerivedAlignment(ray, p, C, pol), DELTA);
    }

    @Test
    void tangentEntryWithPerpendicularPolIsAlsoOneHalf() {
        // Same geometry as above, pol (0,1,0) instead. Blended = (1,-1,0)/sqrt(2), dot^2 = 0.5.
        Vec3d ray = new Vec3d(1, 0, 0);
        Vec3d pol = new Vec3d(0, 1, 0);
        Vec3d p = new Vec3d(1, 2, 1);

        assertEquals(0.5, Physics.dualDerivedAlignment(ray, p, C, pol), DELTA);
    }

    @Test
    void sumThenNormalizeFormulaExactValue() {
        // P = top edge of -X face; C-P = (1,-1,0)/sqrt(2). Blended = normalize(ray + offsetNorm),
        // dot^2 with pol (1,0,0) works out exactly to (2 + sqrt(2)) / 4.
        Vec3d ray = new Vec3d(1, 0, 0);
        Vec3d pol = new Vec3d(1, 0, 0);
        Vec3d p = new Vec3d(0, 2, 1);

        assertEquals((2 + Math.sqrt(2)) / 4, Physics.dualDerivedAlignment(ray, p, C, pol), DELTA);
    }

    @Test
    void degenerateOffsetFallsBackToRawRay() {
        // P == C exactly -> offset is the zero vector, degenerate -> fall back to normalize(ray).
        Vec3d ray = new Vec3d(1, 1, 0);
        Vec3d pol = new Vec3d(0, 1, 0);
        Vec3d rayNorm = ray.normalize();

        assertEquals(Physics.polarAlignment(rayNorm, pol),
                Physics.dualDerivedAlignment(ray, C, C, pol), DELTA);
    }

    @Test
    void antiParallelSumFallsBackToRawRay() {
        // P = center of +X face; C-P = (-1,0,0), exactly anti-parallel to ray, so ray + offsetNorm
        // sums to the zero vector - degenerate -> fall back to normalize(ray), never NaN.
        Vec3d ray = new Vec3d(1, 0, 0);
        Vec3d pol = new Vec3d(0, 1, 0);
        Vec3d p = new Vec3d(2, 1, 1);

        double result = Physics.dualDerivedAlignment(ray, p, C, pol);
        assertEquals(Physics.polarAlignment(ray.normalize(), pol), result, DELTA);
        assertFalse(Double.isNaN(result), "anti-parallel sum must fall back, never produce NaN");
    }

    @Test
    void dualDerivedNormIsUnitLength() {
        Vec3d[] rays = {
                new Vec3d(1, 0, 0),
                new Vec3d(1, 1, 0),
                new Vec3d(0, 1, 1),
                new Vec3d(1, 1, 1),
        };
        Vec3d[] points = {
                new Vec3d(0, 1, 1),
                new Vec3d(0, 1, 1),
                new Vec3d(2, 1, 1),
                new Vec3d(2, 0, 1),
        };
        for (int i = 0; i < rays.length; i++) {
            Vec3d result = Physics.dualDerivedNorm(rays[i], points[i], C);
            assertEquals(1.0, result.length(), DELTA,
                    "dualDerivedNorm must always return a unit vector (case " + i + ")");
        }
    }

    @Test
    void dualDerivedNormNormalizesRayInput() {
        // ray magnitude must not leak into the result - only its direction matters.
        Vec3d p = new Vec3d(0, 2, 1);
        Vec3d longRay = new Vec3d(2, 0, 0);
        Vec3d unitRay = new Vec3d(1, 0, 0);

        Vec3d fromLong = Physics.dualDerivedNorm(longRay, p, C);
        Vec3d fromUnit = Physics.dualDerivedNorm(unitRay, p, C);

        assertEquals(fromUnit.x, fromLong.x, DELTA);
        assertEquals(fromUnit.y, fromLong.y, DELTA);
        assertEquals(fromUnit.z, fromLong.z, DELTA);
    }

    @Test
    void polSignInvarianceStillHolds() {
        // Same fixture as sumThenNormalizeFormulaExactValue, but pol flipped to (-1,0,0): the
        // squared dot product is side-agnostic, so the result must be unchanged.
        Vec3d ray = new Vec3d(1, 0, 0);
        Vec3d pol = new Vec3d(-1, 0, 0);
        Vec3d p = new Vec3d(0, 2, 1);

        assertEquals((2 + Math.sqrt(2)) / 4, Physics.dualDerivedAlignment(ray, p, C, pol), DELTA);
    }

    // --- alignment a = (ray_norm . pol_norm)^2 --------------------------------------------------

    @Test
    void alignmentIsOneWhenParallel() {
        assertEquals(1.0, Physics.polarAlignment(new Vec3d(1, 0, 0), new Vec3d(1, 0, 0)), DELTA);
    }

    @Test
    void alignmentIsZeroWhenPerpendicular() {
        assertEquals(0.0, Physics.polarAlignment(new Vec3d(0, 1, 0), new Vec3d(1, 0, 0)), DELTA);
    }

    @Test
    void alignmentIgnoresSignSinceItIsSquared() {
        assertEquals(1.0, Physics.polarAlignment(new Vec3d(1, 0, 0), new Vec3d(-1, 0, 0)), DELTA,
                "anti-parallel must alignment-match parallel: a is squared, side of the axis doesn't matter");
    }

    @Test
    void alignmentAtFortyFiveDegreesIsOneHalf() {
        double s = Math.sqrt(2) / 2;
        assertEquals(0.5, Physics.polarAlignment(new Vec3d(s, s, 0), new Vec3d(1, 0, 0)), DELTA);
    }

    // --- blend weight w = clamp(a*s, 0, 1) ------------------------------------------------------

    @Test
    void blendWeightUnclampedMultiplication() {
        assertEquals(0.5, Physics.polarBlendWeight(0.5, 1.0), DELTA);
        assertEquals(0.6, Physics.polarBlendWeight(0.3, 2.0), DELTA);
        assertEquals(0.0, Physics.polarBlendWeight(0.0, 2.0), DELTA);
    }

    @Test
    void blendWeightClampsAtOne() {
        assertEquals(1.0, Physics.polarBlendWeight(0.5, 2.0), DELTA, "0.5*2 = 1.0 exactly, boundary");
        assertEquals(1.0, Physics.polarBlendWeight(1.0, 2.0), DELTA, "1.0*2 = 2.0, must clamp down to 1.0");
    }

    // --- effective impedance = primary*w + secondary*(1-w) ---------------------------------------

    @Test
    void blendImpedanceFullyPrimaryAtWOne() {
        assertEquals(1000.0, Physics.blendImpedance(1000.0, 100.0, 1.0), DELTA);
    }

    @Test
    void blendImpedanceFullySecondaryAtWZero() {
        assertEquals(100.0, Physics.blendImpedance(1000.0, 100.0, 0.0), DELTA);
    }

    @Test
    void blendImpedanceLinearInterpolationAtMidpoints() {
        assertEquals(550.0, Physics.blendImpedance(1000.0, 100.0, 0.5), DELTA);
        assertEquals(325.0, Physics.blendImpedance(1000.0, 100.0, 0.25), DELTA);
    }

    // --- permeation sign-copy bending -------------------------------------------------------------

    @Test
    void permeationBendPreservesMagnitudeExactly() {
        Vec3d[] rays = {
                new Vec3d(2, 0, 0),
                new Vec3d(1, 1, -1),
                new Vec3d(3, -4, 0),
                new Vec3d(-1, -1, -1),
        };
        Vec3d[] pols = {
                new Vec3d(1, 0, 0),
                new Vec3d(0, 0, 1),
                new Vec3d(1, 1, 0),
                new Vec3d(0, -1, 0),
        };
        for (int i = 0; i < rays.length; i++) {
            Vec3d ray = rays[i];
            Vec3d rayNorm = ray.normalize();
            Vec3d polNorm = pols[i].normalize();
            Vec3d result = Physics.permeationBend(ray, rayNorm, polNorm);
            assertEquals(ray.length(), result.length(), DELTA,
                    "|result| must equal |ray| exactly - only sign bits change, no renormalization");
        }
    }

    @Test
    void permeationBendAlignedRayKeepsOriginalSigns() {
        // dot=1 -> similarity=1 -> t=1 -> target=polNorm=(1,0,0); ray already matches those signs.
        Vec3d ray = new Vec3d(2, 0, 0);
        Vec3d rayNorm = new Vec3d(1, 0, 0);
        Vec3d polNorm = new Vec3d(1, 0, 0);
        Vec3d result = Physics.permeationBend(ray, rayNorm, polNorm);
        assertEquals(new Vec3d(2, 0, 0), result);
    }

    @Test
    void permeationBendAntiAlignedComponentClampsToTangentAndCanFlipSign() {
        // ray_norm = (1,1,-1)/sqrt(3), pol_norm = (0,0,1): dot = -1/sqrt(3) < 0 -> similarity < 0
        // -> t clamps to 0 -> target = tangent = ray_norm - dot*pol_norm = (1/sqrt3, 1/sqrt3, 0).
        // target.z is (positive) zero, so copysign(ray.z=-1, +0.0) flips the z sign to +1.
        double c = 1.0 / Math.sqrt(3);
        Vec3d ray = new Vec3d(1, 1, -1);
        Vec3d rayNorm = new Vec3d(c, c, -c);
        Vec3d polNorm = new Vec3d(0, 0, 1);

        Vec3d result = Physics.permeationBend(ray, rayNorm, polNorm);

        assertEquals(1.0, result.x, DELTA);
        assertEquals(1.0, result.y, DELTA);
        assertEquals(1.0, result.z, DELTA, "z must flip from -1 to +1: anti-aligned clamp pushes target.z to +0");
        assertEquals(Math.sqrt(3), result.length(), DELTA);
    }

    // --- notable-interaction gate: budget-dependent threshold -------------------------------------

    @Test
    void higherBudgetNeverRaisesTheThreshold() {
        assertTrue(Physics.notableThreshold(4) <= Physics.notableThreshold(2));
        assertTrue(Physics.notableThreshold(2) <= Physics.notableThreshold(0));
        assertTrue(Physics.notableThreshold(4) < Physics.notableThreshold(0),
                "more budget must strictly lower the threshold somewhere across the range, per the plan's qualitative spec");
    }

    @Test
    void moreBudgetNeverMakesAnInteractionLessNotable() {
        // "more budget left -> lower threshold, splits more readily" - so for a fixed contrast
        // magnitude, if a lower budget already found the interaction notable, a higher budget must
        // too (monotonic permissiveness), regardless of the exact threshold curve chosen later.
        double contrast = 0.5;
        for (int lowBudget = 0; lowBudget < 4; lowBudget++) {
            int highBudget = lowBudget + 1;
            boolean low = Physics.isNotableInteraction(contrast, lowBudget);
            boolean high = Physics.isNotableInteraction(contrast, highBudget);
            if (low) {
                assertTrue(high, "budget " + highBudget + " must stay notable when budget " + lowBudget + " already was, for the same contrast");
            }
        }
    }

    // --- flat commit cutoff once budget is exhausted -----------------------------------------------

    @Test
    void commitReflectUsesFlatOneHalfCutoff() {
        assertTrue(Physics.commitReflect(0.5), "w == 0.5 must commit to reflect (>=)");
        assertTrue(Physics.commitReflect(0.75));
        assertFalse(Physics.commitReflect(0.49));
        assertFalse(Physics.commitReflect(0.0));
    }

    // --- zero-budget no-split: exhausted budget must never authorize another split -----------------

    @Test
    void zeroBudgetThresholdIsAboveAnyContrast_soNoContrastIsNotable() {
        // Bug: notableThreshold(0) == 1.0, so contrast >= 1.0 still authorizes a split. Budget
        // exhausted means commit only — every contrast at splitsRemaining=0 must be non-notable.
        assertFalse(Physics.isNotableInteraction(0.0, 0));
        assertFalse(Physics.isNotableInteraction(0.5, 0));
        assertFalse(Physics.isNotableInteraction(0.999, 0));
        assertFalse(Physics.isNotableInteraction(1.0, 0),
                "contrast==1.0 at splitsRemaining=0 must not split; beam must commit instead");
        assertFalse(Physics.isNotableInteraction(2.0, 0));
    }
}
