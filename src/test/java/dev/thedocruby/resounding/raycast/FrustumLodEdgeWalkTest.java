package dev.thedocruby.resounding.raycast;

import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DDA-axis N/E/D maps and edge-walk that only corners on CORNER/SPLIT.
 */
class FrustumLodEdgeWalkTest {

    private static final double DELTA = 1e-9;

    @Test
    void classifyFourMaps() {
        assertEquals(FrustumLod.Interaction.CORNER, FrustumLod.classify(true, true, true));
        assertEquals(FrustumLod.Interaction.GAP, FrustumLod.classify(true, false, false));
        assertEquals(FrustumLod.Interaction.SPLIT, FrustumLod.classify(true, true, false));
        assertEquals(FrustumLod.Interaction.FACE, FrustumLod.classify(true, false, true));
    }

    @Test
    void openExitFaceIsGapEvenWhenSideWallsBlock() {
        // N open + E/D solid used to return FACE and Cast upgraded air:air hosts to R=1.
        assertEquals(FrustumLod.Interaction.GAP, FrustumLod.classify(false, true, true));
        assertEquals(FrustumLod.Interaction.GAP, FrustumLod.classify(false, true, false));
        assertEquals(FrustumLod.Interaction.GAP, FrustumLod.classify(false, false, true));
        assertEquals(FrustumLod.Interaction.GAP, FrustumLod.classify(false, false, false));
    }

    @Test
    void matchedHostReflectivityIgnoresNeighborWallImpedance() {
        // Regression lock for latest.log air lod2 Zprev=Z=air R∈(0,1]: reflectivity is only from
        // prior↔host. Neighbor walls may block the forward map but must not mint R on their own.
        double air = 426.9;
        assertEquals(0.0, Cast.impedancesClose(air, air) ? 0.0 : 1.0, 0.0);
        assertTrue(FrustumLod.blocksPermeation(air, 1e7, 0.05), "N can still block");
        assertEquals(
                FrustumLod.Interaction.FACE,
                FrustumLod.classify(true, false, true),
                "FACE map is fine — Cast must still keep R=0 when prior≈host");
    }

    @Test
    void xyExitPlusYSurveysForwardYThenX() {
        // Leave H through +Y; from mid-face with +X+Y the next same-size DDA face is +X.
        FrustumLod.ForwardMap map = FrustumLod.forwardMap(
                Vec3d.ZERO, 2, new Vec3d(1.0, 2.0, 1.0), new Vec3d(1, 1, 0), new Vec3i(0, -1, 0));
        assertNotNull(map);
        assertEquals(1, map.faceAxis());
        assertEquals(0, map.tangentAxis(), "second DDA axis is +X");
        assertEquals(new Vec3i(0, 2, 0), map.nOffset());
        assertEquals(new Vec3i(2, 0, 0), map.eOffset());
        assertEquals(new Vec3i(2, 2, 0), map.dOffset());
    }

    @Test
    void xzExitPlusZSurveysForwardZThenX() {
        FrustumLod.ForwardMap map = FrustumLod.forwardMap(
                Vec3d.ZERO, 2, new Vec3d(1.0, 1.0, 2.0), new Vec3d(1, 0, 1), new Vec3i(0, 0, -1));
        assertNotNull(map);
        assertEquals(2, map.faceAxis());
        assertEquals(0, map.tangentAxis());
        assertEquals(new Vec3i(0, 0, 2), map.nOffset());
        assertEquals(new Vec3i(2, 0, 0), map.eOffset());
        assertEquals(new Vec3i(2, 0, 2), map.dOffset());
    }

    @Test
    void yzExitPlusZSurveysForwardZThenY() {
        FrustumLod.ForwardMap map = FrustumLod.forwardMap(
                Vec3d.ZERO, 2, new Vec3d(1.0, 1.0, 2.0), new Vec3d(0, 1, 1), new Vec3i(0, 0, -1));
        assertNotNull(map);
        assertEquals(2, map.faceAxis());
        assertEquals(1, map.tangentAxis());
        assertEquals(new Vec3i(0, 0, 2), map.nOffset());
        assertEquals(new Vec3i(0, 2, 0), map.eOffset());
        assertEquals(new Vec3i(0, 2, 2), map.dOffset());
    }

    @Test
    void castNegativePlaneIndexStillWalksForwardThroughTheFace() {
        FrustumLod.ForwardMap map = FrustumLod.forwardMap(
                Vec3d.ZERO, 2, new Vec3d(2.0, 1.0, 1.0), new Vec3d(1, 1, 0), new Vec3i(-1, 0, 0));
        assertNotNull(map);
        assertEquals(new Vec3i(2, 0, 0), map.nOffset());
        assertEquals(new Vec3i(0, 2, 0), map.eOffset());
    }

    @Test
    void headOnSameAxisProjectionSkipsNed() {
        // Pure +X: after crossing +X of H, the next face of the virtual neighbor is also +X.
        FrustumLod.ForwardMap map = FrustumLod.forwardMap(
                Vec3d.ZERO, 2, new Vec3d(2.0, 1.0, 1.0), new Vec3d(1, 0, 0), new Vec3i(-1, 0, 0));
        assertNull(map);
    }

    @Test
    void secondDdaAxisDependsOnHitLocationNotDominantTangent() {
        // Ray has larger |Z| than |Y|, but from this hit the next DDA face inside the neighbor is +Y.
        Vec3d dir = new Vec3d(1, 0.5, 2);
        Vec3d hitNearTop = new Vec3d(2.0, 1.9, 0.1);
        FrustumLod.ForwardMap map = FrustumLod.forwardMap(
                Vec3d.ZERO, 2, hitNearTop, dir, new Vec3i(-1, 0, 0));
        assertNotNull(map);
        assertEquals(0, map.faceAxis());
        assertEquals(1, map.tangentAxis(), "near +Y face → second DDA is Y, not dominant Z");
        assertEquals(new Vec3i(2, 0, 0), map.nOffset());
        assertEquals(new Vec3i(0, 2, 0), map.eOffset());
    }

    @Test
    void nextDdaPlaneIsArithmeticOnly() {
        Vec3i next = FrustumLod.nextDdaPlane(
                Vec3d.ZERO, 2, new Vec3d(2.0, 1.0, 1.0), new Vec3d(1, 1, 0), 0, 1);
        assertEquals(new Vec3i(0, -1, 0), next);
    }

    @Test
    void cornerWalksToSharedVertexOnXFace() {
        Vec3d hit = new Vec3d(2, 1.0, 1.0);
        Vec3d base = Vec3d.ZERO;
        Vec3d ray = new Vec3d(1, 1, -1);
        Vec3d walked = FrustumLod.edgeWalk(
                hit, base, 2, new Vec3i(1, 0, 0), ray, 1.0, FrustumLod.Interaction.CORNER);

        assertEquals(2.0, walked.x, DELTA, "stay on +X face");
        assertEquals(2.0, walked.y, DELTA, "leading +Y");
        assertEquals(0.0, walked.z, DELTA, "leading −Z");
    }

    @Test
    void faceWalksToVertexSharedOnlyByDAndE() {
        // +Y face of H [0,1]³; ray +X+Y. H's N-face leading corner is (1,1); D–E-only is (2,1).
        Vec3d hit = new Vec3d(0.4, 1.0, 0.5);
        Vec3d walked = FrustumLod.edgeWalk(
                hit, Vec3d.ZERO, 1, new Vec3i(0, 1, 0), new Vec3d(1, 1, 0), 1.0,
                FrustumLod.Interaction.FACE);

        assertEquals(2.0, walked.x, DELTA, "past H along +E to D–E-only vertex");
        assertEquals(1.0, walked.y, DELTA, "stay on the N wall");
        assertEquals(0.5, walked.z, DELTA, "non-primary tangent unchanged");
    }

    @Test
    void faceDoesNotStopAtAllFourVertex() {
        Vec3d hit = new Vec3d(0.25, 1.0, 0.5);
        Vec3d walked = FrustumLod.edgeWalk(
                hit, Vec3d.ZERO, 1, new Vec3i(0, 1, 0), new Vec3d(1, 1, 0), 1.0,
                FrustumLod.Interaction.FACE);
        Vec3d corner = FrustumLod.edgeWalk(
                hit, Vec3d.ZERO, 1, new Vec3i(0, 1, 0), new Vec3d(1, 1, 0), 1.0,
                FrustumLod.Interaction.CORNER);

        assertEquals(1.0, corner.x, DELTA, "CORNER stops at H's leading vertex");
        assertEquals(2.0, walked.x, DELTA, "FACE continues one cell into E/D");
        assertTrue(walked.x > corner.x);
    }

    @Test
    void gapStaysOnFaceWithoutWalking() {
        Vec3d hit = new Vec3d(0.4, 1.0, 0.5);
        Vec3d walked = FrustumLod.edgeWalk(
                hit, Vec3d.ZERO, 1, new Vec3i(0, 1, 0), new Vec3d(1, 1, 0), 1.0,
                FrustumLod.Interaction.GAP);

        assertEquals(0.4, walked.x, DELTA);
        assertEquals(1.0, walked.y, DELTA);
        assertEquals(0.5, walked.z, DELTA);
    }

    @Test
    void splitReusesCornerWalk() {
        Vec3d hit = new Vec3d(2, 1.0, 1.0);
        Vec3d corner = FrustumLod.edgeWalk(
                hit, Vec3d.ZERO, 2, new Vec3i(1, 0, 0), new Vec3d(1, 1, -1), 1.0,
                FrustumLod.Interaction.CORNER);
        Vec3d split = FrustumLod.edgeWalk(
                hit, Vec3d.ZERO, 2, new Vec3i(1, 0, 0), new Vec3d(1, 1, -1), 1.0,
                FrustumLod.Interaction.SPLIT);
        assertEquals(corner, split);
    }

    @Test
    void trailingCellsAreNotInTheForwardMap() {
        FrustumLod.ForwardMap map = FrustumLod.forwardMap(
                Vec3d.ZERO, 2, new Vec3d(1.0, 2.0, 1.0), new Vec3d(1, 1, 0), new Vec3i(0, -1, 0));
        assertNotNull(map);
        assertTrue(map.nOffset().getY() > 0);
        assertTrue(map.eOffset().getX() > 0);
        assertTrue(map.dOffset().getX() > 0 && map.dOffset().getY() > 0);
        assertEquals(0, map.nOffset().getX());
        assertEquals(0, map.eOffset().getY());
    }

    @Test
    void isStiffRequiresDistinctlyHigherImpedance() {
        assertTrue(FrustumLod.isStiff(1e7, 415));
        assertFalse(FrustumLod.isStiff(415, 415));
        assertFalse(FrustumLod.isStiff(400, 415));
        assertFalse(FrustumLod.isStiff(Double.NaN, 415));
    }

    @Test
    void blocksPermeationUsesHostNeighborInteractionNotOneSidedStiffness() {
        // Air → stone: high R, low permeate → blocks
        assertTrue(FrustumLod.blocksPermeation(415.0, 1e7, 0.05));
        // Air → air: open
        assertFalse(FrustumLod.blocksPermeation(415.0, 415.0, 1.0));
        // Soft absorber with matched-ish Z but very low permeation still blocks
        assertTrue(FrustumLod.blocksPermeation(415.0, 500.0, 0.01));
        // Same mild Z contrast with high permeation does not
        assertFalse(FrustumLod.blocksPermeation(415.0, 500.0, 1.0));
        assertEquals(1.0, FrustumLod.interactionPermeation(415.0, Double.NaN, 0.05), 1e-9);
    }
}
