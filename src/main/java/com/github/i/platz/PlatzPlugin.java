package com.github.i.platz;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;
import java.text.DecimalFormat;

@Slf4j
@PluginDescriptor(name = "platz")
public class PlatzPlugin extends Plugin {
	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;


	@Override
	protected void startUp() {
	}

	@Override
	protected void shutDown() {
	}

	@Inject
	PlatzConfig config;

	@Provides
	PlatzConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(PlatzConfig.class);
	}

	private static final int MY_TRADE_VALUE_WIDGET_ID = 21954584;
	private static final int OTHER_TRADE_VALUE_WIDGET_ID = 21954587;


	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event) {
		var inventoryId = event.getContainerId();
		int widgetIdToUpdate;

		if (inventoryId == InventoryID.TRADEOTHER.getId()) {
			widgetIdToUpdate = OTHER_TRADE_VALUE_WIDGET_ID;
		} else if (inventoryId == InventoryID.TRADE.getId()) {
			widgetIdToUpdate = MY_TRADE_VALUE_WIDGET_ID;
		} else {
			return;
		}

		var total = 0L;
		for (Item item : client.getItemContainer(inventoryId).getItems()) {
			total += (long) this.itemManager.getItemPrice(item.getId()) * (long) item.getQuantity();
		}

		setTradeValueText(widgetIdToUpdate, total);
	}


	private void setTradeValueText(int widgetId, long value) {
		var formattedNumber = (this.config.abbreviate() && value > 100_000_000L)
				? abbreviateBigNumber(value)
				: String.format("%,d", value);

		var msg =  String.format(
				"%s offer:<br>(Value: <col=ffffff>%s</col> coins)",
				widgetId == MY_TRADE_VALUE_WIDGET_ID ? "Your" : "Their",
				formattedNumber);
		client.getWidget(widgetId).setText(msg);
	}



	private static String[] suffix = new String[]{"","K", "M", "B", "T"};
	private static final int MAX_SHORT_LENGTH = 10;

	@VisibleForTesting
	public static String abbreviateBigNumber(long number) {
		String r = new DecimalFormat("##0E0").format(number);
		r = r.replaceAll("E[0-9]", suffix[Character.getNumericValue(r.charAt(r.length() - 1)) / 3]);
		while (r.length() > MAX_SHORT_LENGTH || r.matches("[0-9]+\\.[a-z]")) {
			r = r.substring(0, r.length() - 2) + r.substring(r.length() - 1);
		}
		return r;
	}

}
