package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.material.Material;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Approved decision + log-format contract for the K key: a pure read of {@link CaptureBuffer} for
 * the focused ray. Empty/no focus is a dead key (no side effects); non-empty logs a header, one
 * line per bounce, and a footer, then reports that a flash should start.
 */
class KaptureActionTest {

	@Test
	void deadKey_noFocus() {
		List<String> lines = new ArrayList<>();
		boolean started = KaptureAction.execute(-1, List.of(bounce(5, 0)), lines::add);

		assertFalse(started);
		assertTrue(lines.isEmpty(), "logSink must never be invoked when there is no focused ray");
	}

	@Test
	void deadKey_emptyCapture() {
		List<String> lines = new ArrayList<>();
		boolean started = KaptureAction.execute(5, List.of(), lines::add);

		assertFalse(started);
		assertTrue(lines.isEmpty(), "logSink must never be invoked for an empty capture");
	}

	@Test
	void withData_logsAndFlashes() {
		List<String> lines = new ArrayList<>();
		List<CaptureBuffer.CapturedRay> bounces = List.of(
				bounce(5, 0),
				bounce(5, 1),
				bounce(5, 2)
		);

		boolean started = KaptureAction.execute(5, bounces, lines::add);

		assertTrue(started, "non-empty capture for the focused ray must start a flash");
		assertEquals(5, lines.size(), "header + 3 bounce lines + footer");

		assertTrue(lines.get(0).startsWith("Resounding KAPTURE ray #5"));
		assertTrue(lines.get(0).contains("sound=42"));
		assertTrue(lines.get(0).contains("bounces=3"));

		assertTrue(lines.get(4).startsWith("Resounding KAPTURE ray #5"));
		assertTrue(lines.get(4).endsWith("end"));

		String bounceLine = lines.get(1);
		assertTrue(bounceLine.startsWith("Resounding KAPTURE: ray #5 bounce #0"));
		Pattern shape = Pattern.compile(
				".*\\bpolar=-?\\d+\\.\\d{3}\\b.*\\bfrustum=\\d+\\.\\d{2}\\b.*");
		assertTrue(shape.matcher(bounceLine).matches(),
				"bounce line must include polar= and frustum= with the approved precision: " + bounceLine);
	}

	private static CaptureBuffer.CapturedRay bounce(int rayIndex, int bounceIndex) {
		return new CaptureBuffer.CapturedRay(
				new Vec3d(bounceIndex, 0, 0),
				new Vec3d(bounceIndex + 1, 0, 0),
				0xFFFFFFFF,
				42,
				rayIndex,
				bounceIndex,
				new Material(1000.0, 0.5, 1.0),
				0.3,
				0.7,
				64.0,
				400.0,
				1,
				"STONE",
				false,
				415.0,
				0.87,
				2.5,
				null,
				true,
				false,
				false,
				false,
				false
		);
	}
}
