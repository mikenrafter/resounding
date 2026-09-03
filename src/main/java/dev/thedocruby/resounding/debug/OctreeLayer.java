package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.OctreeManager;
import dev.thedocruby.resounding.raycast.Beam;
import dev.thedocruby.resounding.raycast.BeamBudget;
import dev.thedocruby.resounding.raycast.BeamVisitRecorder;
import dev.thedocruby.resounding.raycast.Branch;
import dev.thedocruby.resounding.toolbox.ChunkChain;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.world.chunk.ChunkStatus;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Environment(EnvType.CLIENT)
public final class OctreeLayer implements DebugLayer {

	/** Max seed beams drawn in BEAM_PATH mode (capture subset or look fallback). */
	static final int MAX_FRUSTUM_BEAMS = 8;
	/** Starting footprint radius so large homogeneous leaves show virtual 1³ steps. */
	static final double DEBUG_BASE_FOOTPRINT = 1.5;
	/** Mild widening with distance so later steps climb footprint tiers. */
	static final double DEBUG_FOOTPRINT_GROWTH = 0.05;
	/** Cyan tint for virtual (finer-than-leaf) step boxes. */
	static final int VIRTUAL_COLOR = 0xFF66F0FF;

	public enum DisplayMode {
		NEIGHBORHOOD,
		BEAM_PATH
	}

	private final GpuLineBuffer buffer = new GpuLineBuffer(VertexBuffer.Usage.STATIC, false, 2.25F);
	private boolean enabled;
	private DisplayMode displayMode = DisplayMode.NEIGHBORHOOD;

	private int lastSectionX = Integer.MIN_VALUE;
	private int lastSectionY = Integer.MIN_VALUE;
	private int lastSectionZ = Integer.MIN_VALUE;
	private int lastLeafCount = -1;
	private int lastOctantCount = -1;
	private int lastCaptureVersion = -1;
	private List<OctreeOverlay.OctantView> octants = List.of();

	public int octantCount() {
		return octants.size();
	}

	public int leafCount() {
		return lastLeafCount;
	}

	public DisplayMode displayMode() {
		return displayMode;
	}

	public void setDisplayMode(DisplayMode displayMode) {
		this.displayMode = displayMode;
		invalidateCache();
	}

	@Override
	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
		if (enabled) {
			invalidateCache();
		}
	}

	public List<OctreeOverlay.OctantView> octants() {
		return octants;
	}

	private void invalidateCache() {
		lastSectionX = Integer.MIN_VALUE;
		lastLeafCount = -1;
		lastOctantCount = -1;
		lastCaptureVersion = -1;
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

		int leafCount = OctreeManager.leafCount(sectionBranch);
		int captureVersion = CaptureBuffer.INSTANCE.version();

		boolean sectionChanged = sectionX != lastSectionX || sectionY != lastSectionY || sectionZ != lastSectionZ;
		boolean treeChanged = leafCount != lastLeafCount;
		boolean captureChanged = displayMode == DisplayMode.BEAM_PATH && captureVersion != lastCaptureVersion;
		if (!sectionChanged && !treeChanged && !captureChanged && lastOctantCount >= 0) {
			return;
		}

		Profiler profiler = client.getProfiler();
		profiler.push(displayMode == DisplayMode.BEAM_PATH ? "resounding_octree_beam" : "resounding_octree_walk");
		List<OctreeOverlay.OctantView> collected;
		try {
			collected = displayMode == DisplayMode.BEAM_PATH
					? collectBeamPathViews(client, chain, sectionBranch)
					: OctreeOverlay.collectNeighborhood(sectionBranch, playerPos);
		} finally {
			profiler.pop();
		}

		lastSectionX = sectionX;
		lastSectionY = sectionY;
		lastSectionZ = sectionZ;
		lastLeafCount = leafCount;
		lastOctantCount = collected.size();
		lastCaptureVersion = captureVersion;
		octants = List.copyOf(collected);
		buffer.markDirty();
	}

	/**
	 * Rebuilds overlay boxes from a subset of captured ray segments treated as frustums, or from
	 * the player's look vector when nothing is captured yet.
	 */
	private List<OctreeOverlay.OctantView> collectBeamPathViews(
			MinecraftClient client,
			ChunkChain playerChain,
			Branch playerSection
	) {
		List<Seed> seeds = seedsFromCapture();
		if (seeds.isEmpty()) {
			Vec3d eye = client.player.getEyePos();
			Vec3d look = client.player.getRotationVec(1.0F).normalize();
			seeds = List.of(new Seed(eye, look, 32.0));
		}

		List<OctreeOverlay.VisitedStep> steps = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		for (Seed seed : seeds) {
			Branch root = sectionRootFor(playerChain, playerSection, seed.origin);
			if (root == null) {
				continue;
			}
			Beam beam = new Beam(seed.origin, seed.direction, DEBUG_BASE_FOOTPRINT, DEBUG_FOOTPRINT_GROWTH, BeamBudget.full());
			for (BeamVisitRecorder.VisitedBox visit : BeamVisitRecorder.collectAlongBeam(
					root, beam, seed.origin, seed.direction, seed.maxDistance
			)) {
				long key = (((long) visit.start().asLong()) << 8) ^ visit.size() ^ (visit.virtual() ? 1L : 0L);
				if (!seen.add(key)) {
					continue;
				}
				Box box = new Box(
						visit.start().getX(), visit.start().getY(), visit.start().getZ(),
						visit.start().getX() + visit.size(),
						visit.start().getY() + visit.size(),
						visit.start().getZ() + visit.size()
				);
				steps.add(new OctreeOverlay.VisitedStep(box, null, visit.virtual() ? null : "leaf", visit.virtual()));
			}
		}
		return OctreeOverlay.collectBeamPath(steps);
	}

	private static List<Seed> seedsFromCapture() {
		List<CaptureBuffer.CapturedRay> captured = CaptureBuffer.INSTANCE.asCapturedList();
		if (captured.isEmpty()) {
			return List.of();
		}
		List<Seed> seeds = new ArrayList<>(MAX_FRUSTUM_BEAMS);
		Set<Integer> usedSounds = new HashSet<>();
		for (CaptureBuffer.CapturedRay ray : captured) {
			if (ray.bounceIndex() != 0) {
				continue;
			}
			if (!usedSounds.add(ray.soundEventId())) {
				continue;
			}
			Vec3d delta = ray.end().subtract(ray.start());
			double length = delta.length();
			if (length < 1e-4) {
				continue;
			}
			seeds.add(new Seed(ray.start(), delta.multiply(1.0 / length), Math.min(64.0, Math.max(length, 8.0))));
			if (seeds.size() >= MAX_FRUSTUM_BEAMS) {
				break;
			}
		}
		if (seeds.isEmpty()) {
			// Fall back to the first few segments regardless of bounce index.
			for (CaptureBuffer.CapturedRay ray : captured) {
				Vec3d delta = ray.end().subtract(ray.start());
				double length = delta.length();
				if (length < 1e-4) {
					continue;
				}
				seeds.add(new Seed(ray.start(), delta.multiply(1.0 / length), Math.min(64.0, Math.max(length, 8.0))));
				if (seeds.size() >= MAX_FRUSTUM_BEAMS) {
					break;
				}
			}
		}
		return seeds;
	}

	private static Branch sectionRootFor(ChunkChain playerChain, Branch playerSection, Vec3d origin) {
		int sectionY = ((int) Math.floor(origin.y)) >> 4;
		int chunkX = ((int) Math.floor(origin.x)) >> 4;
		int chunkZ = ((int) Math.floor(origin.z)) >> 4;
		ChunkChain chain = playerChain.access(chunkX, chunkZ);
		if (chain == null) {
			return null;
		}
		Branch root = chain.getBranch(sectionY);
		return root != null ? root : playerSection;
	}

	@Override
	public void render(Matrix4f positionMatrix, Matrix4f projectionMatrix, Vec3d cameraPos) {
		buffer.rebuild(this::populate);
		buffer.draw(positionMatrix, projectionMatrix, cameraPos);
	}

	private void populate(BufferBuilder builder) {
		for (OctreeOverlay.OctantView octant : octants) {
			int color = isVirtual(octant) ? VIRTUAL_COLOR : octant.color();
			GpuLineBuffer.boxEdges(
					builder,
					octant.box().minX, octant.box().minY, octant.box().minZ,
					octant.box().maxX, octant.box().maxY, octant.box().maxZ,
					color
			);
		}
	}

	private static boolean isVirtual(OctreeOverlay.OctantView octant) {
		String label = octant.label();
		return label != null && label.toLowerCase().contains("virtual");
	}

	private record Seed(Vec3d origin, Vec3d direction, double maxDistance) {}
}
