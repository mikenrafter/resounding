package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.material.Material;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.List;

import static dev.thedocruby.resounding.config.PrecomputedConfig.pConfig;

@Environment(EnvType.CLIENT)
public final class BounceRayLayer extends RayLineLayer {

	private static final float BASE_LINE_WIDTH = 4.0F;
	private static final double REFERENCE_POWER = 128.0D;
	/** High-contrast marker color for ray path endpoints (drawn without depth test). */
	public static final int TERMINATED_COLOR = 0xFFFF00FF;
	static final float TERMINATED_LINE_WIDTH = 3.0F;
	private static final double MARKER_HALF_EXTENT = 0.12D;

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
			@Nullable Material material,
			double reflectivity,
			double transmission,
			double power,
			boolean terminated
	) {
		addSegment(start, end, color, BASE_LINE_WIDTH, terminated);
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
