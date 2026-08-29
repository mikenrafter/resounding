package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.material.Material;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public final class BounceRayLayer extends RayLineLayer {

	public BounceRayLayer() {
		super(true, 0.25F);
	}

	public void addSoundBounceRay(Vec3d start, Vec3d end, int color, int bounceIndex, int sourceID, @Nullable Material material) {
		addSegment(start, end, color);
	}
}
