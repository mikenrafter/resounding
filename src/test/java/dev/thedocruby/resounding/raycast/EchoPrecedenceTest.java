package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.config.PrecomputedConfig;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4 RED tests for {@link EchoPrecedence}: a pure opportunistic check over a beam-traced
 * {@link Hit}'s existing {@code length}/{@code position} fields (frustums-plan.md "Phase 4 —
 * Higher-order echo (3+)"). No new geometric search — {@link Hit} itself is never modified, only
 * consumed.
 */
class EchoPrecedenceTest {

    private static final double DELTA = 1e-9;

    private static Hit hitWithLength(double length, Vec3d position) {
        return new Hit(position, length, 0.0, 0.0, length, 0.0, 1.0);
    }

    // --- resolved constant: 0.050 * PrecomputedConfig.speedOfSound ---------------------------------

    @Test
    void thresholdConstantMatchesTheResolvedFormulaExactly() {
        assertEquals(0.050 * PrecomputedConfig.speedOfSound, EchoPrecedence.PRECEDENCE_THRESHOLD_METERS, DELTA);
    }

    @Test
    void thresholdConstantIsApproximatelySeventeenMeters() {
        // frustums-plan.md headlines "Resolved: 17.0m", but 0.050 * 343.3 = 17.165 exactly - the
        // formula is the source of truth per the task brief, so this is a loose sanity bound
        // rather than an exact-17.0 assertion.
        assertTrue(EchoPrecedence.PRECEDENCE_THRESHOLD_METERS > 13.7 && EchoPrecedence.PRECEDENCE_THRESHOLD_METERS < 17.2,
                "must fall within the plan's cited 13.7-17.2m Haas/precedence-effect literature band");
    }

    // --- clearsPrecedenceThreshold: hit.length() vs the resolved threshold -------------------------

    @Test
    void hitBelowThresholdDoesNotClear() {
        Hit hit = hitWithLength(EchoPrecedence.PRECEDENCE_THRESHOLD_METERS - 1.0, new Vec3d(0, 0, 0));
        assertFalse(EchoPrecedence.clearsPrecedenceThreshold(hit));
    }

    @Test
    void hitAtOrAboveThresholdClears() {
        Hit atThreshold = hitWithLength(EchoPrecedence.PRECEDENCE_THRESHOLD_METERS, new Vec3d(0, 0, 0));
        Hit aboveThreshold = hitWithLength(EchoPrecedence.PRECEDENCE_THRESHOLD_METERS + 5.0, new Vec3d(0, 0, 0));
        assertTrue(EchoPrecedence.clearsPrecedenceThreshold(atThreshold));
        assertTrue(EchoPrecedence.clearsPrecedenceThreshold(aboveThreshold));
    }

    // --- excess over direct source→listener distance ---------------------------------------------

    @Test
    void pathLengthEqualToDirectDistanceDoesNotClearPrecedence() {
        double direct = 40.0;
        Hit alongDirect = hitWithLength(direct, new Vec3d(0, 0, 0));
        assertFalse(EchoPrecedence.clearsPrecedenceThreshold(alongDirect, direct),
                "path length == directDistance is zero excess; must not clear precedence");
    }

    @Test
    void pathLengthEqualToDirectPlusThresholdClearsPrecedence() {
        double direct = 40.0;
        Hit atExcess = hitWithLength(direct + EchoPrecedence.PRECEDENCE_THRESHOLD_METERS, new Vec3d(0, 0, 0));
        assertTrue(EchoPrecedence.clearsPrecedenceThreshold(atExcess, direct),
                "path length == direct + threshold is exactly the resolved excess clearance");
    }

    @Test
    void isHigherOrderEchoUsesExcessOverDirectDistance() {
        Vec3d listener = new Vec3d(0, 0, 0);
        double direct = 40.0;
        Hit zeroExcessNear = hitWithLength(direct, new Vec3d(0.2, 0, 0));
        Hit excessNear = hitWithLength(direct + EchoPrecedence.PRECEDENCE_THRESHOLD_METERS, new Vec3d(0.2, 0, 0));

        assertFalse(EchoPrecedence.isHigherOrderEcho(zeroExcessNear, listener, 1.0, direct),
                "zero excess must not count as higher-order even when near the listener");
        assertTrue(EchoPrecedence.isHigherOrderEcho(excessNear, listener, 1.0, direct));
    }

    // --- terminatesNearListener: hit.position() vs listener within a tolerance radius ---------------

    @Test
    void hitTerminatingAtTheListenerIsNear() {
        Vec3d listener = new Vec3d(10, 5, 10);
        Hit hit = hitWithLength(20.0, listener);
        assertTrue(EchoPrecedence.terminatesNearListener(hit, listener, 1.0));
    }

    @Test
    void hitTerminatingFarFromTheListenerIsNotNear() {
        Vec3d listener = new Vec3d(10, 5, 10);
        Hit hit = hitWithLength(20.0, new Vec3d(0, 5, 0));
        assertFalse(EchoPrecedence.terminatesNearListener(hit, listener, 1.0));
    }

    @Test
    void toleranceRadiusIsRespectedAtItsBoundary() {
        Vec3d listener = new Vec3d(0, 0, 0);
        Hit justInside = hitWithLength(20.0, new Vec3d(0.5, 0, 0));
        Hit justOutside = hitWithLength(20.0, new Vec3d(2.0, 0, 0));
        assertTrue(EchoPrecedence.terminatesNearListener(justInside, listener, 1.0));
        assertFalse(EchoPrecedence.terminatesNearListener(justOutside, listener, 1.0));
    }

    // --- isHigherOrderEcho: both halves must hold ---------------------------------------------------

    @Test
    void isHigherOrderEchoRequiresBothThresholdAndProximity() {
        Vec3d listener = new Vec3d(0, 0, 0);
        Hit farAndClose = hitWithLength(EchoPrecedence.PRECEDENCE_THRESHOLD_METERS + 5.0, new Vec3d(0.2, 0, 0));
        Hit farAndNotClose = hitWithLength(EchoPrecedence.PRECEDENCE_THRESHOLD_METERS + 5.0, new Vec3d(50, 0, 0));
        Hit shortAndClose = hitWithLength(1.0, new Vec3d(0.2, 0, 0));

        assertTrue(EchoPrecedence.isHigherOrderEcho(farAndClose, listener, 1.0),
                "clears the threshold and terminates near the listener -> counts as a higher-order echo");
        assertFalse(EchoPrecedence.isHigherOrderEcho(farAndNotClose, listener, 1.0),
                "clears the threshold but terminates far away -> not this beam's echo");
        assertFalse(EchoPrecedence.isHigherOrderEcho(shortAndClose, listener, 1.0),
                "close to the listener but too short a path -> would fuse into the direct sound, not a distinguishable echo");
    }
}
