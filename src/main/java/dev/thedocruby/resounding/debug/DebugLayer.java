package dev.thedocruby.resounding.debug;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

@Environment(EnvType.CLIENT)
public interface DebugLayer {

	boolean isEnabled();

	void update();

	void render(Matrix4f positionMatrix, Matrix4f projectionMatrix, Vec3d cameraPos);
}
