package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.MaterialRegistry;
import dev.thedocruby.resounding.material.Material;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CastInteractionMaterialTest {

	private static final Material WOOD = new Material(41471.0, 0.5, 1.0);

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.createGameVersion();
		Bootstrap.initialize();
	}

	@Test
	void airGapOnFirstCastInPartialCellUsesAirMaterialForVoxelTraversal() {
		BlockPos origin = new BlockPos(0, 0, 0);
		Vec3d gap = new Vec3d(0.5, 0.5, 0.5);
		VoxelShape pane = VoxelShapes.cuboid(0, 0, 0, 0.125, 1, 1);

		Material material = Cast.interactionMaterial(
				WOOD, pane, origin, gap, ShapeTraversal.Mode.VOXEL
		);

		assertSame(MaterialRegistry.DEFAULT, material);
	}

	@Test
	void airGapInPartialCellUsesAirMaterialEvenWithPriorImpedance() {
		BlockPos origin = new BlockPos(0, 0, 0);
		Vec3d gap = new Vec3d(0.5, 0.5, 0.5);
		VoxelShape pane = VoxelShapes.cuboid(0, 0, 0, 0.125, 1, 1);

		Material material = Cast.interactionMaterial(
				WOOD, pane, origin, gap, ShapeTraversal.Mode.VOXEL
		);

		assertSame(MaterialRegistry.DEFAULT, material);
	}

	@Test
	void solidRegionInPartialCellUsesBlockMaterialForVoxelTraversal() {
		BlockPos origin = new BlockPos(0, 0, 0);
		Vec3d onPane = new Vec3d(0.05, 0.5, 0.5);
		VoxelShape pane = VoxelShapes.cuboid(0, 0, 0, 0.125, 1, 1);

		Material material = Cast.interactionMaterial(
				WOOD, pane, origin, onPane, ShapeTraversal.Mode.VOXEL
		);

		assertEquals(WOOD, material);
	}

	@Test
	void solidHitInPartialCellUsesBlockMaterial() {
		BlockPos origin = new BlockPos(0, 0, 0);
		Vec3d onPane = new Vec3d(0.05, 0.5, 0.5);
		VoxelShape pane = VoxelShapes.cuboid(0, 0, 0, 0.125, 1, 1);

		Material material = Cast.interactionMaterial(
				WOOD, pane, origin, onPane, ShapeTraversal.Mode.SHAPE
		);

		assertEquals(WOOD, material);
	}

	@Test
	void fullCubeAlwaysUsesBlockMaterial() {
		BlockPos origin = new BlockPos(0, 0, 0);
		Vec3d center = new Vec3d(0.5, 0.5, 0.5);
		VoxelShape cube = VoxelShapes.fullCube();

		Material material = Cast.interactionMaterial(
				WOOD, cube, origin, center, ShapeTraversal.Mode.VOXEL
		);

		assertEquals(WOOD, material);
	}
}
