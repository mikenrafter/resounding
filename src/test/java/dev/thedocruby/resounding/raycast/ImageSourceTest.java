package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.material.Material;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 RED tests for {@link ImageSource}: mirror-point geometry, the visibility test (via a
 * fake {@link ImageSource.Occluder}, keeping this a near-pure unit decoupled from real octree
 * traversal), and order-1 candidate selection. Order-1 only, per frustums-plan.md Phase 3's
 * "Cap exact image-source at order 1" — no order-2+ coverage here.
 */
class ImageSourceTest {

    private static final double DELTA = 1e-9;
    private static final Material STONE = new Material(2700.0, 0.5, 1.0);

    // --- mirrorSource: reflect a point across a patch's plane --------------------------------------

    @Test
    void mirrorAcrossXPlane() {
        Patch patch = new Patch(new Vec3d(5, 0, 0), new Vec3i(1, 0, 0), 1.0, STONE);
        Vec3d mirrored = ImageSource.mirrorSource(new Vec3d(2, 3, 4), patch);
        assertEquals(new Vec3d(8, 3, 4), mirrored, "reflecting x=2 across the plane x=5 gives x=8; y/z untouched");
    }

    @Test
    void mirrorAcrossYPlane() {
        Patch patch = new Patch(new Vec3d(0, 10, 0), new Vec3i(0, 1, 0), 1.0, STONE);
        Vec3d mirrored = ImageSource.mirrorSource(new Vec3d(1, 2, 3), patch);
        assertEquals(new Vec3d(1, 18, 3), mirrored, "reflecting y=2 across the plane y=10 gives y=18");
    }

    @Test
    void mirrorAcrossNegativeZPlane_normalSignDoesNotChangeWhichPlane() {
        // normal (0,0,-1) identifies the same z=centroid.z plane as (0,0,1) would.
        Patch patch = new Patch(new Vec3d(0, 0, -4), new Vec3i(0, 0, -1), 1.0, STONE);
        Vec3d mirrored = ImageSource.mirrorSource(new Vec3d(0, 0, 1), patch);
        assertEquals(new Vec3d(0, 0, -9), mirrored, "reflecting z=1 across the plane z=-4 gives z=-9");
    }

    @Test
    void mirroringTwiceReturnsTheOriginalPoint() {
        Patch patch = new Patch(new Vec3d(3, 3, 3), new Vec3i(0, 1, 0), 1.0, STONE);
        Vec3d source = new Vec3d(1, 2, 3.5);
        Vec3d once = ImageSource.mirrorSource(source, patch);
        Vec3d twice = ImageSource.mirrorSource(once, patch);
        assertEquals(source.x, twice.x, DELTA);
        assertEquals(source.y, twice.y, DELTA);
        assertEquals(source.z, twice.z, DELTA);
    }

    // --- hasLineOfSight: delegates occlusion testing to the injected Occluder ----------------------

    @Test
    void hasLineOfSightIsTrueWhenOccluderReportsNothingBlocking() {
        Vec3d mirror = new Vec3d(0, 0, 0);
        Vec3d listener = new Vec3d(10, 0, 0);
        boolean result = ImageSource.hasLineOfSight(mirror, listener, (from, to) -> false);
        assertTrue(result);
    }

    @Test
    void hasLineOfSightIsFalseWhenOccluderReportsBlocked() {
        Vec3d mirror = new Vec3d(0, 0, 0);
        Vec3d listener = new Vec3d(10, 0, 0);
        boolean result = ImageSource.hasLineOfSight(mirror, listener, (from, to) -> true);
        assertFalse(result);
    }

    @Test
    void hasLineOfSightPassesTheExactMirrorAndListenerPointsToTheOccluder() {
        Vec3d mirror = new Vec3d(1, 2, 3);
        Vec3d listener = new Vec3d(4, 5, 6);
        Vec3d[] seenFrom = new Vec3d[1];
        Vec3d[] seenTo = new Vec3d[1];
        ImageSource.hasLineOfSight(mirror, listener, (from, to) -> {
            seenFrom[0] = from;
            seenTo[0] = to;
            return false;
        });
        assertEquals(mirror, seenFrom[0], "occluder must be queried with the mirror point as one endpoint");
        assertEquals(listener, seenTo[0], "occluder must be queried with the listener as the other endpoint");
    }

    // --- firstOrderEchoes: order-1 exact, unconditional candidate search ---------------------------

    @Test
    void firstOrderEchoesKeepsOnlyPatchesWithClearLineOfSight() {
        Patch visible = new Patch(new Vec3d(5, 0, 0), new Vec3i(1, 0, 0), 1.0, STONE);
        Patch blocked = new Patch(new Vec3d(0, 5, 0), new Vec3i(0, 1, 0), 1.0, STONE);
        Vec3d source = new Vec3d(0, 0, 0);
        Vec3d listener = new Vec3d(1, 1, 1);

        ImageSource.Occluder occluder = (from, to) -> from.equals(ImageSource.mirrorSource(source, blocked));

        List<ImageSource.Candidate> result = ImageSource.firstOrderEchoes(
                source, listener, List.of(visible, blocked), occluder
        );

        assertEquals(1, result.size());
        assertEquals(visible, result.get(0).patch());
    }

    @Test
    void firstOrderEchoesReturnsEmptyWhenNoPatchesGiven() {
        List<ImageSource.Candidate> result = ImageSource.firstOrderEchoes(
                Vec3d.ZERO, new Vec3d(1, 1, 1), List.of(), (from, to) -> false
        );
        assertTrue(result.isEmpty());
    }
}
