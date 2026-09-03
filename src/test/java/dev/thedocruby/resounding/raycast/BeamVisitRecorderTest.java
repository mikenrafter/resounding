package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.material.Material;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase C RED tests: given a {@link Beam} stepping through a known {@link Branch} tree, the visit
 * recorder lists boxes at {@link Cast#effectiveStepSize(int, double)} resolution.
 */
class BeamVisitRecorderTest {

    private static final Material AIR = new Material(1.2, 1.0, 0.5);

    @Test
    void collectAlongBeam_recordsVirtualStepsInsideHomogeneousLargeLeaf() {
        // Homogeneous size-16 air leaf stays pruned; footprint 1.5 → effective step 1, so a short
        // +X march must still emit multiple virtual 1³ boxes without subdividing the Branch.
        Branch root = new Branch(new BlockPos(0, 0, 0), 16, AIR);
        root.maxImpedance = AIR.impedance();
        root.minImpedance = AIR.impedance();
        root.avgImpedance = AIR.impedance();

        Beam beam = new Beam(new Vec3d(0.5, 0.5, 0.5), new Vec3d(1, 0, 0), 1.5, 0.0, BeamBudget.full());
        List<BeamVisitRecorder.VisitedBox> visits = BeamVisitRecorder.collectAlongBeam(
                root, beam, beam.origin(), beam.direction(), 3.0
        );

        assertFalse(visits.isEmpty(), "beam capture must record visited boxes");
        assertTrue(root.leaves.isEmpty(), "homogeneous leaf must stay pruned (no allocated children)");
        assertEquals(1, Cast.effectiveStepSize(root.size, beam.footprintRadiusAt(0.0)));

        Set<Integer> sizes = visits.stream().map(BeamVisitRecorder.VisitedBox::size).collect(Collectors.toSet());
        assertTrue(sizes.contains(1), "visits must be at effective step resolution (1), not only the size-16 leaf");
        assertTrue(visits.stream().anyMatch(BeamVisitRecorder.VisitedBox::virtual),
                "finer-than-leaf steps are virtual");
    }

    @Test
    void collectAlongBeam_includesRealLeafBoundsWhenStepMatchesBranch() {
        Branch root = new Branch(new BlockPos(0, 0, 0), 4, AIR);
        root.put(new BlockPos(0, 0, 0).asLong(), new Branch(new BlockPos(0, 0, 0), 2, AIR));
        root.put(new BlockPos(2, 0, 0).asLong(), new Branch(new BlockPos(2, 0, 0), 2, AIR));

        // Footprint 8 against size-2 children → step min(2,8)=2, matching real leaves.
        Beam beam = new Beam(new Vec3d(0.5, 0.5, 0.5), new Vec3d(1, 0, 0), 8.0, 0.0, BeamBudget.full());
        List<BeamVisitRecorder.VisitedBox> visits = BeamVisitRecorder.collectAlongBeam(
                root, beam, beam.origin(), beam.direction(), 4.0
        );

        assertTrue(visits.stream().anyMatch(v -> v.start().equals(new BlockPos(0, 0, 0)) && v.size() == 2));
        assertTrue(visits.stream().anyMatch(v -> v.start().equals(new BlockPos(2, 0, 0)) && v.size() == 2));
    }
}
