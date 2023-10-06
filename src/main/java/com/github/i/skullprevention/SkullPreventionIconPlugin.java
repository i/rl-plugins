package com.github.i.skullprevention;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Varbits;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;

@Slf4j
@PluginDescriptor(name = "skull-prevention-reminder")
public class SkullPreventionIconPlugin extends Plugin {
	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private SkullPreventionIconOverlay overlay;

	@Override
	protected void startUp() {
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown() {
		overlayManager.remove(overlay);
	}

	private final int SKULL_PREVENTION_VARBIT_ID = 13131;

	private boolean skullPreventionEnabled = false;
	public boolean skullPreventionEnabled() {
		return skullPreventionEnabled;
	}

	@Subscribe
	void onVarbitChanged(VarbitChanged varbitChanged) {
		if (varbitChanged.getVarbitId() == SKULL_PREVENTION_VARBIT_ID) {
			skullPreventionEnabled =  (varbitChanged.getValue() != 0);
		}
	}

	public boolean isInPVP() {
		return client.getVarbitValue(Varbits.IN_WILDERNESS) == 1 ||
				client.getVarbitValue(Varbits.PVP_SPEC_ORB) == 1;
	}

	@Inject
	SkullPreventionIconConfig config;

	@Provides
	SkullPreventionIconConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(SkullPreventionIconConfig.class);
	}

	@Subscribe
	void onConfigChanged(ConfigChanged configChanged) {
		if (configChanged.getKey().equals(SkullPreventionIconConfig.SIZE_KEY)) {
			overlay.reloadImages(config.size());
		}
	}
}
