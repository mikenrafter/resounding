package dev.thedocruby.resounding.raycast;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapeTraversalInsideCubeTest {

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.createGameVersion();
		Bootstrap.initialize();
	}

	private static final ShapeTraversal.ShapeStepper CENTER_STEPPER =
			(base, size, position, vector) -> {
				double coefficient = boundAxis(base.x, position.x, size, vector.x);
				double ystep = boundAxis(base.y, position.y, size, vector.y);
				double zstep = boundAxis(base.z, position.z, size, vector.z);
				Vec3i plane = new Vec3i(-(int) Math.signum(vector.x), 0, 0);
				if (ystep < coefficient) {
					coefficient = ystep;
					plane = new Vec3i(0, -(int) Math.signum(vector.y), 0);
				}
				if (zstep < coefficient) {
					coefficient = zstep;
					plane = new Vec3i(0, 0, -(int) Math.signum(vector.z));
				}
				return new Step(vector.multiply(coefficient), plane);
			};

	private static double boundAxis(double base, double pos, double size, double dir) {
		double value = (base - pos + (dir > 0 ? size : 0)) / dir;
		if (value <= 0 || Double.isNaN(value)) {
			return Double.POSITIVE_INFINITY;
		}
		return value;
	}

	@Test
	void fullCubeFromCentreUsesVoxelMode() {
		BlockPos origin = new BlockPos(0, 0, 0);
		Vec3d center = new Vec3d(0.5, 0.5, 0.5);
		VoxelShape cube = VoxelShapes.fullCube();

		ShapeTraversal.Result result = ShapeTraversal.resolve(
				origin, 1, center, new Vec3d(1, 0, 0), cube, CENTER_STEPPER
		);

		assertEquals(ShapeTraversal.Mode.VOXEL, result.mode());
	}

	@Test
	void partialSolidStillUsesShapeModeWhenCrossingGeometry() {
		BlockPos origin = new BlockPos(0, 0, 0);
		Vec3d position = new Vec3d(0.1, 0.5, 0.5);
		VoxelShape pane = VoxelShapes.cuboid(0, 0, 0, 0.125, 1, 1);

		ShapeTraversal.Result result = ShapeTraversal.resolve(
				origin, 1, position, new Vec3d(1, 0, 0), pane, CENTER_STEPPER
		);

		assertEquals(ShapeTraversal.Mode.SHAPE, result.mode());
		assertTrue(result.permeationDistance() > 0.0);
	}

	@Test
	void shapeModeExitPlaneIsUsedForPartialGeometryOnDiagonal() {
		BlockPos origin = new BlockPos(0, 0, 0);
		Vec3d position = new Vec3d(0.1, 0.5, 0.5);
		Vec3d direction = new Vec3d(1, 0, 0);
		VoxelShape pane = VoxelShapes.cuboid(0, 0, 0, 0.125, 1, 1);

		ShapeTraversal.Result result = ShapeTraversal.resolve(
				origin, 1, position, direction, pane, CENTER_STEPPER
		);

		assertEquals(ShapeTraversal.Mode.SHAPE, result.mode());
		assertTrue(result.reflectStep().plane().getX() != 0
				|| result.reflectStep().plane().getY() != 0
				|| result.reflectStep().plane().getZ() != 0);
	}

	@Test
	void airGapMissInPartialCellUsesVoxelMode() {
		BlockPos origin = new BlockPos(0, 0, 0);
		Vec3d gap = new Vec3d(0.5, 0.5, 0.5);
		VoxelShape pane = VoxelShapes.cuboid(0, 0, 0, 0.125, 1, 1);

		ShapeTraversal.Result result = ShapeTraversal.resolve(
				origin, 1, gap, new Vec3d(1, 0, 0), pane, CENTER_STEPPER
		);

		assertEquals(ShapeTraversal.Mode.VOXEL, result.mode());
	}

	@Test
	void externalPaneHitRecordsEntryStepForReflectionPlane() {
		BlockPos origin = new BlockPos(0, 0, 0);
		Vec3d position = new Vec3d(0.2, 0.5, 0.5);
		VoxelShape pane = VoxelShapes.cuboid(0, 0, 0, 0.125, 1, 1);

		ShapeTraversal.Result result = ShapeTraversal.resolve(
				origin, 1, position, new Vec3d(-1, 0, 0), pane, CENTER_STEPPER
		);

		assertEquals(ShapeTraversal.Mode.SHAPE, result.mode());
		assertTrue(result.entryStep() != null);
		assertTrue(
				result.entryStep().plane().getX() != 0
						|| result.entryStep().plane().getY() != 0
						|| result.entryStep().plane().getZ() != 0
		);
	}

	private static Vec3d normalize(double x, double y, double z) {
		double len = Math.sqrt(x * x + y * y + z * z);
		return new Vec3d(x / len, y / len, z / len);
	}
}
