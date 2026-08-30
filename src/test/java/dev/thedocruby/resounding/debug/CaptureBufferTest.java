package dev.thedocruby.resounding.debug;

import dev.thedocruby.resounding.material.Material;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CaptureBufferTest {

	private static CaptureBuffer.CapturedRay ray(int index) {
		return new CaptureBuffer.CapturedRay(
				new Vec3d(index, 0, 0),
				new Vec3d(index, 1, 0),
				0xFF0000 | index,
				42,
				index,
				null,
				0.0,
				0.0,
				0.0,
				0.0,
				1,
				null
		);
	}

	@Test
	void startCaptureOneEventStopsAfterFirstEvalEnd() {
		CaptureBuffer buffer = new CaptureBuffer(8);
		buffer.startCapture(1);
		assertTrue(buffer.isCapturing());

		buffer.offer(ray(1));
		assertEquals(1, buffer.asCapturedList().size());

		buffer.onSoundEvalEnd();
		assertFalse(buffer.isCapturing());
		buffer.offer(ray(2));
		assertEquals(1, buffer.asCapturedList().size());
	}

	@Test
	void startCaptureTwoEventsSpansTwoEvalCycles() {
		CaptureBuffer buffer = new CaptureBuffer(8);
		buffer.startCapture(2);
		assertTrue(buffer.isCapturing());

		buffer.onSoundEvalStart();
		buffer.offer(ray(1));
		buffer.onSoundEvalEnd();
		assertTrue(buffer.isCapturing());
		assertEquals(1, buffer.asCapturedList().size());

		buffer.onSoundEvalStart();
		buffer.offer(ray(2));
		buffer.onSoundEvalEnd();
		assertFalse(buffer.isCapturing());
		assertEquals(List.of(ray(1), ray(2)), buffer.asCapturedList());
	}

	@Test
	void startCaptureOpenEndedUntilStopCapture() {
		CaptureBuffer buffer = new CaptureBuffer(8);
		buffer.startCapture(-1);

		buffer.onSoundEvalStart();
		buffer.offer(ray(1));
		buffer.onSoundEvalEnd();
		assertTrue(buffer.isCapturing());

		buffer.onSoundEvalStart();
		buffer.offer(ray(2));
		buffer.onSoundEvalEnd();
		assertTrue(buffer.isCapturing());
		assertEquals(2, buffer.asCapturedList().size());

		buffer.stopCapture();
		assertFalse(buffer.isCapturing());
		buffer.offer(ray(3));
		assertEquals(2, buffer.asCapturedList().size());
	}

	@Test
	void offerWhenNotCapturingIsNoOp() {
		CaptureBuffer buffer = new CaptureBuffer(8);
		buffer.offer(ray(1));
		assertTrue(buffer.asCapturedList().isEmpty());
	}

	@Test
	void truncationWarningFiresOncePerCaptureSession() {
		CaptureBuffer buffer = new CaptureBuffer(2);
		buffer.startCapture(-1);

		assertFalse(buffer.consumeTruncationWarning());
		buffer.offer(ray(1));
		buffer.offer(ray(2));
		assertFalse(buffer.consumeTruncationWarning());

		buffer.offer(ray(3));
		assertTrue(buffer.consumeTruncationWarning());
		assertFalse(buffer.consumeTruncationWarning());

		buffer.offer(ray(4));
		assertFalse(buffer.consumeTruncationWarning());

		buffer.stopCapture();
		buffer.startCapture(-1);
		buffer.offer(ray(10));
		buffer.offer(ray(11));
		buffer.offer(ray(12));
		assertTrue(buffer.consumeTruncationWarning());
	}
}
