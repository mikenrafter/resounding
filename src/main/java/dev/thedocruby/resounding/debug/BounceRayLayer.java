package dev.thedocruby.resounding.debug;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.Vec3d;

@Environment(EnvType.CLIENT)
public final class BounceRayLayer extends RayLineLayer {

	public BounceRayLayer() {
		super(true, 0.25F);
	}

	public void addSoundBounceRay(Vec3d start, Vec3d end, int color) {
		addSegment(start, end, color);
	}
}
