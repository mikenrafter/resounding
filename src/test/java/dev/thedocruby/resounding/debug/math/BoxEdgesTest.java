package dev.thedocruby.resounding.debug.math;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BoxEdgesTest {

    private static final double EPS = 1e-9;

    @Test
    void enumeratesExactlyTwelveAxisAlignedEdges() {
        List<Segment3> edges = new ArrayList<>();
        BoxEdges.forEach(0, 0, 0, 2, 3, 4, edges::add);

        assertEquals(12, edges.size());

        for (Segment3 edge : edges) {
            int differing = 0;
            if (Math.abs(edge.ax() - edge.bx()) > EPS) differing++;
            if (Math.abs(edge.ay() - edge.by()) > EPS) differing++;
            if (Math.abs(edge.az() - edge.bz()) > EPS) differing++;
            assertEquals(1, differing, "each edge must vary along exactly one axis: " + edge);
        }
    }

    @Test
    void totalEdgeLengthMatchesFourTimesTheSumOfDimensions() {
        List<Segment3> edges = new ArrayList<>();
        BoxEdges.forEach(0, 0, 0, 2, 3, 4, edges::add);

        double total = edges.stream()
                .mapToDouble(BoxEdgesTest::length)
                .sum();

        assertEquals(4 * (2 + 3 + 4), total, EPS);
    }

    @Test
    void everyEndpointCoordinateIsOneOfTheBoxBoundsOnEachAxis() {
        double minX = 10, minY = 20, minZ = 30, maxX = 12, maxY = 24, maxZ = 33;
        List<Segment3> edges = new ArrayList<>();
        BoxEdges.forEach(minX, minY, minZ, maxX, maxY, maxZ, edges::add);

        for (Segment3 edge : edges) {
            assertTrue(isBound(edge.ax(), minX, maxX));
            assertTrue(isBound(edge.ay(), minY, maxY));
            assertTrue(isBound(edge.az(), minZ, maxZ));
            assertTrue(isBound(edge.bx(), minX, maxX));
            assertTrue(isBound(edge.by(), minY, maxY));
            assertTrue(isBound(edge.bz(), minZ, maxZ));
        }
    }

    private static boolean isBound(double value, double min, double max) {
        return Math.abs(value - min) < EPS || Math.abs(value - max) < EPS;
    }

    private static double length(Segment3 edge) {
        double dx = edge.bx() - edge.ax();
        double dy = edge.by() - edge.ay();
        double dz = edge.bz() - edge.az();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
