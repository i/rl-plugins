package com.github.i.lofi.config;

import lombok.RequiredArgsConstructor;

/**
 * Post-processing art styles applied to the 3D scene before the UI is composited.
 * The shader id must match the STYLE_* constants in painterly_frag.glsl.
 */
@RequiredArgsConstructor
public enum PainterlyStyle {
	OFF("Off", 0),
	SQUIGGLE("Squiggle Vision", 1),
	MS_PAINT("MS Paint", 2),
	OIL("Oil painting", 3),
	ACRYLIC("Acrylic painting", 4),
	// Kept as WATERCOLOR so saved settings from when it was the watercolor style still load
	WATERCOLOR("Landscape painting", 5);

	private final String name;

	public final int shaderId;

	@Override
	public String toString() {
		return name;
	}
}
