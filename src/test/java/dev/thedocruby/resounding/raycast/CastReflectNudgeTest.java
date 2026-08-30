package dev.thedocruby.resounding.raycast;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CastReflectNudgeTest {

	@Test
	void nudgeAdvancesOriginAlongReflectedDirectionWhenDistanceIsZero() {
		Vec3d origin = new Vec3d(1.0, 0.5, 0.5);
		Vec3d reflected = new Vec3d(-1.0, 0.0, 0.0);

		Vec3d nudged = Cast.nudgeReflectOrigin(origin, reflected, 0.0);

		assertTrue(nudged.x < origin.x, "reflected ray must start slightly back into the prior medium");
		assertEquals(origin.y, nudged.y, 1e-9);
		assertEquals(origin.z, nudged.z, 1e-9);
	}

	@Test
	void nudgeSkipsWhenReflectDistanceAlreadyAdvances() {
		Vec3d origin = new Vec3d(1.0, 0.5, 0.5);
		Vec3d reflected = new Vec3d(-1.0, 0.0, 0.0);

		Vec3d nudged = Cast.nudgeReflectOrigin(origin, reflected, 0.5);

		assertEquals(origin, nudged);
	}
}
