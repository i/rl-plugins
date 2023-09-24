package com.github.i.fuzzybanksearch;

import com.github.i.fuzzybanksearch.matcher.FZFMatcher;
import com.github.i.fuzzybanksearch.matcher.JaroWinklerMatcher;
import com.github.i.fuzzybanksearch.matcher.Matcher;
import com.github.i.fuzzybanksearch.matcher.fzf.FuzzyMatcherV1;
import com.github.i.fuzzybanksearch.matcher.fzf.OrderBy;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.bank.BankSearch;

import javax.inject.Inject;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@PluginDescriptor(name = "Fuzzy Bank Search")
public class FuzzyBankSearchPlugin extends Plugin {
	@Inject
	private Client client;

	@Inject
	private FuzzyBankSearchConfig config;

	@Inject
	private KeyManager keyManager;

	@Inject
	BankSearch bankSearch;

	@Inject
	ItemManager itemManager;

	@Override
	protected void startUp() {
		keyManager.registerKeyListener(searchHotkeyListener);
	}

	@Override
	protected void shutDown() {
		keyManager.unregisterKeyListener(searchHotkeyListener);
	}

	@Provides
	FuzzyBankSearchConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(FuzzyBankSearchConfig.class);
	}

	private final static String BANK_SEARCH_FILTER_EVENT = "bankSearchFilter";

	// these two fields are used to cache results
	String oldQuery = "";
	Set<String> cachedResults = null;

	private Matcher fzfMatcher = null;
	private Matcher jaroWinklerMatcher = null;

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event) {
		// reindex every time the bank is opened
		if (event.getContainerId() == InventoryID.BANK.getId()) {
			this.itemIdsToNames = Arrays.stream(client.getItemContainer(InventoryID.BANK).getItems())
					.map(item -> this.itemManager.getItemComposition(item.getId()))
					.collect(Collectors.toMap(
							ItemComposition::getId,
							ItemComposition::getName,
							(x, __) -> x));

			this.fzfMatcher = new FZFMatcher(this.itemIdsToNames.values());
			this.jaroWinklerMatcher = new JaroWinklerMatcher(this.itemIdsToNames.values());
		}
	}

	private Map<Integer, String> itemIdsToNames = null;


	public boolean filterBankSearch(final int itemId, final String query) {
		// previous results are cached until in text input changes.
		// the client will try to update every 40ms
		if (!oldQuery.equals(query) || cachedResults == null) {
			Matcher matcher;
			if (config.useFzf()) {
				matcher = fzfMatcher;
			} else {
				matcher = jaroWinklerMatcher;
			}

			this.cachedResults = matcher.match(query, config.limit());
			oldQuery = query;
		}

		return cachedResults.contains(itemIdsToNames.get(itemId));
	}

	private final KeyListener searchHotkeyListener = new KeyListener() {
		@Override
		public void keyPressed(KeyEvent e) {
			if (config.hotkey().matches(e)) {
				Widget bankContainer = client.getWidget(WidgetInfo.BANK_ITEM_CONTAINER);
				if (bankContainer != null && !bankContainer.isSelfHidden())
				{
					bankSearch.initSearch();
					e.consume();
				}
			}
		}

		@Override
		public void keyTyped(KeyEvent e) { }

		@Override
		public void keyReleased(KeyEvent e) { }
	};


	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent event) {
		int[] intStack = client.getIntStack();
		String[] stringStack = client.getStringStack();
		int intStackSize = client.getIntStackSize();
		int stringStackSize = client.getStringStackSize();

		if (event.getEventName().equals(BANK_SEARCH_FILTER_EVENT)) {
			int itemId = intStack[intStackSize - 1];
			String query = stringStack[stringStackSize - 1];
			intStack[intStackSize - 2] = filterBankSearch(itemId, query) ? 1 : 0;
		}
	}
}
