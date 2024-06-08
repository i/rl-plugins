package com.github.i.autotags;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.http.api.item.ItemEquipmentStats;

import javax.inject.Inject;
import java.awt.*;
import java.lang.reflect.Type;
import java.util.List;
import java.util.*;
import java.util.stream.Stream;

@Slf4j
@PluginDescriptor(name = "000-gear-tags")
public class AutoTagsPlugin extends Plugin {
	@Inject
	private Gson gson;

	private Map<CombatType, Set<String>> overrides;

	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	@Inject
	OverlayManager overlayManager;

	@Inject
	AutoTagsOverlay overlay;

	@Override
	protected void startUp() {
		overlayManager.add(overlay);
		reloadOverrides();
	}

	@Override
	protected void shutDown() {
		overlayManager.remove(overlay);
	}

	@Inject
	AutoTagsConfig config;

	@Inject ConfigManager configManager;

	@Provides
	AutoTagsConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(AutoTagsConfig.class);
	}

	private Tag tagForCombatType(CombatType combatType) {
		switch(combatType) {
			case MAGIC:
				return new Tag(config.magicColor());
			case MELEE:
				return new Tag(config.meleeColor());
			case RANGE:
				return new Tag(config.rangedColor());
			case SPECIAL:
				return new Tag(config.specialColor());
			default:
				return Tag.NONE;
		}
	}

	public Tag getTag(int itemId) {
		return tagForCombatType(classifyItem(itemId));
	}

	private CombatType checkOverride(int itemId) {
		var itemName = itemManager.getItemComposition(itemId).getName();

		return overrides
				.entrySet()
				.stream()
				.filter(kv -> kv.getValue().contains(itemName))
				.findFirst()
				.map(Map.Entry::getKey)
				.orElse(CombatType.UNKNOWN);

	}

	/*
		Classifies an item as special, magic, melee, ranged, or ignore.
		Order of precedence for determining item type:
			1. Manual override
			2. Damage bonus
			3. Accuracy bonus

		This handles most uses cases aside from a few niche cases, such as:
			- toxic staff of the dead
			- void equipment (might get around to this one day...)
	 */
	private CombatType classifyItem(int itemId) {
		// allow overriding items that cannot be equipped
		var override = checkOverride(itemId);
		if (override != CombatType.UNKNOWN) {
			return override;
		}

		var itemStats = itemManager.getItemStats(itemId, false);

		if (itemStats == null) {
			return CombatType.UNKNOWN;
		}

		if (!itemStats.isEquipable()) {
			return CombatType.UNKNOWN;
		}

		ItemEquipmentStats itemEquipmentStats = itemStats.getEquipment();

		var damageBonuses = Stream.of(
						new Stat(CombatType.MELEE, itemEquipmentStats.getStr()),
						new Stat(CombatType.RANGE, itemEquipmentStats.getRstr()),
						new Stat(CombatType.MAGIC, itemEquipmentStats.getMdmg()))
				.max(Comparator.comparingInt(Stat::getBonus))
				.flatMap(x -> Optional.ofNullable(x.bonus > 0 ? x.type : null));

		if (damageBonuses.isPresent()) {
			return damageBonuses.get();
		}

		Optional<CombatType> combatType = List.of(
						new Stat(CombatType.MAGIC, itemEquipmentStats.getAmagic()),
						new Stat(CombatType.RANGE, itemEquipmentStats.getArange()),
						new Stat(CombatType.MELEE, itemEquipmentStats.getAcrush()),
						new Stat(CombatType.MELEE, itemEquipmentStats.getAslash()),
						new Stat(CombatType.MELEE, itemEquipmentStats.getAstab()))
				.stream()
				.max(Comparator.comparingInt(Stat::getBonus))
				.flatMap(x -> Optional.ofNullable(x.bonus > 0 ? x.type : null));

		return combatType.orElse(CombatType.UNKNOWN);
	}



	private void reloadOverrides() {
		Type type = new TypeToken<Map<CombatType, Set<String>>>(){}.getType();
		var json = configManager.getConfiguration(AutoTagsConfig.GROUP, "overrides");
		Map<CombatType, Set<String>> overrides = gson.fromJson(json, type);
		if (overrides == null) {
			overrides = Map.of();
		}
		this.overrides = overrides;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged) {
		if (!configChanged.getGroup().equals(AutoTagsConfig.GROUP)) {
			return;
		}
		overlay.invalidateCache();
		reloadOverrides();
	}

	@AllArgsConstructor
	private static class Stat {
		@Getter
		CombatType type;

		@Getter
		int bonus;
	}

	@Subscribe
	// Adapted from https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/plugins/inventorytags/InventoryTagsPlugin.java#L166
	public void onMenuOpened(final MenuOpened event) {
		if (!client.isKeyPressed(KeyCode.KC_SHIFT)) {
			return;
		}

		final MenuEntry[] entries = event.getMenuEntries();
		for (int idx = entries.length - 1; idx >= 0; --idx)
		{
			final MenuEntry entry = entries[idx];
			final Widget w = entry.getWidget();

			if (w != null && WidgetUtil.componentToInterface(w.getId()) == InterfaceID.INVENTORY
					&& "Examine".equals(entry.getOption()) && entry.getIdentifier() == 10)
			{
				final int itemId = w.getItemId();
				final String itemName = itemManager.getItemComposition(itemId).getName();
				final CombatType selectedType = classifyItem(itemId);

				final MenuEntry parent = client.createMenuEntry(idx)
						.setOption("Set combat type")
						.setTarget(entry.getTarget())
						.setType(MenuAction.RUNELITE_SUBMENU);

				Set<CombatType> choices = new HashSet<>(CombatType.CHOICE_LIST);
				choices.remove(selectedType);

				for (CombatType type : choices) {
						var optionStr = colorForType(type)
								.map(color -> ColorUtil.prependColorTag(type.toString(), color))
								.orElse(type.toString());
						client.createMenuEntry(idx)
								.setOption(optionStr)
								.setType(MenuAction.RUNELITE)
								.setParent(parent)
								.onClick(e -> override(itemName, selectedType, e.getOption()));
				}
			}
		}
	}

	private Optional<Color> colorForType(CombatType combatType) {
		return Optional.ofNullable(tagForCombatType(combatType).color);
	}

	void override(String itemName, CombatType oldType, String newTypeStr) {
		CombatType newType = CombatType.fromString(newTypeStr);

		// need to check because the old type could be unknown
		if (overrides.containsKey(oldType)) {
			overrides.get(oldType).remove(itemName);
		}

		var newTypeItems = overrides.getOrDefault(newType, Set.of());
		newTypeItems.add(itemName);

		var json = gson.toJson(overrides);
		configManager.setConfiguration(AutoTagsConfig.GROUP, "overrides", json);
	}
}
