package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.material.Material;
import dev.thedocruby.resounding.raycast.Branch;
import dev.thedocruby.resounding.debug.math.OctantColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.BlockView;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks a section octree and emits axis-aligned boxes for wireframe rendering.
 */
public final class OctreeOverlay {

	public record OctantView(
			Box box,
			Material material,
			String label,
			int size,
			int color,
			@Nullable Vec3d polar,
			/** N/E/D(+H) cluster id for focused frustum; null when not part of a set. */
			@Nullable Integer setId,
			/** True when this cube is the incident host (H) of its set. */
			boolean incidentHost,
			/** Exit / hit face of H ({@code ±X/±Y/±Z} unit); null when not the host. */
			@Nullable Vec3i incidentFace
	) {
		public OctantView(
				Box box,
				Material material,
				String label,
				int size,
				int color,
				@Nullable Vec3d polar,
				@Nullable Integer setId
		) {
			this(box, material, label, size, color, polar, setId, false, null);
		}

		public OctantView(Box box, Material material, String label, int size, int color, @Nullable Vec3d polar) {
			this(box, material, label, size, color, polar, null, false, null);
		}
	}

	private OctreeOverlay() {}

	public static List<OctantView> collectOctants(Branch root) {
		return collectOctants(root, null);
	}

	public static List<OctantView> collectOctants(Branch root, @Nullable BlockView world) {
		List<OctantView> octants = new ArrayList<>();
		collectOctants(root, world, octants);
		return octants;
	}

	/**
	 * Collects leaf boxes intersecting the player's octant and its six face neighbors at the
	 * anchor resolution. Finer subdivisions inside those seven cells are included; leaves outside
	 * are omitted.
	 */
	public static List<OctantView> collectNeighborhood(Branch root, BlockPos playerPos) {
		return collectNeighborhood(root, playerPos, null);
	}

	public static List<OctantView> collectNeighborhood(Branch root, BlockPos playerPos, @Nullable BlockView world) {
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
		collectIntersectingLeaves(root, world, regions, octants);
		return octants;
	}

	private static void collectIntersectingLeaves(Branch node, @Nullable BlockView world, List<Box> regions, List<OctantView> octants) {
		Box nodeBox = boxOf(node);
		if (!intersectsAny(nodeBox, regions)) {
			return;
		}
		if (node.isEmpty()) {
			octants.add(toView(node, world));
			return;
		}
		for (Branch child : node.children) {
			if (child != null) {
				collectIntersectingLeaves(child, world, regions, octants);
			}
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

	private static OctantView toView(Branch node, @Nullable BlockView world) {
		int x = node.start.getX();
		int y = node.start.getY();
		int z = node.start.getZ();
		int size = node.size;
		int color = OctantColor.forNode(node.start, size);
		return new OctantView(
				new Box(x, y, z, x + size, y + size, z + size),
				node.material,
				node.ensureMaterialLabel(world),
				size,
				color,
				node.polar()
		);
	}

	private static void collectOctants(Branch node, @Nullable BlockView world, List<OctantView> octants) {
		if (!node.isEmpty()) {
			for (Branch child : node.children) {
				if (child != null) {
					collectOctants(child, world, octants);
				}
			}
			return;
		}
		octants.add(toView(node, world));
	}

	/**
	 * Collection mode for beam-visited octants / virtual step boxes (runtime-visual Phase D).
	 */
	public static List<OctantView> collectVisited(List<Box> visitedBoxes) {
		List<OctantView> views = new ArrayList<>(visitedBoxes.size());
		for (Box box : visitedBoxes) {
			views.add(toView(box, null, null, null));
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
			views.add(toView(step.box(), step.material(), label, step.polar(), step.color()));
		}
		return views;
	}

	private static OctantView toView(Box box, Material material, String label, @Nullable Vec3d polar) {
		return toView(box, material, label, polar, null);
	}

	private static OctantView toView(
			Box box,
			Material material,
			String label,
			@Nullable Vec3d polar,
			@Nullable Integer colorOverride
	) {
		int size = (int) Math.round(box.maxX - box.minX);
		int originX = (int) Math.round(box.minX);
		int originY = (int) Math.round(box.minY);
		int originZ = (int) Math.round(box.minZ);
		int color = colorOverride != null ? colorOverride : OctantColor.forNode(originX, originY, originZ, size);
		return new OctantView(box, material, label, size, color, polar);
	}

	/** One beam-traversal step for {@link #collectBeamPath(List)}. */
	public record VisitedStep(
			Box box,
			Material material,
			String label,
			boolean virtual,
			@Nullable Vec3d polar,
			@Nullable Integer color
	) {
		public VisitedStep(Box box, Material material, String label, boolean virtual) {
			this(box, material, label, virtual, null, null);
		}

		public VisitedStep(Box box, Material material, String label, boolean virtual, @Nullable Vec3d polar) {
			this(box, material, label, virtual, polar, null);
		}
	}
}
