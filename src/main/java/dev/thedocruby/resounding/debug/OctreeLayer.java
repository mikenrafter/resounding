package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.OctreeManager;
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
import net.minecraft.world.chunk.ChunkStatus;
import org.joml.Matrix4f;

import java.util.List;

@Environment(EnvType.CLIENT)
public final class OctreeLayer implements DebugLayer {

	private final GpuLineBuffer buffer = new GpuLineBuffer(VertexBuffer.Usage.STATIC, false, 2.25F);
	private boolean enabled;

	private int lastSectionX = Integer.MIN_VALUE;
	private int lastSectionY = Integer.MIN_VALUE;
	private int lastSectionZ = Integer.MIN_VALUE;
	private int lastLeafCount = -1;
	private int lastOctantCount = -1;
	private List<OctreeOverlay.OctantView> octants = List.of();

	public int octantCount() {
		return octants.size();
	}

	public int leafCount() {
		return lastLeafCount;
	}

	@Override
	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
		if (enabled) {
			lastSectionX = Integer.MIN_VALUE;
			lastLeafCount = -1;
			lastOctantCount = -1;
		}
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

		ChunkPos chunkPos = new ChunkPos(playerPos);
		ChunkChain chain = (ChunkChain) client.world.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL, false);
		if (chain == null) {
			return;
		}

		Branch sectionBranch = chain.getBranch(sectionY);
		if (sectionBranch == null) {
			return;
		}

		List<OctreeOverlay.OctantView> collected = OctreeOverlay.collectNeighborhood(sectionBranch, playerPos);
		int leafCount = OctreeManager.leafCount(sectionBranch);

		boolean sectionChanged = sectionX != lastSectionX || sectionY != lastSectionY || sectionZ != lastSectionZ;
		boolean treeChanged = leafCount != lastLeafCount || collected.size() != lastOctantCount;
		if (!sectionChanged && !treeChanged) {
			return;
		}

		lastSectionX = sectionX;
		lastSectionY = sectionY;
		lastSectionZ = sectionZ;
		lastLeafCount = leafCount;
		lastOctantCount = collected.size();

		Profiler profiler = client.getProfiler();
		profiler.push("resounding_octree_walk");
		try {
			octants = List.copyOf(collected);
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
