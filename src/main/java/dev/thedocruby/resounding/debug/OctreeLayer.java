package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.raycast.Branch;
import dev.thedocruby.resounding.toolbox.ChunkChain;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import org.joml.Matrix4f;

import java.util.List;

@Environment(EnvType.CLIENT)
public final class OctreeLayer implements DebugLayer {

	private final GpuLineBuffer buffer = new GpuLineBuffer(VertexBuffer.Usage.STATIC, true, 0.25F);
	private boolean enabled;

	private int lastSectionX = Integer.MIN_VALUE;
	private int lastSectionY = Integer.MIN_VALUE;
	private int lastSectionZ = Integer.MIN_VALUE;
	private List<OctreeOverlay.OctantView> octants = List.of();

	@Override
	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public List<OctreeOverlay.OctantView> octants() {
		return octants;
	}

	@Override
	public void update() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.world == null) {
			return;
		}

		BlockPos playerPos = client.player.getBlockPos();
		int sectionX = playerPos.getX() >> 4;
		int sectionY = playerPos.getY() >> 4;
		int sectionZ = playerPos.getZ() >> 4;
		if (sectionX == lastSectionX && sectionY == lastSectionY && sectionZ == lastSectionZ) {
			return;
		}

		lastSectionX = sectionX;
		lastSectionY = sectionY;
		lastSectionZ = sectionZ;

		Profiler profiler = client.getProfiler();
		profiler.push("resounding_octree_walk");
		try {
			octants = collectSectionOctants(client.world, playerPos);
		} finally {
			profiler.pop();
		}
		buffer.markDirty();
	}

	@Override
	public void render(Matrix4f positionMatrix, Matrix4f projectionMatrix, Vec3d cameraPos) {
		buffer.rebuild(this::populate);
		buffer.draw(positionMatrix, projectionMatrix, cameraPos);
	}

	private static List<OctreeOverlay.OctantView> collectSectionOctants(World world, BlockPos playerPos) {
		ChunkPos chunkPos = new ChunkPos(playerPos);
		ChunkChain chain = (ChunkChain) world.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL, false);
		if (chain == null) {
			return List.of();
		}

		Branch branch = chain.getBranch(playerPos.getY() >> 4);
		if (branch == null) {
			return List.of();
		}

		return OctreeOverlay.collectOctants(branch);
	}

	private void populate(BufferBuilder builder) {
		for (OctreeOverlay.OctantView octant : octants) {
			GpuLineBuffer.boxEdges(
					builder,
					octant.box().minX, octant.box().minY, octant.box().minZ,
					octant.box().maxX, octant.box().maxY, octant.box().maxZ,
					octant.color()
			);
		}
	}
}
