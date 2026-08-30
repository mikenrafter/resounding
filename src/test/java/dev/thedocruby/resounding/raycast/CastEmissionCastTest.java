package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.MaterialRegistry;
import dev.thedocruby.resounding.material.Acoustics;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class CastEmissionCastTest {

	private static final Material WOOD = new Material(41471.0, 0.5, 1.0);
	private static final double AIR = MaterialRegistry.DEFAULT.impedance();

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.createGameVersion();
		Bootstrap.initialize();
	}

	@Test
	void nullPriorMatchesAirWhenBornInAirGap() {
		assertEquals(AIR, Cast.priorImpedanceForCast(null, AIR));
		assertEquals(0.0, Acoustics.reflection(AIR, AIR), 1e-9);
	}

	@Test
	void nullPriorMatchesStoneWhenBornInSolid() {
		double stone = WOOD.impedance();
		assertEquals(stone, Cast.priorImpedanceForCast(null, stone));
		assertEquals(0.0, Acoustics.reflection(stone, stone), 1e-9);
	}

	@Test
	void zeroPriorIsNotTheSameAsUnsetPrior() {
		assertTrue(Acoustics.reflection(0.0, AIR) > 0.99, "vacuum prior must not stand in for air");
	}

	@Test
	void emissionExitShouldAdoptEnteredMediumNotBirthMedium() {
		// Log evidence (stone.place @ -296.5,-40.5,570.5): emission reached air faces but
		// commitPermeation left Zprev=stone, so bounce #1 showed stone:air R=1.0 at the face.
		// commitEmissionExit must set prior to the exit cell (air), giving air:air R=0.
		double stone = 32_676_640.0;
		double air = AIR;
		assertEquals(0.0, Acoustics.reflection(air, air), 1e-9);
		assertTrue(Acoustics.reflection(stone, air) > 0.99);
	}

	@Test
	void emissionExitDistanceUsesUnitCellNotCoarseOctreeNode() {
		Vec3d cellOrigin = new Vec3d(5, 64, 3);
		Vec3d center = new Vec3d(5.5, 64.5, 3.5);
		assertEquals(0.5, Cast.emissionCellExitDistance(cellOrigin, center, new Vec3d(1, 0, 0)), 1e-6);
		assertEquals(0.5, Cast.emissionCellExitDistance(cellOrigin, center, new Vec3d(0, -1, 0)), 1e-6);
	}

	@Test
	void emissionExitNudgeMovesAlongTravelDirection() {
		Vec3d exit = new Vec3d(6.0, 64.5, 3.5);
		Vec3d nudged = Cast.nudgeEmissionExit(exit, new Vec3d(1, 0, 0));
		assertTrue(nudged.x > exit.x);
		assertEquals(exit.y, nudged.y, 1e-9);
	}

	@Test
	void emissionCastUsesAirInPartialCellGap() {
		BlockPos origin = new BlockPos(0, 0, 0);
		Vec3d gap = new Vec3d(0.5, 0.5, 0.5);
		VoxelShape pane = VoxelShapes.cuboid(0, 0, 0, 0.125, 1, 1);

		Material material = Cast.interactionMaterialForCast(
				true, WOOD, pane, origin, gap, ShapeTraversal.Mode.SHAPE
		);

		assertSame(MaterialRegistry.DEFAULT, material);
	}

	@Test
	void emissionCastUsesBlockMaterialWhenBornInsideSolidRegion() {
		BlockPos origin = new BlockPos(0, 0, 0);
		Vec3d onPane = new Vec3d(0.05, 0.5, 0.5);
		VoxelShape pane = VoxelShapes.cuboid(0, 0, 0, 0.125, 1, 1);

		Material material = Cast.interactionMaterialForCast(
				true, WOOD, pane, origin, onPane, ShapeTraversal.Mode.SHAPE
		);

		assertEquals(WOOD, material);
	}

	@Test
	void nonEmissionCastStillUsesAirInPartialCellGap() {
		BlockPos origin = new BlockPos(0, 0, 0);
		Vec3d gap = new Vec3d(0.5, 0.5, 0.5);
		VoxelShape pane = VoxelShapes.cuboid(0, 0, 0, 0.125, 1, 1);

		Material material = Cast.interactionMaterialForCast(
				false, WOOD, pane, origin, gap, ShapeTraversal.Mode.VOXEL
		);

		assertSame(MaterialRegistry.DEFAULT, material);
	}
}
