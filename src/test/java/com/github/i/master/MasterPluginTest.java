package com.github.i.master;

import com.github.i.fuzzybanksearch.FuzzyBankSearchPlugin;
import com.github.i.platz.PlatzPlugin;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class MasterPluginTest {
	public static void main(String[] args) throws Exception {
		ExternalPluginManager.loadBuiltin(
				FuzzyBankSearchPlugin.class,
				PlatzPlugin.class);
		RuneLite.main(args);
	}
}