package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.fixture.FakeChunkChain;
import dev.thedocruby.resounding.material.Material;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 0.5 RED tests for the edge-neighbor resolution described in frustums-plan.md's "Neighbor
 * resolution for the interaction: edges, not vertices": the {@code start % (size*2) == 0}
 * same-parent/cross-parent arithmetic decision ({@link Branch#crossesParentBoundary}), and the two
 * dispatch paths it feeds ({@link Branch#edgeNeighbors}).
 */
class BranchEdgeNeighborTest {

    private static final Material STONE = new Material(2700.0, 0.5, 1.0);
    private static final Material GRASS = new Material(500.0, 0.8, 0.9);
    private static final Material AIR = new Material(1.2, 1.0, 0.5);
    private static final Material DIRT = new Material(1200.0, 0.6, 0.95);
    private static final Material SAND = new Material(1400.0, 0.55, 0.9);
    private static final Material GRAVEL = new Material(1600.0, 0.5, 0.95);
    private static final Material CLAY = new Material(1500.0, 0.6, 0.9);
    private static final Material ICE = new Material(3200.0, 0.9, 1.0);
    private static final Material SNOW = new Material(600.0, 0.85, 0.9);

    // --- crossesParentBoundary arithmetic: start % (size*2) == 0 ---------------------------------

    @Test
    void lowChildOnX_crossesOnlyWhenSteppingNegative() {
        Branch low = new Branch(new BlockPos(0, 0, 0), 2); // 0 % 4 == 0 -> low child on X
        assertTrue(low.crossesParentBoundary(new Vec3i(-1, 0, 0)));
        assertFalse(low.crossesParentBoundary(new Vec3i(1, 0, 0)));
    }

    @Test
    void highChildOnX_crossesOnlyWhenSteppingPositive() {
        Branch high = new Branch(new BlockPos(2, 0, 0), 2); // 2 % 4 == 2 != 0 -> high child on X
        assertTrue(high.crossesParentBoundary(new Vec3i(1, 0, 0)));
        assertFalse(high.crossesParentBoundary(new Vec3i(-1, 0, 0)));
    }

    @Test
    void lowAndHighChildOnY_atLargerSize() {
        Branch low = new Branch(new BlockPos(0, 0, 0), 4); // 0 % 8 == 0 -> low child on Y
        Branch high = new Branch(new BlockPos(0, 4, 0), 4); // 4 % 8 == 4 != 0 -> high child on Y

        assertTrue(low.crossesParentBoundary(new Vec3i(0, -1, 0)));
        assertFalse(low.crossesParentBoundary(new Vec3i(0, 1, 0)));

        assertTrue(high.crossesParentBoundary(new Vec3i(0, 1, 0)));
        assertFalse(high.crossesParentBoundary(new Vec3i(0, -1, 0)));
    }

    @Test
    void lowAndHighChildOnZ() {
        Branch low = new Branch(new BlockPos(0, 0, 0), 2);
        Branch high = new Branch(new BlockPos(0, 0, 2), 2);

        assertTrue(low.crossesParentBoundary(new Vec3i(0, 0, -1)));
        assertFalse(low.crossesParentBoundary(new Vec3i(0, 0, 1)));

        assertTrue(high.crossesParentBoundary(new Vec3i(0, 0, 1)));
        assertFalse(high.crossesParentBoundary(new Vec3i(0, 0, -1)));
    }

    // --- edgeNeighbors dispatch: same-parent (free) vs cross-parent (falls through to Phase 0) ----

    private static Branch buildParentWithEightChildren() {
        Branch parent = new Branch(new BlockPos(0, 0, 0), 4);
        Material[] materials = { STONE, GRASS, AIR, DIRT, SAND, GRAVEL, CLAY, ICE };
        BlockPos[] offsets = {
                new BlockPos(0, 0, 0), new BlockPos(2, 0, 0), new BlockPos(0, 2, 0), new BlockPos(2, 2, 0),
                new BlockPos(0, 0, 2), new BlockPos(2, 0, 2), new BlockPos(0, 2, 2), new BlockPos(2, 2, 2),
        };
        for (int i = 0; i < offsets.length; i++) {
            Branch child = new Branch(offsets[i], 2, materials[i]);
            parent.put(offsets[i].asLong(), child);
        }
        return parent;
    }

    @Test
    void sameParentEdge_resolvesViaFreeSiblingLookup_noChunkNeeded() {
        Branch parent = buildParentWithEightChildren();
        Branch corner = parent.leaves.get(new BlockPos(0, 0, 0).asLong());

        // Stepping +X from the (0,0,0) corner child stays inside the parent (it's the low child on
        // X), so this is the same-parent case: a free lookup in parent.leaves, no chunk required.
        Branch[] result = corner.edgeNeighbors(new Vec3i(1, 0, 0), parent, null);

        assertEquals(3, result.length, "an edge in 3D is shared by exactly 4 cells: this octant + 3 others");
        for (Branch neighbor : result) {
            assertNotSame(corner, neighbor, "must not include the querying octant itself");
            assertTrue(parent.leaves.containsValue(neighbor), "same-parent case must resolve from parent.leaves, not conjure new branches");
        }
    }

    @Test
    void crossParentEdge_resolvesNeighborMaterialFromAdjacentChunkSection() {
        // Parent at origin; -X from the (0,0,0) child exits into chunk (-1,0). The old false-green
        // only checked length/accessCalls while neighbor() NPEs were swallowed into empty Branches —
        // assert the resolved face neighbor's material/start against real section content instead.
        Branch parent = buildParentWithEightChildren();
        Branch corner = parent.leaves.get(new BlockPos(0, 0, 0).asLong());

        FakeChunkChain home = new FakeChunkChain(0, 0);
        Branch homeRoot = new Branch(new BlockPos(0, 0, 0), 16);
        homeRoot.put(parent.start.asLong(), parent);
        home.putSection(0, homeRoot);

        FakeChunkChain west = home.neighborChunk(-1, 0);
        Branch westRoot = new Branch(new BlockPos(-16, 0, 0), 16, SNOW);
        westRoot.maxImpedance = SNOW.impedance();
        westRoot.minImpedance = SNOW.impedance();
        westRoot.avgImpedance = SNOW.impedance();
        west.putSection(0, westRoot);

        Branch[] result = corner.edgeNeighbors(new Vec3i(-1, 0, 0), parent, home);

        assertEquals(3, result.length);
        Branch faceNeighbor = null;
        for (Branch neighbor : result) {
            if (neighbor.start.getX() < 0 && neighbor.start.getY() == 0 && neighbor.start.getZ() == 0) {
                faceNeighbor = neighbor;
                break;
            }
        }
        assertNotNull(faceNeighbor, "must include the -X face neighbor across the chunk boundary");
        assertEquals(new BlockPos(-2, 0, 0), faceNeighbor.start);
        assertEquals(SNOW, faceNeighbor.material,
                "cross-parent edge must resolve real SNOW content, not an empty NPE-placeholder");
        assertEquals(SNOW.impedance(), faceNeighbor.maxImpedance);
        for (Branch neighbor : result) {
            assertFalse(neighbor.material == null && Double.isNaN(neighbor.maxImpedance),
                    "no pinwheel member may be an empty NPE-swallow placeholder at " + neighbor.start);
        }
    }

    @Test
    void hitPointsNearDifferentEdgesOfSameFace_returnDifferentSiblingSets() {
        Branch parent = buildParentWithEightChildren();
        // Interior cell so +X face stays same-parent; hit near +Y vs +Z edge of that face.
        Branch cell = parent.leaves.get(new BlockPos(0, 0, 0).asLong());
        Vec3i face = new Vec3i(1, 0, 0);

        // +X face of the (0,0,0) size-2 cell spans x=2, y∈[0,2], z∈[0,2].
        Vec3d nearPositiveY = new Vec3d(2.0, 1.8, 1.0);
        Vec3d nearPositiveZ = new Vec3d(2.0, 1.0, 1.8);

        Branch[] nearY = cell.edgeNeighbors(face, parent, null, nearPositiveY);
        Branch[] nearZ = cell.edgeNeighbors(face, parent, null, nearPositiveZ);

        assertEquals(3, nearY.length);
        assertEquals(3, nearZ.length);
        Set<Long> yStarts = Arrays.stream(nearY).map(b -> b.start.asLong()).collect(Collectors.toCollection(HashSet::new));
        Set<Long> zStarts = Arrays.stream(nearZ).map(b -> b.start.asLong()).collect(Collectors.toCollection(HashSet::new));
        assertNotEquals(yStarts, zStarts,
                "hits on the same face near different edges must select different pinwheel sibling sets");
    }
}
