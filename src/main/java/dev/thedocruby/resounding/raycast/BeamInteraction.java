package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.Physics;

/**
 * Phase 1 face-hit interaction wiring (frustums-plan.md Phase 1 task 5): at a face-hit, a beam
 * must apply Phase 0.5's already-implemented notable-interaction gate
 * ({@code Physics.isNotableInteraction}) to decide <em>whether</em> to react at all, and — once its
 * split budget is exhausted — the flat commit cutoff ({@code Physics.commitReflect}) to decide
 * reflect-vs-permeate. This class is the thin Phase 1 call site for those already-green Phase 0.5
 * primitives; it does not re-derive their formulas.
 *
 * <p>Deliberately takes a raw {@code splitsRemaining} count rather than a {@link BeamBudget}
 * directly, so callers/tests can exercise the gate-routing decision without needing a constructed
 * {@code BeamBudget} instance. In real use the caller passes {@code beam.budget().splitsRemaining()}.
 */
public final class BeamInteraction {
    private BeamInteraction() {}

    /**
     * Whether a face-hit with the given polarization contrast magnitude is notable enough to split
     * the beam, given {@code splitsRemaining} tiers of subdivision budget left — delegates to
     * {@code dev.thedocruby.resounding.Physics#isNotableInteraction}. Not yet implemented — always
     * throws.
     */
    public static boolean shouldSplit(double contrastMagnitude, int splitsRemaining) {
        return Physics.isNotableInteraction(contrastMagnitude, splitsRemaining);
    }

    /**
     * Once a beam's split budget is fully exhausted (frustums-plan.md: "the beam must commit to
     * exactly one of reflect/permeate"), whether to commit to reflect (vs. permeate) given blend
     * weight {@code w} — delegates to {@code dev.thedocruby.resounding.Physics#commitReflect}. Not
     * yet implemented — always throws.
     */
    public static boolean shouldCommitReflect(double w) {
        return Physics.commitReflect(w);
    }
}
