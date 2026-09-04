package dev.thedocruby.resounding.debug;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BounceRayLayerTest {

	@Test
	void lineWidthScalesWithPowerAndStepsDownPerBounce() {
		assertEquals(4.0F, BounceRayLayer.lineWidthFor(128.0, 0), 1e-6);
		assertEquals(2.0F, BounceRayLayer.lineWidthFor(64.0, 0), 1e-6);
		assertEquals(3.0F, BounceRayLayer.lineWidthFor(128.0, 1), 1e-6);
		assertEquals(1.0F, BounceRayLayer.lineWidthFor(32.0, 2), 1e-6);
		assertEquals(1.0F, BounceRayLayer.lineWidthFor(1.0, 10), 1e-6);
	}

	@Test
	void terminatedColorIsMagenta() {
		assertEquals(0xFFFF00FF, BounceRayLayer.TERMINATED_COLOR);
	}

	@Test
	void activeRayColorIsWhite() {
		assertEquals(0xFFFFFFFF, BounceRayLayer.ACTIVE_RAY_COLOR);
	}

	@Test
	void intersectsFocusedOctantWhenEndpointInside() {
		Box zone = new Box(0, 0, 0, 2, 2, 2);
		RayLineLayer.LineSegment inside = new RayLineLayer.LineSegment(
				new Vec3d(0.5, 0.5, 0.5), new Vec3d(3, 0.5, 0.5), 0xFFFFFF, 4.0F, false, 1, 1);
		RayLineLayer.LineSegment outside = new RayLineLayer.LineSegment(
				new Vec3d(5, 5, 5), new Vec3d(6, 5, 5), 0xFFFFFF, 4.0F, false, 1, 1);
		assertTrue(BounceRayLayer.intersectsFocusedOctant(inside, List.of(zone)));
		assertFalse(BounceRayLayer.intersectsFocusedOctant(outside, List.of(zone)));
	}

	@Test
	void terminatedMarkersUseDedicatedLineWidth() {
		assertEquals(3.0F, BounceRayLayer.TERMINATED_LINE_WIDTH, 1e-6);
	}

	@Test
	void groupConnectedRaysKeepsConnectedBouncesAsOnePolyline() {
		List<RayLineLayer.LineSegment> snapshot = List.of(
				seg(0, 0, 0, 4, 0, 0),
				seg(4, 0, 0, 4, 3, 0)
		);

		List<List<RayLineLayer.LineSegment>> rays = BounceRayLayer.groupConnectedRays(snapshot);

		assertEquals(1, rays.size());
		assertEquals(2, rays.getFirst().size());
	}

	@Test
	void groupConnectedRaysStartsANewRayWhenStartDoesNotMeetPreviousEnd() {
		List<RayLineLayer.LineSegment> snapshot = List.of(
				seg(0, 0, 0, 4, 0, 0),
				seg(10, 0, 0, 14, 0, 0)
		);

		List<List<RayLineLayer.LineSegment>> rays = BounceRayLayer.groupConnectedRays(snapshot);

		assertEquals(2, rays.size());
		assertEquals(1, rays.get(0).size());
		assertEquals(1, rays.get(1).size());
	}

	@Test
	void groupConnectedRaysSkipsTerminatorMarkersWithoutSplittingAConnectedPath() {
		Vec3d join = new Vec3d(4, 0, 0);
		List<RayLineLayer.LineSegment> snapshot = List.of(
				seg(0, 0, 0, 4, 0, 0),
				terminator(join),
				seg(4, 0, 0, 4, 2, 0)
		);

		List<List<RayLineLayer.LineSegment>> rays = BounceRayLayer.groupConnectedRays(snapshot);

		assertEquals(1, rays.size());
		assertEquals(2, rays.getFirst().size());
	}

	@Test
	void groupConnectedRaysSkipsTerminatorBetweenDisconnectedRays() {
		List<RayLineLayer.LineSegment> snapshot = List.of(
				seg(0, 0, 0, 2, 0, 0),
				terminator(new Vec3d(2, 0, 0)),
				seg(8, 1, 0, 10, 1, 0)
		);

		List<List<RayLineLayer.LineSegment>> rays = BounceRayLayer.groupConnectedRays(snapshot);

		assertEquals(2, rays.size());
	}

	@Test
	void groupConnectedRaysIgnoresMarkerOnlySnapshot() {
		List<RayLineLayer.LineSegment> snapshot = List.of(
				terminator(new Vec3d(1, 2, 3))
		);

		assertTrue(BounceRayLayer.groupConnectedRays(snapshot).isEmpty());
	}

	@Test
	void groupConnectedRaysKeepsLastRealSegmentEvenIfFlaggedTerminated() {
		RayLineLayer.LineSegment last = new RayLineLayer.LineSegment(
				new Vec3d(0, 0, 0), new Vec3d(3, 0, 0), 0xFFFFFF, 4.0F, true
		);

		List<List<RayLineLayer.LineSegment>> rays = BounceRayLayer.groupConnectedRays(List.of(last));

		assertEquals(1, rays.size());
		assertEquals(1, rays.getFirst().size());
	}

	@Test
	void groupByRayIndexKeepsInterleavedCastsAsWholePolylines() {
		List<RayLineLayer.LineSegment> snapshot = List.of(
				seg(0, 0, 0, 1, 0, 0, 0),
				seg(0, 1, 0, 1, 1, 0, 1),
				seg(1, 0, 0, 2, 0, 0, 0),
				seg(1, 1, 0, 2, 1, 0, 1)
		);

		List<List<RayLineLayer.LineSegment>> rays = BounceRayLayer.groupByRayIndex(snapshot);

		assertEquals(2, rays.size());
		assertEquals(0, rays.get(0).getFirst().rayIndex());
		assertEquals(2, rays.get(0).size());
		assertEquals(1, rays.get(1).getFirst().rayIndex());
		assertEquals(2, rays.get(1).size());
	}

	@Test
	void groupByRayIndexSkipsMarkersWithoutRayIndex() {
		List<RayLineLayer.LineSegment> snapshot = List.of(
				seg(0, 0, 0, 2, 0, 0, 5),
				terminator(new Vec3d(2, 0, 0)),
				seg(2, 0, 0, 4, 0, 0, 5)
		);

		List<List<RayLineLayer.LineSegment>> rays = BounceRayLayer.groupByRayIndex(snapshot);

		assertEquals(1, rays.size());
		assertEquals(2, rays.getFirst().size());
		assertEquals(5, rays.getFirst().getFirst().rayIndex());
	}

	private static RayLineLayer.LineSegment seg(
			double x0, double y0, double z0, double x1, double y1, double z1
	) {
		return new RayLineLayer.LineSegment(
				new Vec3d(x0, y0, z0), new Vec3d(x1, y1, z1), 0xFFFFFF, 4.0F, false
		);
	}

	private static RayLineLayer.LineSegment seg(
			double x0, double y0, double z0, double x1, double y1, double z1, int rayIndex
	) {
		return new RayLineLayer.LineSegment(
				new Vec3d(x0, y0, z0), new Vec3d(x1, y1, z1), 0xFFFFFF, 4.0F, false, rayIndex, 1
		);
	}

	private static RayLineLayer.LineSegment terminator(Vec3d center) {
		return new RayLineLayer.LineSegment(center, center, BounceRayLayer.TERMINATED_COLOR, 4.0F, true);
	}
}
