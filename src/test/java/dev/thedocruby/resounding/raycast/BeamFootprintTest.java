package dev.thedocruby.resounding.raycast;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1 RED tests for {@link Beam}'s footprint-at-distance growth (frustums-plan.md Phase 1 task
 * 1: "a beam representation (origin, direction, solid angle or footprint radius at distance)").
 * The plan does not resolve an exact growth curve, so these assert only the qualitative shape any
 * reasonable implementation must satisfy: footprint at zero distance equals the beam's base
 * footprint, and footprint is monotonically non-decreasing with travel distance ("widens with
 * travel distance", Phase 1 intro).
 *
 * <p>{@code budget} is passed as {@code null} throughout — footprint growth is pure geometry over
 * {@code baseFootprintRadius}/{@code growthRate}/{@code distance} only, so these tests must fail at
 * {@link Beam#footprintRadiusAt} itself, not at unrelated {@link BeamBudget} construction.
 */
class BeamFootprintTest {

    private static Beam beam(double baseFootprint, double growthRate) {
        return new Beam(Vec3d.ZERO, new Vec3d(0, 0, 1), baseFootprint, growthRate, null);
    }

    @Test
    void footprintAtZeroDistanceEqualsBaseFootprint() {
        Beam beam = beam(0.5, 0.1);
        assertEquals(0.5, beam.footprintRadiusAt(0.0), 1e-9);
    }

    @Test
    void footprintGrowsMonotonicallyWithDistance() {
        Beam beam = beam(0.5, 0.1);
        double near = beam.footprintRadiusAt(2.0);
        double far = beam.footprintRadiusAt(20.0);
        assertTrue(far > near, "footprint must widen, not shrink, as the beam travels further");
    }

    @Test
    void footprintNeverShrinksBelowBaseFootprint() {
        Beam beam = beam(0.5, 0.1);
        assertTrue(beam.footprintRadiusAt(50.0) >= beam.footprintRadiusAt(0.0));
    }

    @Test
    void widerBaseFootprintStaysWiderAtTheSameDistance() {
        Beam narrow = beam(0.25, 0.1);
        Beam wide = beam(1.0, 0.1);
        assertTrue(wide.footprintRadiusAt(10.0) > narrow.footprintRadiusAt(10.0),
                "a beam that starts wider must stay wider at the same travel distance, all else equal");
    }
}
