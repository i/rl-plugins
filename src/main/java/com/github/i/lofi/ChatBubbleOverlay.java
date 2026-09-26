package com.github.i.lofi;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.util.Text;

/**
 * Draws overhead text, like public chat and NPC shouts, as comic book speech bubbles. The game's own overhead
 * text is hidden by replacing it with a blank, the way RuneLite's chat filter does. Everything runs on the
 * client thread.
 */
@Singleton
class ChatBubbleOverlay extends Overlay
{
	/** How long a bubble stays up, matching the game's 150 client cycles of overhead text */
	private static final long SHOW_MILLIS = 3000;
	private static final long FADE_MILLIS = 300;
	private static final int MAX_TEXT_WIDTH = 160;
	private static final int PADDING_X = 8;
	private static final int PADDING_Y = 5;
	private static final int CORNER = 14;
	private static final int TAIL_HEIGHT = 10;
	private static final int TAIL_WIDTH = 10;
	/** Game units above the actor's model top where the tail points */
	private static final int HEAD_CLEARANCE = 20;
	private static final Color FILL = new Color(255, 253, 245);
	private static final Color INK = new Color(20, 20, 20);
	/** What the game's overhead text is replaced with. A space rather than empty, like RuneLite's chat filter. */
	private static final String BLANK = " ";

	private static final class Bubble
	{
		final String text;
		final long shownAt;

		Bubble(String text, long shownAt)
		{
			this.text = text;
			this.shownAt = shownAt;
		}
	}

	private final Client client;
	private final Map<Actor, Bubble> bubbles = new WeakHashMap<>();

	@Inject
	ChatBubbleOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(OverlayPriority.HIGH);
	}

	/** Shows text over an actor as a bubble, and blanks the game's own overhead text for it. */
	void show(Actor actor, String overheadText)
	{
		String text = overheadText == null ? "" : Text.removeTags(overheadText).trim();
		// Blank text is either the game clearing it or our own blanking below; the bubble times out on its own
		if (text.isEmpty())
		{
			return;
		}
		bubbles.put(actor, new Bubble(text, System.currentTimeMillis()));
		// Fires OverheadTextChanged again, with blank text, which is ignored above
		actor.setOverheadText(BLANK);
	}

	void clear()
	{
		bubbles.clear();
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (bubbles.isEmpty())
		{
			return null;
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setFont(FontManager.getRunescapeBoldFont());
		long now = System.currentTimeMillis();

		Iterator<Map.Entry<Actor, Bubble>> it = bubbles.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<Actor, Bubble> entry = it.next();
			Actor actor = entry.getKey();
			Bubble bubble = entry.getValue();
			long age = now - bubble.shownAt;
			if (actor == null || age >= SHOW_MILLIS)
			{
				it.remove();
				continue;
			}

			Point anchor = actor.getCanvasTextLocation(g, "", actor.getLogicalHeight() + HEAD_CLEARANCE);
			if (anchor == null)
			{
				continue;
			}

			float alpha = Math.min(1f, (SHOW_MILLIS - age) / (float) FADE_MILLIS);
			drawBubble(g, bubble.text, anchor.getX(), anchor.getY(), alpha);
		}
		return null;
	}

	private void drawBubble(Graphics2D g, String text, int tipX, int tipY, float alpha)
	{
		FontMetrics metrics = g.getFontMetrics();
		List<String> lines = wrap(text, metrics);
		int textWidth = 0;
		for (String line : lines)
		{
			textWidth = Math.max(textWidth, metrics.stringWidth(line));
		}

		int width = textWidth + PADDING_X * 2;
		int height = lines.size() * metrics.getHeight() + PADDING_Y * 2;
		int bottom = tipY - TAIL_HEIGHT;
		int top = bottom - height;
		// Keep the bubble on screen, while the tail still points at the speaker
		int left = tipX - width / 2;
		left = Math.max(2, Math.min(client.getCanvasWidth() - width - 2, left));
		int tailBase = Math.max(left + CORNER, Math.min(left + width - CORNER - TAIL_WIDTH, tipX - TAIL_WIDTH / 2));

		Area shape = new Area(new RoundRectangle2D.Float(left, top, width, height, CORNER, CORNER));
		// The tail leans slightly, like a hand-drawn bubble, and overlaps the body so the seam is hidden
		Polygon tail = new Polygon(
			new int[]{tailBase, tailBase + TAIL_WIDTH, tipX},
			new int[]{bottom - 2, bottom - 2, tipY},
			3
		);
		shape.add(new Area(tail));

		Color fill = withAlpha(FILL, alpha);
		Color ink = withAlpha(INK, alpha);
		g.setColor(fill);
		g.fill(shape);
		g.setColor(ink);
		g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(shape);

		int y = top + PADDING_Y + metrics.getAscent();
		for (String line : lines)
		{
			g.drawString(line, left + (width - metrics.stringWidth(line)) / 2, y);
			y += metrics.getHeight();
		}
	}

	private static Color withAlpha(Color color, float alpha)
	{
		return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.round(alpha * 255));
	}

	/** Greedy word wrap to MAX_TEXT_WIDTH, splitting words that are too long on their own */
	static List<String> wrap(String text, FontMetrics metrics)
	{
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split("\\s+"))
		{
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (metrics.stringWidth(candidate) <= MAX_TEXT_WIDTH)
			{
				line.setLength(0);
				line.append(candidate);
				continue;
			}
			if (line.length() > 0)
			{
				lines.add(line.toString());
				line.setLength(0);
			}
			while (metrics.stringWidth(word) > MAX_TEXT_WIDTH)
			{
				int cut = word.length() - 1;
				while (cut > 1 && metrics.stringWidth(word.substring(0, cut)) > MAX_TEXT_WIDTH)
				{
					cut--;
				}
				lines.add(word.substring(0, cut));
				word = word.substring(cut);
			}
			line.append(word);
		}
		if (line.length() > 0)
		{
			lines.add(line.toString());
		}
		return lines;
	}
}
