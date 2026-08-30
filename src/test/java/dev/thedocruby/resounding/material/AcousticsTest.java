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

    @Test
    void phaseStateClampsBelowMeltAndAboveBoil() {
        assertEquals(1.0, Acoustics.phaseState(50.0, 0.0, 100.0), 1e-12); // 50 K, well below 0°C melt → solid
        assertEquals(0.0, Acoustics.phaseState(500.0, 0.0, 100.0), 1e-12); // 500 K, above 100°C boil → gas
    }

    @Test
    void phaseStateInterpolatesBetweenMeltAndBoil() {
        // 287.15 K = 14°C, between 0°C melt and 100°C boil → melt-boil progress 0.14, state 0.86
        assertEquals(0.86, Acoustics.phaseState(287.15, 0.0, 100.0), 1e-12);
    }

    @Test
    void permeationOverDistanceScalesByDistanceOnly() {
        assertEquals(0.9, Acoustics.permeationOverDistance(0.9, 1.0), 1e-9);
        assertEquals(0.81, Acoustics.permeationOverDistance(0.9, 2.0), 1e-9);
        assertEquals(Math.pow(0.9, 4.2), Acoustics.permeationOverDistance(0.9, 4.2), 1e-6);
    }

    @Test
    void clampLimitsToRange() {
        assertEquals(0.0, Acoustics.clamp(-1.0, 0.0, 1.0), 1e-12);
        assertEquals(1.0, Acoustics.clamp(2.0, 0.0, 1.0), 1e-12);
        assertEquals(0.5, Acoustics.clamp(0.5, 0.0, 1.0), 1e-12);
    }
}
