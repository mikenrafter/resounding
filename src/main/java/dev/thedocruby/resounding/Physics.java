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
     * Blended "effective ray norm": {@code normalize(restrict(rayNorm) + normalize(octantCenter -
     * incidentPoint))}. The incident-angle term ({@code rayNorm}) is first restricted to whichever
     * single axis {@code polNorm} is dominant on (the same axis {@link
     * dev.thedocruby.resounding.raycast.FrustumLod#wouldDoubleCover} isolates for its own polarity
     * check) — zeroing the other two so off-axis ray motion can't dilute the alignment. The
     * position term (the offset from {@code incidentPoint} to {@code octantCenter}) is deliberately
     * left unrestricted. Falls back to {@code normalize(rayNorm)} when the offset is degenerate and
     * the restricted ray is also zero, or when the final sum is degenerate.
     */
    public static Vec3d dualDerivedNorm(
            @NotNull Vec3d rayNorm, @NotNull Vec3d incidentPoint, @NotNull Vec3d octantCenter, @NotNull Vec3d polNorm
    ) {
        Vec3d rn = rayNorm.normalize();
        Vec3d restrictedRn = restrictToDominantAxis(rn, polNorm);
        Vec3d offset = octantCenter.subtract(incidentPoint);
        if (offset.lengthSquared() < 1e-12) {
            return restrictedRn.lengthSquared() < 1e-12 ? rn : restrictedRn.normalize();
        }
        Vec3d offsetNorm = offset.normalize();
        Vec3d sum = restrictedRn.add(offsetNorm);
        if (sum.lengthSquared() < 1e-12) {
            return rn;
        }
        return sum.normalize();
    }

    /**
     * Zeroes every component of {@code v} except the one on {@code axisSource}'s dominant axis
     * (largest absolute component; ties favor X then Y).
     */
    private static Vec3d restrictToDominantAxis(@NotNull Vec3d v, @NotNull Vec3d axisSource) {
        return switch (dominantAxis(axisSource)) {
            case 0 -> new Vec3d(v.x, 0, 0);
            case 1 -> new Vec3d(0, v.y, 0);
            default -> new Vec3d(0, 0, v.z);
        };
    }

    /** Index (0=X, 1=Y, 2=Z) of {@code v}'s largest-magnitude component; ties favor X then Y. */
    private static int dominantAxis(@NotNull Vec3d v) {
        int axis = 0;
        double best = Math.abs(v.x);
        if (Math.abs(v.y) > best) {
            axis = 1;
            best = Math.abs(v.y);
        }
        if (Math.abs(v.z) > best) {
            axis = 2;
        }
        return axis;
    }

    /**
     * {@code polarAlignment(dualDerivedNorm(...), polNorm)} &mdash; position-aware alignment.
     */
    public static double dualDerivedAlignment(@NotNull Vec3d rayNorm, @NotNull Vec3d incidentPoint, @NotNull Vec3d octantCenter, @NotNull Vec3d polNorm) {
        return polarAlignment(dualDerivedNorm(rayNorm, incidentPoint, octantCenter, polNorm), polNorm);
    }

    /**
     * Orients {@code polNorm} to always face the ray it's interacting with: each axis's sign is
     * copied from {@code rayNorm} onto {@code polNorm}'s magnitude on that axis (same per-component
     * sign-copy idiom as {@link #permeationBend}), guaranteeing {@code dot(result, rayNorm) >= 0}.
     *
     * <p>{@code polar} is baked ({@code Polarization#bakeOctant}) as "points toward the
     * higher-impedance corner cluster" — a fixed property of the octant's own corner layout,
     * independent of which side of the gradient any particular ray approaches from. Feeding that
     * raw direction straight into {@link #grazeBend}/{@link #permeationBend} (which both assume
     * {@code polNorm} already faces the incoming ray) is only correct for approaches where the
     * baked sign happens to agree — roughly half of them; the other half see it pointing away and
     * get bent/permeated backwards. The result of this method (call it {@code
     * PolarityIncidentNormal}) is what should be passed to those two functions instead of the raw
     * baked direction.
     */
    public static @NotNull Vec3d polarityIncidentNormal(@NotNull Vec3d polNorm, @NotNull Vec3d rayNorm) {
        return new Vec3d(
                Math.copySign(polNorm.x, rayNorm.x),
                Math.copySign(polNorm.y, rayNorm.y),
                Math.copySign(polNorm.z, rayNorm.z)
        );
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
     * Free graze-refraction bend toward the open half when frustum growth would double-cover past
     * the polarity plane. Partial flip damped by plain {@link #polarAlignment} (not dual-derived).
     * Magnitude of {@code ray} is preserved.
     */
    public static @NotNull Vec3d grazeBend(@NotNull Vec3d ray, @NotNull Vec3d rayNorm, @NotNull Vec3d polNorm) {
        double n = polNorm.dotProduct(rayNorm);
        if (n <= 0) {
            return ray; // already heading away from solid
        }
        double align = polarAlignment(rayNorm, polNorm);
        Vec3d tangent = rayNorm.subtract(polNorm.multiply(n));
        Vec3d bent = tangent.subtract(polNorm.multiply(n * (1.0 - align)));
        return bent.normalize().multiply(ray.length());
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
