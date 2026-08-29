package dev.thedocruby.resounding.debug.math;

/**
 * Fixed depth-to-color mapping for octree octant wireframes (sizes 16/8/4/2/1).
 */
public final class DepthColor {

    private DepthColor() {}

    public static int colorForSize(int size) {
        return switch (size) {
            case 16 -> 0xFFFF0000;
            case 8 -> 0xFF00FF00;
            case 4 -> 0xFF0000FF;
            case 2 -> 0xFFFFFF00;
            case 1 -> 0xFFFF00FF;
            default -> throw new IllegalArgumentException("unexpected octree node size: " + size);
        };
    }
}
