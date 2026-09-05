package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.material.Material;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract for the 5 trailing kapture fields added to {@link CaptureBuffer.CapturedRay}:
 * resolvedImpedance, polarAlignment, frustumSize, blankReason, shapeMode.
 *
 * <p>blankReason contract: {@code null} means NONE (the bounce was not blank); a non-null string
 * is a reason name (e.g. "VACUUM") that the Renderer maps to a human label when formatting. This
 * test only asserts the field round-trips — it does not call into Renderer.
 */
class CapturedRayKaptureFieldsTest {

	@Test
	void accessorsRoundTripNewFields() {
		CaptureBuffer.CapturedRay ray = new CaptureBuffer.CapturedRay(
				new Vec3d(0, 0, 0),
				new Vec3d(1, 0, 0),
				0xFFFFFFFF,
				42,
				5,
				0,
				new Material(1000.0, 0.5, 1.0),
				0.3,
				0.7,
				64.0,
				400.0,
				1,
				"STONE",
				false,
				415.0,
				0.87,
				2.5,
				"VACUUM",
				true
		);

		assertEquals(415.0, ray.resolvedImpedance(), 1e-9);
		assertEquals(0.87, ray.polarAlignment(), 1e-9);
		assertEquals(2.5, ray.frustumSize(), 1e-9);
		assertEquals("VACUUM", ray.blankReason());
		assertTrue(ray.shapeMode());
	}

	@Test
	void blankReasonNullMeansNoneAndDoesNotForceShapeMode() {
		CaptureBuffer.CapturedRay ray = new CaptureBuffer.CapturedRay(
				new Vec3d(0, 0, 0),
				new Vec3d(1, 0, 0),
				0xFFFFFFFF,
				42,
				5,
				0,
				null,
				0.0,
				0.0,
				0.0,
				0.0,
				1,
				null,
				false,
				0.0,
				0.0,
				0.0,
				null,
				false
		);

		assertNull(ray.blankReason());
		assertFalse(ray.shapeMode());
	}
}
