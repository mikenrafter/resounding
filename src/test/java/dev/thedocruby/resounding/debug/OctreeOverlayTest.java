package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.raycast.Branch;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Octree walk that emits one box per node; needs BlockPos/Box but not a running client.
 */
class OctreeOverlayTest {

    private static final double EPS = 1e-9;

    @Test
    void collectOctantsEmitsOneBoxPerNodeForHandBuiltTree() {
        BlockPos rootOrigin = new BlockPos(16, 32, 48);
        Branch root = new Branch(rootOrigin, 8);
        int half = root.size >> 1;

        root.put(rootOrigin.asLong(), new Branch(rootOrigin, half));
        root.put(rootOrigin.add(half, 0, 0).asLong(), new Branch(rootOrigin.add(half, 0, 0), half));
        root.put(rootOrigin.add(0, half, 0).asLong(), new Branch(rootOrigin.add(0, half, 0), half));

        List<Box> boxes = OctreeOverlay.collectOctants(root);

        assertEquals(4, boxes.size(), "root plus three subdivided children");
        assertBoxPresent(boxes, rootOrigin.getX(), rootOrigin.getY(), rootOrigin.getZ(), root.size);
        assertBoxPresent(boxes, rootOrigin.getX(), rootOrigin.getY(), rootOrigin.getZ(), half);
        assertBoxPresent(boxes, rootOrigin.getX() + half, rootOrigin.getY(), rootOrigin.getZ(), half);
        assertBoxPresent(boxes, rootOrigin.getX(), rootOrigin.getY() + half, rootOrigin.getZ(), half);
    }

    private static void assertBoxPresent(List<Box> boxes, int originX, int originY, int originZ, int size) {
        boolean found = boxes.stream().anyMatch(box ->
                Math.abs(box.minX - originX) < EPS
                        && Math.abs(box.minY - originY) < EPS
                        && Math.abs(box.minZ - originZ) < EPS
                        && Math.abs(box.maxX - (originX + size)) < EPS
                        && Math.abs(box.maxY - (originY + size)) < EPS
                        && Math.abs(box.maxZ - (originZ + size)) < EPS);
        assertTrue(found, () -> "expected box at (" + originX + "," + originY + "," + originZ + ") size " + size);
    }
}
