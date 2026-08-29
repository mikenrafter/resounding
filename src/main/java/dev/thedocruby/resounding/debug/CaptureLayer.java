package dev.thedocruby.resounding.debug;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

@Environment(EnvType.CLIENT)
public final class CaptureLayer implements DebugLayer {

	private final GpuLineBuffer buffer = new GpuLineBuffer(VertexBuffer.Usage.STATIC, true, 0.25F);
	private int lastVersion = -1;

	@Override
	public boolean isEnabled() {
		// Visible while capturing or while frozen capture data remains on screen.
		return CaptureBuffer.INSTANCE.isCapturing() || !CaptureBuffer.INSTANCE.asCapturedList().isEmpty();
	}

	@Override
	public void update() {
		int version = CaptureBuffer.INSTANCE.version();
		if (version != lastVersion) {
			lastVersion = version;
			buffer.markDirty();
		}

		if (CaptureBuffer.INSTANCE.consumeTruncationWarning()) {
			MinecraftClient client = MinecraftClient.getInstance();
			if (client.player != null) {
				client.player.sendMessage(Text.literal("Capture buffer full; oldest rays dropped."), true);
			}
		}
	}

	@Override
	public void render(MatrixStack matrices, Vec3d cameraPos) {
		buffer.rebuild(this::populate);
		buffer.draw(matrices, cameraPos);
	}

	private void populate(BufferBuilder builder) {
		for (CaptureBuffer.CapturedRay ray : CaptureBuffer.INSTANCE.asCapturedList()) {
			GpuLineBuffer.line(
					builder,
					ray.start().x, ray.start().y, ray.start().z,
					ray.end().x, ray.end().y, ray.end().z,
					ray.color()
			);
		}
	}
}
