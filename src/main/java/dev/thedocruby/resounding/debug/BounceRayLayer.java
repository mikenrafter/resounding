package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.material.Material;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public final class BounceRayLayer extends RayLineLayer {

	private static final float BASE_LINE_WIDTH = 4.0F;
	private static final double REFERENCE_POWER = 128.0D;

	public BounceRayLayer() {
		super(true, BASE_LINE_WIDTH);
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
			double power
	) {
		addSegment(start, end, color, lineWidthFor(power, bounceIndex));
	}

	static float lineWidthFor(double power, int bounceIndex) {
		float scaled = (float) (BASE_LINE_WIDTH * power / REFERENCE_POWER);
		return Math.max(1.0F, scaled - bounceIndex);
	}
}
