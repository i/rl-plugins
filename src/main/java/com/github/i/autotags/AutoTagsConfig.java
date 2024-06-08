/*
 * Copyright (c) 2018 kulers
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
package com.github.i.autotags;

import com.google.gson.Gson;
import net.runelite.client.config.*;

import java.awt.*;
import java.util.Set;
import java.util.stream.Collectors;

@ConfigGroup(AutoTagsConfig.GROUP)
public interface AutoTagsConfig extends Config {
	String GROUP = "auto-tags";

	@ConfigSection(
		name = "Tag display mode",
		description = "How tags are displayed in the inventory",
		position = 0
	)
	String tagStyleSection = "tagStyleSection";

	@ConfigItem(
		position = 0,
		keyName = "showTagOutline",
		name = "Outline",
		description = "Configures whether or not item tags show be outlined",
		section = tagStyleSection
	)
	default boolean showTagOutline()
	{
		return true;
	}

	@ConfigItem(
		position = 1,
		keyName = "tagUnderline",
		name = "Underline",
		description = "Configures whether or not item tags should be underlined",
		section = tagStyleSection
	)
	default boolean showTagUnderline()
	{
		return false;
	}

	@ConfigItem(
		position = 2,
		keyName = "tagFill",
		name = "Fill",
		description = "Configures whether or not item tags should be filled",
		section = tagStyleSection
	)
	default boolean showTagFill()
	{
		return false;
	}

	@Range(
		max = 255
	)
	@ConfigItem(
		position = 3,
		keyName = "fillOpacity",
		name = "Fill opacity",
		description = "Configures the opacity of the tag \"Fill\"",
		section = tagStyleSection
	)
	default int fillOpacity() {
		return 50;
	}

	@ConfigItem(
			keyName = "meleeColor",
			name = "Melee Tag Color",
			description = "Configures the overlay color for melee items",
			section = tagStyleSection
	)
	default Color meleeColor() {
		return Color.PINK;
	}

	@ConfigItem(
			keyName = "magicColor",
			name = "Magic Tag Color",
			description = "Configures the overlay color for magic items",
			section = tagStyleSection
	)
	default Color magicColor() {
		return Color.CYAN;
	}

	@ConfigItem(
			keyName = "rangedColor",
			name = "Ranged Tag Color",
			description = "Configures the overlay color for ranged items",
			section = tagStyleSection
	)
	default Color rangedColor() {
		return Color.GREEN;
	}

	@ConfigItem(
			keyName = "specialColor",
			name = "Special Tag Color",
			description = "Configures the overlay color for special items",
			section = tagStyleSection
	)
	default Color specialColor() {
		return Color.YELLOW;
	}


	Gson gson = new Gson();
	@ConfigItem(
			keyName = "overrides",
			name = "overrides",
			description = "holds json for overrides",
			hidden = true
	)
	default String overrides() {
		return gson.toJson(CombatType.CHOICE_LIST.stream()
				.collect(Collectors.toMap(
						combatType -> combatType,
						combatType -> Set.of()
				)));
	}
}
