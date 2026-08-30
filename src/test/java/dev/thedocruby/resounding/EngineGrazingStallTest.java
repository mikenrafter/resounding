package dev.thedocruby.resounding;

import dev.thedocruby.resounding.raycast.Ray;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineGrazingStallTest {

	// A ray grazing a flat surface keeps re-detecting the same boundary a hair away on every cell
	// crossing, producing many consecutive near-zero permeate steps but no genuine travel or bounce.
	@Test
	void isGrazingStallRequiresManyConsecutiveNearZeroSteps() {
		assertFalse(Engine.isGrazingStall(0));
		assertFalse(Engine.isGrazingStall(7));
		assertTrue(Engine.isGrazingStall(8));
	}

	@Test
	void escapeGrazingStallStepsForwardAlongTheRaysOwnDirection() {
		Ray ray = new Ray(64.0, new Vec3d(1, 2, 3), new Vec3d(1, 0, 0), 0.0001);
		Ray escaped = Engine.escapeGrazingStall(ray);

		assertEquals(64.0, escaped.power(), 1e-9, "escaping a stall must not change ray power");
		assertEquals(new Vec3d(1.05, 2, 3), escaped.position(), "escape must move along the ray's own direction, not perpendicular to a surface");
	}

	@Test
	void escapeGrazingStallWorksForNonAxisAlignedDirections() {
		Vec3d direction = new Vec3d(1, 1, 0).normalize();
		Ray ray = new Ray(64.0, Vec3d.ZERO, direction, 0.0001);
		Ray escaped = Engine.escapeGrazingStall(ray);

		assertEquals(0.05, escaped.position().distanceTo(Vec3d.ZERO), 1e-9,
				"escape distance must be angle-independent, unlike a fixed nudge along a surface normal");
	}
}
