package dev.thedocruby.resounding.debug;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.*;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.profiler.Profiler;
import org.joml.Matrix4f;

import java.util.function.Consumer;

/**
 * Translucent axis-aligned box fills (no depth test) for frustum octant shading.
 */
@Environment(EnvType.CLIENT)
public final class GpuFillBuffer implements AutoCloseable {

	private final VertexBuffer.Usage usage;
	private VertexBuffer vertexBuffer;
	private boolean dirty = true;
	private boolean hasGeometry;

	public GpuFillBuffer(VertexBuffer.Usage usage) {
		this.usage = usage;
		this.hasGeometry = false;
	}

	public void markDirty() {
		dirty = true;
	}

	public void rebuild(Consumer<BufferBuilder> populate) {
		if (!dirty) {
			return;
		}
		if (vertexBuffer == null) {
			vertexBuffer = new VertexBuffer(usage);
		}

		Profiler profiler = MinecraftClient.getInstance().getProfiler();
		profiler.push("resounding_fill_rebuild");
		try {
			Tessellator tessellator = Tessellator.getInstance();
			BufferBuilder builder = tessellator.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
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

	public void draw(Matrix4f positionMatrix, Matrix4f projectionMatrix, Vec3d cameraPos) {
		if (!hasGeometry) {
			return;
		}

		RenderSystem.disableDepthTest();
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShader(GameRenderer::getPositionColorProgram);

		Matrix4f modelView = GpuLineBuffer.worldToView(positionMatrix, cameraPos);
		vertexBuffer.bind();
		vertexBuffer.draw(modelView, projectionMatrix, GameRenderer.getPositionColorProgram());

		RenderSystem.disableBlend();
	}

	static void boxFaces(
			BufferBuilder builder,
			double minX, double minY, double minZ,
			double maxX, double maxY, double maxZ,
			int argb
	) {
		int r = GpuLineBuffer.red(argb);
		int g = GpuLineBuffer.green(argb);
		int b = GpuLineBuffer.blue(argb);
		int a = (argb >>> 24) & 0xFF;
		// -X +X -Y +Y -Z +Z
		quad(builder, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ, minX, minY, maxZ, r, g, b, a);
		quad(builder, maxX, minY, minZ, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, r, g, b, a);
		quad(builder, minX, minY, minZ, minX, minY, maxZ, maxX, minY, maxZ, maxX, minY, minZ, r, g, b, a);
		quad(builder, minX, maxY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, a);
		quad(builder, minX, minY, minZ, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ, r, g, b, a);
		quad(builder, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, minY, maxZ, r, g, b, a);
	}

	/** One AABB face selected by a unit axis normal ({@code ±1,0,0} / {@code 0,±1,0} / {@code 0,0,±1}). */
	static void boxFace(
			BufferBuilder builder,
			double minX, double minY, double minZ,
			double maxX, double maxY, double maxZ,
			int faceX, int faceY, int faceZ,
			int argb
	) {
		int r = GpuLineBuffer.red(argb);
		int g = GpuLineBuffer.green(argb);
		int b = GpuLineBuffer.blue(argb);
		int a = (argb >>> 24) & 0xFF;
		if (faceX < 0) {
			quad(builder, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ, minX, minY, maxZ, r, g, b, a);
		} else if (faceX > 0) {
			quad(builder, maxX, minY, minZ, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, r, g, b, a);
		} else if (faceY < 0) {
			quad(builder, minX, minY, minZ, minX, minY, maxZ, maxX, minY, maxZ, maxX, minY, minZ, r, g, b, a);
		} else if (faceY > 0) {
			quad(builder, minX, maxY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, a);
		} else if (faceZ < 0) {
			quad(builder, minX, minY, minZ, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ, r, g, b, a);
		} else if (faceZ > 0) {
			quad(builder, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, minY, maxZ, r, g, b, a);
		}
	}

	private static void quad(
			BufferBuilder builder,
			double x1, double y1, double z1,
			double x2, double y2, double z2,
			double x3, double y3, double z3,
			double x4, double y4, double z4,
			int r, int g, int b, int a
	) {
		vert(builder, x1, y1, z1, r, g, b, a);
		vert(builder, x2, y2, z2, r, g, b, a);
		vert(builder, x3, y3, z3, r, g, b, a);
		vert(builder, x1, y1, z1, r, g, b, a);
		vert(builder, x3, y3, z3, r, g, b, a);
		vert(builder, x4, y4, z4, r, g, b, a);
	}

	private static void vert(BufferBuilder builder, double x, double y, double z, int r, int g, int b, int a) {
		builder.vertex((float) x, (float) y, (float) z).color(r, g, b, a);
	}

	@Override
	public void close() {
		if (vertexBuffer != null) {
			vertexBuffer.close();
		}
	}
}
