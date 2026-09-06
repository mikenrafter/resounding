package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.material.Material;
import dev.thedocruby.resounding.raycast.Branch;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
		// Arrange — LOD≥2 so getAtLod keeps the wall's coarse polar (1³ virtual leaves strip it).
		// Stronger Y so the second DDA face wins over the far +X of the virtual neighbor.
		Branch root = wallTree();
		List<OctreeOverlay.VisitedStep> steps = new ArrayList<>();
		Vec3d hit = new Vec3d(8, 0.5, 0.5);
		Vec3d incident = new Vec3d(1, 2, 0);
		int castStepSize = 2;

		// Act
		OctreeLayer.appendBounceOff(root, hit, incident, castStepSize, steps, new HashSet<>());

		// Assert — neighbor beyond the +X face is the polarized wall cell
		assertFalse(steps.isEmpty());
		OctreeOverlay.VisitedStep bounce = steps.getFirst();
		assertEquals("bounce", bounce.label());
		assertFalse(bounce.virtual());
		assertEquals(WALL_POLAR, bounce.polar());
		assertTrue(bounce.box().minX >= 8.0 - 1e-9, "plus-X bounce-off must sit at or past x=8");
		assertTrue(bounce.box().minX < 16.0);
	}

	@Test
	void bounceOffPlusXAtLod1StripsPolarFromVirtualLeaf() {
		// Arrange — same geometry as plus-X, but pencil LOD (Task B: 1³ never carries polar)
		Branch root = wallTree();
		List<OctreeOverlay.VisitedStep> steps = new ArrayList<>();

		// Act
		OctreeLayer.appendBounceOff(
				root, new Vec3d(8, 0.5, 0.5), new Vec3d(1, 2, 0), 1, steps, new HashSet<>());

		// Assert — still beyond the face, but polar is intentionally null at size 1
		assertFalse(steps.isEmpty());
		OctreeOverlay.VisitedStep bounce = steps.getFirst();
		assertEquals("bounce", bounce.label());
		assertNull(bounce.polar(), "1³ virtual leaves must not leak coarse wall polar");
		assertTrue(bounce.box().minX >= 8.0 - 1e-9);
		assertTrue(bounce.box().minX < 16.0);
	}

	@Test
	void bounceOffMinusXSelectsTheCellBeyondTheHitFace() {
		// Arrange
		Branch root = wallTree();
		List<OctreeOverlay.VisitedStep> steps = new ArrayList<>();
		Vec3d hit = new Vec3d(8, 0.5, 0.5);
		Vec3d incident = new Vec3d(-1, 2, 0);
		int castStepSize = 2;

		// Act
		OctreeLayer.appendBounceOff(root, hit, incident, castStepSize, steps, new HashSet<>());

		// Assert — neighbor beyond the −X face sits in the air half-space
		assertFalse(steps.isEmpty());
		OctreeOverlay.VisitedStep bounce = steps.getFirst();
		assertEquals("bounce", bounce.label());
		assertTrue(bounce.box().minX < 8.0);
		assertTrue(bounce.box().maxX <= 8.0 + 1e-9);
	}

	@Test
	void bounceOffHeadOnSkipsNed() {
		Branch root = wallTree();
		List<OctreeOverlay.VisitedStep> steps = new ArrayList<>();
		OctreeLayer.appendBounceOff(
				root, new Vec3d(8, 0.5, 0.5), new Vec3d(1, 0, 0), 2, steps, new HashSet<>()
		);
		assertTrue(steps.isEmpty(), "same-axis DDA projection must not invent N/E/D neighbors");
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
	void collectCastVisitedViewsEmitsQuartetShapeAtATurnAndKeepsPolar() {
		Branch root = wallTree();
		// LOD > 1 with oblique approach so first/second DDA axes differ (NED applies).
		List<RayLineLayer.LineSegment> path = List.of(
				seg(0.5, 0.5, 0.5, 8.0, 3.5, 0.5, 3, 2),
				seg(8.0, 3.5, 0.5, 8.0, 3.5, 4.0, 3, 2)
		);

		List<OctreeOverlay.OctantView> views = OctreeLayer.collectCastVisitedViews(path, pos -> root);

		assertFalse(views.isEmpty(), "cast path must produce LOD boxes without a C capture");
		assertTrue(
				views.stream().anyMatch(OctreeOverlay.OctantView::incidentHost),
				"direction change at LOD>1 must emit a bordered incident octant"
		);
		List<OctreeOverlay.OctantView> quartets = views.stream()
				.filter(OctreeOverlay.OctantView::insetFill)
				.toList();
		assertFalse(quartets.isEmpty(), "NED survey must emit an inset quartet shape");
		assertTrue(
				quartets.stream().allMatch(OctreeOverlay.OctantView::drawBorder),
				"quartet shapes keep a white border (distance-faded when unfocused; forced when focused)"
		);
		assertTrue(
				quartets.stream().noneMatch(v -> v.label() != null && v.label().toLowerCase().contains("virtual")),
				"quartet shapes must not be tagged virtual-cyan"
		);
		assertTrue(
				views.stream().anyMatch(v -> WALL_POLAR.equals(v.polar())),
				"quartet/incident polar must come from the LOD branch"
		);
	}

	@Test
	void collectCastVisitedViewsSkipsNedAtLod1() {
		Branch root = wallTree();
		List<RayLineLayer.LineSegment> path = List.of(
				seg(0.5, 0.5, 0.5, 8.0, 0.5, 0.5, 3, 1),
				seg(8.0, 0.5, 0.5, 8.0, 0.5, 4.0, 3, 1)
		);

		List<OctreeOverlay.OctantView> views = OctreeLayer.collectCastVisitedViews(path, pos -> root);

		assertTrue(
				views.stream().noneMatch(v -> v.label() != null && v.label().contains("bounce")),
				"1³ pencil-ray mode must not emit N/E/D bounce-off octants"
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

	@Test
	void firstRayContactUsesStartWhenAlreadyInside() {
		Box box = new Box(0, 0, 0, 2, 2, 2);
		Vec3d start = new Vec3d(0.5, 0.5, 0.5);
		assertEquals(start, OctreeLayer.firstRayContact(start, new Vec3d(3, 0.5, 0.5), box));
	}

	@Test
	void firstRayContactFindsEntryOnTheSegment() {
		Box box = new Box(2, 0, 0, 4, 2, 2);
		Vec3d contact = OctreeLayer.firstRayContact(
				new Vec3d(0, 1, 1), new Vec3d(5, 1, 1), box);
		assertEquals(2.0, contact.x, 1e-9);
		assertEquals(1.0, contact.y, 1e-9);
		assertEquals(1.0, contact.z, 1e-9);
	}

	@Test
	void greenMarkersSitOnRayContactNotCellCenters() {
		Branch root = wallTree();
		List<RayLineLayer.LineSegment> path = List.of(
				seg(0.5, 0.5, 0.5, 4.0, 0.5, 0.5, 2, 2)
		);
		OctreeLayer.CastPathOverlay overlay = OctreeLayer.collectCastVisitedOverlay(path, pos -> root);
		assertEquals(
				List.of(new OctreeLayer.NedMarker(new Vec3d(0.5, 0.5, 0.5), OctreeLayer.NedMarkerState.TRANSMISSION)),
				overlay.nedMarkers());
	}

	@Test
	void insetBoxShrinksEachSide() {
		Box box = new Box(0, 0, 0, 4, 4, 4);
		Box inset = OctreeLayer.insetBox(box, 0.15 * 4);
		assertEquals(0.6, inset.minX, 1e-9);
		assertEquals(3.4, inset.maxX, 1e-9);
		assertEquals(0.6, inset.minY, 1e-9);
		assertEquals(3.4, inset.maxY, 1e-9);
	}

	@Test
	void insetBoxRefusesToInvert() {
		Box box = new Box(0, 0, 0, 1, 1, 1);
		assertEquals(box, OctreeLayer.insetBox(box, 1.0));
	}

	@Test
	void size1TurnEmitsIncidentOnlyNoQuartetShape() {
		Branch root = wallTree();
		List<RayLineLayer.LineSegment> path = List.of(
				seg(0.5, 0.5, 0.5, 8.0, 0.5, 0.5, 3, 1),
				seg(8.0, 0.5, 0.5, 8.0, 0.5, 4.0, 3, 1)
		);
		List<OctreeOverlay.OctantView> views = OctreeLayer.collectCastVisitedViews(path, pos -> root);
		assertTrue(
				views.stream().noneMatch(v -> v.insetFill() || !v.drawBorder()),
				"1³ interactions must not emit a quartet shape"
		);
	}

	@Test
	void occupancyFilterKeepsWholeClusterWhenPlayerHitsOneMember() {
		OctreeOverlay.OctantView h = view(new Box(0, 0, 0, 2, 2, 2), 0xFF0000, 7);
		OctreeOverlay.OctantView n = view(new Box(2, 0, 0, 4, 2, 2), 0xFF0000, 7);
		OctreeOverlay.OctantView e = view(new Box(0, 0, 2, 2, 2, 4), 0xFF0000, 7);
		OctreeOverlay.OctantView other = view(new Box(20, 0, 0, 22, 2, 2), 0x00FF00, 3);
		List<OctreeOverlay.OctantView> filtered = OctreeLayer.occupancyFilter(
				List.of(h, n, e, other), new Vec3d(1, 1, 1));
		assertEquals(3, filtered.size());
		assertTrue(filtered.contains(h));
		assertTrue(filtered.contains(n));
		assertTrue(filtered.contains(e));
		assertFalse(filtered.contains(other));
	}

	@Test
	void occupancyFilterKeepsAllIntersectedClusters() {
		OctreeOverlay.OctantView a = view(new Box(0, 0, 0, 2, 2, 2), 0xFF0000, 1);
		OctreeOverlay.OctantView a2 = view(new Box(2, 0, 0, 4, 2, 2), 0xFF0000, 1);
		OctreeOverlay.OctantView b = view(new Box(1, 0, 0, 3, 2, 2), 0x00FF00, 2); // overlaps player
		OctreeOverlay.OctantView b2 = view(new Box(10, 0, 0, 12, 2, 2), 0x00FF00, 2);
		List<OctreeOverlay.OctantView> filtered = OctreeLayer.occupancyFilter(
				List.of(a, a2, b, b2), new Vec3d(1.5, 1, 1));
		assertEquals(4, filtered.size(), "player in both set 1 and set 2 members → both full clusters");
	}

	@Test
	void occupancyFilterKeepsAllWhenPlayerOutside() {
		OctreeOverlay.OctantView a = view(new Box(0, 0, 0, 2, 2, 2), 0xFF0000, 1);
		OctreeOverlay.OctantView b = view(new Box(10, 0, 0, 12, 2, 2), 0x00FF00, 2);
		List<OctreeOverlay.OctantView> all = List.of(a, b);
		assertEquals(all, OctreeLayer.occupancyFilter(all, new Vec3d(5, 5, 5)));
	}

	@Test
	void fillOpacityFadesOverTenBlocks() {
		Box box = new Box(0, 0, 0, 1, 1, 1);
		assertEquals(0.2F, OctreeLayer.fillOpacity(box, new Vec3d(0.5, 0.5, 0.5)), 1e-5F);
		assertEquals(0.05F, OctreeLayer.fillOpacity(box, new Vec3d(100, 0.5, 0.5)), 1e-5F);
		float mid = OctreeLayer.fillOpacity(box, new Vec3d(5.5, 0.5, 0.5)); // ~5 blocks from face at x=1
		assertTrue(mid < 0.2F && mid > 0.05F, "mid-range opacity was " + mid);
	}

	@Test
	void bordersDropAtFadeDistance() {
		Box box = new Box(0, 0, 0, 1, 1, 1);
		assertTrue(OctreeLayer.drawBorders(box, new Vec3d(0.5, 0.5, 0.5)));
		assertFalse(OctreeLayer.drawBorders(box, new Vec3d(20, 0.5, 0.5)));
	}

	@Test
	void focusedClusterAlwaysDrawsBorders() {
		Box box = new Box(0, 0, 0, 1, 1, 1);
		assertTrue(OctreeLayer.shouldDrawBorders(box, new Vec3d(20, 0.5, 0.5), true));
		assertFalse(OctreeLayer.shouldDrawBorders(box, new Vec3d(20, 0.5, 0.5), false));
	}

	@Test
	void focusedIncidentHostHighlightsPerCluster() {
		OctreeOverlay.OctantView h = view(new Box(0, 0, 0, 2, 2, 2), 0xFF0000, 7, true, new Vec3i(1, 0, 0));
		OctreeOverlay.OctantView n = view(new Box(2, 0, 0, 4, 2, 2), 0xFF0000, 7);
		OctreeOverlay.OctantView other = view(new Box(20, 0, 0, 22, 2, 2), 0x00FF00, 3);
		OctreeLayer.ClusterFocus focus = OctreeLayer.resolveClusterFocus(
				List.of(h, n, other), new Vec3d(1, 1, 1));
		assertTrue(focus.focused());
		assertEquals(Set.of(7), focus.hitSetIds());
		assertTrue(OctreeLayer.isFocusedIncidentHost(h, focus));
		assertFalse(OctreeLayer.isFocusedIncidentHost(n, focus));
	}

	@Test
	void multiClusterFocusHighlightsEveryIncidentHost() {
		OctreeOverlay.OctantView a = view(new Box(0, 0, 0, 2, 2, 2), 0xFF0000, 1, true, new Vec3i(1, 0, 0));
		OctreeOverlay.OctantView b = view(new Box(1, 0, 0, 3, 2, 2), 0x00FF00, 2, true, new Vec3i(0, 0, 1));
		OctreeLayer.ClusterFocus focus = OctreeLayer.resolveClusterFocus(
				List.of(a, b), new Vec3d(1.5, 1, 1));
		assertTrue(focus.focused());
		assertEquals(Set.of(1, 2), focus.hitSetIds());
		assertTrue(OctreeLayer.isFocusedIncidentHost(a, focus));
		assertTrue(OctreeLayer.isFocusedIncidentHost(b, focus));
	}

	@Test
	void bounceOffMarksIncidentHostWithExitFace() {
		Branch root = wallTree();
		List<RayLineLayer.LineSegment> path = List.of(
				seg(4, 4, 4, 8, 4, 4, 0, 8),
				seg(8, 4, 4, 8, 4, 12, 0, 8)
		);
		List<OctreeOverlay.OctantView> views = OctreeLayer.collectCastVisitedViews(path, p -> root);
		OctreeOverlay.OctantView host = views.stream()
				.filter(OctreeOverlay.OctantView::incidentHost)
				.findFirst()
				.orElseThrow();
		assertNotNull(host.setId());
		assertEquals(new Vec3i(1, 0, 0), host.incidentFace());
	}

	private static OctreeOverlay.OctantView view(Box box, int color, int setId) {
		return view(box, color, setId, false, null);
	}

	private static OctreeOverlay.OctantView view(
			Box box,
			int color,
			int setId,
			boolean incidentHost,
			Vec3i incidentFace
	) {
		int size = (int) Math.round(box.maxX - box.minX);
		return new OctreeOverlay.OctantView(
				box, null, "leaf", size, color, null, setId, incidentHost, incidentFace);
	}

	private static Branch wallTree() {
		Branch root = new Branch(new BlockPos(0, 0, 0), 16, AIR);
		root.bake(new Branch.NodeDescriptor(AIR.impedance(), AIR.impedance(), AIR.impedance(), Double.NaN, null));
		Branch air = new Branch(new BlockPos(0, 0, 0), 8, AIR);
		air.bake(new Branch.NodeDescriptor(AIR.impedance(), AIR.impedance(), AIR.impedance(), Double.NaN, null));
		Branch wall = new Branch(new BlockPos(8, 0, 0), 8, STONE);
		wall.bake(new Branch.NodeDescriptor(STONE.impedance(), STONE.impedance(), STONE.impedance(), Double.NaN, WALL_POLAR));
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
