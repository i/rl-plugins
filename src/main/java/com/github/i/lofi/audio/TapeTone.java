package com.github.i.lofi.audio;

/**
 * The tone of a worn tape: a gentle low cut, a soft high roll-off and mild saturation, plus optional grit.
 * <p>
 * Grit is the sound of cheap gear: hiss and the odd crackle mixed in, the saturation driven harder, and the result
 * run through a sample-and-hold and bit crusher like an old sampler, so highs alias and quiet parts turn grainy.
 * <p>
 * Each band edge is two one-pole filters in a row, a gentle 12 dB per octave slope that sounds like old gear rather
 * than a hard EQ. Saturation is a tanh curve scaled so quiet audio passes at the same level and only loud peaks
 * get rounded off, which is what tape does when it's driven. Settings may change from any thread and apply on the
 * next frame; processing happens on one thread only.
 */
public final class TapeTone {
	/** Drive at 100% saturation. Kept low so it warms the sound instead of audibly distorting it. */
	private static final float MAX_DRIVE = 3f;
	/** Extra drive at 100% grit, on top of the saturation's */
	private static final float GRIT_DRIVE = 4f;
	/** Bit depth at 100% grit, falling from 16 bits at 0% */
	private static final float GRIT_MIN_BITS = 6f;
	/** Samples are held this many frames at 100% grit, from 1 (no hold) at 0% */
	private static final int GRIT_MAX_HOLD = 5;
	/** Hiss level at 100% grit, as a fraction of full scale (about -40 dB) */
	private static final float GRIT_HISS = 0.01f;
	/** Crackles per second at 100% grit */
	private static final float GRIT_CRACKLES_PER_SECOND = 3f;
	private static final float CRACKLE_LEVEL = 0.12f;

	private final int channels;
	private final float sampleRate;

	private volatile float lowCutHz;
	private volatile float highCutHz;
	private volatile float saturation;
	private volatile float grit;

	// Grit state: the held frame and how many more frames to hold it, the crackle's decaying level, and the
	// noise generator, a xorshift so it's cheap and repeatable
	private final float[] held;
	private int holdLeft;
	private float crackle;
	private int noiseState = 0x2545F491;

	// Coefficients, recomputed when the settings they come from change
	private float appliedLowCutHz = -1, appliedHighCutHz = -1;
	private float lowCutCoefficient, highCutCoefficient;

	// Per channel filter state: two low-pass stages, and two high-pass stages with their previous inputs
	private final float[] lowPass1, lowPass2;
	private final float[] highPass1, highPass2, highPassInput1, highPassInput2;

	public TapeTone(int channels, float sampleRate) {
		this.channels = channels;
		this.sampleRate = sampleRate;
		lowPass1 = new float[channels];
		lowPass2 = new float[channels];
		highPass1 = new float[channels];
		highPass2 = new float[channels];
		highPassInput1 = new float[channels];
		highPassInput2 = new float[channels];
		held = new float[channels];
	}

	/**
	 * @param lowCutHz   frequencies below this are rolled off, 0 for off
	 * @param highCutHz  frequencies above this are rolled off, 0 for off
	 * @param saturation 0 for clean, 1 for the strongest tape drive
	 * @param grit       0 for none, 1 for the grittiest: hiss, crackle, harder drive, sample-and-hold and crushing
	 */
	public void set(
		float lowCutHz,
		float highCutHz,
		float saturation,
		float grit
	) {
		this.lowCutHz = Math.max(0, lowCutHz);
		this.highCutHz = Math.max(0, highCutHz);
		this.saturation = Math.max(0, Math.min(1, saturation));
		this.grit = Math.max(0, Math.min(1, grit));
	}

	/** Whether processing would change the audio at all. */
	public boolean isActive() {
		return lowCutHz > 0 || isHighCutActive() || saturation > 0 || grit > 0;
	}

	/** Processes one frame of samples in place, one per channel. */
	public void process(float[] frame) {
		updateCoefficients();
		boolean lowCut = lowCutHz > 0;
		boolean highCut = isHighCutActive();
		float grit = this.grit;
		float drive = 1 + saturation * (MAX_DRIVE - 1) + grit * GRIT_DRIVE;

		// One crackle level per frame, shared by all channels like a pop on the record
		if (grit > 0) {
			crackle *= 0.9f;
			if (nextRandom() < grit * GRIT_CRACKLES_PER_SECOND / sampleRate)
				crackle = CRACKLE_LEVEL * (nextRandom() < 0.5f ? -1 : 1);
		}

		for (int c = 0; c < channels; c++) {
			float x = frame[c];
			if (grit > 0) {
				float hiss = (nextRandom() * 2 - 1) * GRIT_HISS * grit;
				x += hiss + crackle;
			}

			if (lowCut) {
				// One-pole high-pass: y = a * (y_prev + x - x_prev)
				float stage1 = lowCutCoefficient * (highPass1[c] + x - highPassInput1[c]);
				highPassInput1[c] = x;
				highPass1[c] = stage1;
				float stage2 = lowCutCoefficient * (highPass2[c] + stage1 - highPassInput2[c]);
				highPassInput2[c] = stage1;
				highPass2[c] = stage2;
				x = stage2;
			}

			if (highCut) {
				// One-pole low-pass: y += a * (x - y)
				lowPass1[c] += highCutCoefficient * (x - lowPass1[c]);
				lowPass2[c] += highCutCoefficient * (lowPass1[c] - lowPass2[c]);
				x = lowPass2[c];
			}

			if (drive > 1)
				x = (float) Math.tanh(drive * x) / drive;

			frame[c] = x;
		}

		if (grit > 0)
			crush(frame, grit);
	}

	/** Sample-and-hold, then quantize, like an old sampler with a low rate and few bits. */
	private void crush(float[] frame, float grit) {
		if (holdLeft > 0) {
			holdLeft--;
			System.arraycopy(held, 0, frame, 0, channels);
			return;
		}
		holdLeft = holdFrames(grit) - 1;

		float steps = (float) Math.pow(2, bits(grit) - 1);
		for (int c = 0; c < channels; c++) {
			frame[c] = Math.round(frame[c] * steps) / steps;
			held[c] = frame[c];
		}
	}

	static int holdFrames(float grit) {
		return 1 + Math.round(grit * (GRIT_MAX_HOLD - 1));
	}

	static float bits(float grit) {
		return 16 - grit * (16 - GRIT_MIN_BITS);
	}

	/** Uniform in [0, 1) */
	private float nextRandom() {
		int x = noiseState;
		x ^= x << 13;
		x ^= x >>> 17;
		x ^= x << 5;
		noiseState = x;
		return (x >>> 8) / (float) (1 << 24);
	}

	private boolean isHighCutActive() {
		float cut = highCutHz;
		return cut > 0 && cut < sampleRate / 2;
	}

	private void updateCoefficients() {
		float low = lowCutHz;
		if (low != appliedLowCutHz) {
			appliedLowCutHz = low;
			float rc = 1 / (2 * (float) Math.PI * Math.max(low, 1));
			float dt = 1 / sampleRate;
			lowCutCoefficient = rc / (rc + dt);
		}

		float high = highCutHz;
		if (high != appliedHighCutHz) {
			appliedHighCutHz = high;
			float rc = 1 / (2 * (float) Math.PI * Math.max(high, 1));
			float dt = 1 / sampleRate;
			highCutCoefficient = dt / (rc + dt);
		}
	}
}
