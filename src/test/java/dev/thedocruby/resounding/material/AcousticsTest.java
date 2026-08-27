package dev.thedocruby.resounding.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The physics the material bake needs, with no Minecraft on the classpath (see class javadoc).
 */
class AcousticsTest {

    @Test
    void reflectionIsZeroForEqualImpedances() {
        assertEquals(0.0, Acoustics.reflection(500.0, 500.0), 1e-12);
    }

    @Test
    void reflectionApproachesOneForExtremeImpedanceMismatch() {
        double reflection = Acoustics.reflection(1.0, 1_000_000.0);
        assertTrue(reflection > 0.999, "an extreme impedance mismatch must reflect almost everything");
    }

    @Test
    void reflectionIsSymmetric() {
        assertEquals(Acoustics.reflection(300.0, 700.0), Acoustics.reflection(700.0, 300.0), 1e-12);
    }

    @Test
    void lerpAndLerpProgressRoundTrip() {
        double start = 10.0, end = 50.0, delta = 0.3;
        double value = Acoustics.lerp(delta, start, end);
        assertEquals(delta, Acoustics.lerpProgress(value, start, end), 1e-12);
    }

    @Test
    void lerpProgressReturnsZeroForAnEmptyRange() {
        assertEquals(0.0, Acoustics.lerpProgress(5.0, 10.0, 10.0), 1e-12);
    }
}
