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
			keyName = "largeNumberCutoff",
			name = "Large number cutoff",
			description = "Determines the cutoff for large numbers"
	)
	default long largeNumberCutoff() { return 100_000_000L; }
}
