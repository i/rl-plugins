package com.github.i.lofi.audio;

import org.junit.Test;

import static org.junit.Assert.*;

public class TapeToneTest {
	private static final float SAMPLE_RATE = 44_100;

	private static TapeTone tone(float lowCut, float highCut, float saturation) {
		TapeTone tone = new TapeTone(1, SAMPLE_RATE);
		tone.set(lowCut, highCut, saturation);
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
		tone.set(0, 0, 0);
		assertFalse(tone.isActive());
	}

	@Test
	public void midrangePassesWhileBothEndsRollOff() {
		TapeTone tone = new TapeTone(1, SAMPLE_RATE);
		tone.set(80, 6000, 0);

		double mid = gainAt(tone, 1000, 0.1);
		assertTrue("1 kHz gain " + mid, mid > 0.9 && mid < 1.01);

		// Two poles give 12 dB per octave, so two octaves past each edge is well down
		double bass = gainAt(tone(80, 6000, 0), 20, 0.1);
		assertTrue("20 Hz gain " + bass, bass < 0.15);
		double treble = gainAt(tone(80, 6000, 0), 18_000, 0.1);
		assertTrue("18 kHz gain " + treble, treble < 0.2);
	}

	@Test
	public void saturationKeepsTypicalLevelsAndSquashesPeaks() {
		// A typical level comes out about as loud as it went in, within 3 dB, at any drive
		double typical = gainAt(tone(0, 0, 1), 1000, 0.3);
		assertTrue("typical gain " + typical, typical > 0.7 && typical < 1.41);

		// Quiet parts come up and peaks go down: the dynamics get squashed
		double quiet = gainAt(tone(0, 0, 1), 1000, 0.02);
		double loud = gainAt(tone(0, 0, 1), 1000, 0.9);
		assertTrue("quiet gain " + quiet, quiet > 2);
		assertTrue("loud gain " + loud, loud < 0.5);
	}

	@Test
	public void saturationKeepsSilenceSilent() {
		assertEquals(0, TapeTone.saturate(0, 10, 0.15f, TapeTone.makeup(10)), 0f);
	}

	@Test
	public void asymmetryClipsSwingsDifferently() {
		float makeup = TapeTone.makeup(10);
		float up = TapeTone.saturate(0.8f, 10, 0.15f, makeup);
		float down = TapeTone.saturate(-0.8f, 10, 0.15f, makeup);
		assertNotEquals(up, -down, 1e-3f);
	}
}
