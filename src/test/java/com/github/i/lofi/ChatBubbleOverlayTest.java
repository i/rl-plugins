package com.github.i.lofi;

import java.awt.Canvas;
import java.awt.Font;
import java.awt.FontMetrics;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChatBubbleOverlayTest
{
	private final FontMetrics metrics = new Canvas().getFontMetrics(new Font(Font.SANS_SERIF, Font.BOLD, 12));

	@Test
	public void shortTextStaysOnOneLine()
	{
		assertEquals(List.of("Buying gf"), ChatBubbleOverlay.wrap("Buying gf", metrics));
	}

	@Test
	public void longTextWrapsWithinTheBubble()
	{
		String text = "Selling a whole lot of lobsters and swordfish at the grand exchange for cheap";
		List<String> lines = ChatBubbleOverlay.wrap(text, metrics);
		assertTrue(lines.size() > 1);
		for (String line : lines)
		{
			assertTrue(line, metrics.stringWidth(line) <= 160);
		}
		assertEquals(text, String.join(" ", lines));
	}

	@Test
	public void overlongWordsAreSplit()
	{
		String word = "a".repeat(200);
		List<String> lines = ChatBubbleOverlay.wrap(word, metrics);
		assertTrue(lines.size() > 1);
		for (String line : lines)
		{
			assertTrue(line, metrics.stringWidth(line) <= 160);
		}
		assertEquals(word, String.join("", lines));
	}
}
