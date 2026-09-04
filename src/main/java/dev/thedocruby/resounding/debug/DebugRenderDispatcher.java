package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.Engine;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.profiler.Profiler;
import org.joml.Matrix4f;

@Environment(EnvType.CLIENT)
public final class DebugRenderDispatcher {

	public static final DebugRenderDispatcher INSTANCE = new DebugRenderDispatcher();

	private final BounceRayLayer bounceRays = new BounceRayLayer();
	private final OcclusionRayLayer occlusionRays = new OcclusionRayLayer();
	private final CaptureLayer capture = new CaptureLayer();
	private final OctreeLayer octree = new OctreeLayer();

	private DebugRenderDispatcher() {}

	public void register() {
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(this::render);
	}

	public BounceRayLayer bounceRays() {
		return bounceRays;
	}

	public OcclusionRayLayer occlusionRays() {
		return occlusionRays;
	}

	public OctreeLayer octree() {
		return octree;
	}

	public CaptureLayer capture() {
		return capture;
	}

	private void render(WorldRenderContext context) {
		if (!Engine.isActive) {
			return;
		}

		Vec3d cameraPos = context.camera().getPos();
		Matrix4f positionMatrix = context.positionMatrix();
		Matrix4f projectionMatrix = context.projectionMatrix();
		Profiler profiler = context.profiler();
		profiler.push("resounding_debug");
		try {
			// Updates first so focus state / octant boxes are current before any draw.
			updateIfEnabled(occlusionRays, profiler, "occlusion_rays_update");
			updateIfEnabled(capture, profiler, "capture_update");
			updateIfEnabled(bounceRays, profiler, "bounce_rays_update");
			updateIfEnabled(octree, profiler, "octree_update");

			boolean focusedFrustum = bounceRays.isEnabled()
					&& octree.isEnabled()
					&& BounceRayLayer.activeRayIndex() >= 0;

			if (focusedFrustum) {
				// Bottom → top: other rays, cubes, white focused ray, quartet-interaction crosses, termination markers.
				profiler.push("bounce_rays_bg");
				try {
					bounceRays.renderBackgroundRays(positionMatrix, projectionMatrix, cameraPos);
				} finally {
					profiler.pop();
				}
				profiler.push("octree_cubes");
				try {
					octree.renderCubes(positionMatrix, projectionMatrix, cameraPos);
				} finally {
					profiler.pop();
				}
				profiler.push("bounce_rays_white");
				try {
					bounceRays.renderFocusedWhiteRay(positionMatrix, projectionMatrix, cameraPos);
				} finally {
					profiler.pop();
				}
				profiler.push("octree_ned");
				try {
					octree.renderNedMarkers(positionMatrix, projectionMatrix, cameraPos);
				} finally {
					profiler.pop();
				}
				profiler.push("bounce_rays_terminators");
				try {
					bounceRays.renderTerminatorMarkers(positionMatrix, projectionMatrix, cameraPos);
				} finally {
					profiler.pop();
				}
			} else {
				renderLayer(bounceRays, "bounce_rays", positionMatrix, projectionMatrix, cameraPos, profiler);
				renderLayer(octree, "octree", positionMatrix, projectionMatrix, cameraPos, profiler);
			}

			renderLayer(occlusionRays, "occlusion_rays", positionMatrix, projectionMatrix, cameraPos, profiler);
			renderLayer(capture, "capture", positionMatrix, projectionMatrix, cameraPos, profiler);
		} finally {
			profiler.pop();
		}
	}

	private static void updateIfEnabled(DebugLayer layer, Profiler profiler, String name) {
		if (!layer.isEnabled()) {
			return;
		}
		profiler.push(name);
		try {
			layer.update();
		} finally {
			profiler.pop();
		}
	}

	private static void renderLayer(
			DebugLayer layer,
			String name,
			Matrix4f positionMatrix,
			Matrix4f projectionMatrix,
			Vec3d cameraPos,
			Profiler profiler
	) {
		if (!layer.isEnabled()) {
			return;
		}
		profiler.push(name);
		try {
			layer.render(positionMatrix, projectionMatrix, cameraPos);
		} finally {
			profiler.pop();
		}
	}
}
