package com.github.i.lofi.config;

import lombok.RequiredArgsConstructor;

/**
 * Intermediate buffers of the painterly pass, for tuning and troubleshooting.
 * The shader id must match the DEBUG_* constants in painterly_frag.glsl.
 */
@RequiredArgsConstructor
public enum PainterlyDebugView {
	OFF("Off", 0),
	SCENE("Unstyled frame", 1),
	OUTLINES("Outline mask", 2),
	DISTANCE("Distance", 3);

	private final String name;

	public final int shaderId;

	@Override
	public String toString() {
		return name;
	}
}
