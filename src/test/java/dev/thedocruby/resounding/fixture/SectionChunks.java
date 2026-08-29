package dev.thedocruby.resounding.fixture;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import org.mockito.Mockito;

/**
 * Mockito-backed {@link WorldChunk} for 16³ section tests.
 */
public final class SectionChunks {

	private SectionChunks() {}

	public static SectionFixture fixture(BlockPos sectionOrigin) {
		return new SectionFixture(sectionOrigin);
	}

	public static final class SectionFixture {
		private final BlockPos sectionOrigin;
		private final BlockState[][][] states = new BlockState[16][16][16];

		private SectionFixture(BlockPos sectionOrigin) {
			this.sectionOrigin = sectionOrigin;
		}

		public void setLocal(int x, int y, int z, BlockState state) {
			states[x][y][z] = state;
		}

		public WorldChunk chunk() {
			WorldChunk chunk = Mockito.mock(WorldChunk.class);
			Mockito.when(chunk.getBlockState(Mockito.any())).thenAnswer(invocation -> {
				BlockPos pos = invocation.getArgument(0);
				int x = pos.getX() - sectionOrigin.getX();
				int y = pos.getY() - sectionOrigin.getY();
				int z = pos.getZ() - sectionOrigin.getZ();
				if (x < 0 || x >= 16 || y < 0 || y >= 16 || z < 0 || z >= 16) {
					return Blocks.AIR.getDefaultState();
				}
				BlockState state = states[x][y][z];
				return state != null ? state : Blocks.AIR.getDefaultState();
			});
			return chunk;
		}
	}
}
