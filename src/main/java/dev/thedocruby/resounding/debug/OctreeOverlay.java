package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.raycast.Branch;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks a section octree and emits one axis-aligned box per node for wireframe rendering.
 */
public final class OctreeOverlay {

    private OctreeOverlay() {}

    public static List<Box> collectOctants(Branch root) {
        List<Box> boxes = new ArrayList<>();
        collectOctants(root, boxes);
        return boxes;
    }

    private static void collectOctants(Branch node, List<Box> boxes) {
        int x = node.start.getX();
        int y = node.start.getY();
        int z = node.start.getZ();
        int size = node.size;
        boxes.add(new Box(x, y, z, x + size, y + size, z + size));

        if (!node.leaves.isEmpty()) {
            for (Branch child : node.leaves.values()) {
                collectOctants(child, boxes);
            }
        }
    }
}
