package dev.thedocruby.resounding.raycast;

import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Forward-orthant N/E/D maps on XY / XZ / YZ, and edge-walk that only corners on CORNER/SPLIT.
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
    void xyHitPlusYSurveysForwardXAndDiagonal() {
        // Hit +Y face of H, ray heading +X +Y (SW→NE in the XY plane).
        FrustumLod.ForwardMap map = FrustumLod.forwardMap(
                new Vec3i(0, 1, 0), new Vec3d(1, 1, 0), 2);
        assertEquals(1, map.faceAxis());
        assertEquals(0, map.tangentAxis(), "leading tangent is +X");
        assertEquals(new Vec3i(0, 2, 0), map.nOffset());
        assertEquals(new Vec3i(2, 0, 0), map.eOffset());
        assertEquals(new Vec3i(2, 2, 0), map.dOffset());
    }

    @Test
    void xzHitPlusZSurveysForwardXAndDiagonal() {
        FrustumLod.ForwardMap map = FrustumLod.forwardMap(
                new Vec3i(0, 0, 1), new Vec3d(1, 0, 1), 2);
        assertEquals(2, map.faceAxis());
        assertEquals(0, map.tangentAxis());
        assertEquals(new Vec3i(0, 0, 2), map.nOffset());
        assertEquals(new Vec3i(2, 0, 0), map.eOffset());
        assertEquals(new Vec3i(2, 0, 2), map.dOffset());
    }

    @Test
    void yzHitPlusZSurveysForwardYAndDiagonal() {
        FrustumLod.ForwardMap map = FrustumLod.forwardMap(
                new Vec3i(0, 0, 1), new Vec3d(0, 1, 1), 2);
        assertEquals(2, map.faceAxis());
        assertEquals(1, map.tangentAxis());
        assertEquals(new Vec3i(0, 0, 2), map.nOffset());
        assertEquals(new Vec3i(0, 2, 0), map.eOffset());
        assertEquals(new Vec3i(0, 2, 2), map.dOffset());
    }

    @Test
    void castNegativePlaneIndexStillWalksForwardThroughTheFace() {
        // Cast stores plane = -sign(ray). Travel +X, plane is -X; N must still be +X.
        FrustumLod.ForwardMap map = FrustumLod.forwardMap(
                new Vec3i(-1, 0, 0), new Vec3d(1, 1, 0), 2);
        assertEquals(new Vec3i(2, 0, 0), map.nOffset());
        assertEquals(new Vec3i(0, 2, 0), map.eOffset());
    }

    @Test
    void headOnHasNoTangentAndClassifiesAsFace() {
        FrustumLod.ForwardMap map = FrustumLod.forwardMap(
                new Vec3i(1, 0, 0), new Vec3d(1, 0, 0), 2);
        assertFalse(map.hasTangent());
        assertEquals(FrustumLod.Interaction.FACE, FrustumLod.classify(true, false, true));
        assertEquals(FrustumLod.Interaction.GAP, FrustumLod.classify(false, false, false));
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
                new Vec3i(0, 1, 0), new Vec3d(1, 1, 0), 2);
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
