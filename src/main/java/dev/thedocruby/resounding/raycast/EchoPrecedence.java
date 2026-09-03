package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.config.PrecomputedConfig;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;

/**
 * Phase 4 higher-order echo reuse (frustums-plan.md "Phase 4 — Higher-order echo (3+): reuse the
 * beam tracer's own bounce chains"): a pure opportunistic check tacked onto a beam-traced
 * {@link Hit}'s already-populated {@code length} (cumulative path distance) and {@code position}
 * (terminal point) fields. No new geometric search — consumes {@link Hit} exactly as-is, adds or
 * modifies none of its fields.
 */
public final class EchoPrecedence {
    private EchoPrecedence() {}

    /**
     * One-way excess path length a {@link Hit#length()} must clear to register as a distinguishable
     * higher-order echo rather than perceptually fusing into the direct sound (frustums-plan.md
     * Phase 4 "Resolved: 17.0m one-way excess path length",
     * {@code = 0.050 * PrecomputedConfig.speedOfSound}). Computed directly from the plan's own
     * formula rather than the hardcoded "17.0" headline number, since
     * {@code 0.050 * 343.3 = 17.165}, not exactly {@code 17.0} — the plan's prose rounds where its
     * own formula does not; flagged for review.
     */
    public static final double PRECEDENCE_THRESHOLD_METERS = 0.050 * PrecomputedConfig.speedOfSound;

    /**
     * Whether {@code hit}'s accumulated {@link Hit#length()} clears {@link #PRECEDENCE_THRESHOLD_METERS}.
     * Not yet implemented — always throws.
     */
    public static boolean clearsPrecedenceThreshold(@NotNull Hit hit) {
        return hit.length() >= PRECEDENCE_THRESHOLD_METERS;
    }

    /**
     * Whether {@code hit}'s terminal {@link Hit#position()} lies within {@code toleranceRadius} of
     * {@code listener} — the "is this beam's terminal point close to the listener" half of the
     * Phase 4 opportunistic check. Not yet implemented — always throws.
     */
    public static boolean terminatesNearListener(@NotNull Hit hit, @NotNull Vec3d listener, double toleranceRadius) {
        return hit.position().distanceTo(listener) <= toleranceRadius;
    }

    /**
     * Combined opportunistic higher-order-echo check (frustums-plan.md Phase 4): {@code hit} both
     * clears the precedence-effect threshold and terminates near {@code listener}. No new
     * geometric search — both halves consume {@code hit}'s existing fields only. Not yet
     * implemented — always throws.
     */
    public static boolean isHigherOrderEcho(@NotNull Hit hit, @NotNull Vec3d listener, double toleranceRadius) {
        return clearsPrecedenceThreshold(hit) && terminatesNearListener(hit, listener, toleranceRadius);
    }
}
