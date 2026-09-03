package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.material.Material;
import dev.thedocruby.resounding.raycast.Branch;
import dev.thedocruby.resounding.debug.math.OctantColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks a section octree and emits axis-aligned boxes for wireframe rendering.
 */
public final class OctreeOverlay {

	public record OctantView(Box box, Material material, String label, int size, int color) {}

	private OctreeOverlay() {}

	public static List<OctantView> collectOctants(Branch root) {
		List<OctantView> octants = new ArrayList<>();
		collectOctants(root, octants);
		return octants;
	}

	/**
	 * Collects leaf boxes intersecting the player's octant and its six face neighbors at the
	 * anchor resolution. Finer subdivisions inside those seven cells are included; leaves outside
	 * are omitted.
	 */
	public static List<OctantView> collectNeighborhood(Branch root, BlockPos playerPos) {
		Branch anchor = root.get(playerPos);
		int cellSize = anchor.size;
		BlockPos cellOrigin = anchor.start;

		int sectionMinX = root.start.getX();
		int sectionMinY = root.start.getY();
		int sectionMinZ = root.start.getZ();
		int sectionMaxX = sectionMinX + root.size;
		int sectionMaxY = sectionMinY + root.size;
		int sectionMaxZ = sectionMinZ + root.size;

		List<Box> regions = new ArrayList<>(7);
		int[][] offsets = {
				{0, 0, 0},
				{cellSize, 0, 0}, {-cellSize, 0, 0},
				{0, cellSize, 0}, {0, -cellSize, 0},
				{0, 0, cellSize}, {0, 0, -cellSize},
		};
		for (int[] offset : offsets) {
			int ox = cellOrigin.getX() + offset[0];
			int oy = cellOrigin.getY() + offset[1];
			int oz = cellOrigin.getZ() + offset[2];
			if (ox < sectionMinX || oy < sectionMinY || oz < sectionMinZ) {
				continue;
			}
			if (ox + cellSize > sectionMaxX || oy + cellSize > sectionMaxY || oz + cellSize > sectionMaxZ) {
				continue;
			}
			regions.add(new Box(ox, oy, oz, ox + cellSize, oy + cellSize, oz + cellSize));
		}

		List<OctantView> octants = new ArrayList<>();
		collectIntersectingLeaves(root, regions, octants);
		return octants;
	}

	private static void collectIntersectingLeaves(Branch node, List<Box> regions, List<OctantView> octants) {
		Box nodeBox = boxOf(node);
		if (!intersectsAny(nodeBox, regions)) {
			return;
		}
		if (node.leaves.isEmpty()) {
			octants.add(toView(node));
			return;
		}
		for (Branch child : node.leaves.values()) {
			collectIntersectingLeaves(child, regions, octants);
		}
	}

	private static boolean intersectsAny(Box nodeBox, List<Box> regions) {
		for (Box region : regions) {
			if (nodeBox.intersects(region)) {
				return true;
			}
		}
		return false;
	}

	private static Box boxOf(Branch node) {
		int x = node.start.getX();
		int y = node.start.getY();
		int z = node.start.getZ();
		int size = node.size;
		return new Box(x, y, z, x + size, y + size, z + size);
	}

	private static OctantView toView(Branch node) {
		int x = node.start.getX();
		int y = node.start.getY();
		int z = node.start.getZ();
		int size = node.size;
		int color = OctantColor.forNode(node.start, size);
		return new OctantView(
				new Box(x, y, z, x + size, y + size, z + size),
				node.material,
				node.materialLabel,
				size,
				color
		);
	}

	private static void collectOctants(Branch node, List<OctantView> octants) {
		if (!node.leaves.isEmpty()) {
			for (Branch child : node.leaves.values()) {
				collectOctants(child, octants);
			}
			return;
		}
		octants.add(toView(node));
	}

	/**
	 * Collection mode for beam-visited octants / virtual step boxes (runtime-visual Phase D).
	 */
	public static List<OctantView> collectVisited(List<Box> visitedBoxes) {
		List<OctantView> views = new ArrayList<>(visitedBoxes.size());
		for (Box box : visitedBoxes) {
			views.add(toView(box, null, null));
		}
		return views;
	}

	/**
	 * Same as {@link #collectVisited(List)} but tags virtual vs real leaves when the caller marks
	 * them.
	 */
	public static List<OctantView> collectBeamPath(List<VisitedStep> steps) {
		List<OctantView> views = new ArrayList<>(steps.size());
		for (VisitedStep step : steps) {
			String label = step.label();
			if (step.virtual()) {
				label = label == null || label.isEmpty() ? "virtual" : label + " virtual";
			}
			views.add(toView(step.box(), step.material(), label));
		}
		return views;
	}

	private static OctantView toView(Box box, Material material, String label) {
		int size = (int) Math.round(box.maxX - box.minX);
		int originX = (int) Math.round(box.minX);
		int originY = (int) Math.round(box.minY);
		int originZ = (int) Math.round(box.minZ);
		int color = OctantColor.forNode(originX, originY, originZ, size);
		return new OctantView(box, material, label, size, color);
	}

	/** One beam-traversal step for {@link #collectBeamPath(List)}. */
	public record VisitedStep(Box box, Material material, String label, boolean virtual) {}
}
