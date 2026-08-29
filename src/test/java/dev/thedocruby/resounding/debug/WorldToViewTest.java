package dev.thedocruby.resounding.debug;

import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldToViewTest {

	@Test
	void worldToViewAppliesCameraRotationAndTranslation() {
		Matrix4f rotation = new Matrix4f().rotateY((float) Math.toRadians(90));
		Vec3d cameraPos = new Vec3d(10, 0, 0);

		Matrix4f modelView = GpuLineBuffer.worldToView(rotation, cameraPos);

		Vector4f worldPoint = new Vector4f(10, 0, 5, 1);
		modelView.transform(worldPoint);

		// Camera at (10,0,0) looking -Z after 90° yaw: world (10,0,5) -> view (5,0,0)
		assertEquals(5F, worldPoint.x, 1e-4F);
		assertEquals(0F, worldPoint.y, 1e-4F);
		assertEquals(0F, worldPoint.z, 1e-4F);
	}
}
