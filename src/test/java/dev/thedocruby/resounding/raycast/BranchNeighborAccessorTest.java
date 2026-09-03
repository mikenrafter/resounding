package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.fixture.FakeChunkChain;
import dev.thedocruby.resounding.material.Material;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Phase 0 RED tests for {@link Branch#neighbor}: the same-size neighbor accessor. Covers the two
 * resolution outcomes ("neighbor is coarser" / "neighbor is at or finer") plus the
 * cross-section/cross-chunk handoff via {@link dev.thedocruby.resounding.toolbox.ChunkChain}. See
 * references/research/frustums-plan.md's "Neighbor accessor" section.
 */
class BranchNeighborAccessorTest {

    private static final Material STONE = new Material(2700.0, 0.5, 1.0);
    private static final Material GRASS = new Material(500.0, 0.8, 0.9);
    private static final Material WATER = new Material(1480.0, 0.6, 0.75);

    @Test
    void coarserNeighbor_prunedHomogeneousAncestor_isTrivialReplication() {
        // Section root (0,0,0) size 4, homogeneous stone: pruned, leaves empty, exactly as
        // OctreeManager.growOctree leaves a uniform region.
        Branch root = new Branch(new BlockPos(0, 0, 0), 4, STONE);

        FakeChunkChain chain = new FakeChunkChain(0, 0);
        chain.putSection(0, root);

        // Accessing octant (west of the root), size 2, asking what's to its east.
        Branch accessor = new Branch(new BlockPos(-2, 0, 0), 2);
        Branch result = accessor.neighbor(new Vec3i(1, 0, 0), chain);

        assertEquals(2, result.size, "virtual node must be the accessor's own size, not the coarser ancestor's");
        assertEquals(new BlockPos(0, 0, 0), result.start);
        assertEquals(STONE, result.material);
        assertEquals(STONE.impedance(), result.maxImpedance);
        assertEquals(STONE.impedance(), result.minImpedance);
        assertEquals(STONE.impedance(), result.avgImpedance);
        assertNull(result.polar, "uniform region has no gradient");
    }

    @Test
    void atOrFinerNeighbor_returnsRealPreBakedBranchDirectly_noAggregation() {
        Branch root = new Branch(new BlockPos(0, 0, 0), 4);
        Branch realChild = new Branch(new BlockPos(2, 0, 0), 2, GRASS);
        realChild.maxImpedance = 640.0;
        realChild.minImpedance = 400.0;
        realChild.avgImpedance = 520.0;
        realChild.polar = null;
        root.put(new BlockPos(2, 0, 0).asLong(), realChild);
        // Root is heterogeneous (non-empty leaves) so growOctree-style pruning does not apply here.
        root.put(new BlockPos(0, 0, 0).asLong(), new Branch(new BlockPos(0, 0, 0), 2, STONE));

        FakeChunkChain chain = new FakeChunkChain(0, 0);
        chain.putSection(0, root);

        Branch accessor = new Branch(new BlockPos(0, 0, 0), 2);
        Branch result = accessor.neighbor(new Vec3i(1, 0, 0), chain);

        assertSame(realChild, result, "already-baked descriptor must be returned directly, not recomputed");
    }

    @Test
    void crossChunkHandoff_shiftedBoxLeavesTheSection_usesChunkChainAccess() {
        FakeChunkChain west = new FakeChunkChain(0, 0);
        Branch westRoot = new Branch(new BlockPos(0, 0, 0), 16);
        west.putSection(0, westRoot);

        FakeChunkChain east = west.neighborChunk(1, 0);
        Branch eastRoot = new Branch(new BlockPos(16, 0, 0), 16, GRASS);
        east.putSection(0, eastRoot);

        // Accessor sits at the eastern edge of the west chunk; stepping east crosses into chunk (1,0).
        Branch accessor = new Branch(new BlockPos(14, 0, 0), 2);
        Branch result = accessor.neighbor(new Vec3i(1, 0, 0), west);

        assertEquals(2, result.size);
        assertEquals(new BlockPos(16, 0, 0), result.start);
        assertEquals(GRASS, result.material);
        assertEquals(GRASS.impedance(), result.maxImpedance);
        assertNull(result.polar);
    }

    @Test
    void crossSectionHandoff_shiftedBoxLeavesTheYSection_usesGetBranch() {
        FakeChunkChain chain = new FakeChunkChain(0, 0);
        Branch sectionY0 = new Branch(new BlockPos(0, 0, 0), 16);
        Branch sectionY1 = new Branch(new BlockPos(0, 16, 0), 16, WATER);
        chain.putSection(0, sectionY0);
        chain.putSection(1, sectionY1);

        // Accessor sits at the top edge of section y=0; stepping up crosses into section y=1.
        Branch accessor = new Branch(new BlockPos(0, 14, 0), 2);
        Branch result = accessor.neighbor(new Vec3i(0, 1, 0), chain);

        assertEquals(2, result.size);
        assertEquals(new BlockPos(0, 16, 0), result.start);
        assertEquals(WATER, result.material);
        assertEquals(WATER.impedance(), result.avgImpedance);
        assertNull(result.polar);
    }
}
