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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression sweep for {@link OctreeManager#growOctree}: heterogeneous sections must keep 1³
 * leaves at every block position, and homogeneous sections must coalesce.
 */
class OctreeGrowSweepTest {

	private static final Material STONE = new Material(2700.0, 0.5, 1.0);
	private static final Material GRASS = new Material(500.0, 0.8, 0.9);
	private static final Material AIR = new Material(1.2, 1.0, 0.5);

	private static BlockState stoneState;
	private static BlockState grassState;
	private static BlockState airState;

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.createGameVersion();
		Bootstrap.initialize();
		stoneState = Blocks.STONE.getDefaultState();
		grassState = Blocks.GRASS_BLOCK.getDefaultState();
		airState = Blocks.AIR.getDefaultState();
	}

	@BeforeEach
	void publishMaterials() {
		MaterialRegistry.publish(Map.of(
				Ident.parse("minecraft:stone"), STONE,
				Ident.parse("minecraft:grass_block"), GRASS,
				Ident.parse("minecraft:air"), AIR
		));
	}

	@Test
	void allPositionsResolvable() {
		BlockPos sectionOrigin = new BlockPos(0, 0, 0);
		WorldChunk chunk = surfaceSection(sectionOrigin);
		Branch root = new Branch(sectionOrigin, 16);

		OctreeManager.growOctree(chunk, root);

		int lost = 0;
		for (int x = 0; x < 16; x++) {
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					BlockPos local = BlockPos.ofFloored(x, y, z);
					Material expected = expectedMaterial(x, y, z);
					Branch leaf = root.get(sectionOrigin.add(local));
					if (!expected.equals(leaf.material)) {
						lost++;
					}
				}
			}
		}
		assertEquals(0, lost, "every block position must resolve to a 1³ leaf with the correct material");
	}

	@Test
	void grassProbeAt_3_7_3() {
		BlockPos sectionOrigin = new BlockPos(0, 0, 0);
		WorldChunk chunk = surfaceSection(sectionOrigin);
		Branch root = new Branch(sectionOrigin, 16);

		OctreeManager.growOctree(chunk, root);

		BlockPos grassPos = sectionOrigin.add(3, 7, 3);
		Branch leaf = root.get(grassPos);

		assertEquals(1, leaf.size, "grass block must not be coarsened away");
		assertEquals(GRASS, leaf.material);
		assertNotEquals(STONE, leaf.material);
		assertEquals(MaterialRegistry.material(grassState), leaf.material);
	}

	@Test
	void uniformSectionCoalesces() {
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

		int leaves = countLeaves(root);
		assertTrue(leaves <= 29, () -> "uniform stone must coalesce, got " + leaves + " leaves");
	}

	private static WorldChunk surfaceSection(BlockPos sectionOrigin) {
		SectionChunks.SectionFixture fixture = SectionChunks.fixture(sectionOrigin);
		for (int x = 0; x < 16; x++) {
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					if (y <= 7) {
						fixture.setLocal(x, y, z, stoneState);
					} else {
						fixture.setLocal(x, y, z, airState);
					}
				}
			}
		}
		fixture.setLocal(3, 7, 3, grassState);
		return fixture.chunk();
	}

	private static Material expectedMaterial(int x, int y, int z) {
		if (x == 3 && y == 7 && z == 3) {
			return GRASS;
		}
		if (y <= 7) {
			return STONE;
		}
		return AIR;
	}

	private static int countLeaves(Branch node) {
		if (node.isEmpty()) {
			return 1;
		}
		int count = 0;
		for (Branch child : node.children) {
			if (child != null) {
				count += countLeaves(child);
			}
		}
		return count;
	}
}
