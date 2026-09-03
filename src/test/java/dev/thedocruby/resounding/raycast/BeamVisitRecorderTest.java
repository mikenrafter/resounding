package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.material.Material;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Beam visit recorder walks at {@link FrustumLod} step sizes through a known Branch tree.
 */
class BeamVisitRecorderTest {

    private static final Material AIR = new Material(1.2, 1.0, 0.5);
    private static final double GROWTH_PER_BLOCK = 0.5;

    @Test
    void collectAlongBeam_recordsVirtualStepsInsideHomogeneousLargeLeaf() {
        Branch root = new Branch(new BlockPos(0, 0, 0), 16, AIR);
        root.mostCommonImpedance = AIR.impedance();
        root.leastCommonImpedance = AIR.impedance();
        root.avgImpedance = AIR.impedance();

        Beam beam = new Beam(
                new Vec3d(0.5, 0.5, 0.5), new Vec3d(1, 0, 0),
                FrustumLod.BASE_FOOTPRINT, GROWTH_PER_BLOCK, BeamBudget.full()
        );
        List<BeamVisitRecorder.VisitedBox> visits = BeamVisitRecorder.collectAlongBeam(
                root, beam, beam.origin(), beam.direction(), 3.0
        );

        assertFalse(visits.isEmpty(), "beam capture must record visited boxes");
        assertTrue(root.leaves.isEmpty(), "homogeneous leaf must stay pruned (no allocated children)");
        Set<Integer> sizes = visits.stream().map(BeamVisitRecorder.VisitedBox::size).collect(Collectors.toSet());
        assertTrue(sizes.contains(1), "early distance schedule is step 1");
        assertTrue(visits.stream().anyMatch(BeamVisitRecorder.VisitedBox::virtual),
                "finer-than-leaf steps are virtual");
    }

    @Test
    void collectAlongBeam_usesSizeTwoOnceDistanceReachesTier() {
        Branch root = new Branch(new BlockPos(0, 0, 0), 16, AIR);
        root.mostCommonImpedance = AIR.impedance();
        root.leastCommonImpedance = AIR.impedance();
        root.avgImpedance = AIR.impedance();

        Beam beam = new Beam(
                new Vec3d(0.5, 0.5, 0.5), new Vec3d(1, 0, 0),
                FrustumLod.BASE_FOOTPRINT, GROWTH_PER_BLOCK, BeamBudget.full()
        );
        // step schedule hits size 2 at distance >= 4
        List<BeamVisitRecorder.VisitedBox> visits = BeamVisitRecorder.collectAlongBeam(
                root, beam, beam.origin(), beam.direction(), 8.0
        );

        assertTrue(visits.stream().anyMatch(v -> v.size() == 2 && v.virtual()),
                "after 4 blocks of travel, LOD step 2 virtual cells should appear");
    }

    @Test
    void getAtLod_returnsRealChildWhenLodMatchesNodeSize() {
        Branch root = new Branch(new BlockPos(0, 0, 0), 4, AIR);
        Branch left = new Branch(new BlockPos(0, 0, 0), 2, AIR);
        Branch right = new Branch(new BlockPos(2, 0, 0), 2, AIR);
        root.put(left.start.asLong(), left);
        root.put(right.start.asLong(), right);

        Branch atLeft = root.getAtLod(new BlockPos(0, 0, 0), 2);
        Branch atRight = root.getAtLod(new BlockPos(2, 0, 0), 2);

        assertTrue(atLeft == left, "LOD 2 must return the real size-2 child, not drill to virtual 1");
        assertTrue(atRight == right);
    }
}
