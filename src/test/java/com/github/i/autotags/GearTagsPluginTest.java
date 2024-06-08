package com.github.i.autotags;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class GearTagsPluginTest {
	public static void main(String[] args) throws Exception {
		ExternalPluginManager.loadBuiltin(AutoTagsPlugin.class);
		RuneLite.main(args);
	}
}