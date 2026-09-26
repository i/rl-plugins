package com.github.i.lofi;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.Arrays;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.HealthBarConfig;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Skill;
import net.runelite.api.SpritePixels;
import net.runelite.api.WorldView;
import net.runelite.client.game.NPCManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * Draws blocky health bars in place of the game's: a row of green blocks for the health left, then a solid red
 * bar for what's missing. The bar is wider for actors with more total hitpoints. The game's bars are hidden by
 * blanking their sprites as the client loads them, see {@link #hideGameBar}. Everything runs on the client thread.
 */
@Singleton
class HealthBarOverlay extends Overlay
{
	static final int HEIGHT = 8;
	/** Game units above the actor's model top where the bar sits, matching where the game draws its own */
	static final int HEAD_CLEARANCE = 15;
	private static final int MIN_WIDTH = 24;
	private static final int MAX_WIDTH = 160;
	/** Blocks are at least this wide, gap included, so small bars don't turn into stripes */
	private static final int MIN_BLOCK_PIXELS = 5;
	/** Players other than you don't share their total hitpoints, so they get this size */
	private static final int UNKNOWN_HITPOINTS = 50;
	private static final Color GREEN = new Color(40, 190, 50);
	private static final Color RED = new Color(190, 30, 25);
	private static final Color INK = new Color(15, 15, 15);

	private final Client client;
	private final NPCManager npcManager;
	private final LofiConfig config;

	@Inject
	HealthBarOverlay(Client client, NPCManager npcManager, LofiConfig config)
	{
		this.client = client;
		this.npcManager = npcManager;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(OverlayPriority.HIGH);
	}

	/** Blanks the game's own bar sprites. They stay blank until {@link Client#resetHealthBarCaches}. */
	static void hideGameBar(HealthBarConfig bar)
	{
		blank(bar.getHealthBarFrontSprite());
		blank(bar.getHealthBarBackSprite());
	}

	private static void blank(SpritePixels sprite)
	{
		if (sprite != null)
		{
			Arrays.fill(sprite.getPixels(), 0);
		}
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		WorldView view = client.getTopLevelWorldView();
		if (view == null || !config.blockyHealthBars())
		{
			return null;
		}
		for (Player player : view.players())
		{
			draw(g, player, player == client.getLocalPlayer() ? client.getRealSkillLevel(Skill.HITPOINTS) : UNKNOWN_HITPOINTS);
		}
		for (NPC npc : view.npcs())
		{
			Integer hitpoints = npcManager.getHealth(npc.getId());
			draw(g, npc, hitpoints != null ? hitpoints : UNKNOWN_HITPOINTS);
		}
		return null;
	}

	private void draw(Graphics2D g, Actor actor, int hitpoints)
	{
		if (actor == null)
		{
			return;
		}
		int ratio = actor.getHealthRatio();
		int scale = actor.getHealthScale();
		// The game only sends health while the bar is showing, -1 otherwise
		if (ratio < 0 || scale <= 0)
		{
			return;
		}
		Point anchor = actor.getCanvasTextLocation(g, "", actor.getLogicalHeight() + HEAD_CLEARANCE);
		if (anchor == null)
		{
			return;
		}

		int width = width(hitpoints);
		int blocks = blocks(width, hitpoints);
		int filled = filledBlocks(ratio, scale, blocks);
		int left = anchor.getX() - width / 2;
		int top = anchor.getY() - HEIGHT;

		g.setColor(INK);
		g.fillRect(left - 1, top - 1, width + 2, HEIGHT + 2);
		g.setColor(RED);
		g.fillRect(left, top, width, HEIGHT);
		for (int i = 0; i < filled; i++)
		{
			int x0 = left + i * width / blocks;
			int x1 = left + (i + 1) * width / blocks;
			g.setColor(GREEN);
			// A one pixel ink gap after each block, except against the red, which has its own edge
			g.fillRect(x0, top, x1 - x0 - (i + 1 < blocks ? 1 : 0), HEIGHT);
		}
		if (filled > 0 && filled < blocks)
		{
			g.setColor(INK);
			g.fillRect(left + filled * width / blocks - 1, top, 1, HEIGHT);
		}
	}

	/** Bar width in pixels: grows with the square root of total hitpoints, so bosses aren't screen-wide */
	static int width(int hitpoints)
	{
		int width = (int) Math.round(8 * Math.sqrt(Math.max(hitpoints, 1)));
		return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width));
	}

	/** One block per hitpoint when they fit, otherwise as many as fit at the minimum block width */
	static int blocks(int width, int hitpoints)
	{
		return Math.max(1, Math.min(Math.max(hitpoints, 1), width / MIN_BLOCK_PIXELS));
	}

	/** Green blocks for a health ratio. Anything alive shows at least one, and only full health fills the bar. */
	static int filledBlocks(int ratio, int scale, int blocks)
	{
		if (ratio <= 0)
		{
			return 0;
		}
		if (ratio >= scale)
		{
			return blocks;
		}
		int filled = (int) Math.ceil((double) ratio * blocks / scale);
		return Math.max(1, Math.min(blocks - 1, filled));
	}
}
