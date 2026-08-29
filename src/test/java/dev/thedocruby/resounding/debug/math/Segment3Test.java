package dev.thedocruby.resounding.debug.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Closest-distance geometry for debug picking, with no Minecraft on the classpath.
 */
class Segment3Test {

    private static final double EPS = 1e-9;

    @Test
    void parallelSegmentsReportSeparationDistance() {
        Segment3 lower = new Segment3(0, 0, 0, 1, 0, 0);
        Segment3 upper = new Segment3(0, 2, 0, 1, 2, 0);

        assertEquals(2.0, lower.closestDistanceTo(upper), EPS);
        assertEquals(2.0, upper.closestDistanceTo(lower), EPS);
    }

    @Test
    void skewSegmentsReportShortestDistanceBetweenInfiniteLinesClampedToSegments() {
        Segment3 alongX = new Segment3(0, 0, 0, 1, 0, 0);
        Segment3 alongZ = new Segment3(0, 1, 1, 0, 1, 2);

        assertEquals(Math.sqrt(2.0), alongX.closestDistanceTo(alongZ), EPS);
    }

    @Test
    void overlappingCollinearSegmentsAreCoincident() {
        Segment3 first = new Segment3(0, 0, 0, 2, 0, 0);
        Segment3 second = new Segment3(1, 0, 0, 3, 0, 0);

        assertEquals(0.0, first.closestDistanceTo(second), EPS);
        assertEquals(0.0, second.closestDistanceTo(first), EPS);
    }

    @Test
    void degeneratePointSegmentUsesPointToSegmentDistance() {
        Segment3 point = new Segment3(0.5, 1.0, 0.0, 0.5, 1.0, 0.0);
        Segment3 segment = new Segment3(0, 0, 0, 1, 0, 0);

        assertEquals(1.0, point.closestDistanceTo(segment), EPS);
        assertEquals(1.0, segment.closestDistanceTo(point), EPS);
        assertEquals(1.0, point.closestDistanceToPoint(0.5, 0.0, 0.0), EPS);
    }
}
