package dev.thedocruby.resounding.debug.math;

/**
 * Plain-double 3D line segment for closest-distance queries (no Minecraft types).
 */
public record Segment3(double ax, double ay, double az, double bx, double by, double bz) {

    public double closestDistanceTo(Segment3 other) {
        return Double.MAX_VALUE;
    }

    public double closestDistanceToPoint(double x, double y, double z) {
        return Double.MAX_VALUE;
    }
}
