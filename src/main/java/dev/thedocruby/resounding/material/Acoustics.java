package dev.thedocruby.resounding.material;

/**
 * The physics the material bake needs, with no Minecraft on the classpath.
 *
 * <p>Exists because {@code MathHelper.lerp} / {@code getLerpProgress} were the coupling that kept
 * the bake from being testable, and because {@code Physics.reflection} lives in a class that imports
 * {@code Vec3d}. {@code Physics} delegates here so there is exactly one definition of each formula.
 */
public final class Acoustics {

    private Acoustics() {}

    /**
     * Fraction of incident acoustic power reflected at a boundary between two impedances.
     *
     * <p>{@code ((a - b) / (a + b))^2}
     */
    public static double reflection(double impedanceA, double impedanceB) {
        double diff = impedanceA - impedanceB;
        double sum = impedanceA + impedanceB;
        double ratio = diff / sum;
        return ratio * ratio;
    }

    /** Position of {@code value} within {@code [start, end]}, unclamped; 0 when the range is empty. */
    public static double lerpProgress(double value, double start, double end) {
        double range = end - start;
        if (range == 0.0) {
            return 0.0;
        }
        return (value - start) / range;
    }

    /** Linear interpolation from {@code start} to {@code end}, unclamped. */
    public static double lerp(double delta, double start, double end) {
        return start + delta * (end - start);
    }
}
