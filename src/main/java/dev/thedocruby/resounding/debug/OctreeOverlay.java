package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.raycast.Branch;
import net.minecraft.util.math.Box;

import java.util.Collections;
import java.util.List;

/**
 * Walks a section octree and emits one axis-aligned box per node for wireframe rendering.
 */
public final class OctreeOverlay {

    private OctreeOverlay() {}

    public static List<Box> collectOctants(Branch root) {
        return Collections.emptyList();
    }
}
