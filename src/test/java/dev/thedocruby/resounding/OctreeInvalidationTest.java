package dev.thedocruby.resounding;

import dev.thedocruby.resounding.fixture.SectionChunks;
import dev.thedocruby.resounding.material.Material;
import dev.thedocruby.resounding.raycast.Branch;
import dev.thedocruby.resounding.tag.Ident;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Targeted invalidation must clear only the 1³ leaf along {@code pos}, leaving sibling octants
 * on their coarse cached materials so raycasts keep large-cell stepping — and re-bake finite
 * descriptors on the touched path (runtime-visual Phase A).
 */
class OctreeInvalidationTest {

	private static final Material STONE = new Material(2700.0, 0.5, 1.0);
	private static final Material AIR = new Material(1.2, 1.0, 0.5);

	private static BlockState stoneState;
	private static BlockState airState;

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.createGameVersion();
		Bootstrap.initialize();
		stoneState = Blocks.STONE.getDefaultState();
		airState = Blocks.AIR.getDefaultState();
	}

	@BeforeEach
	void publishMaterials() {
		MaterialRegistry.publish(Map.ofEntries(
				Map.entry(Ident.parse("minecraft:stone"), STONE),
				Map.entry(Ident.parse("minecraft:air"), AIR)
		));
	}

	private static Branch subdivided2Cube(Material material) {
		Branch root = new Branch(new BlockPos(0, 0, 0), 2);
		for (BlockPos offset : OctreeManager.blockSequence) {
			BlockPos origin = root.start.add(offset);
			Branch leaf = new Branch(origin, 1, material);
			leaf.mostCommonImpedance = material.impedance();
			leaf.leastCommonImpedance = material.impedance();
			leaf.avgImpedance = material.impedance();
			root.put(origin.asLong(), leaf);
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

	@Test
	void invalidateBlock_rebakesFiniteDescriptorsAlongAffectedPath() {
		BlockPos sectionOrigin = new BlockPos(0, 0, 0);
		SectionChunks.SectionFixture fixture = SectionChunks.fixture(sectionOrigin);
		for (int x = 0; x < 16; x++) {
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					fixture.setLocal(x, y, z, stoneState);
				}
			}
		}
		WorldChunk chunk = fixture.chunk();

		Branch root = new Branch(sectionOrigin, 16);
		OctreeManager.growOctree(chunk, root);
		assertTrue(Double.isFinite(root.mostCommonImpedance), "precondition: grow bakes finite descriptors");

		BlockPos target = sectionOrigin.add(3, 7, 3);
		fixture.setLocal(3, 7, 3, airState);
		OctreeManager.invalidateBlock(chunk, root, target);

		Branch cursor = root;
		while (cursor.size > 1 && !cursor.isEmpty()) {
			assertTrue(Double.isFinite(cursor.mostCommonImpedance),
					"ancestor size " + cursor.size + " must keep a finite mostCommonImpedance after invalidate/subdivide");
			assertTrue(Double.isFinite(cursor.leastCommonImpedance));
			assertTrue(Double.isFinite(cursor.avgImpedance));
			assertFalse(Double.isNaN(cursor.mostCommonImpedance));
			Branch child = cursor.childAt(target);
			assertNotNull(child, "invalidate/subdivide must materialize the child toward " + target);
			cursor = child;
		}
		Branch leaf = cursor;
		assertEquals(1, leaf.size);
		assertTrue(Double.isFinite(leaf.mostCommonImpedance) || leaf.material == null,
				"cleared leaf may drop material, but a retained material leaf must be finite-baked");
		if (leaf.material != null) {
			assertEquals(leaf.material.impedance(), leaf.mostCommonImpedance);
			assertEquals(leaf.material.impedance(), leaf.leastCommonImpedance);
			assertEquals(leaf.material.impedance(), leaf.avgImpedance);
		}
		// Sibling of the cleared path that still holds stone must also be finite after subdivide.
		Branch sibling = root.get(sectionOrigin.add(8, 0, 0));
		assertTrue(Double.isFinite(sibling.mostCommonImpedance),
				"subdivide must bake finite descriptors on siblings, not leave NaN from a bare Branch()");
		assertEquals(STONE.impedance(), sibling.mostCommonImpedance);
	}
}
