package dev.thedocruby.resounding.raycast;

import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Phase 1 beam representation (frustums-plan.md "Phase 1 — Beams instead of rays", task 1):
 * replaces {@code Cast}'s infinitely-thin ray with a cone/frustum that widens with travel
 * distance. Carries the same origin/direction shape as {@link Ray}/{@link Step}, plus the
 * footprint-at-distance growth parameters and this beam's remaining
 * per-footprint-size-tier split budget ({@link BeamBudget}).
 *
 * <p>{@code budget} may be {@code null} in contexts that only exercise footprint growth and never
 * touch subdivision state (e.g. pure footprint-math unit tests).
 */
public record Beam(
        @NotNull Vec3d origin,
        @NotNull Vec3d direction,
        double baseFootprintRadius,
        double growthRate,
        @Nullable BeamBudget budget
) {

    /**
     * Footprint radius after traveling {@code distance} blocks from {@link #origin} along
     * {@link #direction}. The plan does not resolve an exact growth curve (cone half-angle vs.
     * linear widening vs. something else) — only that the footprint "widens with travel distance"
     * (frustums-plan.md Phase 1 intro). Not yet implemented — always throws.
     */
    public double footprintRadiusAt(double distance) {
        return baseFootprintRadius + growthRate * distance;
    }

    /**
     * Returns a copy of this beam with {@link #budget} replaced — used when a split consumes one
     * footprint-size tier's budget slot (see {@link BeamBudget#consumeAt(double)}) and produces
     * child beams that must carry the depleted budget forward. Not yet implemented — always throws.
     */
    public @NotNull Beam withBudget(@NotNull BeamBudget newBudget) {
        return new Beam(origin, direction, baseFootprintRadius, growthRate, newBudget);
    }
}
