package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.material.Material;
import dev.thedocruby.resounding.raycast.Branch;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OctreeLayerLiveRayTest {

	private static final Material AIR = new Material(1.2, 1.0, 0.5);
	private static final Material STONE = new Material(8000.0, 0.02, 0.1);
	private static final Vec3d WALL_POLAR = new Vec3d(1, 0, 0);

	@Test
	void isDirectionChangeDetectsARightAngleBounce() {
		assertTrue(OctreeLayer.isDirectionChange(new Vec3d(1, 0, 0), new Vec3d(0, 0, 1)));
		assertFalse(OctreeLayer.isDirectionChange(new Vec3d(1, 0, 0), new Vec3d(2, 0, 0)));
	}

	@Test
	void bounceOffPlusXSelectsTheCellBeyondTheHitFace() {
		Branch root = wallTree();
		List<OctreeOverlay.VisitedStep> steps = new ArrayList<>();

		OctreeLayer.appendBounceOff(
				root, new Vec3d(8, 0.5, 0.5), new Vec3d(1, 0, 0), 1, steps, new HashSet<>()
		);

		assertFalse(steps.isEmpty());
		OctreeOverlay.VisitedStep bounce = steps.getFirst();
		assertEquals("bounce", bounce.label());
		assertFalse(bounce.virtual());
		assertEquals(WALL_POLAR, bounce.polar());
		assertTrue(bounce.box().minX >= 8.0 - 1e-9, "plus-X bounce-off must sit at or past x=8");
		assertTrue(bounce.box().minX < 16.0);
	}

	@Test
	void bounceOffMinusXSelectsTheCellBeyondTheHitFace() {
		Branch root = wallTree();
		List<OctreeOverlay.VisitedStep> steps = new ArrayList<>();

		OctreeLayer.appendBounceOff(
				root, new Vec3d(8, 0.5, 0.5), new Vec3d(-1, 0, 0), 1, steps, new HashSet<>()
		);

		assertFalse(steps.isEmpty());
		assertEquals("bounce", steps.getFirst().label());
		assertTrue(steps.getFirst().box().minX < 8.0);
		assertTrue(steps.getFirst().box().maxX <= 8.0 + 1e-9);
	}

	@Test
	void collectCastVisitedViewsUsesRecordedBranchSizeNotResampledLod() {
		Branch root = wallTree();
		List<RayLineLayer.LineSegment> path = List.of(
				seg(0.5, 0.5, 0.5, 4.0, 0.5, 0.5, 7, 4)
		);

		List<OctreeOverlay.OctantView> views = OctreeLayer.collectCastVisitedViews(path, pos -> root);

		assertFalse(views.isEmpty());
		assertTrue(
				views.stream().anyMatch(v -> v.size() == 4),
				"cast step size 4 must draw a size-4 octant, not a re-walked LOD schedule"
		);
	}

	@Test
	void collectCastVisitedViewsEmitsBounceOffAtATurnAndKeepsPolar() {
		Branch root = wallTree();
		List<RayLineLayer.LineSegment> path = List.of(
				seg(0.5, 0.5, 0.5, 8.0, 0.5, 0.5, 3, 1),
				seg(8.0, 0.5, 0.5, 8.0, 0.5, 4.0, 3, 1)
		);

		List<OctreeOverlay.OctantView> views = OctreeLayer.collectCastVisitedViews(path, pos -> root);

		assertFalse(views.isEmpty(), "cast path must produce LOD boxes without a C capture");
		List<OctreeOverlay.OctantView> bounces = views.stream()
				.filter(v -> v.label() != null && v.label().contains("bounce"))
				.toList();
		assertFalse(bounces.isEmpty(), "direction change must emit a bounced-off octant");
		assertTrue(
				bounces.stream().noneMatch(v -> v.label() != null && v.label().toLowerCase().contains("virtual")),
				"bounce-off boxes must not be tagged virtual-cyan"
		);
		assertTrue(
				bounces.stream().anyMatch(v -> WALL_POLAR.equals(v.polar())),
				"bounce-off polar must come from the LOD branch"
		);
	}

	@Test
	void collectCastVisitedViewsDoesNotEmitBounceOffOnAStraightRay() {
		Branch root = wallTree();
		List<RayLineLayer.LineSegment> path = List.of(
				seg(0.5, 0.5, 0.5, 4.0, 0.5, 0.5, 2, 1)
		);

		List<OctreeOverlay.OctantView> views = OctreeLayer.collectCastVisitedViews(path, pos -> root);

		assertTrue(views.stream().noneMatch(v -> v.label() != null && v.label().contains("bounce")));
	}

	private static Branch wallTree() {
		Branch root = new Branch(new BlockPos(0, 0, 0), 16, AIR);
		root.mostCommonImpedance = AIR.impedance();
		root.leastCommonImpedance = AIR.impedance();
		root.avgImpedance = AIR.impedance();
		Branch air = new Branch(new BlockPos(0, 0, 0), 8, AIR);
		air.mostCommonImpedance = AIR.impedance();
		air.leastCommonImpedance = AIR.impedance();
		air.avgImpedance = AIR.impedance();
		Branch wall = new Branch(new BlockPos(8, 0, 0), 8, STONE);
		wall.mostCommonImpedance = STONE.impedance();
		wall.leastCommonImpedance = STONE.impedance();
		wall.avgImpedance = STONE.impedance();
		wall.polar = WALL_POLAR;
		root.put(air.start.asLong(), air);
		root.put(wall.start.asLong(), wall);
		return root;
	}

	private static RayLineLayer.LineSegment seg(
			double x0, double y0, double z0, double x1, double y1, double z1, int rayIndex, int branchSize
	) {
		return new RayLineLayer.LineSegment(
				new Vec3d(x0, y0, z0), new Vec3d(x1, y1, z1), 0xFFFFFF, 4.0F, false, rayIndex, branchSize
		);
	}
}
