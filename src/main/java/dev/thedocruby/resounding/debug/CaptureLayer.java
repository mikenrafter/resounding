package dev.thedocruby.resounding.debug;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;

@Environment(EnvType.CLIENT)
public final class CaptureLayer implements DebugLayer {

	@Override
	public boolean isEnabled() {
		return false;
	}

	@Override
	public void update() {
	}

	@Override
	public void render(MatrixStack matrices, Vec3d cameraPos) {
	}
}
