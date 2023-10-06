package com.github.i.skullprevention;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("Skull prevention")
public interface SkullPreventionIconConfig extends Config {
	@ConfigItem(
		keyName = "pvpOnly",
		name = "PVP only",
		description = "Only draw overlay in pvp"
	)
	default boolean pvpOnly() { return false; }

	enum Mode { BOTH, DISABLED_ONLY, ENABLED_ONLY }
	@ConfigItem(
		keyName = "mode",
		name = "Mode",
		description = "Show the icon only when protection disabled, enabled or both"
	)
	default Mode mode() { return Mode.BOTH; }

	String SIZE_KEY = "size";
	@ConfigItem(
			keyName = SIZE_KEY,
			name = "Size",
			description = "Size"
	)
	default int size() { return 60; }
}
