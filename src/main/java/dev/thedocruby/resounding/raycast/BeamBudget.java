package dev.thedocruby.resounding.raycast;

import org.jetbrains.annotations.NotNull;

/**
 * Phase 1 tiered beam-subdivision budget (frustums-plan.md Phase 1 task 3, "Resolved": 4 total
 * splits per beam, budgeted one split per footprint-size tier — one allowed at frustum size 1,
 * another at size 2, a third at size 4, a fourth at size 8; beyond size 8, or once all four tiers
 * are spent, no further splitting).
 *
 * <p>Design decision (not dictated verbatim by the plan): the four size tiers are treated as the
 * half-open ranges {@code [1,2)}, {@code [2,4)}, {@code [4,8)}, {@code [8,16)} — i.e. each tier
 * covers footprint sizes up to (but not including) the next doubling. A footprint below size 1, or
 * at/above size 16, maps to no tier at all ({@link #tierIndexForFootprint} returns {@code -1}),
 * consistent with the plan's "beyond size 8 ... no further splitting". This is one reasonable
 * reading of an otherwise discrete-sounding "at frustum size 1/2/4/8" description; confirmed
 * against {@code BeamBudgetTest} — the half-open interpretation above is what the test suite
 * exercises.
 */
public final class BeamBudget {

    /** Number of footprint-size tiers (sizes 1, 2, 4, 8) — see class doc for the resolved cap. */
    public static final int TIER_COUNT = 4;

    /** Bit {@code i} set means tier {@code i}'s single split has been consumed. */
    private final int consumedMask;

    private BeamBudget(int consumedMask) {
        this.consumedMask = consumedMask;
    }

    /**
     * Fresh budget with all {@value #TIER_COUNT} tiers available (frustums-plan.md Phase 1
     * "Resolved: 4 total splits per beam").
     */
    public static @NotNull BeamBudget full() {
        return new BeamBudget(0);
    }

    /**
     * Budget with all {@value #TIER_COUNT} tiers already consumed — zero splits remaining.
     * Symmetric with {@link #full()}; used where callers need to force every notable-interaction
     * gate straight to its no-splits-left ({@code splitsRemaining() == 0}) behavior.
     */
    public static @NotNull BeamBudget empty() {
        return new BeamBudget((1 << TIER_COUNT) - 1);
    }

    /**
     * Maps a beam footprint size to its tier index (0..3 for the size-1/2/4/8 tiers), or {@code -1}
     * when the footprint falls outside every tier (below size 1, or at/beyond size 16 — see class
     * doc).
     */
    public static int tierIndexForFootprint(double footprintSize) {
        if (footprintSize < 1.0) return -1;
        if (footprintSize < 2.0) return 0;
        if (footprintSize < 4.0) return 1;
        if (footprintSize < 8.0) return 2;
        if (footprintSize < 16.0) return 3;
        return -1;
    }

    /**
     * Whether a split is still available at the tier matching {@code footprintSize}. Always
     * {@code false} when {@link #tierIndexForFootprint} would return {@code -1}.
     */
    public boolean canSplitAt(double footprintSize) {
        int tier = tierIndexForFootprint(footprintSize);
        if (tier == -1) return false;
        return (consumedMask & (1 << tier)) == 0;
    }

    /**
     * Returns a new budget with the tier matching {@code footprintSize} consumed (its single split
     * spent). A no-op-equivalent (returns an equal budget) when that tier was already spent or when
     * {@code footprintSize} maps to no tier.
     */
    public @NotNull BeamBudget consumeAt(double footprintSize) {
        int tier = tierIndexForFootprint(footprintSize);
        if (tier == -1) return this;
        return new BeamBudget(consumedMask | (1 << tier));
    }

    /**
     * Count of tiers not yet consumed, {@code 0..}{@value #TIER_COUNT} — this is what feeds
     * {@code Physics.notableThreshold}/{@code Physics.isNotableInteraction}'s {@code
     * splitsRemaining} parameter (frustums-plan.md Phase 0.5 "Notable-interaction gate ... tied to
     * the beam's remaining split budget").
     */
    public int splitsRemaining() {
        return TIER_COUNT - Integer.bitCount(consumedMask);
    }
}
