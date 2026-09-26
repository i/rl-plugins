package com.github.i.lofi.config;

import lombok.RequiredArgsConstructor;

/**
 * How players and NPCs are turned into flat sprites.
 */
@RequiredArgsConstructor
public enum SpriteMode {
	OFF("Off", 0),
	EIGHT_DIRECTIONS("8 directions", 8),
	TWO_DIRECTIONS("2 directions (paper)", 2);

	private final String name;
	public final int directions;

	@Override
	public String toString() {
		return name;
	}
}
