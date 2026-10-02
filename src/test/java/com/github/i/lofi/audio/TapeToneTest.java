package com.github.i.lofi.audio;

import org.junit.Test;

import static org.junit.Assert.*;

public class TapeToneTest {
	private static final float SAMPLE_RATE = 44_100;

	private static TapeTone tone(float lowCut, float highCut, float saturation) {
		TapeTone tone = new TapeTone(1, SAMPLE_RATE);
		tone.set(lowCut, highCut, saturation, 0);
		return tone;
	}

	/** Level of a sine wave after processing, relative to its input level. */
	private static double gainAt(TapeTone tone, double hz, double amplitude) {
		float[] frame = new float[1];
		int settle = (int) SAMPLE_RATE / 2;
		int measure = (int) SAMPLE_RATE / 2;
		double sumIn = 0, sumOut = 0;
		for (int i = 0; i < settle + measure; i++) {
			float in = (float) (amplitude * Math.sin(2 * Math.PI * hz * i / SAMPLE_RATE));
			frame[0] = in;
			tone.process(frame);
			if (i >= settle) {
				sumIn += in * in;
				sumOut += frame[0] * frame[0];
			}
		}
		return Math.sqrt(sumOut / sumIn);
	}

	@Test
	public void offLeavesAudioUntouched() {
		TapeTone tone = new TapeTone(1, SAMPLE_RATE);
		tone.set(0, 0, 0, 0);
		assertFalse(tone.isActive());
	}

	@Test
	public void midrangePassesWhileBothEndsRollOff() {
		TapeTone tone = new TapeTone(1, SAMPLE_RATE);
		tone.set(80, 6000, 0, 0);

		double mid = gainAt(tone, 1000, 0.1);
		assertTrue("1 kHz gain " + mid, mid > 0.9 && mid < 1.01);

		// Two poles give 12 dB per octave, so two octaves past each edge is well down
		double bass = gainAt(tone(80, 6000, 0), 20, 0.1);
		assertTrue("20 Hz gain " + bass, bass < 0.15);
		double treble = gainAt(tone(80, 6000, 0), 18_000, 0.1);
		assertTrue("18 kHz gain " + treble, treble < 0.2);
	}

	@Test
	public void saturationKeepsQuietLevelsAndRoundsOffPeaks() {
		double quiet = gainAt(tone(0, 0, 1), 1000, 0.02);
		assertEquals(1, quiet, 0.01);

		double loud = gainAt(tone(0, 0, 1), 1000, 0.9);
		assertTrue("loud gain " + loud, loud < 0.5);
	}

	@Test
	public void gritHoldsAndCrushesSamples()
	{
		TapeTone tone = new TapeTone(1, SAMPLE_RATE);
		tone.set(0, 0, 0, 1);
		int hold = TapeTone.holdFrames(1);
		assertEquals(5, hold);
		float steps = (float) Math.pow(2, TapeTone.bits(1) - 1);

		float[] first = {0.3f};
		tone.process(first);
		// Quantized to the crushed bit depth
		assertEquals(Math.round(first[0] * steps) / steps, first[0], 1e-6);
		// Then held for the rest of the hold, whatever comes in
		for (int i = 1; i < hold; i++)
		{
			float[] next = {-0.8f};
			tone.process(next);
			assertEquals(first[0], next[0], 0f);
		}
		float[] after = {-0.8f};
		tone.process(after);
		assertTrue(after[0] < 0);
	}

	@Test
	public void noGritMeansNoHoldOrCrush()
	{
		assertEquals(1, TapeTone.holdFrames(0));
		assertEquals(16f, TapeTone.bits(0), 0f);
		TapeTone tone = new TapeTone(1, SAMPLE_RATE);
		tone.set(0, 0, 0, 0);
		assertFalse(tone.isActive());
	}

	@Test
	public void gritAddsHissToSilence()
	{
		TapeTone tone = new TapeTone(1, SAMPLE_RATE);
		tone.set(0, 0, 0, 1);
		double energy = 0;
		for (int i = 0; i < 10_000; i++)
		{
			float[] frame = {0};
			tone.process(frame);
			energy += frame[0] * frame[0];
		}
		assertTrue(energy > 0);
	}
}
