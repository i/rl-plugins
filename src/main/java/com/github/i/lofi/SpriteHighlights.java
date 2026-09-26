package com.github.i.lofi;

import java.awt.Color;
import java.lang.reflect.Field;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.client.game.npcoverlay.HighlightedNpc;
import net.runelite.client.game.npcoverlay.NpcOverlayService;

/**
 * Collects which characters to outline this frame, for the art style pass to draw around their sprites. Other
 * plugins outline the real 3D model at its real facing, which doesn't line up with a flattened sprite, so while
 * sprites are on this stands in for Interact Highlight and NPC Indicators' outlines. The pass finds each
 * character's pixels through its id, see {@link LofiPlugin#characterId}.
 */
@Slf4j
@Singleton
class SpriteHighlights
{
	/** Must match MAX_HIGHLIGHTS in painterly_frag.glsl */
	static final int MAX = 32;
	private static final Color HOVER = new Color(0, 255, 255, 144);
	private static final Color INTERACT = new Color(255, 0, 0, 144);

	private final Client client;
	private final NpcOverlayService npcOverlayService;
	private final LofiConfig config;

	/** Character ids, and their colors as rgba quadruples */
	private final int[] ids = new int[MAX];
	private final float[] colors = new float[MAX * 4];
	private int count;

	// NpcOverlayService keeps the NPCs other plugins highlight in a private map, read by reflection
	private Field highlightedNpcsField;
	private boolean highlightedNpcsUnavailable;

	@Inject
	SpriteHighlights(
		Client client,
		NpcOverlayService npcOverlayService,
		LofiConfig config
	)
	{
		this.client = client;
		this.npcOverlayService = npcOverlayService;
		this.config = config;
	}

	/**
	 * Gathers this frame's highlights. Call on the client thread before the art style pass.
	 *
	 * @param spritesOn whether characters are drawn as sprites this frame; nothing is outlined otherwise
	 */
	void update(boolean spritesOn)
	{
		count = 0;
		if (!spritesOn || !config.spriteHighlights())
		{
			return;
		}

		// Other plugins' highlighted NPCs first, so hover and interaction win on overlap below
		Map<NPC, HighlightedNpc> highlighted = highlightedNpcs();
		if (highlighted != null)
		{
			for (HighlightedNpc h : highlighted.values())
			{
				if (h.isOutline() || h.isHull())
				{
					add(h.getNpc(), h.getHighlightColor());
				}
			}
		}

		Player local = client.getLocalPlayer();
		if (local != null)
		{
			add(local.getInteracting(), INTERACT);
		}
		add(hoveredActor(), HOVER);
	}

	int getCount()
	{
		return count;
	}

	int[] getIds()
	{
		return ids;
	}

	float[] getColors()
	{
		return colors;
	}

	/** The actor the top menu entry targets, which is what a left click would act on */
	private Actor hoveredActor()
	{
		if (client.isMenuOpen())
		{
			return null;
		}
		MenuEntry[] entries = client.getMenuEntries();
		if (entries.length == 0)
		{
			return null;
		}
		return entries[entries.length - 1].getActor();
	}

	private void add(Actor actor, Color color)
	{
		if (actor == null || color == null)
		{
			return;
		}
		int id = LofiPlugin.characterId(actor);
		// A later highlight of the same character replaces the earlier one
		int slot = count;
		for (int i = 0; i < count; i++)
		{
			if (ids[i] == id)
			{
				slot = i;
				break;
			}
		}
		if (slot == MAX)
		{
			return;
		}
		ids[slot] = id;
		colors[slot * 4] = color.getRed() / 255f;
		colors[slot * 4 + 1] = color.getGreen() / 255f;
		colors[slot * 4 + 2] = color.getBlue() / 255f;
		// Highlights are drawn solid; faint ones would vanish into painted styles
		colors[slot * 4 + 3] = 1f;
		if (slot == count)
		{
			count++;
		}
	}

	@SuppressWarnings("unchecked")
	private Map<NPC, HighlightedNpc> highlightedNpcs()
	{
		if (highlightedNpcsUnavailable)
		{
			return null;
		}
		try
		{
			if (highlightedNpcsField == null)
			{
				highlightedNpcsField = NpcOverlayService.class.getDeclaredField("highlightedNpcs");
				highlightedNpcsField.setAccessible(true);
			}
			return (Map<NPC, HighlightedNpc>) highlightedNpcsField.get(npcOverlayService);
		}
		catch (ReflectiveOperationException | RuntimeException ex)
		{
			log.warn("Can't read NPC Indicators highlights, only hover and interaction are outlined", ex);
			highlightedNpcsUnavailable = true;
			return null;
		}
	}
}
