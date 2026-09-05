package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.material.Material;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.thedocruby.resounding.config.PrecomputedConfig.pConfig;

@Environment(EnvType.CLIENT)
public final class BounceRayLayer extends RayLineLayer {

	private static final float BASE_LINE_WIDTH = 4.0F;
	private static final double REFERENCE_POWER = 128.0D;
	/** Fallback marker color for a terminated segment with no {@link TerminationCause} attached. */
	public static final int TERMINATED_COLOR = 0xFFFF00FF;
	/** J-cycled active bounce ray (B on): all segments white, drawn without depth test. */
	public static final int ACTIVE_RAY_COLOR = 0xFFFFFFFF;
	static final float TERMINATED_LINE_WIDTH = 3.0F;

	/**
	 * Why a ray's propagation loop stopped, from {@code Engine.raycast}'s {@code terminationReason}
	 * — one color per cause so a death marker alone tells you which failure mode it was, without
	 * reading the log. Distinct from white (active ray), green (quartet transmission markers), and
	 * the polarity-axis purple ({@link OctreeLayer#POLAR_COLOR}).
	 */
	public enum TerminationCause {
		/** Ran out of power / path length / bounce budget — the expected, benign way a ray ends. */
		BUDGET(0xFFFF3B30),
		/** Octree/chunk resolution failed at the ray's position ("left the known world"). */
		LEFT_WORLD(0xFFFF8C00),
		/** Vacuum / soft-stop blank ({@code Cast.blank}) — null transmit dir after unusable medium. */
		VACUUM(0xFF00BCD4),
		/** A reflected boundary resolved to a degenerate (null) direction. */
		NO_DIRECTION(0xFF2979FF),
		/** Reflected several times in a row while confined to very few octree host cells — see
		 *  {@code Engine.QUARTET_STALL_REFLECTS}. */
		STALLED(0xFFFFD400);

		public final int color;

		TerminationCause(int color) {
			this.color = color;
		}
	}
	private static final double MARKER_HALF_EXTENT = 0.12D;
	/** Max gap between one segment's end and the next segment's start to stay on the same ray. */
	static final double CONNECT_EPS = 1e-4;

	private final GpuLineBuffer terminatorBuffer;
	/** Active-ray overlay: no depth test so the J-selected polyline always punches through. */
	private final GpuLineBuffer activeRayBuffer;

	/** {@code System.currentTimeMillis()} the K-key kapture flash started; {@code -1} when idle. */
	private volatile long flashStartMillis = -1L;
	/** Env-eval ray index the active kapture flash applies to; {@code -1} when idle. */
	private volatile int flashRayIndex = -1;

	public BounceRayLayer() {
		super(true, BASE_LINE_WIDTH);
		this.terminatorBuffer = new GpuLineBuffer(VertexBuffer.Usage.DYNAMIC, false, TERMINATED_LINE_WIDTH);
		this.activeRayBuffer = new GpuLineBuffer(VertexBuffer.Usage.DYNAMIC, false, BASE_LINE_WIDTH);
	}

	public void clearSegments() {
		clear();
	}

	/** Starts the K-key kapture flash (white 3x over 1.5s, see {@link KaptureFlash}) for {@code rayIndex}. */
	public void startKaptureFlash(int rayIndex) {
		flashStartMillis = System.currentTimeMillis();
		flashRayIndex = rayIndex;
	}

	@Override
	void clear() {
		super.clear();
		terminatorBuffer.markDirty();
		activeRayBuffer.markDirty();
	}

	@Override
	public void render(Matrix4f positionMatrix, Matrix4f projectionMatrix, Vec3d cameraPos) {
		// Non-orchestrated path (octree off / no focus): background + white + terminators together.
		renderBackgroundRays(positionMatrix, projectionMatrix, cameraPos);
		renderFocusedWhiteRay(positionMatrix, projectionMatrix, cameraPos);
		renderTerminatorCrosses(positionMatrix, projectionMatrix, cameraPos);
	}

	/**
	 * Colored bounce segments excluding the J-focused ray (and excluding segments hidden inside
	 * focused octants). Drawn under frustum cubes when the dispatcher orchestrates focus order.
	 */
	void renderBackgroundRays(Matrix4f positionMatrix, Matrix4f projectionMatrix, Vec3d cameraPos) {
		List<LineSegment> snapshot = segmentSnapshot();
		int activeRay = activeRayIndex();
		if (activeRay < 0) {
			renderSegments(snapshot, positionMatrix, projectionMatrix, cameraPos, null);
			return;
		}
		List<Box> hideZones = DebugRenderDispatcher.INSTANCE.octree().focusedOctantBoxes();
		List<LineSegment> background = new ArrayList<>(snapshot.size());
		for (LineSegment segment : snapshot) {
			if (segment.rayIndex() == activeRay) {
				continue;
			}
			if (!intersectsFocusedOctant(segment, hideZones)) {
				background.add(segment);
			}
		}
		renderSegments(background, positionMatrix, projectionMatrix, cameraPos, null);
	}

	/** White focused polyline — drawn above cubes, below green NED markers. */
	void renderFocusedWhiteRay(Matrix4f positionMatrix, Matrix4f projectionMatrix, Vec3d cameraPos) {
		int activeRay = activeRayIndex();
		if (activeRay < 0) {
			return;
		}
		if (isKaptureFlashHidingRay(activeRay)) {
			return;
		}
		List<LineSegment> focused = new ArrayList<>();
		for (LineSegment segment : segmentSnapshot()) {
			if (segment.rayIndex() == activeRay) {
				focused.add(segment);
			}
		}
		renderActiveRay(focused, positionMatrix, projectionMatrix, cameraPos);
	}

	/** Cause-colored terminators (see {@link TerminationCause}) — drawn last (above white ray and
	 *  quartet-interaction crosses). */
	void renderTerminatorMarkers(Matrix4f positionMatrix, Matrix4f projectionMatrix, Vec3d cameraPos) {
		renderTerminatorCrosses(positionMatrix, projectionMatrix, cameraPos);
	}

	/**
	 * Env-eval cast index selected via J while bounce rays (B) drive frustum mode; {@code -1} when
	 * nothing is focused (look-frustum fallback, octree off, or neighborhood mode).
	 */
	static int activeRayIndex() {
		OctreeLayer octree = DebugRenderDispatcher.INSTANCE.octree();
		if (!octree.isEnabled() || octree.displayMode() != OctreeLayer.DisplayMode.BEAM_PATH) {
			return -1;
		}
		return octree.selectedRayIndex();
	}

	/**
	 * True when an in-progress kapture flash (see {@link #startKaptureFlash}) is on its transparent
	 * phase for {@code activeRay}, so the white ray should be skipped this frame. Also retires the
	 * flash (resets {@link #flashStartMillis}) once its 1.5s sequence has fully elapsed.
	 */
	private boolean isKaptureFlashHidingRay(int activeRay) {
		long startMillis = flashStartMillis;
		if (startMillis < 0) {
			return false;
		}
		long elapsed = System.currentTimeMillis() - startMillis;
		if (!KaptureFlash.isFlashing(elapsed)) {
			flashStartMillis = -1L;
			return false;
		}
		return flashRayIndex == activeRay && !KaptureFlash.isVisible(elapsed);
	}

	/** True when either endpoint lies inside a focused frustum octant (unrelated rays stay out). */
	static boolean intersectsFocusedOctant(LineSegment segment, List<Box> zones) {
		if (zones.isEmpty()) {
			return false;
		}
		for (Box box : zones) {
			if (box.contains(segment.start()) || box.contains(segment.end())) {
				return true;
			}
		}
		return false;
	}

	private void renderActiveRay(
			List<LineSegment> focused,
			Matrix4f positionMatrix,
			Matrix4f projectionMatrix,
			Vec3d cameraPos
	) {
		if (focused.isEmpty()) {
			return;
		}
		activeRayBuffer.markDirty();
		activeRayBuffer.rebuild(builder -> {
			for (LineSegment segment : focused) {
				GpuLineBuffer.line(
						builder,
						segment.start().x, segment.start().y, segment.start().z,
						segment.end().x, segment.end().y, segment.end().z,
						ACTIVE_RAY_COLOR
				);
			}
		});
		activeRayBuffer.draw(positionMatrix, projectionMatrix, cameraPos, BASE_LINE_WIDTH);
	}

	public void addSoundBounceRay(
			Vec3d start,
			Vec3d end,
			int color,
			int bounceIndex,
			int sourceID,
			int rayIndex,
			@Nullable Material material,
			double reflectivity,
			double transmission,
			double power,
			int branchSize,
			boolean terminated,
			@Nullable TerminationCause cause
	) {
		int terminatorColor = cause != null ? cause.color : TERMINATED_COLOR;
		addSegment(start, end, color, BASE_LINE_WIDTH, terminated, rayIndex, Math.max(0, branchSize), terminatorColor);
	}

	/**
	 * Groups live bounce segments by env-eval cast {@code rayIndex} (the 64 polylines), first-seen
	 * order. Interleaved rays stay separate; connectivity is not used. Markers ({@code rayIndex < 0}
	 * or zero-length) are skipped.
	 */
	public static List<List<LineSegment>> groupByRayIndex(List<LineSegment> snapshot) {
		LinkedHashMap<Integer, List<LineSegment>> byRay = new LinkedHashMap<>();
		double epsSq = CONNECT_EPS * CONNECT_EPS;
		for (LineSegment segment : snapshot) {
			if (segment.rayIndex() < 0) {
				continue;
			}
			if (segment.start().squaredDistanceTo(segment.end()) <= epsSq) {
				continue;
			}
			byRay.computeIfAbsent(segment.rayIndex(), ignored -> new ArrayList<>()).add(segment);
		}
		List<List<LineSegment>> rays = new ArrayList<>(byRay.size());
		for (Map.Entry<Integer, List<LineSegment>> entry : byRay.entrySet()) {
			rays.add(List.copyOf(entry.getValue()));
		}
		return rays;
	}

	/**
	 * @deprecated Prefer {@link #groupByRayIndex}; connectivity splits interleaved casts.
	 */
	public static List<List<LineSegment>> groupConnectedRays(List<LineSegment> snapshot) {
		List<List<LineSegment>> rays = new ArrayList<>();
		List<LineSegment> current = new ArrayList<>();
		LineSegment lastReal = null;
		double epsSq = CONNECT_EPS * CONNECT_EPS;
		for (LineSegment segment : snapshot) {
			if (segment.start().squaredDistanceTo(segment.end()) <= epsSq) {
				continue;
			}
			if (lastReal != null && lastReal.end().squaredDistanceTo(segment.start()) > epsSq) {
				if (!current.isEmpty()) {
					rays.add(List.copyOf(current));
					current = new ArrayList<>();
				}
			}
			current.add(segment);
			lastReal = segment;
		}
		if (!current.isEmpty()) {
			rays.add(List.copyOf(current));
		}
		return rays;
	}

	public void addTerminatorCross(Vec3d center, @Nullable TerminationCause cause) {
		if (!pConfig.dRays) {
			return;
		}
		int color = cause != null ? cause.color : TERMINATED_COLOR;
		// Marker-only entry: shares the segment ring buffer so crosses expire with their rays.
		addSegment(center, center, color, BASE_LINE_WIDTH, true, -1, 0, color);
	}

	private void renderTerminatorCrosses(Matrix4f positionMatrix, Matrix4f projectionMatrix, Vec3d cameraPos) {
		List<LineSegment> snapshot = segmentSnapshot();
		boolean hasMarkers = false;
		for (LineSegment segment : snapshot) {
			if (segment.terminator()) {
				hasMarkers = true;
				break;
			}
		}
		if (!hasMarkers) {
			return;
		}

		terminatorBuffer.markDirty();
		terminatorBuffer.rebuild(builder -> {
			for (LineSegment segment : snapshot) {
				if (!segment.terminator()) {
					continue;
				}
				double x = segment.end().x;
				double y = segment.end().y;
				double z = segment.end().z;
				double s = MARKER_HALF_EXTENT;
				int color = segment.terminatorColor();
				GpuLineBuffer.line(builder, x - s, y, z, x + s, y, z, color);
				GpuLineBuffer.line(builder, x, y - s, z, x, y + s, z, color);
				GpuLineBuffer.line(builder, x, y, z - s, x, y, z + s, color);
			}
		});
		terminatorBuffer.draw(positionMatrix, projectionMatrix, cameraPos);
	}

	static float lineWidthFor(double power, int bounceIndex) {
		float scaled = (float) (BASE_LINE_WIDTH * power / REFERENCE_POWER);
		return Math.max(1.0F, scaled - bounceIndex);
	}
}
