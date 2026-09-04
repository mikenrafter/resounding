package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.debug.math.RingBuffer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.thedocruby.resounding.config.PrecomputedConfig.pConfig;

@Environment(EnvType.CLIENT)
abstract class RayLineLayer implements DebugLayer {

	// Plan B1: fixed-capacity live ray buffer, decoupled from physics config.
	private static final int MAX_LIVE_SEGMENTS = 4096;

	private final GpuLineBuffer buffer;
	private final RingBuffer<LineSegment> segments = new RingBuffer<>(MAX_LIVE_SEGMENTS);
	private boolean enabled = true;
	private int version;

	RayLineLayer(boolean depthTest, float lineWidth) {
		this.buffer = new GpuLineBuffer(VertexBuffer.Usage.DYNAMIC, depthTest, lineWidth);
	}

	void addSegment(Vec3d start, Vec3d end, int color, float width) {
		addSegment(start, end, color, width, false, -1, 0);
	}

	void addSegment(Vec3d start, Vec3d end, int color, float width, boolean terminator) {
		addSegment(start, end, color, width, terminator, -1, 0);
	}

	void addSegment(
			Vec3d start,
			Vec3d end,
			int color,
			float width,
			boolean terminator,
			int rayIndex,
			int branchSize
	) {
		if (!pConfig.dRays) {
			return;
		}
		synchronized (segments) {
			segments.offer(new LineSegment(start, end, color, width, terminator, rayIndex, branchSize));
			version++;
		}
		buffer.markDirty();
	}

	public List<LineSegment> segmentSnapshot() {
		synchronized (segments) {
			return segments.asList();
		}
	}

	public int version() {
		return version;
	}

	void clear() {
		synchronized (segments) {
			segments.clear();
			version++;
		}
		buffer.markDirty();
	}

	@Override
	public boolean isEnabled() {
		return enabled && pConfig.dRays;
	}

	void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	@Override
	public void update() {
	}

	@Override
	public void render(Matrix4f positionMatrix, Matrix4f projectionMatrix, Vec3d cameraPos) {
		List<LineSegment> snapshot;
		synchronized (segments) {
			snapshot = segments.asList();
		}
		renderSegments(snapshot, positionMatrix, projectionMatrix, cameraPos, null);
	}

	/**
	 * Draws {@code snapshot} through this layer's buffer. When {@code colorOverride} is non-null,
	 * every segment uses that ARGB instead of {@link LineSegment#color()}.
	 */
	void renderSegments(
			List<LineSegment> snapshot,
			Matrix4f positionMatrix,
			Matrix4f projectionMatrix,
			Vec3d cameraPos,
			@Nullable Integer colorOverride
	) {
		if (snapshot.isEmpty()) {
			return;
		}

		Map<Float, List<LineSegment>> byWidth = new LinkedHashMap<>();
		for (LineSegment segment : snapshot) {
			byWidth.computeIfAbsent(segment.width, ignored -> new ArrayList<>()).add(segment);
		}

		for (Map.Entry<Float, List<LineSegment>> entry : byWidth.entrySet().stream()
				.sorted(Map.Entry.comparingByKey())
				.toList()) {
			float width = entry.getKey();
			List<LineSegment> group = entry.getValue();
			buffer.markDirty();
			buffer.rebuild(builder -> {
				for (LineSegment segment : group) {
					int color = colorOverride != null ? colorOverride : segment.color;
					GpuLineBuffer.line(
							builder,
							segment.start.x, segment.start.y, segment.start.z,
							segment.end.x, segment.end.y, segment.end.z,
							color
					);
				}
			});
			buffer.draw(positionMatrix, projectionMatrix, cameraPos, width);
		}
	}

	public 	record LineSegment(
			Vec3d start,
			Vec3d end,
			int color,
			float width,
			boolean terminator,
			/** Env-eval cast index (0..63); {@code -1} for markers / unknown. */
			int rayIndex,
			/** LOD cell size the cast resolved this segment at; {@code 0} if unknown. */
			int branchSize
	) {
		LineSegment(Vec3d start, Vec3d end, int color, float width, boolean terminator) {
			this(start, end, color, width, terminator, -1, 0);
		}
	}
}
