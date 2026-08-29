package dev.thedocruby.resounding.debug;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;

@Environment(EnvType.CLIENT)
public interface DebugLayer {

	boolean isEnabled();

	void update();

	void render(MatrixStack matrices, Vec3d cameraPos);
}
