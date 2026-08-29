package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.material.Material;
import dev.thedocruby.resounding.raycast.Branch;
import dev.thedocruby.resounding.debug.math.OctantColor;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks a section octree and emits one axis-aligned box per leaf node for wireframe rendering.
 */
public final class OctreeOverlay {

	public record OctantView(Box box, Material material, int size, int color) {}

	private OctreeOverlay() {}

	public static List<OctantView> collectOctants(Branch root) {
		List<OctantView> octants = new ArrayList<>();
		collectOctants(root, octants);
		return octants;
	}

	private static void collectOctants(Branch node, List<OctantView> octants) {
		if (!node.leaves.isEmpty()) {
			for (Branch child : node.leaves.values()) {
				collectOctants(child, octants);
			}
			return;
		}

		int x = node.start.getX();
		int y = node.start.getY();
		int z = node.start.getZ();
		int size = node.size;
		int color = OctantColor.forNode(node.start, size);
		octants.add(new OctantView(new Box(x, y, z, x + size, y + size, z + size), node.material, size, color));
	}
}
