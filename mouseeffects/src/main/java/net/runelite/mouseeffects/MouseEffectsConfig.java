/*
 * Copyright (c) 2026, reich
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
package net.runelite.mouseeffects;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(MouseEffectsConfig.GROUP)
public interface MouseEffectsConfig extends Config
{
	String GROUP = "mouseeffects";

	@ConfigSection(
		name = "Mouse trail",
		description = "Customise the glowing trail that follows your cursor.",
		position = 0
	)
	String trailSection = "trail";

	@ConfigSection(
		name = "Click effects",
		description = "Choose the burst that plays when you click.",
		position = 1
	)
	String clickSection = "click";

	@ConfigItem(
		keyName = "trailEnabled",
		name = "Enable trail",
		description = "Draw a trail behind the mouse cursor.",
		section = trailSection,
		position = 0
	)
	default boolean trailEnabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "trailStyle",
		name = "Trail style",
		description = "The shape and behaviour of the cursor trail.",
		section = trailSection,
		position = 1
	)
	default TrailStyle trailStyle()
	{
		return TrailStyle.COMET;
	}

	@ConfigItem(
		keyName = "trailColor",
		name = "Trail colour",
		description = "Main colour of the cursor trail.",
		section = trailSection,
		position = 2
	)
	default Color trailColor()
	{
		return new Color(0, 229, 255);
	}

	@ConfigItem(
		keyName = "trailAccent",
		name = "Trail accent",
		description = "Secondary colour used for the glow and gradient.",
		section = trailSection,
		position = 3
	)
	default Color trailAccent()
	{
		return new Color(255, 0, 200);
	}

	@Range(min = 4, max = 80)
	@ConfigItem(
		keyName = "trailLength",
		name = "Trail length",
		description = "How many positions the trail remembers.",
		section = trailSection,
		position = 4
	)
	default int trailLength()
	{
		return 28;
	}

	@Range(min = 1, max = 16)
	@ConfigItem(
		keyName = "trailSize",
		name = "Trail size",
		description = "Thickness of the cursor trail.",
		section = trailSection,
		position = 5
	)
	default int trailSize()
	{
		return 6;
	}

	@Range(min = 100, max = 3000)
	@ConfigItem(
		keyName = "trailDuration",
		name = "Trail fade (ms)",
		description = "How long trail points take to fade away.",
		section = trailSection,
		position = 6
	)
	default int trailDuration()
	{
		return 900;
	}

	@ConfigItem(
		keyName = "clickEnabled",
		name = "Enable click effects",
		description = "Play an effect whenever you click.",
		section = clickSection,
		position = 0
	)
	default boolean clickEnabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "clickStyle",
		name = "Click style",
		description = "The animation played at the click point.",
		section = clickSection,
		position = 1
	)
	default ClickStyle clickStyle()
	{
		return ClickStyle.NOVA;
	}

	@ConfigItem(
		keyName = "clickColor",
		name = "Click colour",
		description = "Main colour of the click effect.",
		section = clickSection,
		position = 2
	)
	default Color clickColor()
	{
		return new Color(255, 170, 40);
	}

	@ConfigItem(
		keyName = "clickAccent",
		name = "Click accent",
		description = "Secondary colour of the click effect.",
		section = clickSection,
		position = 3
	)
	default Color clickAccent()
	{
		return new Color(255, 40, 120);
	}

	@Range(min = 10, max = 250)
	@ConfigItem(
		keyName = "clickSize",
		name = "Click size",
		description = "Radius of the click burst.",
		section = clickSection,
		position = 4
	)
	default int clickSize()
	{
		return 70;
	}

	@Range(min = 150, max = 3000)
	@ConfigItem(
		keyName = "clickDuration",
		name = "Click duration (ms)",
		description = "How long the click effect lasts.",
		section = clickSection,
		position = 5
	)
	default int clickDuration()
	{
		return 650;
	}
}
