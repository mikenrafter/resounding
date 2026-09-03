package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.material.Material;
import dev.thedocruby.resounding.raycast.Branch;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Octree walk that emits one box per leaf node; needs BlockPos/Box but not a running client.
 */
class OctreeOverlayTest {

	private static final double EPS = 1e-9;

	@Test
	void collectOctantsEmitsOnlyLeafNodes() {
		BlockPos rootOrigin = new BlockPos(16, 32, 48);
		Branch root = new Branch(rootOrigin, 8);
		int half = root.size >> 1;

		root.put(rootOrigin.asLong(), new Branch(rootOrigin, half));
		root.put(rootOrigin.add(half, 0, 0).asLong(), new Branch(rootOrigin.add(half, 0, 0), half));
		root.put(rootOrigin.add(0, half, 0).asLong(), new Branch(rootOrigin.add(0, half, 0), half));

		List<OctreeOverlay.OctantView> octants = OctreeOverlay.collectOctants(root);

		assertEquals(3, octants.size(), "only subdivided leaves, not the parent node");
		assertBoxPresent(octants, rootOrigin.getX(), rootOrigin.getY(), rootOrigin.getZ(), half);
		assertBoxPresent(octants, rootOrigin.getX() + half, rootOrigin.getY(), rootOrigin.getZ(), half);
		assertBoxPresent(octants, rootOrigin.getX(), rootOrigin.getY() + half, rootOrigin.getZ(), half);
	}

	@Test
	void homogeneousNodeEmitsSingleLeaf() {
		BlockPos origin = new BlockPos(0, 0, 0);
		Branch leaf = new Branch(origin, 16);

		List<OctreeOverlay.OctantView> octants = OctreeOverlay.collectOctants(leaf);

		assertEquals(1, octants.size());
		assertEquals(16, octants.getFirst().size());
	}

	@Test
	void collectNeighborhoodShowsFaceAdjacentCellsOnly() {
		BlockPos rootOrigin = new BlockPos(0, 0, 0);
		Branch root = new Branch(rootOrigin, 16);
		root.put(rootOrigin.asLong(), new Branch(rootOrigin, 8));
		root.put(new BlockPos(8, 0, 0).asLong(), new Branch(new BlockPos(8, 0, 0), 8));
		root.put(new BlockPos(8, 8, 0).asLong(), new Branch(new BlockPos(8, 8, 0), 8));

		List<OctreeOverlay.OctantView> octants = OctreeOverlay.collectNeighborhood(root, new BlockPos(1, 1, 1));

		assertEquals(2, octants.size(), "player cell plus +X face neighbor only");
		assertBoxPresent(octants, 0, 0, 0, 8);
		assertBoxPresent(octants, 8, 0, 0, 8);
	}

	@Test
	void collectNeighborhoodIncludesSubdivisionsInsideNeighborCell() {
		BlockPos rootOrigin = new BlockPos(0, 0, 0);
		Branch root = new Branch(rootOrigin, 8);
		Branch playerCell = new Branch(rootOrigin, 4);
		Branch neighbor = new Branch(new BlockPos(4, 0, 0), 4);
		neighbor.put(new BlockPos(4, 0, 0).asLong(), new Branch(new BlockPos(4, 0, 0), 2));
		neighbor.put(new BlockPos(6, 0, 0).asLong(), new Branch(new BlockPos(6, 0, 0), 2));
		root.put(rootOrigin.asLong(), playerCell);
		root.put(new BlockPos(4, 0, 0).asLong(), neighbor);

		List<OctreeOverlay.OctantView> octants = OctreeOverlay.collectNeighborhood(root, new BlockPos(1, 1, 1));

		assertEquals(3, octants.size(), "player 4³ leaf plus two subdivided 2³ leaves in +X neighbor");
		assertBoxPresent(octants, 0, 0, 0, 4);
		assertBoxPresent(octants, 4, 0, 0, 2);
		assertBoxPresent(octants, 6, 0, 0, 2);
	}

	@Test
	void collectVisited_returnsViewsForProvidedBeamBoxes() {
		List<Box> visited = List.of(
				new Box(0, 0, 0, 2, 2, 2),
				new Box(2, 0, 0, 4, 2, 2)
		);

		List<OctreeOverlay.OctantView> views = OctreeOverlay.collectVisited(visited);

		assertEquals(2, views.size());
		assertBoxPresent(views, 0, 0, 0, 2);
		assertBoxPresent(views, 2, 0, 0, 2);
	}

	@Test
	void collectBeamPath_preservesVirtualVsRealDistinctionInLabels() {
		Material air = new Material(1.2, 1.0, 0.5);
		List<OctreeOverlay.VisitedStep> steps = List.of(
				new OctreeOverlay.VisitedStep(new Box(0, 0, 0, 16, 16, 16), air, "air", false),
				new OctreeOverlay.VisitedStep(new Box(0, 0, 0, 1, 1, 1), air, "air", true)
		);

		List<OctreeOverlay.OctantView> views = OctreeOverlay.collectBeamPath(steps);

		assertEquals(2, views.size());
		assertTrue(views.stream().anyMatch(v -> v.size() == 16 && (v.label() == null || !v.label().contains("virtual"))));
		assertTrue(views.stream().anyMatch(v -> v.size() == 1 && v.label() != null && v.label().toLowerCase().contains("virtual")),
				"virtual steps should be labeled so the overlay can distinguish them from real leaves");
	}

	@Test
	void collectNeighborhoodStillWorksAlongsideVisitedMode() {
		BlockPos rootOrigin = new BlockPos(0, 0, 0);
		Branch root = new Branch(rootOrigin, 16);
		root.put(rootOrigin.asLong(), new Branch(rootOrigin, 8));
		root.put(new BlockPos(8, 0, 0).asLong(), new Branch(new BlockPos(8, 0, 0), 8));

		List<OctreeOverlay.OctantView> neighborhood = OctreeOverlay.collectNeighborhood(root, new BlockPos(1, 1, 1));
		assertEquals(2, neighborhood.size());
	}

	private static void assertBoxPresent(List<OctreeOverlay.OctantView> octants, int originX, int originY, int originZ, int size) {
		boolean found = octants.stream().anyMatch(octant -> {
			var box = octant.box();
			return Math.abs(box.minX - originX) < EPS
					&& Math.abs(box.minY - originY) < EPS
					&& Math.abs(box.minZ - originZ) < EPS
					&& Math.abs(box.maxX - (originX + size)) < EPS
					&& Math.abs(box.maxY - (originY + size)) < EPS
					&& Math.abs(box.maxZ - (originZ + size)) < EPS;
		});
		assertTrue(found, () -> "expected box at (" + originX + "," + originY + "," + originZ + ") size " + size);
	}
}
