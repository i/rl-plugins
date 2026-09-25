package com.github.i.lofi;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Launches the dev client with Lo-Fi loaded. Turn off the built-in GPU plugin in the client, since only one
 * GPU renderer can run at a time.
 */
public class LofiPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(LofiPlugin.class);
		RuneLite.main(args);
	}
}
