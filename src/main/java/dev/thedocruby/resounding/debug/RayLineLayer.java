package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.debug.math.RingBuffer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

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

	RayLineLayer(boolean depthTest, float lineWidth) {
		this.buffer = new GpuLineBuffer(VertexBuffer.Usage.DYNAMIC, depthTest, lineWidth);
	}

	void addSegment(Vec3d start, Vec3d end, int color, float width) {
		addSegment(start, end, color, width, false);
	}

	void addSegment(Vec3d start, Vec3d end, int color, float width, boolean terminator) {
		if (!pConfig.dRays) {
			return;
		}
		synchronized (segments) {
			segments.offer(new LineSegment(start, end, color, width, terminator));
		}
		buffer.markDirty();
	}

	List<LineSegment> segmentSnapshot() {
		synchronized (segments) {
			return segments.asList();
		}
	}

	void clear() {
		synchronized (segments) {
			segments.clear();
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
					GpuLineBuffer.line(
							builder,
							segment.start.x, segment.start.y, segment.start.z,
							segment.end.x, segment.end.y, segment.end.z,
							segment.color
					);
				}
			});
			buffer.draw(positionMatrix, projectionMatrix, cameraPos, width);
		}
	}

	record LineSegment(Vec3d start, Vec3d end, int color, float width, boolean terminator) {}
}
