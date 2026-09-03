package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.material.Material;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gl.VertexBuffer;
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
	/** High-contrast marker color for ray path endpoints (drawn without depth test). */
	public static final int TERMINATED_COLOR = 0xFFFF00FF;
	static final float TERMINATED_LINE_WIDTH = 3.0F;
	private static final double MARKER_HALF_EXTENT = 0.12D;
	/** Max gap between one segment's end and the next segment's start to stay on the same ray. */
	static final double CONNECT_EPS = 1e-4;

	private final GpuLineBuffer terminatorBuffer;

	public BounceRayLayer() {
		super(true, BASE_LINE_WIDTH);
		this.terminatorBuffer = new GpuLineBuffer(VertexBuffer.Usage.DYNAMIC, false, TERMINATED_LINE_WIDTH);
	}

	public void clearSegments() {
		clear();
	}

	@Override
	void clear() {
		super.clear();
		terminatorBuffer.markDirty();
	}

	@Override
	public void render(Matrix4f positionMatrix, Matrix4f projectionMatrix, Vec3d cameraPos) {
		super.render(positionMatrix, projectionMatrix, cameraPos);
		renderTerminatorCrosses(positionMatrix, projectionMatrix, cameraPos);
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
			boolean terminated
	) {
		addSegment(start, end, color, BASE_LINE_WIDTH, terminated, rayIndex, Math.max(0, branchSize));
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

	public void addTerminatorCross(Vec3d center) {
		if (!pConfig.dRays) {
			return;
		}
		// Marker-only entry: shares the segment ring buffer so crosses expire with their rays.
		addSegment(center, center, TERMINATED_COLOR, BASE_LINE_WIDTH, true);
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
				GpuLineBuffer.line(builder, x - s, y, z, x + s, y, z, TERMINATED_COLOR);
				GpuLineBuffer.line(builder, x, y - s, z, x, y + s, z, TERMINATED_COLOR);
				GpuLineBuffer.line(builder, x, y, z - s, x, y, z + s, TERMINATED_COLOR);
			}
		});
		terminatorBuffer.draw(positionMatrix, projectionMatrix, cameraPos);
	}

	static float lineWidthFor(double power, int bounceIndex) {
		float scaled = (float) (BASE_LINE_WIDTH * power / REFERENCE_POWER);
		return Math.max(1.0F, scaled - bounceIndex);
	}
}
