package com.github.i.platz;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("platz")
public interface PlatzConfig extends Config
{
	@ConfigItem(
			keyName = "abbreviate",
			name = "Abbreviate large numbers values",
			description = "Abbreviate values (e.g. 2,147,483,647 is shown as 2.147B)"
	)
	default boolean abbreviate() { return false; }

	@ConfigItem(
			keyName = "threshold",
			name = "Abbreviation threshold",
			description = "Minimum value to abbreviate"
	)
	default Threshold threshold() { return Threshold._100m; }

	static enum Threshold {
		_1k,
		_10k,
		_100k,
		_1m,
		_10m,
		_100m,
		_1b,
		_max;

		long value() {
			switch (this) {
				case _1k: return 1000;
				case _10k: return 10_000;
				case _100k: return 100_000;
				case _1m: return 1_000_000;
				case _10m: return 10_000_000;
				case _100m: return 100_000_000;
				case _1b: return 1_000_000_000;
				case _max:
				default: return Integer.MAX_VALUE;
			}
		}

	}
}
