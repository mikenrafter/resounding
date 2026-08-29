package dev.thedocruby.resounding.debug;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.Vec3d;

@Environment(EnvType.CLIENT)
public final class OcclusionRayLayer extends RayLineLayer {

	public OcclusionRayLayer() {
		super(false, 3F);
	}

	public void addOcclusionRay(Vec3d start, Vec3d end, int color) {
		addSegment(start, end, color);
	}
}
