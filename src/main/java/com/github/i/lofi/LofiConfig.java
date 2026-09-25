/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.github.i.lofi;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;
import static com.github.i.lofi.LofiPlugin.MAX_DISTANCE;
import static com.github.i.lofi.LofiPlugin.MAX_FOG_DEPTH;
import com.github.i.lofi.config.AntiAliasingMode;
import com.github.i.lofi.config.ColorBlindMode;
import com.github.i.lofi.config.PainterlyDebugView;
import com.github.i.lofi.config.PainterlyStyle;
import com.github.i.lofi.config.UIScalingMode;

@ConfigGroup(LofiConfig.GROUP)
public interface LofiConfig extends Config
{
	String GROUP = "lofi";

	/*====== Art style ======*/

	@ConfigSection(
		name = "Art style",
		description = "Hand-drawn art styles applied to the whole frame. HUD strength controls how much the interface gets.",
		position = -1
	)
	String painterlySettings = "painterlySettings";

	@ConfigItem(
		keyName = "painterlyStyle",
		name = "Art style",
		description = "Post-processing style applied to the 3D scene.",
		position = 0,
		section = painterlySettings
	)
	default PainterlyStyle painterlyStyle()
	{
		return PainterlyStyle.SQUIGGLE;
	}

	@Range(min = 1, max = 6)
	@ConfigItem(
		keyName = "painterlyLineWidth",
		name = "Outline thickness",
		description = "Thickness of the drawn outlines, in scene pixels.",
		position = 1,
		section = painterlySettings
	)
	default int painterlyLineWidth()
	{
		return 2;
	}

	@Units(Units.PERCENT)
	@Range(min = 0, max = 100)
	@ConfigItem(
		keyName = "painterlyWobble",
		name = "Wobble",
		description = "How far lines and fills squiggle. 0% keeps everything still.",
		position = 2,
		section = painterlySettings
	)
	default int painterlyWobble()
	{
		return 50;
	}

	@Range(min = 1, max = 24)
	@ConfigItem(
		keyName = "painterlyBoilRate",
		name = "Redraws per second",
		description = "How many times per second the wobble pattern changes. Lower feels more hand-animated.",
		position = 3,
		section = painterlySettings
	)
	default int painterlyBoilRate()
	{
		return 8;
	}

	@Range(min = 0, max = 8)
	@ConfigItem(
		keyName = "painterlyPaintRadius",
		name = "Brush size",
		description = "Size of the flattening brush, or pixel size in MS Paint style. Larger values cost more GPU.",
		position = 4,
		section = painterlySettings
	)
	default int painterlyPaintRadius()
	{
		return 3;
	}

	@Units(Units.PERCENT)
	@Range(min = 0, max = 100)
	@ConfigItem(
		keyName = "painterlyCanvasStrength",
		name = "Paper texture (not MS Paint)",
		description = "Strength of the paper or canvas grain. MS Paint stays flat, like a real paint program.",
		position = 5,
		section = painterlySettings
	)
	default int painterlyCanvasStrength()
	{
		return 35;
	}

	@Range(min = 0, max = 36)
	@ConfigItem(
		keyName = "painterlyHueSteps",
		name = "Hue steps (Acrylic)",
		description = "Acrylic painting only: how many distinct hues the paint uses. Fewer looks more hand-mixed. 0 is unlimited.",
		position = 6,
		section = painterlySettings
	)
	default int painterlyHueSteps()
	{
		return 12;
	}

	@Units(Units.PERCENT)
	@Range(min = 0, max = 100)
	@ConfigItem(
		keyName = "painterlyHudStrength",
		name = "HUD strength",
		description = "How strongly the art style applies to the HUD, chat and overlays. Lower keeps text more readable.",
		position = 7,
		section = painterlySettings
	)
	default int painterlyHudStrength()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "painterlyDebugView",
		name = "Debug view",
		description = "Show an intermediate step of the art style instead of the final image, for tuning.",
		position = 8,
		section = painterlySettings
	)
	default PainterlyDebugView painterlyDebugView()
	{
		return PainterlyDebugView.OFF;
	}

	/*====== Lo-fi audio ======*/

	@ConfigSection(
		name = "Lo-fi audio",
		description = "Plays the game's audio slower, with a gentle tape-style wobble in pitch and speed.",
		position = 100
	)
	String lofiAudioSettings = "lofiAudioSettings";

	@ConfigItem(
		keyName = "lofiAudio",
		name = "Lo-fi audio",
		description = "Slow the game's audio down and let it wobble, like a worn tape.",
		position = 0,
		section = lofiAudioSettings
	)
	default boolean lofiAudio()
	{
		return true;
	}

	@Units(Units.PERCENT)
	@Range(min = 70, max = 100)
	@ConfigItem(
		keyName = "lofiSpeed",
		name = "Playback speed",
		description = "Average speed compared to normal. Lower is slower and deeper.",
		position = 1,
		section = lofiAudioSettings
	)
	default int lofiSpeed()
	{
		return 92;
	}

	@Units(Units.PERCENT)
	@Range(min = 0, max = 100)
	@ConfigItem(
		keyName = "lofiWobble",
		name = "Wobble",
		description = "How much the speed drifts slower and faster around the average. 100% drifts about 3.5% each way.",
		position = 2,
		section = lofiAudioSettings
	)
	default int lofiWobble()
	{
		return 50;
	}

	@Units(Units.PERCENT)
	@Range(min = 0, max = 100)
	@ConfigItem(
		keyName = "lofiSaturation",
		name = "Tape saturation",
		description = "Warms the sound by gently rounding off loud peaks, like overdriven tape. 0% is clean.",
		position = 3,
		section = lofiAudioSettings
	)
	default int lofiSaturation()
	{
		return 30;
	}

	@Units(" Hz")
	@Range(min = 0, max = 500)
	@ConfigItem(
		keyName = "lofiLowCut",
		name = "Low cut",
		description = "Gently rolls off bass below this frequency, like a small speaker. 0 is off.",
		position = 4,
		section = lofiAudioSettings
	)
	default int lofiLowCut()
	{
		return 80;
	}

	@Units(" Hz")
	@Range(min = 0, max = 20000)
	@ConfigItem(
		keyName = "lofiHighCut",
		name = "High cut",
		description = "Gently rolls off treble above this frequency, for a muffled tape sound. Lower is darker. 0 is off.",
		position = 5,
		section = lofiAudioSettings
	)
	default int lofiHighCut()
	{
		return 6000;
	}

	@Range(
		max = MAX_DISTANCE
	)
	@ConfigItem(
		keyName = "drawDistance",
		name = "Draw distance",
		description = "Draw distance.",
		position = 1
	)
	default int drawDistance()
	{
		return 50;
	}

	@ConfigItem(
		keyName = "hideUnrelatedMaps",
		name = "Hide unrelated maps",
		description = "Hide unrelated map areas you shouldn't see.",
		position = 2
	)
	default boolean hideUnrelatedMaps()
	{
		return true;
	}

	@Range(
		max = 5
	)
	@ConfigItem(
		keyName = "expandedMapLoadingChunks",
		name = "Extended map loading",
		description = "Extra map area to load, in 8 tile chunks.",
		position = 1
	)
	default int expandedMapLoadingZones()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "smoothBanding",
		name = "Remove color banding",
		description = "Smooths out the color banding that is present in the CPU renderer.",
		position = 2
	)
	default boolean smoothBanding()
	{
		return true;
	}

	@ConfigItem(
		keyName = "antiAliasingMode",
		name = "Anti aliasing",
		description = "Configures the anti-aliasing mode.",
		position = 3
	)
	default AntiAliasingMode antiAliasingMode()
	{
		return AntiAliasingMode.MSAA_2;
	}

	@ConfigItem(
		keyName = "uiScalingMode",
		name = "UI scaling mode",
		description = "Sampling function to use for the UI in stretched mode.",
		position = 4
	)
	default UIScalingMode uiScalingMode()
	{
		return UIScalingMode.HYBRID;
	}

	@Range(
		max = MAX_FOG_DEPTH
	)
	@ConfigItem(
		keyName = "fogDepth",
		name = "Fog depth",
		description = "Distance from the scene edge the fog starts.",
		position = 5
	)
	default int fogDepth()
	{
		return 0;
	}

	@Range(
		min = 0,
		max = 16
	)
	@ConfigItem(
		keyName = "anisotropicFilteringLevel",
		name = "Anisotropic filtering",
		description = "Configures the anisotropic filtering level.",
		position = 7
	)
	default int anisotropicFilteringLevel()
	{
		return 1;
	}

	@ConfigItem(
		keyName = "colorBlindMode",
		name = "Colorblindness correction",
		description = "Adjusts colors to account for colorblindness.",
		position = 8
	)
	default ColorBlindMode colorBlindMode()
	{
		return ColorBlindMode.NONE;
	}

	@Range(
		min = 0,
		max = 100
	)
	@ConfigItem(
		keyName = "colorBlindIntensity",
		name = "Colorblindness intensity",
		description = "Strength of the colorblindness correction effect.",
		position = 9
	)
	default int colorBlindIntensity()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "brightTextures",
		name = "Bright textures",
		description = "Use old texture lighting method which results in brighter game textures.",
		position = 10
	)
	default boolean brightTextures()
	{
		return false;
	}

	@ConfigItem(
		keyName = "unlockFps",
		name = "Unlock FPS",
		description = "Removes the 50 FPS cap for camera movement.",
		position = 11
	)
	default boolean unlockFps()
	{
		return true;
	}

	enum SyncMode
	{
		OFF,
		ON,
		ADAPTIVE
	}

	@ConfigItem(
		keyName = "vsyncMode",
		name = "Vsync mode",
		description = "Method to synchronize frame rate with refresh rate.",
		position = 12
	)
	default SyncMode syncMode()
	{
		return SyncMode.OFF;
	}

	@ConfigItem(
		keyName = "fpsTarget",
		name = "FPS target",
		description = "Target FPS when 'Unlock FPS' is enabled and 'Vsync mode' is off.",
		position = 13
	)
	@Range(
		min = 1,
		max = 999
	)
	default int fpsTarget()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "removeVertexSnapping",
		name = "Remove vertex snapping",
		description = "Removes vertex snapping from most animations.",
		position = 14
	)
	default boolean removeVertexSnapping()
	{
		return true;
	}

	@ConfigItem(
		keyName = "numThreads",
		name = "Threads",
		description = "Number of render threads to use.",
		position = 20
	)
	@Range(min = 0, max = 15)
	default int numThreads()
	{
		return 3;
	}
}
