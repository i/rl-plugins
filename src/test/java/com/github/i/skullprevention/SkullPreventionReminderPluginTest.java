package com.github.i.skullprevention;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class SkullPreventionReminderPluginTest {
	public static void main(String[] args) throws Exception {
		ExternalPluginManager.loadBuiltin(SkullPreventionIconPlugin.class);
		RuneLite.main(args);
	}
}