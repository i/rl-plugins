package com.github.i.lofi;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.Point;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * Draws hitsplats as blocky tiles to match {@link HealthBarOverlay}. The API can't hide the game's own hitsplats,
 * so each tile is drawn over the slot the game uses, big enough to cover its splat. Everything runs on the client
 * thread.
 */
@Singleton
class HitsplatOverlay extends Overlay
{
	/** The game shows up to 4 hitsplats per actor, in these slots around the middle of the model */
	static final int SLOTS = 4;
	private static final int[] SLOT_X = {0, 0, -15, 15};
	private static final int[] SLOT_Y = {0, -20, -10, -10};
	/** At least as big as the game's splat sprites, so they're fully covered */
	private static final int MIN_WIDTH = 28;
	private static final int HEIGHT = 24;
	private static final int PADDING_X = 5;
	private static final Color INK = new Color(15, 15, 15);
	private static final Color TEXT = new Color(250, 250, 245);
	private static final Color MAX_HIT = new Color(240, 190, 40);
	private static final Color BEVEL = new Color(255, 255, 255, 50);

	private final Client client;
	private final LofiConfig config;
	private final Map<Actor, Hitsplat[]> hitsplats = new WeakHashMap<>();

	@Inject
	HitsplatOverlay(Client client, LofiConfig config)
	{
		this.client = client;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(OverlayPriority.HIGH);
	}

	void add(Actor actor, Hitsplat hitsplat)
	{
		Hitsplat[] slots = hitsplats.computeIfAbsent(actor, a -> new Hitsplat[SLOTS]);
		slots[slotFor(slots, client.getGameCycle())] = hitsplat;
	}

	void clear()
	{
		hitsplats.clear();
	}

	/** Like the game: the first empty or expired slot, otherwise the one that would disappear soonest */
	static int slotFor(Hitsplat[] slots, int gameCycle)
	{
		int soonest = 0;
		for (int i = 0; i < slots.length; i++)
		{
			if (slots[i] == null || slots[i].getDisappearsOnGameCycle() <= gameCycle)
			{
				return i;
			}
			if (slots[i].getDisappearsOnGameCycle() < slots[soonest].getDisappearsOnGameCycle())
			{
				soonest = i;
			}
		}
		return soonest;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (hitsplats.isEmpty() || !config.blockyHealthBars())
		{
			return null;
		}

		g.setFont(FontManager.getRunescapeBoldFont());
		int cycle = client.getGameCycle();
		Iterator<Map.Entry<Actor, Hitsplat[]>> it = hitsplats.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<Actor, Hitsplat[]> entry = it.next();
			Actor actor = entry.getKey();
			Hitsplat[] slots = entry.getValue();
			boolean any = false;
			Point anchor = actor == null ? null : actor.getCanvasTextLocation(g, "", actor.getLogicalHeight() / 2);
			for (int i = 0; i < SLOTS; i++)
			{
				Hitsplat hitsplat = slots[i];
				if (hitsplat == null || hitsplat.getDisappearsOnGameCycle() <= cycle)
				{
					slots[i] = null;
					continue;
				}
				any = true;
				if (anchor != null)
				{
					drawTile(g, hitsplat, anchor.getX() + SLOT_X[i], anchor.getY() + SLOT_Y[i]);
				}
			}
			if (!any)
			{
				it.remove();
			}
		}
		return null;
	}

	private void drawTile(Graphics2D g, Hitsplat hitsplat, int centerX, int centerY)
	{
		FontMetrics metrics = g.getFontMetrics();
		String text = Integer.toString(hitsplat.getAmount());
		int width = Math.max(MIN_WIDTH, metrics.stringWidth(text) + PADDING_X * 2);
		int left = centerX - width / 2;
		int top = centerY - HEIGHT / 2;
		// Other players' hits are darker, like the game's tinted splats. Still opaque, so the game's splat is covered.
		Color fill = fill(hitsplat);
		if (hitsplat.isOthers())
		{
			fill = fill.darker();
		}

		g.setColor(INK);
		g.fillRect(left - 1, top - 1, width + 2, HEIGHT + 2);
		g.setColor(fill);
		g.fillRect(left, top, width, HEIGHT);
		// A lighter top row gives the tile a little bevel, like the health bar's blocks
		g.setColor(BEVEL);
		g.fillRect(left, top, width, 2);
		if (isMaxHit(hitsplat.getHitsplatType()))
		{
			g.setColor(MAX_HIT);
			g.setStroke(new BasicStroke(2f));
			g.drawRect(left, top, width - 1, HEIGHT - 1);
		}

		int x = left + (width - metrics.stringWidth(text)) / 2;
		int y = top + (HEIGHT + metrics.getAscent() - metrics.getDescent()) / 2;
		g.setColor(INK);
		g.drawString(text, x + 1, y + 1);
		g.setColor(TEXT);
		g.drawString(text, x, y);
	}

	static Color fill(Hitsplat hitsplat)
	{
		if (hitsplat.getAmount() == 0 && !isHeal(hitsplat.getHitsplatType()))
		{
			return new Color(50, 90, 200);
		}
		switch (hitsplat.getHitsplatType())
		{
			case HitsplatID.POISON:
				return new Color(40, 150, 40);
			case HitsplatID.VENOM:
				return new Color(30, 110, 90);
			case HitsplatID.DISEASE:
			case HitsplatID.DISEASE_BLOCKED:
				return new Color(200, 170, 40);
			case HitsplatID.HEAL:
			case HitsplatID.SANITY_RESTORE:
				return new Color(210, 90, 170);
			case HitsplatID.PRAYER_DRAIN:
			case HitsplatID.CYAN_UP:
			case HitsplatID.CYAN_DOWN:
			case HitsplatID.DAMAGE_ME_CYAN:
			case HitsplatID.DAMAGE_OTHER_CYAN:
			case HitsplatID.DAMAGE_MAX_ME_CYAN:
				return new Color(40, 160, 190);
			case HitsplatID.DAMAGE_ME_ORANGE:
			case HitsplatID.DAMAGE_OTHER_ORANGE:
			case HitsplatID.DAMAGE_MAX_ME_ORANGE:
			case HitsplatID.BURN:
				return new Color(215, 110, 30);
			case HitsplatID.DAMAGE_ME_YELLOW:
			case HitsplatID.DAMAGE_OTHER_YELLOW:
			case HitsplatID.DAMAGE_MAX_ME_YELLOW:
				return new Color(200, 170, 30);
			case HitsplatID.DAMAGE_ME_WHITE:
			case HitsplatID.DAMAGE_OTHER_WHITE:
			case HitsplatID.DAMAGE_MAX_ME_WHITE:
				return new Color(150, 150, 150);
			case HitsplatID.CORRUPTION:
			case HitsplatID.DOOM:
			case HitsplatID.SANITY_DRAIN:
				return new Color(110, 50, 140);
			default:
				return new Color(190, 30, 25);
		}
	}

	private static boolean isHeal(int type)
	{
		return type == HitsplatID.HEAL || type == HitsplatID.SANITY_RESTORE;
	}

	private static boolean isMaxHit(int type)
	{
		switch (type)
		{
			case HitsplatID.DAMAGE_MAX_ME:
			case HitsplatID.DAMAGE_MAX_ME_CYAN:
			case HitsplatID.DAMAGE_MAX_ME_ORANGE:
			case HitsplatID.DAMAGE_MAX_ME_YELLOW:
			case HitsplatID.DAMAGE_MAX_ME_WHITE:
			case HitsplatID.DAMAGE_MAX_ME_POISE:
				return true;
			default:
				return false;
		}
	}
}
