package com.github.i.lofi.audio;

import java.util.Random;

/**
 * Playback speed over time for the lo-fi tape sound: slower than normal, drifting a little slower and faster around
 * that, which bends pitch and tempo together like a worn tape or warped record.
 * <p>
 * The drift mixes two slow, detuned waves with a smoothed random walk, so it never settles into an obvious loop, plus
 * a faint fast flutter. Not thread-safe: each audio line owns one.
 */
public final class Wobble {
	/** Speed change at full wobble, as a fraction of the base speed. */
	private static final double MAX_DRIFT = 0.035;
	private static final double FLUTTER = 0.0015;
	private static final double SLOW_PERIOD_SECONDS = 7.3;
	private static final double MEDIUM_PERIOD_SECONDS = 2.9;
	private static final double FLUTTER_HZ = 5.5;
	/** How quickly the random walk wanders, and how strongly it is pulled back to the middle. */
	private static final double WANDER_RATE = 0.9;
	private static final double WANDER_PULL = 0.35;

	private final Random random;
	private final double slowPhase;
	private final double mediumPhase;
	private double wander;

	public Wobble(long seed) {
		random = new Random(seed);
		slowPhase = random.nextDouble() * Math.PI * 2;
		mediumPhase = random.nextDouble() * Math.PI * 2;
	}

	/**
	 * Advances the random walk by {@code deltaSeconds} and returns the playback speed at {@code timeSeconds}.
	 *
	 * @param baseSpeed average speed, 1 being the game's normal speed
	 * @param depth     0 for a steady speed, 1 for the strongest wobble
	 */
	public double speed(double timeSeconds, double deltaSeconds, double baseSpeed, double depth) {
		// Ornstein-Uhlenbeck style walk: random nudges, pulled back towards zero, staying roughly within -1..1
		wander += (random.nextGaussian() * WANDER_RATE * Math.sqrt(deltaSeconds)) - wander * WANDER_PULL * deltaSeconds;
		wander = Math.max(-1.5, Math.min(1.5, wander));

		double drift =
			0.45 * Math.sin(2 * Math.PI * timeSeconds / SLOW_PERIOD_SECONDS + slowPhase) +
			0.2 * Math.sin(2 * Math.PI * timeSeconds / MEDIUM_PERIOD_SECONDS + mediumPhase) +
			0.35 * wander;
		double flutter = Math.sin(2 * Math.PI * timeSeconds * FLUTTER_HZ);

		return baseSpeed * (1 + depth * (drift * MAX_DRIFT + flutter * FLUTTER));
	}
}
