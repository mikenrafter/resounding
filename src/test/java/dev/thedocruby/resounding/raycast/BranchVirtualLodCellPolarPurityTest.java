package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.material.Material;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * RED (bug leak #1): a genuine 1x1x1 (finest) octree leaf must never carry a polarization
 * descriptor ({@code NodeDescriptor.polar() != null}) — polarity is a coarse-aggregate concept
 * only. {@link Branch#virtualLodCell} (reached through {@link Branch#getAtLod}) currently copies a
 * coarse pruned node's baked descriptor verbatim into every synthesized virtual cell, regardless of
 * the requested LOD size. That's correct when synthesizing a real LOD aggregate ({@code lodSize >
 * 1}), but wrong at {@code lodSize == 1}: a coarse pruned/homogeneous node can still carry a
 * polarized descriptor from before {@code OctreeManager.sameAcousticCell}'s same-{@code Block}
 * shortcut collapsed it (a known follow-up issue, not under test here) — asking that node for its
 * finest-resolution cell must strip the polar/blend aggregate and fall back to the underlying
 * material's own impedance, exactly like {@link Branch#neighbor}'s coarser-ancestor case already
 * does ("trivial replication ... polar = null").
 */
class BranchVirtualLodCellPolarPurityTest {

    private static final Material AIR = new Material(426.9, 1.0, 0.5);

    /**
     * Size-4 pruned (homogeneous-by-shortcut) node carrying a polarized aggregate descriptor —
     * mirrors what {@code OctreeManager.growOctree}/{@code sameAcousticCell} can leave behind per
     * the bug writeup: {@code children == null} but {@code descriptor.polar() != null}.
     */
    private static Branch pollutedCoarseNode() {
        Branch coarse = new Branch(new BlockPos(0, 0, 0), 4, AIR);
        coarse.bake(new Branch.NodeDescriptor(2_700_000.0, 200.0, 1_350_100.0, 0.75, new Vec3d(2, 0, 0)));
        return coarse;
    }

    @Test
    void lod1_stripsPolarAndUsesUnderlyingMaterialImpedance_notTheBlendedAggregate() {
        Branch coarse = pollutedCoarseNode();

        Branch cell = coarse.getAtLod(new BlockPos(0, 0, 0), 1);

        assertEquals(1, cell.size, "requested lod-1 cell must actually be size 1");
        assertNull(cell.descriptor.polar(),
                "a genuine 1x1x1 leaf must never carry a polarization descriptor");
        assertEquals(AIR.impedance(), cell.effectiveImpedance(), 1e-9,
                "lod-1 impedance must be the underlying material's own impedance, not the blended "
                        + "most/least-common aggregate");
    }

    @Test
    void lodGreaterThanOne_stillCopiesTheCoarseAggregateDescriptorVerbatim() {
        Branch coarse = pollutedCoarseNode();

        Branch cell = coarse.getAtLod(new BlockPos(0, 0, 0), 2);

        assertEquals(2, cell.size);
        assertNotNull(cell.descriptor.polar(),
                "real LOD aggregation at size>=2 must keep the polar vector — pinning the fix "
                        + "against regressing genuine LOD aggregation");
        assertEquals(new Vec3d(2, 0, 0), cell.descriptor.polar());
        assertEquals(2_700_000.0, cell.descriptor.mostCommonImpedance(), 1e-9);
        assertEquals(200.0, cell.descriptor.leastCommonImpedance(), 1e-9);
    }
}
