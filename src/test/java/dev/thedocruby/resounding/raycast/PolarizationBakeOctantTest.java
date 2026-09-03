package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.material.Material;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0 / Phase 0.5 RED tests for {@link Polarization}, the corner-sign-sum primitive shared by
 * the baked per-branch descriptor (Phase 0) and the native-resolution reflection/permeation
 * material selection (Phase 0.5). See references/research/frustums-plan.md.
 *
 * <p>All 8-corner arrays below follow {@code OctreeManager.blockSequence} order (idx 0-7:
 * {@code (0,0,0) (1,0,0) (0,1,0) (1,1,0) (0,0,1) (1,0,1) (0,1,1) (1,1,1)}).
 */
class PolarizationBakeOctantTest {

    private static final double DELTA = 1e-9;

    private static final Material X = new Material(2000.0, 0.2, 1.0);
    private static final Material O = new Material(200.0, 0.9, 1.0);

    // --- cornerSign -------------------------------------------------------------------------

    @Test
    void cornerSignMapsZeroOneToMinusOnePlusOne() {
        assertEquals(new Vec3d(-1, -1, -1), Polarization.cornerSign(new BlockPos(0, 0, 0)));
        assertEquals(new Vec3d(1, 1, 1), Polarization.cornerSign(new BlockPos(1, 1, 1)));
        assertEquals(new Vec3d(1, -1, 1), Polarization.cornerSign(new BlockPos(1, 0, 1)));
        assertEquals(new Vec3d(-1, 1, -1), Polarization.cornerSign(new BlockPos(0, 1, 0)));
    }

    // --- P worked examples from the plan's 3D table ------------------------------------------

    @Test
    void checkerboardPatternCancelsToZeroDespiteCleanFiftyFiftySplit() {
        // idx 0-7: X,O,X,O,O,X,O,X -> 4 X / 4 O, but P = (0,0,0) (real symmetry, not a gap)
        Material[] corners = { X, O, X, O, O, X, O, X };
        Polarization.Descriptor d = Polarization.bakeOctant(corners);
        assertEquals(new Vec3d(0, 0, 0), d.polar(), "checkerboard aggregate must genuinely cancel to zero");
    }

    @Test
    void axisAlignedStripedPatternPolarizesAlongSingleAxis() {
        // idx 0-7: X,O,X,O,X,O,X,O -> 4 X / 4 O, P = (-8,0,0): clean single-axis normal
        Material[] corners = { X, O, X, O, X, O, X, O };
        Polarization.Descriptor d = Polarization.bakeOctant(corners);
        assertEquals(new Vec3d(-8, 0, 0), d.polar(), "axis-striped pattern must polarize along X, not cancel");
    }

    @Test
    void bothFiftyFiftyPatternsShareBlendCoefficientButDifferInPolar() {
        Polarization.Descriptor checkerboard = Polarization.bakeOctant(new Material[]{ X, O, X, O, O, X, O, X });
        Polarization.Descriptor striped = Polarization.bakeOctant(new Material[]{ X, O, X, O, X, O, X, O });

        assertEquals(0.5, checkerboard.blendCoefficient(), DELTA);
        assertEquals(0.5, striped.blendCoefficient(), DELTA);
        assertEquals(X, checkerboard.primary());
        assertEquals(O, checkerboard.secondary());
        assertEquals(X, striped.primary());
        assertEquals(O, striped.secondary());
    }

    // --- s magnitude term (count-based, resolved) ---------------------------------------------

    @Test
    void sDefaultsToOneBelowSixOfEightPrimaryCount() {
        // 5 X / 3 O: primary count 5 < 6 -> s = 1
        Material[] corners = { X, X, X, X, X, O, O, O };
        Polarization.Descriptor d = Polarization.bakeOctant(corners);
        assertEquals(1.0, d.s(), DELTA);
    }

    @Test
    void sBoostsToTwoAtSixOfEightPrimaryCount() {
        // 6 X / 2 O: primary count 6 >= 6 -> s = 2
        Material[] corners = { X, X, X, X, X, X, O, O };
        Polarization.Descriptor d = Polarization.bakeOctant(corners);
        assertEquals(2.0, d.s(), DELTA);
    }

    @Test
    void magnitudeTermStandaloneFormula() {
        assertEquals(1.0, Polarization.magnitudeTerm(0), DELTA);
        assertEquals(1.0, Polarization.magnitudeTerm(5), DELTA);
        assertEquals(2.0, Polarization.magnitudeTerm(6), DELTA);
        assertEquals(2.0, Polarization.magnitudeTerm(7), DELTA);
        assertEquals(2.0, Polarization.magnitudeTerm(8), DELTA);
    }

    // --- high/low/avg: >=4 distinct values vs <4 distinct values -------------------------------

    @Test
    void eightDistinctImpedances_highIsMeanOfTop4_lowIsMeanOfBottom4() {
        Material[] corners = {
                new Material(100.0, 1.0, 1.0), new Material(200.0, 1.0, 1.0),
                new Material(300.0, 1.0, 1.0), new Material(400.0, 1.0, 1.0),
                new Material(500.0, 1.0, 1.0), new Material(600.0, 1.0, 1.0),
                new Material(700.0, 1.0, 1.0), new Material(800.0, 1.0, 1.0),
        };
        Polarization.Descriptor d = Polarization.bakeOctant(corners);
        assertEquals(650.0, d.maxImpedance(), DELTA, "mean of top 4 (500,600,700,800)");
        assertEquals(250.0, d.minImpedance(), DELTA, "mean of bottom 4 (100,200,300,400)");
        assertEquals(450.0, d.avgImpedance(), DELTA, "mean of all 8");
    }

    @Test
    void threeDistinctImpedances_underFourDistinct_usesSingleMaxAndSingleMin_notMeanOfFour() {
        // 3 x 900, 2 x 500 (middle tier), 3 x 100 -> only 3 distinct values, so Phase 0's table
        // says high = the single max (900) and low = the single min (100) - NOT
        // mean-of-top-4-by-rank (which would incorrectly be (900+900+900+500)/4 = 800) or
        // mean-of-bottom-4-by-rank (which would incorrectly be (100+100+100+500)/4 = 175).
        Material hi = new Material(900.0, 1.0, 1.0);
        Material mid = new Material(500.0, 1.0, 1.0);
        Material lo = new Material(100.0, 1.0, 1.0);
        Material[] corners = { hi, hi, hi, mid, mid, lo, lo, lo };

        Polarization.Descriptor d = Polarization.bakeOctant(corners);
        assertEquals(900.0, d.maxImpedance(), DELTA, "single max, not mean-of-top-4 (which would be 800)");
        assertEquals(100.0, d.minImpedance(), DELTA, "single min, not mean-of-bottom-4 (which would be 175)");
        assertEquals(500.0, d.avgImpedance(), DELTA, "mean of all 8 regardless of the max/min branch");

        // G_high/G_low still well-defined here (single material each, middle tier excluded).
        assertEquals(hi, d.primary());
        assertEquals(lo, d.secondary());
        assertEquals(3.0 / 6.0, d.blendCoefficient(), DELTA, "count(G_high) / (count(G_high)+count(G_low)), middle excluded");
        assertEquals(1.0, d.s(), DELTA, "primary count 3 < 6");
    }

    // --- homogeneous octant: no gradient, not a real P=0 -----------------------------------------

    @Test
    void homogeneousOctantHasNoGradient_polarNullNotZero() {
        Material[] corners = { X, X, X, X, X, X, X, X };
        Polarization.Descriptor d = Polarization.bakeOctant(corners);
        assertEquals(2000.0, d.maxImpedance(), DELTA);
        assertEquals(2000.0, d.minImpedance(), DELTA);
        assertEquals(2000.0, d.avgImpedance(), DELTA);
        assertNull(d.polar(), "single-material octant has no gradient, distinct from a real (0,0,0) cancellation");
        assertNull(d.primary());
        assertNull(d.secondary());
        assertTrue(Double.isNaN(d.blendCoefficient()));
        assertTrue(Double.isNaN(d.s()));
    }

    // --- stiff_weight quantization (size>2 combine) ---------------------------------------------

    @Test
    void stiffWeightQuantizesToNearestQuarter() {
        assertEquals(0.5, Polarization.stiffWeight(0.5), DELTA, "already an exact quarter");
        assertEquals(0.75, Polarization.stiffWeight(0.75), DELTA, "already an exact quarter");
        assertEquals(1.0, Polarization.stiffWeight(0.9), DELTA, "0.9 is nearer 1.0 than 0.75");
        assertEquals(0.5, Polarization.stiffWeight(0.6), DELTA, "0.6 is nearer 0.5 than 0.75");
    }

    // --- combinePolar: weighted average, not renormalized ----------------------------------------

    @Test
    void combinePolarIsWeightedAverageOfChildPolars() {
        Vec3d[] childPolar = { new Vec3d(4, 0, 0), new Vec3d(0, 4, 0) };
        double[] childWeight = { 1.0, 0.5 };

        Vec3d combined = Polarization.combinePolar(childPolar, childWeight);

        assertEquals(4.0 / 1.5, combined.x, DELTA);
        assertEquals(2.0 / 1.5, combined.y, DELTA);
        assertEquals(0.0, combined.z, DELTA);
    }

    @Test
    void combinePolarIsNotRenormalizedToUnitLength() {
        Vec3d[] childPolar = { new Vec3d(8, 0, 0) };
        double[] childWeight = { 1.0 };

        Vec3d combined = Polarization.combinePolar(childPolar, childWeight);

        assertEquals(8.0, combined.length(), DELTA, "polar stays a raw sum/weighted-average, never renormalized to a unit normal");
    }
}
