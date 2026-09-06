package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.debug.math.OctantColor;
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
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@Environment(EnvType.CLIENT)
public final class OctreeLayer implements DebugLayer {

	/** Cyan tint for virtual (finer-than-finest-leaf) step boxes. */
	static final int VIRTUAL_COLOR = 0xFF66F0FF;
	/** Hot magenta for polarity axes (both + and − through the octant). */
	static final int POLAR_COLOR = 0xFFFF14F0;
	static final float NED_MARKER_LINE_WIDTH = 3.0F;
	private static final double NED_MARKER_HALF_EXTENT = 0.12D;
	/** Nearby fill opacity (at the player). */
	static final float OCTANT_FILL_OPACITY = 0.2F;
	/** Fill opacity at / beyond {@link #OCTANT_FADE_BLOCKS}. */
	static final float OCTANT_FILL_OPACITY_FAR = 0.05F;
	/** Distance over which fill fades from near→far and borders drop (when not cluster-focused). */
	static final double OCTANT_FADE_BLOCKS = 10.0;
	/** Solo-focused incident host wireframe + exit face. */
	static final int INCIDENT_HIGHLIGHT_COLOR = 0xFFFFFFFF;
	/** Opaque-leaning white for the incident N/E face overlays. */
	static final int INCIDENT_FACE_COLOR = 0xE6FFFFFF;
	/** Quartet-union fill inset on each side, as a fraction of the incident octant's edge length. */
	static final double QUARTET_INSET_FRACTION = 0.15;

	public enum DisplayMode {
		NEIGHBORHOOD,
		BEAM_PATH
	}

	/**
	 * Outcome a quartet ({@code H+N+E+D}) marker represents, from {@link FrustumLod.Interaction}
	 * (collapsing {@link FrustumLod.Interaction#CORNER}/{@link FrustumLod.Interaction#FACE} into one
	 * REFLECTION bucket): distinct, saturated hues that avoid white (active ray), the polarity-axis
	 * purple ({@link #POLAR_COLOR}), and the ray-termination palette ({@link BounceRayLayer.TerminationCause}).
	 */
	public enum NedMarkerState {
		/** {@link FrustumLod.Interaction#GAP} — the ray passed through; also the default for a
		 *  marker with no prior segment (ray origin) or no direction change from its predecessor. */
		TRANSMISSION(0xFF00FF00),
		/** {@link FrustumLod.Interaction#CORNER} / {@link FrustumLod.Interaction#FACE}, or any
		 *  voxel-level (branchSize 1, no quartet survey) bounce. */
		REFLECTION(0xFFFF0000),
		/** {@link FrustumLod.Interaction#SPLIT} — classified, but {@code Cast} doesn't yet branch
		 *  into two child beams for it; falls through to a single reflect today. */
		SPLIT(0xFFFFFF00);

		public final int color;

		NedMarkerState(int color) {
			this.color = color;
		}
	}

	/** A quartet-interaction marker: where it sits on the ray, and what the survey classified there. */
	public record NedMarker(Vec3d position, NedMarkerState state) {}

	private final GpuLineBuffer buffer = new GpuLineBuffer(VertexBuffer.Usage.STATIC, false, 2.25F);
	private final GpuFillBuffer fillBuffer = new GpuFillBuffer(VertexBuffer.Usage.STATIC);
	private final GpuLineBuffer nedMarkerBuffer = new GpuLineBuffer(VertexBuffer.Usage.DYNAMIC, false, NED_MARKER_LINE_WIDTH);
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
	private List<NedMarker> nedMarkers = List.of();

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

	/** Focused beam-path octant AABBs (empty when not in live-frustum focus). */
	public List<Box> focusedOctantBoxes() {
		if (!enabled || displayMode != DisplayMode.BEAM_PATH || allowLookFallback || selectedRayIndex < 0) {
			return List.of();
		}
		List<Box> boxes = new ArrayList<>(octants.size());
		for (OctreeOverlay.OctantView octant : octants) {
			boxes.add(octant.box());
		}
		return boxes;
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
		CastPathOverlay collected;
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
				collected = new CastPathOverlay(
						OctreeOverlay.collectNeighborhood(sectionBranch, playerPos, client.world),
						List.of()
				);
			}
		} finally {
			profiler.pop();
		}

		lastSectionX = sectionX;
		lastSectionY = sectionY;
		lastSectionZ = sectionZ;
		lastLeafCount = leafCount;
		lastOctantCount = collected.views().size();
		lastBounceVersion = bounceVersion;
		lastSelectedRayIndex = selectedRayIndex;
		octants = List.copyOf(collected.views());
		nedMarkers = List.copyOf(collected.nedMarkers());
		buffer.markDirty();
		fillBuffer.markDirty();
		nedMarkerBuffer.markDirty();
	}

	private CastPathOverlay collectSelectedRayViews(
			MinecraftClient client,
			ChunkChain playerChain,
			Branch playerSection
	) {
		if (!allowLookFallback) {
			List<RayLineLayer.LineSegment> path = selectedLivePath();
			return collectCastVisitedOverlay(path, origin -> sectionRootFor(playerChain, playerSection, origin));
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
		return new CastPathOverlay(OctreeOverlay.collectBeamPath(steps), List.of());
	}

	private List<RayLineLayer.LineSegment> selectedLivePath() {
		if (selectedRayCursor < 0 || selectedRayCursor >= groupedLiveRays.size()) {
			return List.of();
		}
		return groupedLiveRays.get(selectedRayCursor);
	}

	/**
	 * Boxes the cast actually resolved: one octant per recorded segment at that segment's
	 * {@code branchSize}, plus bounce-off N/E/D neighbors at direction changes when LOD &gt; 1.
	 * Each H+N+E+D quartet shares {@link OctantColor#forSet}. Human-view rendering draws the
	 * bordered incident host plus an optional inset quartet union (white distance-faded border; no
	 * half-splits).
	 */
	static List<OctreeOverlay.OctantView> collectCastVisitedViews(
			List<RayLineLayer.LineSegment> path,
			Function<Vec3d, Branch> rootFor
	) {
		return collectCastVisitedOverlay(path, rootFor).views();
	}

	static CastPathOverlay collectCastVisitedOverlay(
			List<RayLineLayer.LineSegment> path,
			Function<Vec3d, Branch> rootFor
	) {
		LinkedHashMap<Long, Occupancy> byKey = new LinkedHashMap<>();
		List<NedMarker> markers = new ArrayList<>();
		Vec3d prevDir = null;
		Vec3d prevEnd = null;
		int prevBranchSize = 1;
		int setId = 0;
		for (RayLineLayer.LineSegment segment : path) {
			Vec3d delta = segment.end().subtract(segment.start());
			double length = delta.length();
			if (length < 1e-4) {
				continue;
			}
			Vec3d dir = delta.multiply(1.0 / length);
			int stepSize = Math.max(1, segment.branchSize());
			// No prior segment / no direction change from it -> this entry is a plain transmit.
			// A direction change is classified by recordBounceOff's real impedance comparison
			// (Task F1) -- not assumed to be a reflect just because the direction changed, since
			// Task E's transmit-path graze/permeation bends also change direction with R=0.
			NedMarkerState transition = NedMarkerState.TRANSMISSION;
			if (prevDir != null && prevEnd != null && isDirectionChange(prevDir, dir)) {
				Branch bounceRoot = rootFor.apply(prevEnd);
				if (bounceRoot != null) {
					int id = setId++;
					transition = recordBounceOff(bounceRoot, prevEnd, prevDir, prevBranchSize, byKey,
							OctantColor.forSet(id), id);
				} else {
					// No root at all to classify against -- true fail-safe, not a design assumption.
					transition = NedMarkerState.REFLECTION;
				}
			}
			Branch root = rootFor.apply(segment.start());
			if (root != null) {
				recordCastCell(root, segment.start(), segment.end(), dir, stepSize, byKey, markers, null, null, transition);
			}
			prevDir = dir;
			prevEnd = segment.end();
			prevBranchSize = stepSize;
		}
		return new CastPathOverlay(occupanciesToViews(byKey), List.copyOf(markers));
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
		LinkedHashMap<Long, Occupancy> byKey = new LinkedHashMap<>();
		Vec3d dir = direction.lengthSquared() > 1e-12 ? direction : new Vec3d(1, 0, 0);
		recordCastCell(root, start, start.add(dir), dir, branchSize, byKey, null, null, null, NedMarkerState.TRANSMISSION);
		flushOccupancies(byKey, steps, seen);
	}

	static void appendBounceOff(
			Branch root,
			Vec3d hit,
			Vec3d incident,
			int castStepSize,
			List<OctreeOverlay.VisitedStep> steps,
			Set<Long> seen
	) {
		LinkedHashMap<Long, Occupancy> byKey = new LinkedHashMap<>();
		recordBounceOff(root, hit, incident, castStepSize, byKey, null, null);
		flushOccupancies(byKey, steps, seen);
	}

	private static void flushOccupancies(
			LinkedHashMap<Long, Occupancy> byKey,
			List<OctreeOverlay.VisitedStep> steps,
			Set<Long> seen
	) {
		for (Map.Entry<Long, Occupancy> e : byKey.entrySet()) {
			if (!seen.add(e.getKey())) {
				continue;
			}
			Occupancy occ = e.getValue();
			steps.add(new OctreeOverlay.VisitedStep(
					occ.box, null, occ.label, occ.virtual, occ.polar, occ.colorA));
		}
	}

	private static void recordCastCell(
			Branch root,
			Vec3d start,
			Vec3d end,
			Vec3d direction,
			int branchSize,
			LinkedHashMap<Long, Occupancy> byKey,
			@Nullable List<NedMarker> markers,
			@Nullable Integer color,
			@Nullable Integer setId,
			NedMarkerState transition
	) {
		int size = Math.max(1, branchSize);
		Vec3d dir = direction.lengthSquared() > 1e-12 ? direction : new Vec3d(1, 0, 0);
		Vec3d probe = Cast.normalize(start, dir);
		BlockPos query = BlockPos.ofFloored(probe);
		BlockPos cellOrigin = FrustumLod.alignOrigin(query, root.start, size);
		Branch lod = root.getAtLod(query, size);
		Branch finest = root.get(query);
		boolean virtual = finest.size > size;
		Box box = boxOf(cellOrigin, size);
		Occupancy occ = upsertOccupancy(
				byKey, cellOrigin, size, virtual, virtual ? null : "leaf", lod.polar(), color, setId, dir);
		if (markers != null && occ.contact == null) {
			Vec3d contact = firstRayContact(start, end, box);
			occ.contact = contact;
			markers.add(new NedMarker(contact, transition));
		}
	}

	private static NedMarkerState recordBounceOff(
			Branch root,
			Vec3d hit,
			Vec3d incident,
			int castStepSize,
			LinkedHashMap<Long, Occupancy> byKey,
			@Nullable Integer setColor,
			@Nullable Integer setId
	) {
		Vec3d dir = incident.lengthSquared() > 1e-12 ? incident.normalize() : incident;
		int step = Math.max(1, castStepSize);
		Vec3d hostProbe = Cast.normalize(hit, dir.multiply(-1.0));
		BlockPos hostQuery = BlockPos.ofFloored(hostProbe);
		BlockPos hostOrigin = FrustumLod.alignOrigin(hostQuery, root.start, step);

		Vec3d cellBase = new Vec3d(hostOrigin.getX(), hostOrigin.getY(), hostOrigin.getZ());
		Vec3i firstPlane = FrustumLod.castPlaneAtHit(hit, cellBase, step, dir);
		Vec3i exitFace = FrustumLod.forwardFace(firstPlane, dir);

		// Re-derive the same host->neighbor classification Cast.surveyForward computed live, from
		// current octree state, so the marker shows what actually governs this boundary.
		Branch hostLod = root.getAtLod(hostQuery, step);
		double hostImpedance = hostLod.effectiveImpedance();

		// Materialize H when this bounce owns a visualization set (setId != null), or when the
		// cast already recorded that cube. appendBounceOff (setId null) must not invent a host —
		// its callers assert only on N/E/D bounce neighbors / head-on emptiness.
		boolean hostPresent = byKey.containsKey(occupancyKey(hostOrigin, step, false));
		Occupancy host = null;
		if (setId != null || hostPresent) {
			host = upsertOccupancy(
					byKey, hostOrigin, step, false, "leaf", hostLod.polar(), setColor, setId, dir);
			if (setId != null) {
				host.markIncidentHost(setId, exitFace);
			}
		}

		FrustumLod.ForwardMap map = FrustumLod.forwardMap(cellBase, step, hit, dir, firstPlane);
		if (map == null) {
			// No forward N/E/D survey possible on this axis pairing (ordinary single-axis exit,
			// or a size-1 cell with no dual-axis geometry) -- classify directly from the exit-face
			// neighbor's impedance instead of assuming every direction change is a reflect (Task
			// F1: this hardcoded REFLECTION fallback painted false-positive red markers on
			// Task E's legitimate transmit-path graze/permeation bends).
			Branch exitBranch = root.getAtLod(
					hostOrigin.add(exitFace.getX(), exitFace.getY(), exitFace.getZ()), step);
			boolean exitBlocks = FrustumLod.blocksPermeation(
					hostImpedance, exitBranch.effectiveImpedance(), exitBranch.effectivePermeation());
			return exitBlocks ? NedMarkerState.REFLECTION : NedMarkerState.TRANSMISSION;
		}

		Branch nBranch = root.getAtLod(
				hostOrigin.add(map.nOffset().getX(), map.nOffset().getY(), map.nOffset().getZ()), step);
		boolean nBlocks = FrustumLod.blocksPermeation(
				hostImpedance, nBranch.effectiveImpedance(), nBranch.effectivePermeation());
		recordBounceNeighbor(root, hostOrigin, map.nOffset(), step, byKey, setColor, setId, dir);

		boolean eBlocks = false;
		boolean dBlocks = nBlocks;
		if (map.hasTangent()) {
			Branch eBranch = root.getAtLod(
					hostOrigin.add(map.eOffset().getX(), map.eOffset().getY(), map.eOffset().getZ()), step);
			eBlocks = FrustumLod.blocksPermeation(
					hostImpedance, eBranch.effectiveImpedance(), eBranch.effectivePermeation());
			recordBounceNeighbor(root, hostOrigin, map.eOffset(), step, byKey, setColor, setId, dir);
			if (setId != null && host != null) {
				// Second incident direction (E/tangent) — "it would step here unimpeded".
				host.markIncidentTangent(setId, map.eOffset());
			}

			Branch dBranch = root.getAtLod(
					hostOrigin.add(map.dOffset().getX(), map.dOffset().getY(), map.dOffset().getZ()), step);
			dBlocks = FrustumLod.blocksPermeation(
					hostImpedance, dBranch.effectiveImpedance(), dBranch.effectivePermeation());
			recordBounceNeighbor(root, hostOrigin, map.dOffset(), step, byKey, setColor, setId, dir);
		}

		return switch (FrustumLod.classify(nBlocks, eBlocks, dBlocks)) {
			case SPLIT -> NedMarkerState.SPLIT;
			case GAP -> NedMarkerState.TRANSMISSION;
			case CORNER, FACE -> NedMarkerState.REFLECTION;
		};
	}

	private static void recordBounceNeighbor(
			Branch root,
			BlockPos hostOrigin,
			Vec3i offset,
			int step,
			LinkedHashMap<Long, Occupancy> byKey,
			@Nullable Integer setColor,
			@Nullable Integer setId,
			Vec3d dir
	) {
		if (offset.getX() == 0 && offset.getY() == 0 && offset.getZ() == 0) {
			return;
		}
		BlockPos neighbor = hostOrigin.add(offset.getX(), offset.getY(), offset.getZ());
		Branch lod = root.getAtLod(neighbor, step);
		BlockPos cellOrigin = FrustumLod.alignOrigin(neighbor, root.start, step);
		upsertOccupancy(byKey, cellOrigin, step, false, "bounce", lod.polar(), setColor, setId, dir);
	}

	/**
	 * Insert or merge a cube. A second distinct N/E/D set on the same cube records both set ids
	 * (full overlapping views — no half-split).
	 */
	private static Occupancy upsertOccupancy(
			LinkedHashMap<Long, Occupancy> byKey,
			BlockPos origin,
			int size,
			boolean virtual,
			String label,
			@Nullable Vec3d polar,
			@Nullable Integer color,
			@Nullable Integer setId,
			Vec3d splitHint
	) {
		long key = occupancyKey(origin, size, virtual);
		Occupancy occ = byKey.get(key);
		if (occ == null) {
			occ = new Occupancy(boxOf(origin, size), label, virtual, polar, color, null, setId, null,
					dominantAxis(splitHint));
			byKey.put(key, occ);
			return occ;
		}
		if (polar != null && occ.polar == null) {
			occ.polar = polar;
		}
		if ("bounce".equals(label)) {
			occ.label = label;
		}
		if (setId != null) {
			if (occ.setIdA == null) {
				occ.colorA = color;
				occ.setIdA = setId;
			} else if (!occ.setIdA.equals(setId) && occ.setIdB == null) {
				occ.colorB = color;
				occ.setIdB = setId;
				occ.splitAxis = dominantAxis(splitHint);
			}
		} else if (color != null && occ.colorA == null) {
			occ.colorA = color;
		}
		return occ;
	}

	private static List<OctreeOverlay.OctantView> occupanciesToViews(LinkedHashMap<Long, Occupancy> byKey) {
		List<OctreeOverlay.OctantView> views = new ArrayList<>();
		// Group by quartet set. Cubes claimed by two sets appear in both groups (full overlapping
		// boxes — no half-split). Cubes with no set render as ordinary path octants.
		LinkedHashMap<Integer, List<Occupancy>> bySet = new LinkedHashMap<>();
		List<Occupancy> unset = new ArrayList<>();
		for (Occupancy occ : byKey.values()) {
			boolean inSet = false;
			if (occ.setIdA != null) {
				bySet.computeIfAbsent(occ.setIdA, k -> new ArrayList<>()).add(occ);
				inSet = true;
			}
			if (occ.setIdB != null) {
				bySet.computeIfAbsent(occ.setIdB, k -> new ArrayList<>()).add(occ);
				inSet = true;
			}
			if (!inSet) {
				unset.add(occ);
			}
		}
		for (Occupancy occ : unset) {
			views.add(pathOctantView(occ));
		}
		for (Map.Entry<Integer, List<Occupancy>> entry : bySet.entrySet()) {
			views.addAll(quartetSetViews(entry.getKey(), entry.getValue()));
		}
		return views;
	}

	/**
	 * Human-view quartet rendering: the full bordered incident octant, plus (when a real survey
	 * produced neighbors and the cell is larger than 1³) a separate inset quartet-union fill with a
	 * white distance-faded border. Straight / size-1 interactions have no quartet — only the
	 * incident cube.
	 */
	private static List<OctreeOverlay.OctantView> quartetSetViews(int setId, List<Occupancy> members) {
		Occupancy host = hostForSet(members, setId);
		List<OctreeOverlay.OctantView> views = new ArrayList<>(2);
		if (host != null) {
			views.add(incidentOctantView(host, setId));
		}
		int size = host != null
				? (int) Math.round(host.box.maxX - host.box.minX)
				: (int) Math.round(members.get(0).box.maxX - members.get(0).box.minX);
		// 1³ cells and straight (no N/E/D neighbors recorded) never get a quartet shape.
		if (size > 1 && members.size() > 1) {
			views.add(quartetShapeView(members, host, setId));
		} else if (host == null) {
			// Set members without a recorded host (rare: host not in byKey yet) — fall back to
			// ordinary bordered cubes so the cluster is still visible.
			for (Occupancy m : members) {
				views.add(setMemberFallbackView(m, setId));
			}
		}
		return views;
	}

	private static @Nullable Occupancy hostForSet(List<Occupancy> members, int setId) {
		for (Occupancy m : members) {
			if (m.setIdA != null && m.setIdA.equals(setId) && m.hostA) {
				return m;
			}
			if (m.setIdB != null && m.setIdB.equals(setId) && m.hostB) {
				return m;
			}
		}
		return null;
	}

	private static OctreeOverlay.OctantView pathOctantView(Occupancy occ) {
		String label = occupancyLabel(occ);
		int size = (int) Math.round(occ.box.maxX - occ.box.minX);
		int color = occ.colorA != null
				? occ.colorA
				: OctantColor.forNode(
						(int) Math.round(occ.box.minX),
						(int) Math.round(occ.box.minY),
						(int) Math.round(occ.box.minZ),
						size);
		return new OctreeOverlay.OctantView(
				occ.box, null, label, size, color, occ.polar, null, false, null, null, true, false);
	}

	private static OctreeOverlay.OctantView incidentOctantView(Occupancy host, int setId) {
		String label = occupancyLabel(host);
		int size = (int) Math.round(host.box.maxX - host.box.minX);
		int color = colorForSet(host, setId, size);
		Vec3i face = faceForSet(host, setId, false);
		Vec3i faceE = faceForSet(host, setId, true);
		return new OctreeOverlay.OctantView(
				host.box, null, label, size, color, host.polar, setId, true, face, faceE, true, false);
	}

	/**
	 * Quartet union fill + white border. {@code box} is the full union (focus/hit); {@code insetFill}
	 * shrinks only the drawn fill/border by {@link #QUARTET_INSET_FRACTION}×size.
	 */
	private static OctreeOverlay.OctantView quartetShapeView(
			List<Occupancy> members,
			@Nullable Occupancy host,
			int setId
	) {
		Occupancy seed = host != null ? host : members.get(0);
		Box merged = seed.box;
		Vec3d polar = null;
		for (Occupancy m : members) {
			merged = union(merged, m.box);
			if (polar == null) {
				polar = m.polar;
			}
		}
		int size = (int) Math.round(seed.box.maxX - seed.box.minX);
		int color = colorForSet(seed, setId, size);
		// White border (distance-faded, not focus-gated); fill is inset — see populateBorders/Fills.
		return new OctreeOverlay.OctantView(
				merged, null, "quartet", size, color, polar, setId, false, null, null, true, true);
	}

	private static OctreeOverlay.OctantView setMemberFallbackView(Occupancy occ, int setId) {
		String label = occupancyLabel(occ);
		int size = (int) Math.round(occ.box.maxX - occ.box.minX);
		int color = colorForSet(occ, setId, size);
		boolean host = (occ.setIdA != null && occ.setIdA.equals(setId) && occ.hostA)
				|| (occ.setIdB != null && occ.setIdB.equals(setId) && occ.hostB);
		Vec3i face = host ? faceForSet(occ, setId, false) : null;
		Vec3i faceE = host ? faceForSet(occ, setId, true) : null;
		return new OctreeOverlay.OctantView(
				occ.box, null, label, size, color, occ.polar, setId, host, face, faceE, true, false);
	}

	private static String occupancyLabel(Occupancy occ) {
		String label = occ.label;
		if (occ.virtual) {
			label = label == null || label.isEmpty() ? "virtual" : label + " virtual";
		}
		return label;
	}

	private static int colorForSet(Occupancy occ, int setId, int size) {
		if (occ.setIdA != null && occ.setIdA.equals(setId) && occ.colorA != null) {
			return occ.colorA;
		}
		if (occ.setIdB != null && occ.setIdB.equals(setId) && occ.colorB != null) {
			return occ.colorB;
		}
		return OctantColor.forNode(
				(int) Math.round(occ.box.minX),
				(int) Math.round(occ.box.minY),
				(int) Math.round(occ.box.minZ),
				size);
	}

	private static @Nullable Vec3i faceForSet(Occupancy occ, int setId, boolean tangent) {
		if (occ.setIdA != null && occ.setIdA.equals(setId)) {
			return tangent ? occ.incidentFaceEA : occ.incidentFaceA;
		}
		if (occ.setIdB != null && occ.setIdB.equals(setId)) {
			return tangent ? occ.incidentFaceEB : occ.incidentFaceB;
		}
		return null;
	}

	private static Box union(Box a, Box b) {
		return new Box(
				Math.min(a.minX, b.minX), Math.min(a.minY, b.minY), Math.min(a.minZ, b.minZ),
				Math.max(a.maxX, b.maxX), Math.max(a.maxY, b.maxY), Math.max(a.maxZ, b.maxZ));
	}

	/** Inset {@code box} by {@code inset} blocks on every side; no-ops if that would invert it. */
	static Box insetBox(Box box, double inset) {
		if (inset <= 0) {
			return box;
		}
		double minX = box.minX + inset;
		double minY = box.minY + inset;
		double minZ = box.minZ + inset;
		double maxX = box.maxX - inset;
		double maxY = box.maxY - inset;
		double maxZ = box.maxZ - inset;
		if (minX >= maxX || minY >= maxY || minZ >= maxZ) {
			return box;
		}
		return new Box(minX, minY, minZ, maxX, maxY, maxZ);
	}

	/**
	 * First point on segment {@code start→end} that touches {@code box}: start if already inside,
	 * otherwise the entry hit on the AABB (or start if the segment misses).
	 */
	static Vec3d firstRayContact(Vec3d start, Vec3d end, Box box) {
		if (containsInclusive(box, start)) {
			return start;
		}
		Vec3d delta = end.subtract(start);
		double lenSq = delta.lengthSquared();
		if (lenSq < 1e-18) {
			return start;
		}
		double tEnter = 0.0;
		double tExit = 1.0;
		double[] enterExit = clipSlab(tEnter, tExit, start.x, delta.x, box.minX, box.maxX);
		if (enterExit == null) {
			return start;
		}
		enterExit = clipSlab(enterExit[0], enterExit[1], start.y, delta.y, box.minY, box.maxY);
		if (enterExit == null) {
			return start;
		}
		enterExit = clipSlab(enterExit[0], enterExit[1], start.z, delta.z, box.minZ, box.maxZ);
		if (enterExit == null || enterExit[0] > 1.0) {
			return start;
		}
		double t = Math.max(0.0, enterExit[0]);
		return start.add(delta.multiply(t));
	}

	/** @return {@code {tEnter, tExit}} or null on miss */
	private static double[] clipSlab(double tEnter, double tExit, double origin, double dir, double min, double max) {
		if (Math.abs(dir) < 1e-12) {
			if (origin < min || origin > max) {
				return null;
			}
			return new double[] {tEnter, tExit};
		}
		double inv = 1.0 / dir;
		double t0 = (min - origin) * inv;
		double t1 = (max - origin) * inv;
		if (t0 > t1) {
			double tmp = t0;
			t0 = t1;
			t1 = tmp;
		}
		tEnter = Math.max(tEnter, t0);
		tExit = Math.min(tExit, t1);
		if (tEnter > tExit) {
			return null;
		}
		return new double[] {tEnter, tExit};
	}

	private static boolean containsInclusive(Box box, Vec3d p) {
		return p.x >= box.minX && p.x <= box.maxX
				&& p.y >= box.minY && p.y <= box.maxY
				&& p.z >= box.minZ && p.z <= box.maxZ;
	}

	static int dominantAxis(Vec3d dir) {
		double ax = Math.abs(dir.x);
		double ay = Math.abs(dir.y);
		double az = Math.abs(dir.z);
		if (ax >= ay && ax >= az) {
			return 0;
		}
		if (ay >= az) {
			return 1;
		}
		return 2;
	}

	private static Box boxOf(BlockPos start, int size) {
		return new Box(
				start.getX(), start.getY(), start.getZ(),
				start.getX() + size,
				start.getY() + size,
				start.getZ() + size
		);
	}

	private static long occupancyKey(BlockPos start, int size, boolean virtual) {
		return (start.asLong() << 8) ^ size ^ (virtual ? 1L : 0L);
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
			long key = occupancyKey(visit.start(), visit.size(), visit.virtual());
			if (!seen.add(key)) {
				continue;
			}
			Box box = boxOf(visit.start(), visit.size());
			steps.add(new OctreeOverlay.VisitedStep(
					box, null, visit.virtual() ? null : "leaf", visit.virtual(), visit.polar(), null));
		}
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
		renderCubes(positionMatrix, projectionMatrix, cameraPos);
		renderNedMarkers(positionMatrix, projectionMatrix, cameraPos);
	}

	/** Fills + wireframes + polar axes (under the focused white ray when orchestrated). */
	void renderCubes(Matrix4f positionMatrix, Matrix4f projectionMatrix, Vec3d cameraPos) {
		Vec3d playerPos = playerRenderPos();
		ClusterFocus focus = resolveVisibleFocus(playerPos);
		List<OctreeOverlay.OctantView> visible = focus.visible();
		fillBuffer.markDirty();
		buffer.markDirty();
		fillBuffer.rebuild(builder -> populateFills(builder, visible, playerPos, focus));
		fillBuffer.draw(positionMatrix, projectionMatrix, cameraPos);
		buffer.rebuild(builder -> populateBorders(builder, visible, playerPos, focus));
		buffer.draw(positionMatrix, projectionMatrix, cameraPos);
	}

	/** Quartet-interaction crosses (green=transmission, red=reflection, yellow=split) — above the
	 *  white ray, below the ray-termination markers. */
	void renderNedMarkers(Matrix4f positionMatrix, Matrix4f projectionMatrix, Vec3d cameraPos) {
		Vec3d playerPos = playerRenderPos();
		List<OctreeOverlay.OctantView> visible = resolveVisibleFocus(playerPos).visible();
		List<NedMarker> markers = markersInVisibleOctants(nedMarkers, visible);
		if (markers.isEmpty()) {
			return;
		}
		nedMarkerBuffer.markDirty();
		nedMarkerBuffer.rebuild(builder -> {
			for (NedMarker marker : markers) {
				Vec3d center = marker.position();
				int markerColor = marker.state().color;
				double x = center.x;
				double y = center.y;
				double z = center.z;
				double s = NED_MARKER_HALF_EXTENT;
				GpuLineBuffer.line(builder, x - s, y, z, x + s, y, z, markerColor);
				GpuLineBuffer.line(builder, x, y - s, z, x, y + s, z, markerColor);
				GpuLineBuffer.line(builder, x, y, z - s, x, y, z + s, markerColor);
			}
		});
		nedMarkerBuffer.draw(positionMatrix, projectionMatrix, cameraPos);
	}

	/**
	 * Player inside one or more focused octants → only those clusters; otherwise the full set.
	 * Neighborhood / look-frustum modes always show everything collected.
	 */
	List<OctreeOverlay.OctantView> visibleOctants(Vec3d playerPos) {
		return resolveVisibleFocus(playerPos).visible();
	}

	ClusterFocus resolveVisibleFocus(Vec3d playerPos) {
		if (displayMode != DisplayMode.BEAM_PATH || allowLookFallback || selectedRayIndex < 0) {
			return ClusterFocus.unfocused(octants);
		}
		return resolveClusterFocus(octants, playerPos);
	}

	/**
	 * If the player intersects any N/E/D(+H) cluster member, keep every member of every hit cluster.
	 * Lone path cubes (no set id) that contain the player are kept as themselves. Outside all → all.
	 */
	static List<OctreeOverlay.OctantView> occupancyFilter(
			List<OctreeOverlay.OctantView> all,
			Vec3d playerPos
	) {
		return resolveClusterFocus(all, playerPos).visible();
	}

	static List<OctreeOverlay.OctantView> clusterFilter(
			List<OctreeOverlay.OctantView> all,
			Vec3d playerPos
	) {
		return resolveClusterFocus(all, playerPos).visible();
	}

	static ClusterFocus resolveClusterFocus(
			List<OctreeOverlay.OctantView> all,
			Vec3d playerPos
	) {
		HashSet<Integer> hitSets = new HashSet<>();
		boolean hitAny = false;
		List<OctreeOverlay.OctantView> hitSingletons = new ArrayList<>();
		for (OctreeOverlay.OctantView octant : all) {
			if (!containsInclusive(octant.box(), playerPos)) {
				continue;
			}
			hitAny = true;
			if (octant.setId() != null) {
				hitSets.add(octant.setId());
			} else {
				hitSingletons.add(octant);
			}
		}
		if (!hitAny) {
			return ClusterFocus.unfocused(all);
		}
		List<OctreeOverlay.OctantView> kept = new ArrayList<>();
		for (OctreeOverlay.OctantView octant : all) {
			if (octant.setId() != null && hitSets.contains(octant.setId())) {
				kept.add(octant);
			}
		}
		kept.addAll(hitSingletons);
		return new ClusterFocus(kept, true, Set.copyOf(hitSets));
	}

	static List<NedMarker> markersInVisibleOctants(List<NedMarker> markers, List<OctreeOverlay.OctantView> visible) {
		if (markers.isEmpty() || visible.isEmpty()) {
			return List.of();
		}
		List<NedMarker> kept = new ArrayList<>();
		for (NedMarker marker : markers) {
			for (OctreeOverlay.OctantView octant : visible) {
				if (containsInclusive(octant.box(), marker.position())) {
					kept.add(marker);
					break;
				}
			}
		}
		return kept;
	}

	/** Eye / view position — cluster focus and distance fade follow the camera, not the feet. */
	private static Vec3d playerRenderPos() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return Vec3d.ZERO;
		}
		return client.player.getEyePos();
	}

	private void populateFills(
			BufferBuilder builder,
			List<OctreeOverlay.OctantView> visible,
			Vec3d playerPos,
			ClusterFocus focus
	) {
		for (OctreeOverlay.OctantView octant : visible) {
			int border = isVirtual(octant) ? VIRTUAL_COLOR : octant.color();
			Box fillBox = octant.insetFill()
					? insetBox(octant.box(), QUARTET_INSET_FRACTION * octant.size())
					: octant.box();
			// Focused cluster (incident + quartet) stays at near opacity — no distance fade.
			float opacity = focus.focused() ? OCTANT_FILL_OPACITY : fillOpacity(fillBox, playerPos);
			int fill = OctantColor.withOpacity(border, opacity);
			GpuFillBuffer.boxFaces(
					builder,
					fillBox.minX, fillBox.minY, fillBox.minZ,
					fillBox.maxX, fillBox.maxY, fillBox.maxZ,
					fill
			);
			if (isFocusedIncidentHost(octant, focus) && octant.incidentFace() != null) {
				// White N (+ E when surveyed) walls on the full incident octant — never on the
				// inset quartet fill.
				Box faceBox = octant.box();
				Vec3i face = octant.incidentFace();
				GpuFillBuffer.boxFace(
						builder,
						faceBox.minX, faceBox.minY, faceBox.minZ,
						faceBox.maxX, faceBox.maxY, faceBox.maxZ,
						face.getX(), face.getY(), face.getZ(),
						INCIDENT_FACE_COLOR
				);
				Vec3i faceSecond = octant.incidentFaceSecond();
				if (faceSecond != null) {
					GpuFillBuffer.boxFace(
							builder,
							faceBox.minX, faceBox.minY, faceBox.minZ,
							faceBox.maxX, faceBox.maxY, faceBox.maxZ,
							faceSecond.getX(), faceSecond.getY(), faceSecond.getZ(),
							INCIDENT_FACE_COLOR
					);
				}
			}
		}
	}

	private void populateBorders(
			BufferBuilder builder,
			List<OctreeOverlay.OctantView> visible,
			Vec3d playerPos,
			ClusterFocus focus
	) {
		for (OctreeOverlay.OctantView octant : visible) {
			if (!octant.drawBorder()) {
				continue;
			}
			// Quartet outline matches the inset fill. When the cluster is focused, both the
			// quartet and the incident octant keep borders (distance fade is suspended).
			Box borderBox = octant.insetFill()
					? insetBox(octant.box(), QUARTET_INSET_FRACTION * octant.size())
					: octant.box();
			if (!shouldDrawBorders(borderBox, playerPos, focus.focused())) {
				continue;
			}
			int color;
			if (octant.insetFill() || isFocusedIncidentHost(octant, focus)) {
				color = INCIDENT_HIGHLIGHT_COLOR;
			} else {
				color = isVirtual(octant) ? VIRTUAL_COLOR : octant.color();
			}
			GpuLineBuffer.boxEdges(
					builder,
					borderBox.minX, borderBox.minY, borderBox.minZ,
					borderBox.maxX, borderBox.maxY, borderBox.maxZ,
					color
			);
			drawPolarAxis(builder, borderBox, octant.polar());
		}
	}

	/** Incident host of any cluster the player currently intersects. */
	static boolean isFocusedIncidentHost(OctreeOverlay.OctantView octant, ClusterFocus focus) {
		return focus.focused()
				&& octant.incidentHost()
				&& octant.setId() != null
				&& focus.hitSetIds().contains(octant.setId());
	}

	/** Near {@link #OCTANT_FILL_OPACITY} → far {@link #OCTANT_FILL_OPACITY_FAR} over {@link #OCTANT_FADE_BLOCKS}. */
	static float fillOpacity(Box box, Vec3d playerPos) {
		double t = Math.min(1.0, distanceToBox(playerPos, box) / OCTANT_FADE_BLOCKS);
		return (float) (OCTANT_FILL_OPACITY + (OCTANT_FILL_OPACITY_FAR - OCTANT_FILL_OPACITY) * t);
	}

	/** Borders only inside the fade range (dropped once fully far), unless cluster-focused. */
	static boolean drawBorders(Box box, Vec3d playerPos) {
		return shouldDrawBorders(box, playerPos, false);
	}

	static boolean shouldDrawBorders(Box box, Vec3d playerPos, boolean forceFocusedCluster) {
		return forceFocusedCluster || distanceToBox(playerPos, box) < OCTANT_FADE_BLOCKS;
	}

	/** Euclidean distance to the AABB; 0 when {@code p} is inside. */
	static double distanceToBox(Vec3d p, Box box) {
		double dx = Math.max(box.minX - p.x, Math.max(0.0, p.x - box.maxX));
		double dy = Math.max(box.minY - p.y, Math.max(0.0, p.y - box.maxY));
		double dz = Math.max(box.minZ - p.z, Math.max(0.0, p.z - box.maxZ));
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
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

	record CastPathOverlay(List<OctreeOverlay.OctantView> views, List<NedMarker> nedMarkers) {}

	/**
	 * Focused-frustum visibility: when the player occupies cluster member(s), only those clusters
	 * render. Each intersected HNED set highlights its own incident host (white border + face).
	 */
	record ClusterFocus(
			List<OctreeOverlay.OctantView> visible,
			boolean focused,
			Set<Integer> hitSetIds
	) {
		static ClusterFocus unfocused(List<OctreeOverlay.OctantView> all) {
			return new ClusterFocus(all, false, Set.of());
		}
	}

	/** Mutable per-cube occupancy while building a focused frustum overlay. */
	private static final class Occupancy {
		final Box box;
		String label;
		final boolean virtual;
		@Nullable Vec3d polar;
		@Nullable Integer colorA;
		@Nullable Integer colorB;
		@Nullable Integer setIdA;
		@Nullable Integer setIdB;
		@Nullable Vec3d contact;
		boolean hostA;
		boolean hostB;
		@Nullable Vec3i incidentFaceA;
		@Nullable Vec3i incidentFaceB;
		/** Task F4: host's second ("E"/tangent-direction) incident face — the "it would step here
		 *  unimpeded" check, drawn alongside {@link #incidentFaceA}/{@link #incidentFaceB}. */
		@Nullable Vec3i incidentFaceEA;
		@Nullable Vec3i incidentFaceEB;
		int splitAxis;

		Occupancy(
				Box box,
				String label,
				boolean virtual,
				@Nullable Vec3d polar,
				@Nullable Integer colorA,
				@Nullable Integer colorB,
				@Nullable Integer setIdA,
				@Nullable Integer setIdB,
				int splitAxis
		) {
			this.box = box;
			this.label = label;
			this.virtual = virtual;
			this.polar = polar;
			this.colorA = colorA;
			this.colorB = colorB;
			this.setIdA = setIdA;
			this.setIdB = setIdB;
			this.splitAxis = splitAxis;
		}

		void markIncidentHost(int setId, Vec3i face) {
			if (setIdA != null && setIdA.equals(setId)) {
				hostA = true;
				incidentFaceA = face;
			} else if (setIdB != null && setIdB.equals(setId)) {
				hostB = true;
				incidentFaceB = face;
			}
		}

		void markIncidentTangent(int setId, Vec3i face) {
			if (setIdA != null && setIdA.equals(setId)) {
				incidentFaceEA = face;
			} else if (setIdB != null && setIdB.equals(setId)) {
				incidentFaceEB = face;
			}
		}
	}
}
