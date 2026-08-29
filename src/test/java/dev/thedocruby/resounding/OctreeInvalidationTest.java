package dev.thedocruby.resounding;

import dev.thedocruby.resounding.material.Material;
import dev.thedocruby.resounding.raycast.Branch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Targeted invalidation must clear only the 1³ leaf along {@code pos}, leaving sibling octants
 * on their coarse cached materials so raycasts keep large-cell stepping.
 */
class OctreeInvalidationTest {

	private static final Material STONE = new Material(2.0, 0.5, 1.0);

	private static Branch subdivided2Cube(Material material) {
		Branch root = new Branch(new BlockPos(0, 0, 0), 2);
		for (BlockPos offset : OctreeManager.blockSequence) {
			BlockPos origin = root.start.add(offset);
			root.put(origin.asLong(), new Branch(origin, 1, material));
		}
		root.material = null;
		return root;
	}

	@Test
	void invalidatePathClearsOnlyTheTargetLeaf() {
		Branch root = subdivided2Cube(STONE);

		OctreeManager.invalidatePath(null, root, new BlockPos(1, 0, 0));

		assertNull(root.get(new BlockPos(1, 0, 0)).material);
		assertNotNull(root.get(new BlockPos(0, 0, 0)).material);
		assertEquals(STONE, root.get(new BlockPos(0, 0, 0)).material);
	}

	@Test
	void siblingOctantsKeepCoarseSteppingSize() {
		Branch root = subdivided2Cube(STONE);

		OctreeManager.invalidatePath(null, root, new BlockPos(1, 1, 1));

		assertEquals(1, root.get(new BlockPos(0, 0, 0)).size);
		assertEquals(1, root.get(new BlockPos(1, 1, 0)).size);
		assertNull(root.get(new BlockPos(1, 1, 1)).material);
	}
}
