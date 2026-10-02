package com.github.i.lofi.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Control;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import lombok.extern.slf4j.Slf4j;

/**
 * Stands in for the game's audio output line and plays everything written to it at a wobbling, slower speed.
 * <p>
 * The game renders audio only when its line reports free space. This line buffers what the game writes and drains
 * that buffer at {@link #speed}, resampling into the real line. Draining slower makes the game render slower, so the
 * music itself slows down, pitch and tempo together, without a backlog building up.
 * <p>
 * Only 16-bit signed PCM is resampled, which is what the game uses. Anything else passes through untouched.
 */
@Slf4j
public final class LofiLine implements SourceDataLine {
	private static final int CHUNK_FRAMES = 256;

	private final SourceDataLine real;
	private final AudioFormat format;
	private final int channels;
	private final int frameSize;
	private final boolean bigEndian;
	private final boolean resampling;

	// Buffered samples, as floats in -1..1, interleaved by channel. Guarded by this.
	private final float[] ring;
	private final int capacityFrames;
	private int headFrame;
	private int filledFrames;
	/** Read position in frames from headFrame. Frame 0 is kept as history for interpolation. */
	private double readPosition = 1;

	private final Wobble wobble = new Wobble(System.nanoTime());
	private volatile double baseSpeed = 1;
	private volatile double wobbleDepth;
	private final TapeTone tone;

	private final Thread feeder;
	private volatile boolean running = true;

	public LofiLine(SourceDataLine real) {
		this.real = real;
		format = real.getFormat();
		channels = format.getChannels();
		frameSize = format.getFrameSize();
		bigEndian = format.isBigEndian();
		resampling = format.getEncoding() == AudioFormat.Encoding.PCM_SIGNED &&
			format.getSampleSizeInBits() == 16 &&
			frameSize == channels * 2;

		// Match the real line's size, since the game compares available() against the size it asked for
		capacityFrames = Math.max(real.getBufferSize() / Math.max(frameSize, 1), CHUNK_FRAMES * 4);
		ring = new float[capacityFrames * channels];
		tone = new TapeTone(channels, format.getSampleRate());

		feeder = new Thread(this::feed, "Lo-Fi audio");
		feeder.setDaemon(true);
		feeder.setPriority(Thread.MAX_PRIORITY);
		if (resampling)
			feeder.start();
	}

	/**
	 * @param baseSpeed average playback speed, 1 being normal
	 * @param depth     wobble strength from 0 to 1
	 */
	public void setSpeed(double baseSpeed, double depth) {
		this.baseSpeed = baseSpeed;
		this.wobbleDepth = depth;
	}

	/**
	 * @param lowCutHz   frequencies below this are rolled off, 0 for off
	 * @param highCutHz  frequencies above this are rolled off, 0 for off
	 * @param saturation tape drive from 0 to 1
	 */
	public void setTone(
		float lowCutHz,
		float highCutHz,
		float saturation,
		float grit
	) {
		tone.set(lowCutHz, highCutHz, saturation, grit);
	}

	/**
	 * Stops processing and returns the real line, still open, so the game can use it directly again.
	 * Audio still buffered here is dropped.
	 */
	public SourceDataLine detach() {
		stopFeeder();
		return real;
	}

	public SourceDataLine getReal() {
		return real;
	}

	private void stopFeeder() {
		running = false;
		synchronized (this) {
			notifyAll();
		}
		if (feeder.isAlive() && Thread.currentThread() != feeder) {
			try {
				feeder.join(500);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
	}

	// ---------------------------------------------------------------- game side

	@Override
	public int write(byte[] bytes, int offset, int length) {
		if (!resampling)
			return real.write(bytes, offset, length);

		int frames = length / frameSize;
		int written = 0;
		synchronized (this) {
			while (written < frames && running) {
				while (filledFrames >= capacityFrames && running) {
					try {
						wait();
					} catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
						return written * frameSize;
					}
				}

				for (; written < frames && filledFrames < capacityFrames; written++, filledFrames++) {
					int ringIndex = ((headFrame + filledFrames) % capacityFrames) * channels;
					int byteIndex = offset + written * frameSize;
					for (int c = 0; c < channels; c++, byteIndex += 2)
						ring[ringIndex + c] = readSample(bytes, byteIndex);
				}
				notifyAll();
			}
		}
		return written * frameSize;
	}

	@Override
	public int available() {
		if (!resampling)
			return real.available();
		synchronized (this) {
			return (capacityFrames - filledFrames) * frameSize;
		}
	}

	private float readSample(byte[] bytes, int index) {
		int low = bytes[index + (bigEndian ? 1 : 0)] & 0xFF;
		int high = bytes[index + (bigEndian ? 0 : 1)];
		return (short) (high << 8 | low) / 32768f;
	}

	private void writeSample(byte[] bytes, int index, float sample) {
		int value = Math.round(Math.max(-1, Math.min(1, sample)) * 32767);
		bytes[index + (bigEndian ? 1 : 0)] = (byte) value;
		bytes[index + (bigEndian ? 0 : 1)] = (byte) (value >> 8);
	}

	// ---------------------------------------------------------------- output side

	private void feed() {
		byte[] out = new byte[CHUNK_FRAMES * frameSize];
		float[] frame = new float[channels];
		double seconds = 0;
		double chunkSeconds = CHUNK_FRAMES / format.getSampleRate();

		try {
			while (running) {
				double speed = wobble.speed(seconds, chunkSeconds, baseSpeed, wobbleDepth);
				seconds += chunkSeconds;

				int produced = 0;
				synchronized (this) {
					for (; produced < CHUNK_FRAMES && running; produced++) {
						// Interpolation needs one frame before the read position and two after it. When the game
						// hasn't written enough, send what's ready, or wait briefly if there's nothing yet.
						if (filledFrames < (int) readPosition + 3) {
							if (produced == 0)
								wait(20);
							break;
						}

						interpolate(frame);
						if (tone.isActive())
							tone.process(frame);
						for (int c = 0; c < channels; c++)
							writeSample(out, produced * frameSize + c * 2, frame[c]);

						readPosition += speed;
						// Drop frames that are no longer needed as history
						int consumed = (int) readPosition - 1;
						if (consumed > 0) {
							headFrame = (headFrame + consumed) % capacityFrames;
							filledFrames -= consumed;
							readPosition -= consumed;
							notifyAll();
						}
					}
				}

				// Blocks while the real line is full, which paces this loop to real time
				if (produced > 0)
					real.write(out, 0, produced * frameSize);
			}
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		} catch (Exception ex) {
			log.warn("Lo-fi audio stopped", ex);
		}
	}

	/** Cubic Hermite interpolation between the frames around readPosition. Must hold this. */
	private void interpolate(float[] out) {
		int base = (int) readPosition;
		float t = (float) (readPosition - base);
		int i0 = ((headFrame + base - 1) % capacityFrames) * channels;
		int i1 = ((headFrame + base) % capacityFrames) * channels;
		int i2 = ((headFrame + base + 1) % capacityFrames) * channels;
		int i3 = ((headFrame + base + 2) % capacityFrames) * channels;
		for (int c = 0; c < channels; c++) {
			float y0 = ring[i0 + c], y1 = ring[i1 + c], y2 = ring[i2 + c], y3 = ring[i3 + c];
			float c1 = 0.5f * (y2 - y0);
			float c2 = y0 - 2.5f * y1 + 2 * y2 - 0.5f * y3;
			float c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);
			out[c] = ((c3 * t + c2) * t + c1) * t + y1;
		}
	}

	// ---------------------------------------------------------------- lifecycle, delegated to the real line

	@Override
	public void open(AudioFormat format, int bufferSize) throws LineUnavailableException {
		real.open(format, bufferSize);
	}

	@Override
	public void open(AudioFormat format) throws LineUnavailableException {
		real.open(format);
	}

	@Override
	public void open() throws LineUnavailableException {
		real.open();
	}

	@Override
	public void close() {
		stopFeeder();
		real.close();
	}

	@Override
	public void drain() {
		synchronized (this) {
			while (resampling && running && filledFrames > (int) readPosition + 2) {
				try {
					wait(20);
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
		real.drain();
	}

	@Override
	public void flush() {
		synchronized (this) {
			headFrame = 0;
			filledFrames = 0;
			readPosition = 1;
			notifyAll();
		}
		real.flush();
	}

	@Override
	public void start() {
		real.start();
	}

	@Override
	public void stop() {
		real.stop();
	}

	@Override
	public boolean isRunning() {
		return real.isRunning();
	}

	@Override
	public boolean isActive() {
		return real.isActive();
	}

	@Override
	public AudioFormat getFormat() {
		return format;
	}

	@Override
	public int getBufferSize() {
		return resampling ? capacityFrames * frameSize : real.getBufferSize();
	}

	@Override
	public int getFramePosition() {
		return real.getFramePosition();
	}

	@Override
	public long getLongFramePosition() {
		return real.getLongFramePosition();
	}

	@Override
	public long getMicrosecondPosition() {
		return real.getMicrosecondPosition();
	}

	@Override
	public float getLevel() {
		return real.getLevel();
	}

	@Override
	public Line.Info getLineInfo() {
		return real.getLineInfo();
	}

	@Override
	public boolean isOpen() {
		return real.isOpen();
	}

	@Override
	public Control[] getControls() {
		return real.getControls();
	}

	@Override
	public boolean isControlSupported(Control.Type control) {
		return real.isControlSupported(control);
	}

	@Override
	public Control getControl(Control.Type control) {
		return real.getControl(control);
	}

	@Override
	public void addLineListener(LineListener listener) {
		real.addLineListener(listener);
	}

	@Override
	public void removeLineListener(LineListener listener) {
		real.removeLineListener(listener);
	}
}
