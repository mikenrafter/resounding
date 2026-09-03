package dev.thedocruby.resounding;

import dev.thedocruby.resounding.material.Acoustics;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

public class Physics {

    @Contract("_, _ -> new")
    public static @NotNull Vec3d pseudoReflect(Vec3d ray, @NotNull Vec3i plane) { return pseudoReflect(ray,plane,2); }

    @Contract("_, _, _ -> new")
    public static @NotNull Vec3d pseudoReflect(Vec3d ray, @NotNull Vec3i plane, double fresnel) {
        // Fresnels on a 1-30 scale
        // TODO account for http://hyperphysics.phy-astr.gsu.edu/hbase/Tables/indrf.html

        // `plane` is always exactly one of the 6 axis-unit directions (never a general/oblique
        // normal), so the full ray - 2*dot(ray,n)*n mirror formula collapses to a per-axis check:
        // scale the component on the crossed axis by (1 - fresnel), leave the other two alone.
        // fresnel=2 is a full mirror reflection; smaller values blend in the refraction approximation.
        return new Vec3d(
                plane.getX() != 0 ? ray.x * (1 - fresnel) : ray.x,
                plane.getY() != 0 ? ray.y * (1 - fresnel) : ray.y,
                plane.getZ() != 0 ? ray.z * (1 - fresnel) : ray.z
        );
    }

    /** @see Acoustics#reflection - delegated so the formula has exactly one definition */
    public static @NotNull Double reflection(@NotNull Double impedanceA, @NotNull Double impedanceB) {
        return Acoustics.reflection(impedanceA, impedanceB);
    }

    // --- Phase 0.5: polarized reflection material selection (frustums-plan.md "Blend mechanism",
    // "Permeation bending (sign-copy)", "Notable-interaction gate") -----------------------------

    /**
     * Alignment {@code a = (ray_norm · pol_norm)^2} &isin; [0,1] &mdash; squared, so sign/side of
     * the polarization axis doesn't matter. Not yet implemented &mdash; always throws.
     */
    public static double polarAlignment(@NotNull Vec3d rayNorm, @NotNull Vec3d polNorm) {
        double dot = rayNorm.dotProduct(polNorm);
        return dot * dot;
    }

    /**
     * Blend weight {@code w = clamp(a * s, 0, 1)}. {@code w = 1} &rarr; fully committed to the
     * primary (high-impedance) material; {@code w = 0} &rarr; fully committed to the secondary.
     * Not yet implemented &mdash; always throws.
     */
    public static double polarBlendWeight(double alignment, double s) {
        return Math.max(0.0, Math.min(1.0, alignment * s));
    }

    /**
     * Effective impedance {@code = primary.impedance * w + secondary.impedance * (1 - w)}. Not
     * yet implemented &mdash; always throws.
     */
    public static double blendImpedance(double primaryImpedance, double secondaryImpedance, double w) {
        return primaryImpedance * w + secondaryImpedance * (1 - w);
    }

    /**
     * Permeation sign-copy bending (frustums-plan.md "Which vector to copy against — resolved,
     * replaces the earlier hard cutoff with a smooth lerp"): given the raw incident vector
     * {@code ray} (its magnitude is preserved verbatim, only signs may change), its normalized
     * direction {@code rayNorm}, and the octant's {@code polNorm}, computes
     * {@code dot = polNorm·rayNorm}, {@code similarity = |dot|*dot}, {@code tangent = rayNorm -
     * dot*polNorm}, {@code t = clamp(similarity, 0, 1)}, {@code target = lerp(tangent, polNorm,
     * t)}, then copies {@code target}'s per-component sign onto {@code ray}'s per-component
     * magnitude. {@code |result| == |ray|} always (only sign bits change). Not yet implemented
     * &mdash; always throws.
     */
    public static @NotNull Vec3d permeationBend(@NotNull Vec3d ray, @NotNull Vec3d rayNorm, @NotNull Vec3d polNorm) {
        double dot = polNorm.dotProduct(rayNorm);
        double similarity = Math.abs(dot) * dot;
        Vec3d tangent = rayNorm.subtract(polNorm.multiply(dot));
        double t = Math.max(0.0, Math.min(1.0, similarity));
        Vec3d target = tangent.multiply(1 - t).add(polNorm.multiply(t));
        return new Vec3d(
                Math.copySign(ray.x, target.x),
                Math.copySign(ray.y, target.y),
                Math.copySign(ray.z, target.z)
        );
    }

    /**
     * Contrast-magnitude threshold a face-hit's polarization contrast must clear to be "notable"
     * (frustums-plan.md "Notable-interaction gate decides whether"). Dynamic, tied to the beam's
     * remaining split budget (generic here so a later beam-tracing subagent can feed it Phase 1's
     * per-footprint-size-tier budget): more {@code splitsRemaining} left must lower the threshold
     * (splits more readily); the threshold rises as budget depletes. Not yet implemented &mdash;
     * always throws.
     */
    public static double notableThreshold(int splitsRemaining) {
        // Monotonically non-increasing in splitsRemaining (more budget -> lower/equal threshold,
        // splits more readily), strictly decreasing across the whole 0..4 budget range used by
        // Phase 1's per-size-tier split cap.
        return 1.0 / (1 + Math.max(0, splitsRemaining));
    }

    /**
     * Binary decide-whether gate: is this face-hit's {@code contrastMagnitude} notable given
     * {@code splitsRemaining} split budget left? Not yet implemented &mdash; always throws.
     */
    public static boolean isNotableInteraction(double contrastMagnitude, int splitsRemaining) {
        // Exhausted split budget: commit only — never authorize another split, even at full contrast.
        if (splitsRemaining <= 0) {
            return false;
        }
        return contrastMagnitude >= notableThreshold(splitsRemaining);
    }

    /**
     * Flat commit cutoff used once a beam's split budget is fully exhausted (frustums-plan.md:
     * "the beam must commit to exactly one of reflect/permeate, decided by a flat {@code w >= 0.5}
     * &rarr; reflect / {@code w < 0.5} &rarr; permeate cutoff on the blend weight above"). Not yet
     * implemented &mdash; always throws.
     */
    public static boolean commitReflect(double w) {
        return w >= 0.5;
    }
}
