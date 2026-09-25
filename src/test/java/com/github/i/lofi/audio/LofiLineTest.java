package com.github.i.lofi.audio;

import java.io.ByteArrayOutputStream;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.SourceDataLine;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class LofiLineTest {
	// The format the game opens its lines with
	private static final AudioFormat FORMAT = new AudioFormat(22050, 16, 2, true, false);
	private static final int FRAME_SIZE = 4;

	private static final class FakeOutput {
		final ByteArrayOutputStream written = new ByteArrayOutputStream();
		final SourceDataLine line = mock(SourceDataLine.class);

		FakeOutput() {
			when(line.getFormat()).thenReturn(FORMAT);
			when(line.getBufferSize()).thenReturn(8192);
			when(line.isOpen()).thenReturn(true);
			when(line.write(any(), anyInt(), anyInt())).thenAnswer(invocation -> {
				byte[] bytes = invocation.getArgument(0);
				int offset = invocation.getArgument(1);
				int length = invocation.getArgument(2);
				synchronized (written) {
					written.write(bytes, offset, length);
				}
				// Pace like a real device, so the feeder can't race arbitrarily far ahead
				Thread.sleep(1);
				return length;
			});
		}

		int writtenFrames() {
			synchronized (written) {
				return written.size() / FRAME_SIZE;
			}
		}

		byte[] bytes() {
			synchronized (written) {
				return written.toByteArray();
			}
		}
	}

	private static byte[] ramp(int frames) {
		byte[] bytes = new byte[frames * FRAME_SIZE];
		for (int i = 0; i < frames; i++) {
			short value = (short) ((i * 37) % 20000 - 10000);
			for (int c = 0; c < 2; c++) {
				bytes[i * FRAME_SIZE + c * 2] = (byte) value;
				bytes[i * FRAME_SIZE + c * 2 + 1] = (byte) (value >> 8);
			}
		}
		return bytes;
	}

	/** Writes like the game does: only as much as available() says fits, until everything is written. */
	private static int writeLikeTheGame(LofiLine line, byte[] input, FakeOutput output, int outputFramesWanted)
		throws InterruptedException {
		int offset = 0;
		long deadline = System.currentTimeMillis() + 10_000;
		while (output.writtenFrames() < outputFramesWanted && System.currentTimeMillis() < deadline) {
			int free = line.available() / FRAME_SIZE * FRAME_SIZE;
			int length = Math.min(free, input.length - offset);
			if (length > 0) {
				offset += line.write(input, offset, length);
			} else {
				Thread.sleep(1);
			}
		}
		return offset / FRAME_SIZE;
	}

	@Test
	public void normalSpeedPassesAudioThroughUnchanged() throws Exception {
		FakeOutput output = new FakeOutput();
		LofiLine line = new LofiLine(output.line);
		line.setSpeed(1, 0);

		byte[] input = ramp(20_000);
		writeLikeTheGame(line, input, output, 10_000);
		line.detach();

		// The first frame is held back as interpolation history, so output starts one frame into the input
		byte[] out = output.bytes();
		assertTrue(out.length >= 10_000 * FRAME_SIZE);
		for (int i = 0; i < 10_000 * FRAME_SIZE; i++)
			assertEquals("byte " + i, input[i + FRAME_SIZE], out[i]);
	}

	@Test
	public void slowerSpeedMakesTheGameProduceLessAudio() throws Exception {
		FakeOutput output = new FakeOutput();
		LofiLine line = new LofiLine(output.line);
		line.setSpeed(0.9, 0);

		int consumed = writeLikeTheGame(line, ramp(100_000), output, 40_000);
		int produced = output.writtenFrames();
		line.detach();

		// Everything the game wrote is either played (at 0.9 input frames per output frame) or still buffered
		int buffered = 8192 / FRAME_SIZE;
		double playedInput = produced * 0.9;
		assertTrue("consumed " + consumed + " produced " + produced, consumed <= playedInput + buffered + 4);
		assertTrue("consumed " + consumed + " produced " + produced, consumed >= playedInput - 4);
	}

	@Test
	public void wobbleStaysWithinItsRange() {
		Wobble wobble = new Wobble(42);
		double dt = 256 / 22050.0;
		double sum = 0;
		int steps = 200_000;
		for (int i = 0; i < steps; i++) {
			double speed = wobble.speed(i * dt, dt, 0.92, 1);
			// 3.5% drift at full wobble, plus flutter and some slack for the random walk's occasional peaks
			assertTrue("speed " + speed, speed > 0.92 * 0.94 && speed < 0.92 * 1.06);
			sum += speed;
		}
		assertEquals(0.92, sum / steps, 0.01);
	}
}
