package dev.thedocruby.resounding;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task E: {@link Physics#grazeBend} — partial polarity-plane flip (not a full mirror, not
 * sign-copy {@link Physics#permeationBend}) for free wall-glide refraction.
 */
class PhysicsGrazeBendTest {

    private static final double DELTA = 1e-9;

    @Test
    void upwardPolarPartialFlipPreservesMagnitudeAndLocksSlopeBand() {
        Vec3d ray = new Vec3d(1, 0.6, 0);
        Vec3d rayNorm = ray.normalize();
        Vec3d pol = new Vec3d(0, 1, 0);

        Vec3d result = Physics.grazeBend(ray, rayNorm, pol);

        assertTrue(result.y < 0, "bent ray must cross toward the open half (negative Y)");
        assertTrue(Math.abs(result.y) < 0.6, "partial flip, not a full |0.6| mirror");
        assertEquals(ray.length(), result.length(), DELTA, "magnitude preserved exactly");
        assertTrue(result.x > 0, "forward X sign unchanged");
        double slope = result.y / result.x;
        assertTrue(slope >= -0.5 && slope <= -0.25,
                "slope must land in [-0.5, -0.25] (got " + slope + ")");
    }

    @Test
    void alreadyHeadingAwayFromSolidReturnsUnchanged() {
        Vec3d ray = new Vec3d(1, -0.6, 0);
        Vec3d rayNorm = ray.normalize();
        Vec3d pol = new Vec3d(0, 1, 0);

        Vec3d result = Physics.grazeBend(ray, rayNorm, pol);

        assertSame(ray, result, "n <= 0 is a no-op; return the same instance");
    }
}
