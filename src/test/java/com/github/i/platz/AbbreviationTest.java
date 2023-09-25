package com.github.i.platz;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;
import org.junit.Assert;
import org.junit.Test;

public class AbbreviationTest {
	@Test
	public void testAbbreviations() {
		Assert.assertEquals("1", PlatzPlugin.abbreviateBigNumber(1L));
		Assert.assertEquals("12", PlatzPlugin.abbreviateBigNumber(12L));
		Assert.assertEquals("123", PlatzPlugin.abbreviateBigNumber(123L));
		Assert.assertEquals("1.23K", PlatzPlugin.abbreviateBigNumber(1_234L));
		Assert.assertEquals("12.3K", PlatzPlugin.abbreviateBigNumber(12_345L));
		Assert.assertEquals("123K", PlatzPlugin.abbreviateBigNumber(123_456L));
		Assert.assertEquals("1.23M", PlatzPlugin.abbreviateBigNumber(1_234_567L));
		Assert.assertEquals("12.3M", PlatzPlugin.abbreviateBigNumber(12_345_678L));
		Assert.assertEquals("123M", PlatzPlugin.abbreviateBigNumber(123_456_789L));
		Assert.assertEquals("1.23B", PlatzPlugin.abbreviateBigNumber(1_234_567_891L));
		Assert.assertEquals("12.3B", PlatzPlugin.abbreviateBigNumber(12_345_678_912L));
		Assert.assertEquals("123B", PlatzPlugin.abbreviateBigNumber(123_456_789_123L));
	}
}