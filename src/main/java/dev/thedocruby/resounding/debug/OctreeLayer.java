package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.OctreeManager;
import dev.thedocruby.resounding.config.PrecomputedConfig;
import dev.thedocruby.resounding.raycast.Beam;
import dev.thedocruby.resounding.raycast.BeamBudget;
import dev.thedocruby.resounding.raycast.BeamVisitRecorder;
import dev.thedocruby.resounding.raycast.Branch;
import dev.thedocruby.resounding.raycast.Cast;
import dev.thedocruby.resounding.raycast.FrustumLod;
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
import net.minecraft.util.math.Vec3i;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.world.chunk.ChunkStatus;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

@Environment(EnvType.CLIENT)
public final class OctreeLayer implements DebugLayer {

	/** Cyan tint for virtual (finer-than-finest-leaf) step boxes. */
	static final int VIRTUAL_COLOR = 0xFF66F0FF;
	/** Hot magenta for polarity axes (both + and − through the octant). */
	static final int POLAR_COLOR = 0xFFFF14F0;

	public enum DisplayMode {
		NEIGHBORHOOD,
		BEAM_PATH
	}

	private final GpuLineBuffer buffer = new GpuLineBuffer(VertexBuffer.Usage.STATIC, false, 2.25F);
	private boolean enabled;
	private DisplayMode displayMode = DisplayMode.NEIGHBORHOOD;
	/** When true, BEAM_PATH may fall back to the player's look vector (B off). */
	private boolean allowLookFallback = true;

	/** Index into {@link #groupedLiveRays} for which live bounce ray's frustum boxes are shown. */
	private int selectedRayCursor = 0;
	private List<Integer> rayIndexes = List.of();
	private int selectedRayIndex = -1;
	private List<List<RayLineLayer.LineSegment>> groupedLiveRays = List.of();

	private int lastSectionX = Integer.MIN_VALUE;
	private int lastSectionY = Integer.MIN_VALUE;
	private int lastSectionZ = Integer.MIN_VALUE;
	private int lastLeafCount = -1;
	private int lastOctantCount = -1;
	private int lastBounceVersion = -1;
	private int lastSelectedRayIndex = Integer.MIN_VALUE;
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

	public int selectedRayIndex() {
		return selectedRayIndex;
	}

	public int selectedRayOrdinal() {
		return selectedRayCursor;
	}

	public int rayCount() {
		return rayIndexes.size();
	}

	public void setDisplayMode(DisplayMode displayMode) {
		this.displayMode = displayMode;
		invalidateCache();
	}

	/**
	 * B-on path: cycle one of the env-eval cast polylines (rayIndex 0..63), not connectivity
	 * fragments. No look fallback, no C capture.
	 */
	public int cycleLiveFrustumRay() {
		boolean alreadyFrustum = enabled && displayMode == DisplayMode.BEAM_PATH && !allowLookFallback;
		displayMode = DisplayMode.BEAM_PATH;
		allowLookFallback = false;
		enabled = true;
		refreshRayIndexes();
		if (rayIndexes.isEmpty()) {
			selectedRayIndex = -1;
			selectedRayCursor = 0;
		} else if (!alreadyFrustum) {
			selectedRayCursor = 0;
			selectedRayIndex = rayIndexes.get(0);
		} else {
			selectedRayCursor = (selectedRayCursor + 1) % rayIndexes.size();
			selectedRayIndex = rayIndexes.get(selectedRayCursor);
		}
		invalidateCache();
		return selectedRayIndex;
	}

	/** B-off path: frustum LOD along the player's look vector. */
	public void showLookFrustum() {
		displayMode = DisplayMode.BEAM_PATH;
		allowLookFallback = true;
		enabled = true;
		selectedRayIndex = -1;
		selectedRayCursor = 0;
		invalidateCache();
	}

	private void refreshRayIndexes() {
		groupedLiveRays = BounceRayLayer.groupByRayIndex(
				DebugRenderDispatcher.INSTANCE.bounceRays().segmentSnapshot()
		);
		List<Integer> indexes = new ArrayList<>(groupedLiveRays.size());
		for (int i = 0; i < groupedLiveRays.size(); i++) {
			List<RayLineLayer.LineSegment> path = groupedLiveRays.get(i);
			indexes.add(path.isEmpty() ? i : path.getFirst().rayIndex());
		}
		rayIndexes = indexes;
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
		lastBounceVersion = -1;
		lastSelectedRayIndex = Integer.MIN_VALUE;
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
		int bounceVersion = DebugRenderDispatcher.INSTANCE.bounceRays().version();

		boolean sectionChanged = sectionX != lastSectionX || sectionY != lastSectionY || sectionZ != lastSectionZ;
		boolean treeChanged = leafCount != lastLeafCount;
		boolean bounceChanged = displayMode == DisplayMode.BEAM_PATH && !allowLookFallback
				&& bounceVersion != lastBounceVersion;
		boolean rayChanged = displayMode == DisplayMode.BEAM_PATH && selectedRayIndex != lastSelectedRayIndex;
		if (!sectionChanged && !treeChanged && !bounceChanged && !rayChanged && lastOctantCount >= 0) {
			return;
		}

		Profiler profiler = client.getProfiler();
		profiler.push(displayMode == DisplayMode.BEAM_PATH ? "resounding_octree_beam" : "resounding_octree_walk");
		List<OctreeOverlay.OctantView> collected;
		try {
			if (displayMode == DisplayMode.BEAM_PATH) {
				if (!allowLookFallback) {
					refreshRayIndexes();
					if (!rayIndexes.isEmpty()) {
						if (selectedRayIndex < 0 || !rayIndexes.contains(selectedRayIndex)) {
							selectedRayCursor = 0;
							selectedRayIndex = rayIndexes.get(0);
						} else {
							selectedRayCursor = rayIndexes.indexOf(selectedRayIndex);
						}
					}
				}
				collected = collectSelectedRayViews(client, chain, sectionBranch);
			} else {
				collected = OctreeOverlay.collectNeighborhood(sectionBranch, playerPos);
			}
		} finally {
			profiler.pop();
		}

		lastSectionX = sectionX;
		lastSectionY = sectionY;
		lastSectionZ = sectionZ;
		lastLeafCount = leafCount;
		lastOctantCount = collected.size();
		lastBounceVersion = bounceVersion;
		lastSelectedRayIndex = selectedRayIndex;
		octants = List.copyOf(collected);
		buffer.markDirty();
	}

	private List<OctreeOverlay.OctantView> collectSelectedRayViews(
			MinecraftClient client,
			ChunkChain playerChain,
			Branch playerSection
	) {
		if (!allowLookFallback) {
			List<RayLineLayer.LineSegment> path = selectedLivePath();
			return collectCastVisitedViews(path, origin -> sectionRootFor(playerChain, playerSection, origin));
		}
		List<Seed> seeds = seedsForLook(client);
		List<OctreeOverlay.VisitedStep> steps = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		for (Seed seed : seeds) {
			Branch root = sectionRootFor(playerChain, playerSection, seed.origin);
			if (root == null) {
				continue;
			}
			appendBeamVisits(root, seed.origin, seed.direction, seed.maxDistance, steps, seen);
		}
		return OctreeOverlay.collectBeamPath(steps);
	}

	private List<RayLineLayer.LineSegment> selectedLivePath() {
		if (selectedRayCursor < 0 || selectedRayCursor >= groupedLiveRays.size()) {
			return List.of();
		}
		return groupedLiveRays.get(selectedRayCursor);
	}

	/**
	 * Boxes the cast actually resolved: one octant per recorded segment at that segment's
	 * {@code branchSize}, plus bounce-off neighbors at direction changes.
	 */
	static List<OctreeOverlay.OctantView> collectCastVisitedViews(
			List<RayLineLayer.LineSegment> path,
			Function<Vec3d, Branch> rootFor
	) {
		List<OctreeOverlay.VisitedStep> steps = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		Vec3d prevDir = null;
		Vec3d prevEnd = null;
		int prevBranchSize = 1;
		for (RayLineLayer.LineSegment segment : path) {
			Vec3d delta = segment.end().subtract(segment.start());
			double length = delta.length();
			if (length < 1e-4) {
				continue;
			}
			Vec3d dir = delta.multiply(1.0 / length);
			int stepSize = Math.max(1, segment.branchSize());
			if (prevDir != null && prevEnd != null && isDirectionChange(prevDir, dir)) {
				Branch bounceRoot = rootFor.apply(prevEnd);
				if (bounceRoot != null) {
					appendBounceOff(bounceRoot, prevEnd, prevDir, prevBranchSize, steps, seen);
				}
			}
			Branch root = rootFor.apply(segment.start());
			if (root != null) {
				appendCastCell(root, segment.start(), dir, stepSize, steps, seen);
			}
			prevDir = dir;
			prevEnd = segment.end();
			prevBranchSize = stepSize;
		}
		return OctreeOverlay.collectBeamPath(steps);
	}

	/** @deprecated Use {@link #collectCastVisitedViews}; kept for older tests that re-walk LOD. */
	static List<OctreeOverlay.OctantView> collectLivePathViews(
			List<RayLineLayer.LineSegment> path,
			Function<Vec3d, Branch> rootFor
	) {
		return collectCastVisitedViews(path, rootFor);
	}

	static boolean isDirectionChange(Vec3d previous, Vec3d next) {
		double a = previous.lengthSquared();
		double b = next.lengthSquared();
		if (a < 1e-12 || b < 1e-12) {
			return false;
		}
		double dot = previous.dotProduct(next) / Math.sqrt(a * b);
		return dot < 1.0 - 1e-4;
	}

	/** Records the LOD cell the cast occupied at {@code start}, sized to {@code branchSize}. */
	static void appendCastCell(
			Branch root,
			Vec3d start,
			Vec3d direction,
			int branchSize,
			List<OctreeOverlay.VisitedStep> steps,
			Set<Long> seen
	) {
		int size = Math.max(1, branchSize);
		Vec3d dir = direction.lengthSquared() > 1e-12 ? direction : new Vec3d(1, 0, 0);
		Vec3d probe = Cast.normalize(start, dir);
		BlockPos query = BlockPos.ofFloored(probe);
		BlockPos cellOrigin = FrustumLod.alignOrigin(query, root.start, size);
		Branch lod = root.getAtLod(query, size);
		Branch finest = root.get(query);
		boolean virtual = finest.size > size;
		appendVisit(steps, seen, cellOrigin, size, virtual, virtual ? null : "leaf", lod.polar);
	}

	static void appendBounceOff(
			Branch root,
			Vec3d hit,
			Vec3d incident,
			int castStepSize,
			List<OctreeOverlay.VisitedStep> steps,
			Set<Long> seen
	) {
		Vec3d dir = incident.lengthSquared() > 1e-12 ? incident.normalize() : incident;
		int step = Math.max(1, castStepSize);
		Vec3d hostProbe = Cast.normalize(hit, dir.multiply(-1.0));
		BlockPos hostQuery = BlockPos.ofFloored(hostProbe);
		BlockPos hostOrigin = FrustumLod.alignOrigin(hostQuery, root.start, step);

		Vec3i face = FrustumLod.dominantExitFace(dir);
		FrustumLod.ForwardMap map = FrustumLod.forwardMap(face, dir, step);
		appendBounceNeighbor(root, hostOrigin, map.nOffset(), step, steps, seen);
		if (map.hasTangent()) {
			appendBounceNeighbor(root, hostOrigin, map.eOffset(), step, steps, seen);
			appendBounceNeighbor(root, hostOrigin, map.dOffset(), step, steps, seen);
		}
	}

	private static void appendBounceNeighbor(
			Branch root,
			BlockPos hostOrigin,
			Vec3i offset,
			int step,
			List<OctreeOverlay.VisitedStep> steps,
			Set<Long> seen
	) {
		if (offset.getX() == 0 && offset.getY() == 0 && offset.getZ() == 0) {
			return;
		}
		BlockPos neighbor = hostOrigin.add(offset.getX(), offset.getY(), offset.getZ());
		Branch lod = root.getAtLod(neighbor, step);
		BlockPos cellOrigin = FrustumLod.alignOrigin(neighbor, root.start, step);
		appendVisit(steps, seen, cellOrigin, step, false, "bounce", lod.polar);
	}

	private static void appendBeamVisits(
			Branch root,
			Vec3d origin,
			Vec3d direction,
			double maxDistance,
			List<OctreeOverlay.VisitedStep> steps,
			Set<Long> seen
	) {
		Beam beam = new Beam(
				origin,
				direction,
				FrustumLod.BASE_FOOTPRINT,
				PrecomputedConfig.pConfig.frustumGrowthPerBlock,
				BeamBudget.full()
		);
		for (BeamVisitRecorder.VisitedBox visit : BeamVisitRecorder.collectAlongBeam(
				root, beam, origin, direction, maxDistance
		)) {
			appendVisit(
					steps, seen, visit.start(), visit.size(), visit.virtual(),
					visit.virtual() ? null : "leaf", visit.polar()
			);
		}
	}

	private static void appendVisit(
			List<OctreeOverlay.VisitedStep> steps,
			Set<Long> seen,
			BlockPos start,
			int size,
			boolean virtual,
			String label,
			Vec3d polar
	) {
		long key = (start.asLong() << 8) ^ size ^ (virtual ? 1L : 0L);
		if ("bounce".equals(label)) {
			key ^= 2L;
		}
		if (!seen.add(key)) {
			return;
		}
		Box box = new Box(
				start.getX(), start.getY(), start.getZ(),
				start.getX() + size,
				start.getY() + size,
				start.getZ() + size
		);
		steps.add(new OctreeOverlay.VisitedStep(box, null, label, virtual, polar));
	}

	private List<Seed> seedsForLook(MinecraftClient client) {
		Vec3d eye = client.player.getEyePos();
		Vec3d look = client.player.getRotationVec(1.0F).normalize();
		return List.of(new Seed(eye, look, 32.0));
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
			drawPolarAxis(builder, octant.box(), octant.polar());
		}
	}

	/**
	 * Draws the polarity vector centered in the octant, extending in both + and − until it meets
	 * the box faces.
	 */
	static void drawPolarAxis(BufferBuilder builder, Box box, Vec3d polar) {
		if (polar == null || polar.lengthSquared() < 1e-12) {
			return;
		}
		Vec3d n = polar.normalize();
		double cx = (box.minX + box.maxX) * 0.5;
		double cy = (box.minY + box.maxY) * 0.5;
		double cz = (box.minZ + box.maxZ) * 0.5;
		double tPos = exitDistance(cx, cy, cz, n.x, n.y, n.z, box);
		double tNeg = exitDistance(cx, cy, cz, -n.x, -n.y, -n.z, box);
		GpuLineBuffer.line(
				builder,
				cx - n.x * tNeg, cy - n.y * tNeg, cz - n.z * tNeg,
				cx + n.x * tPos, cy + n.y * tPos, cz + n.z * tPos,
				POLAR_COLOR
		);
	}

	/** Distance from center along {@code (dx,dy,dz)} to the first AABB face. */
	static double exitDistance(double cx, double cy, double cz, double dx, double dy, double dz, Box box) {
		double t = Double.POSITIVE_INFINITY;
		if (dx > 1e-12) t = Math.min(t, (box.maxX - cx) / dx);
		else if (dx < -1e-12) t = Math.min(t, (box.minX - cx) / dx);
		if (dy > 1e-12) t = Math.min(t, (box.maxY - cy) / dy);
		else if (dy < -1e-12) t = Math.min(t, (box.minY - cy) / dy);
		if (dz > 1e-12) t = Math.min(t, (box.maxZ - cz) / dz);
		else if (dz < -1e-12) t = Math.min(t, (box.minZ - cz) / dz);
		return Double.isFinite(t) && t > 0 ? t : 0.0;
	}

	private static boolean isVirtual(OctreeOverlay.OctantView octant) {
		String label = octant.label();
		return label != null && label.toLowerCase().contains("virtual");
	}

	private record Seed(Vec3d origin, Vec3d direction, double maxDistance) {}
}
