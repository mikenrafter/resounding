package dev.thedocruby.resounding.debug.math;

import net.minecraft.util.math.BlockPos;

/**
 * Spatial octant coloring: face-adjacent nodes at the same scale always differ.
 * Eight palette entries map to the child-index parity at the node's resolution.
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
