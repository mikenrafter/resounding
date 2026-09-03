package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.material.Material;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Polarization}: impedance-half polar membership, most/least-common
 * primary/secondary selection, and {@code g_most}/{@code g_least} via {@link Polarization#groupAdjust}.
 *
 * <p>All 8-corner arrays follow {@code OctreeManager.blockSequence} order (idx 0-7:
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

    // --- worked presence patterns ------------------------------------------------------------

    @Test
    void airMajority_XOOOXOOO_primaryAirSecondaryStone() {
        // idx: X O O O X O O O — 2X / 6O
        Material[] corners = { X, O, O, O, X, O, O, O };
        Polarization.Descriptor d = Polarization.bakeOctant(corners);

        assertEquals(O, d.primary());
        assertEquals(X, d.secondary());
        assertEquals(0.75, d.blendCoefficient(), DELTA);
        assertEquals(2.0, d.s(), DELTA);
        assertEquals(Polarization.groupAdjust(200.0, 2000.0, 6.0 / 2.0), d.mostCommonImpedance(), DELTA);
        assertEquals(Polarization.groupAdjust(2000.0, 200.0, 2.0 / 6.0), d.leastCommonImpedance(), DELTA);
    }

    @Test
    void stoneMajority_OXXXOXXX_primaryStoneSecondaryAir() {
        // idx: O X X X O X X X — 6X / 2O
        Material[] corners = { O, X, X, X, O, X, X, X };
        Polarization.Descriptor d = Polarization.bakeOctant(corners);

        assertEquals(X, d.primary());
        assertEquals(O, d.secondary());
        assertEquals(0.75, d.blendCoefficient(), DELTA);
        assertEquals(2.0, d.s(), DELTA);
        assertEquals(Polarization.groupAdjust(2000.0, 200.0, 6.0 / 2.0), d.mostCommonImpedance(), DELTA);
        assertEquals(Polarization.groupAdjust(200.0, 2000.0, 2.0 / 6.0), d.leastCommonImpedance(), DELTA);
    }

    @Test
    void presenceTie_XXOOXXOO_prefersStifferPrimary() {
        // idx: X X O O X X O O — 4X / 4O, polar (0,-8,0)
        Material[] corners = { X, X, O, O, X, X, O, O };
        Polarization.Descriptor d = Polarization.bakeOctant(corners);

        assertEquals(X, d.primary());
        assertEquals(O, d.secondary());
        assertEquals(0.5, d.blendCoefficient(), DELTA);
        assertEquals(1.0, d.s(), DELTA);
        assertEquals(new Vec3d(0, -8, 0), d.polar());
        // equal counts → ratio 1 → g_* collapses to the material means
        assertEquals(2000.0, d.mostCommonImpedance(), DELTA);
        assertEquals(200.0, d.leastCommonImpedance(), DELTA);
    }

    // --- P worked examples -------------------------------------------------------------------

    @Test
    void checkerboardPatternCancelsToZeroDespiteCleanFiftyFiftySplit() {
        Material[] corners = { X, O, X, O, O, X, O, X };
        Polarization.Descriptor d = Polarization.bakeOctant(corners);
        assertEquals(new Vec3d(0, 0, 0), d.polar(), "checkerboard aggregate must genuinely cancel to zero");
        assertEquals(X, d.primary());
        assertEquals(O, d.secondary());
        assertEquals(0.5, d.blendCoefficient(), DELTA);
    }

    @Test
    void axisAlignedStripedPatternPolarizesAlongSingleAxis() {
        Material[] corners = { X, O, X, O, X, O, X, O };
        Polarization.Descriptor d = Polarization.bakeOctant(corners);
        assertEquals(new Vec3d(-8, 0, 0), d.polar(), "axis-striped pattern must polarize along X, not cancel");
        assertEquals(X, d.primary());
        assertEquals(O, d.secondary());
    }

    // --- s magnitude term --------------------------------------------------------------------

    @Test
    void sDefaultsToOneBelowSixOfEightPrimaryCount() {
        Material[] corners = { X, X, X, X, X, O, O, O };
        Polarization.Descriptor d = Polarization.bakeOctant(corners);
        assertEquals(1.0, d.s(), DELTA);
        assertEquals(5.0 / 8.0, d.blendCoefficient(), DELTA);
    }

    @Test
    void sBoostsToTwoAtSixOfEightPrimaryCount() {
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

    @Test
    void groupAdjustEqualRatioReturnsMostlyMean() {
        assertEquals(1000.0, Polarization.groupAdjust(1000.0, 100.0, 1.0), DELTA);
        assertEquals(100.0, Polarization.groupAdjust(100.0, 1000.0, 1.0), DELTA);
    }

    // --- multi-material: middle tier in polar halves; g_* from most/least materials only ------

    @Test
    void eightDistinct_gMostLeastFromStiffestAndSoftestPresenceTie() {
        Material[] corners = {
                new Material(100.0, 1.0, 1.0), new Material(200.0, 1.0, 1.0),
                new Material(300.0, 1.0, 1.0), new Material(400.0, 1.0, 1.0),
                new Material(500.0, 1.0, 1.0), new Material(600.0, 1.0, 1.0),
                new Material(700.0, 1.0, 1.0), new Material(800.0, 1.0, 1.0),
        };
        Polarization.Descriptor d = Polarization.bakeOctant(corners);
        // all count 1 → primary = stiffest (800), secondary = softest (100); ratio 1
        assertEquals(800.0, d.mostCommonImpedance(), DELTA);
        assertEquals(100.0, d.leastCommonImpedance(), DELTA);
        assertEquals(450.0, d.avgImpedance(), DELTA);
        assertEquals(1.0 / 8.0, d.blendCoefficient(), DELTA);
    }

    @Test
    void threeDistinct_middleTierJoinPolarHalves_gFromMostAndLeastMaterials() {
        Material hi = new Material(900.0, 1.0, 1.0);
        Material mid = new Material(500.0, 1.0, 1.0);
        Material lo = new Material(100.0, 1.0, 1.0);
        // 3 hi, 2 mid, 3 lo — presence tie hi/lo → primary=hi; least among rest: mid count2 > lo count3?
        // least-common among non-primary: mid=2, lo=3 → least is mid (count 2)
        Material[] corners = { hi, hi, hi, mid, mid, lo, lo, lo };

        Polarization.Descriptor d = Polarization.bakeOctant(corners);
        assertEquals(hi, d.primary());
        assertEquals(mid, d.secondary());
        assertEquals(3.0 / 8.0, d.blendCoefficient(), DELTA);
        assertEquals(Polarization.groupAdjust(900.0, 500.0, 3.0 / 2.0), d.mostCommonImpedance(), DELTA);
        assertEquals(Polarization.groupAdjust(500.0, 900.0, 2.0 / 3.0), d.leastCommonImpedance(), DELTA);
        assertEquals(500.0, d.avgImpedance(), DELTA);
        assertEquals(1.0, d.s(), DELTA);
    }

    // --- homogeneous -------------------------------------------------------------------------

    @Test
    void homogeneousOctantHasNoGradient_polarNullNotZero() {
        Material[] corners = { X, X, X, X, X, X, X, X };
        Polarization.Descriptor d = Polarization.bakeOctant(corners);
        assertEquals(2000.0, d.mostCommonImpedance(), DELTA);
        assertEquals(2000.0, d.leastCommonImpedance(), DELTA);
        assertEquals(2000.0, d.avgImpedance(), DELTA);
        assertNull(d.polar());
        assertNull(d.primary());
        assertNull(d.secondary());
        assertTrue(Double.isNaN(d.blendCoefficient()));
        assertTrue(Double.isNaN(d.s()));
    }

    // --- stiff_weight / combinePolar ---------------------------------------------------------

    @Test
    void stiffWeightQuantizesToNearestQuarter() {
        assertEquals(0.5, Polarization.stiffWeight(0.5), DELTA);
        assertEquals(0.75, Polarization.stiffWeight(0.75), DELTA);
        assertEquals(1.0, Polarization.stiffWeight(0.9), DELTA);
        assertEquals(0.5, Polarization.stiffWeight(0.6), DELTA);
    }

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
        assertEquals(8.0, combined.length(), DELTA);
    }
}
