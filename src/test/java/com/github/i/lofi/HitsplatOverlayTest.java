package com.github.i.lofi;

import net.runelite.api.Hitsplat;
import org.junit.Test;
import static org.junit.Assert.*;

public class HitsplatOverlayTest
{
	private static Hitsplat disappearingOn(int cycle)
	{
		return new Hitsplat()
		{
			@Override
			public int getHitsplatType()
			{
				return 16;
			}

			@Override
			public int getAmount()
			{
				return 5;
			}

			@Override
			public int getDisappearsOnGameCycle()
			{
				return cycle;
			}
		};
	}

	@Test
	public void fillsEmptySlotsInOrder()
	{
		Hitsplat[] slots = new Hitsplat[HitsplatOverlay.SLOTS];
		assertEquals(0, HitsplatOverlay.slotFor(slots, 100));
		slots[0] = disappearingOn(200);
		assertEquals(1, HitsplatOverlay.slotFor(slots, 100));
	}

	@Test
	public void reusesExpiredSlots()
	{
		Hitsplat[] slots = {disappearingOn(200), disappearingOn(90), disappearingOn(300), disappearingOn(400)};
		assertEquals(1, HitsplatOverlay.slotFor(slots, 100));
	}

	@Test
	public void replacesTheSoonestToDisappearWhenFull()
	{
		Hitsplat[] slots = {disappearingOn(300), disappearingOn(400), disappearingOn(150), disappearingOn(500)};
		assertEquals(2, HitsplatOverlay.slotFor(slots, 100));
	}
}
