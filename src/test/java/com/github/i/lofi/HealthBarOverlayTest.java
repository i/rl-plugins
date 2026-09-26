package com.github.i.lofi;

import org.junit.Test;
import static org.junit.Assert.*;

public class HealthBarOverlayTest
{
	@Test
	public void widthGrowsWithHitpointsWithinLimits()
	{
		assertEquals(24, HealthBarOverlay.width(1));
		assertTrue(HealthBarOverlay.width(99) > HealthBarOverlay.width(20));
		assertTrue(HealthBarOverlay.width(255) > HealthBarOverlay.width(99));
		assertEquals(160, HealthBarOverlay.width(5000));
	}

	@Test
	public void smallMonstersGetOneBlockPerHitpoint()
	{
		int width = HealthBarOverlay.width(3);
		assertEquals(3, HealthBarOverlay.blocks(width, 3));
	}

	@Test
	public void blocksNeverGetThinnerThanTheMinimum()
	{
		for (int hp : new int[]{10, 99, 255, 5000})
		{
			int width = HealthBarOverlay.width(hp);
			int blocks = HealthBarOverlay.blocks(width, hp);
			assertTrue(hp + " hp", width / blocks >= 5);
		}
	}

	@Test
	public void filledBlocksTrackTheRatio()
	{
		assertEquals(0, HealthBarOverlay.filledBlocks(0, 30, 10));
		assertEquals(10, HealthBarOverlay.filledBlocks(30, 30, 10));
		assertEquals(5, HealthBarOverlay.filledBlocks(15, 30, 10));
		// Barely alive still shows a block, nearly full still shows a gap
		assertEquals(1, HealthBarOverlay.filledBlocks(1, 30, 10));
		assertEquals(9, HealthBarOverlay.filledBlocks(29, 30, 10));
	}
}
