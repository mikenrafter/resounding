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

	private final DebugLayer[] layers = {bounceRays, occlusionRays, capture, octree};
	private static final String[] LAYER_NAMES = {"bounce_rays", "occlusion_rays", "capture", "octree"};

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
			for (int i = 0; i < layers.length; i++) {
				DebugLayer layer = layers[i];
				if (!layer.isEnabled()) {
					continue;
				}
				profiler.push(LAYER_NAMES[i]);
				try {
					layer.update();
					layer.render(positionMatrix, projectionMatrix, cameraPos);
				} finally {
					profiler.pop();
				}
			}
		} finally {
			profiler.pop();
		}
	}
}
