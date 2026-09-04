package dev.thedocruby.resounding.debug.math;

import net.minecraft.util.math.BlockPos;

/**
 * Spatial and set-based octant coloring for debug overlays.
 *
 * <p>Neighborhood mode uses {@link #forNode}: face-adjacent same-scale nodes always differ
 * (8-entry child-index parity).
 *
 * <p>Focused frustum / N/E/D mode uses {@link #forSet}: each H+N+E+D quartet shares one color, and
 * consecutive set indices never collide so neighboring 4-octant clusters stay distinct along a path.
 */
public final class OctantColor {

	private static final int[] PALETTE = {
			0xFFE6194B,
			0xFF3CB44B,
			0xFFFFE119,
			0xFF4363D8,
			0xFFF58231,
			0xFF911EB4,
			0xFF42D4F4,
			0xFFF032E6,
	};

	private OctantColor() {}

	public static int forNode(BlockPos origin, int size) {
		return forNode(origin.getX(), origin.getY(), origin.getZ(), size);
	}

	public static int forNode(int originX, int originY, int originZ, int size) {
		int shift = sizeToShift(size);
		int index = (originX >> shift) & 1
				| ((originY >> shift) & 1) << 1
				| ((originZ >> shift) & 1) << 2;
		return PALETTE[index];
	}

	/**
	 * Color for N/E/D(+H) quartet {@code setIndex}. Consecutive indices use different palette
	 * entries ({@code * 3 mod 8}) so adjacent 4-sets along a beam path never match.
	 */
	public static int forSet(int setIndex) {
		return PALETTE[Math.floorMod(setIndex * 3, PALETTE.length)];
	}

	/** ARGB with the given opacity in {@code [0,1]}, keeping the RGB of {@code argb}. */
	public static int withOpacity(int argb, float opacity) {
		int a = Math.max(0, Math.min(255, Math.round(opacity * 255.0F)));
		return (a << 24) | (argb & 0x00FFFFFF);
	}

	private static int sizeToShift(int size) {
		int shift = 0;
		int s = size;
		while (s > 1) {
			shift++;
			s >>= 1;
		}
		return shift;
	}
}
