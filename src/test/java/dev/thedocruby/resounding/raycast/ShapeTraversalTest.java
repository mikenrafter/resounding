package dev.thedocruby.resounding.raycast;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapeTraversalTest {

	private static final ShapeTraversal.ShapeStepper UNIT_STEPPER =
			(base, size, position, vector) -> new Step(vector.multiply(1.0), new Vec3i(1, 0, 0));

	@Test
	void emptyShapeFallsBackToVoxelMode() {
		BlockPos origin = new BlockPos(0, 0, 0);
		Vec3d position = new Vec3d(0.5, 0.5, 0.5);
		Vec3d vector = new Vec3d(1, 0, 0);

		ShapeTraversal.Result result = ShapeTraversal.resolve(
				origin, 1, position, vector, VoxelShapes.empty(), UNIT_STEPPER
		);

		assertEquals(ShapeTraversal.Mode.VOXEL, result.mode());
	}

	@Test
	void unitFullCubeUsesVoxelMode() {
		BlockPos origin = new BlockPos(0, 0, 0);
		Vec3d position = new Vec3d(0.5, 0.5, 0.5);
		Vec3d vector = new Vec3d(1, 0, 0);
		VoxelShape cube = VoxelShapes.fullCube();

		ShapeTraversal.Result result = ShapeTraversal.resolve(
				origin, 1, position, vector, cube, UNIT_STEPPER
		);

		assertEquals(ShapeTraversal.Mode.VOXEL, result.mode());
	}

	@Test
	void largeHomogeneousCubeUsesVoxelMode() {
		BlockPos origin = new BlockPos(0, 0, 0);
		Vec3d position = new Vec3d(0.5, 0.5, 0.5);
		Vec3d vector = new Vec3d(1, 0, 0);
		VoxelShape cube = VoxelShapes.fullCube();

		ShapeTraversal.Result result = ShapeTraversal.resolve(
				origin, 16, position, vector, cube, UNIT_STEPPER
		);

		assertEquals(ShapeTraversal.Mode.VOXEL, result.mode());
	}

	@Test
	void thinShapeUsesShapeMode() {
		BlockPos origin = new BlockPos(0, 0, 0);
		Vec3d position = new Vec3d(0.1, 0.5, 0.5);
		Vec3d vector = new Vec3d(1, 0, 0);
		VoxelShape pane = VoxelShapes.cuboid(0, 0, 0, 0.125, 1, 1);

		ShapeTraversal.Result result = ShapeTraversal.resolve(
				origin, 1, position, vector, pane, UNIT_STEPPER
		);

		assertEquals(ShapeTraversal.Mode.SHAPE, result.mode());
		assertTrue(result.permeationDistance() >= 0);
	}
}
