package dev.thedocruby.resounding.debug;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.thedocruby.resounding.debug.math.BoxEdges;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.profiler.Profiler;
import org.joml.Matrix4f;

import java.util.function.Consumer;

@Environment(EnvType.CLIENT)
public final class GpuLineBuffer implements AutoCloseable {

	private final VertexBuffer vertexBuffer;
	private final boolean depthTest;
	private final float lineWidth;

	private boolean dirty = true;
	private boolean hasGeometry;

	public GpuLineBuffer(VertexBuffer.Usage usage, boolean depthTest, float lineWidth) {
		this.vertexBuffer = new VertexBuffer(usage);
		this.depthTest = depthTest;
		this.lineWidth = lineWidth;
		this.hasGeometry = false;
	}

	public void markDirty() {
		dirty = true;
	}

	public void rebuild(Consumer<BufferBuilder> populate) {
		if (!dirty) {
			return;
		}

		Profiler profiler = MinecraftClient.getInstance().getProfiler();
		profiler.push("resounding_vbo_rebuild");
		try {
			Tessellator tessellator = Tessellator.getInstance();
			BufferBuilder builder = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
			populate.accept(builder);
			BuiltBuffer built = builder.endNullable();
			if (built == null) {
				hasGeometry = false;
				dirty = false;
				return;
			}

			try {
				if (built.getDrawParameters().vertexCount() == 0) {
					hasGeometry = false;
				} else {
					vertexBuffer.bind();
					vertexBuffer.upload(built);
					hasGeometry = true;
				}
			} finally {
				built.close();
			}
			dirty = false;
		} finally {
			profiler.pop();
		}
	}

	public void draw(MatrixStack matrices, Vec3d cameraPos) {
		if (!hasGeometry) {
			return;
		}

		matrices.push();
		matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

		if (depthTest) {
			RenderSystem.enableDepthTest();
		} else {
			RenderSystem.disableDepthTest();
		}
		RenderSystem.disableBlend();
		RenderSystem.lineWidth(lineWidth);
		RenderSystem.setShader(GameRenderer::getPositionColorProgram);

		Matrix4f modelView = new Matrix4f(matrices.peek().getPositionMatrix());
		vertexBuffer.bind();
		vertexBuffer.draw(modelView, RenderSystem.getProjectionMatrix(), GameRenderer.getPositionColorProgram());

		matrices.pop();

		RenderSystem.lineWidth(2F);
		RenderSystem.enableBlend();
	}

	static int red(int argb) {
		return (argb >> 16) & 0xFF;
	}

	static int green(int argb) {
		return (argb >> 8) & 0xFF;
	}

	static int blue(int argb) {
		return argb & 0xFF;
	}

	static void line(BufferBuilder builder, double x1, double y1, double z1, double x2, double y2, double z2, int color) {
		builder.vertex((float) x1, (float) y1, (float) z1).color(red(color), green(color), blue(color), 255);
		builder.vertex((float) x2, (float) y2, (float) z2).color(red(color), green(color), blue(color), 255);
	}

	static void boxEdges(BufferBuilder builder, double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int color) {
		BoxEdges.forEach(minX, minY, minZ, maxX, maxY, maxZ, segment ->
				line(builder, segment.ax(), segment.ay(), segment.az(), segment.bx(), segment.by(), segment.bz(), color)
		);
	}

	@Override
	public void close() {
		vertexBuffer.close();
	}
}
