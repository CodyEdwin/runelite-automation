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
package net.runelite.rsbuddy.plugin;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(TestConfig.GROUP)
public interface TestConfig extends Config
{
	String GROUP = "testplugin";

	@ConfigItem(
		keyName = "greeting",
		name = "Greeting",
		description = "Text to display in the overlay",
		position = 0
	)
	default String greeting()
	{
		return "Hello from the test plugin!";
	}

	@ConfigItem(
		keyName = "showPlayerInfo",
		name = "Show player info",
		description = "Display your player name and world in the overlay",
		position = 1
	)
	default boolean showPlayerInfo()
	{
		return true;
	}

	@ConfigItem(
		keyName = "textColor",
		name = "Text color",
		description = "Color of the overlay text",
		position = 2
	)
	default Color textColor()
	{
		return Color.WHITE;
	}

	@ConfigItem(
		keyName = "targetObjectName",
		name = "Target object",
		description = "Object name used by the sample object interaction script",
		position = 3
	)
	default String targetObjectName()
	{
		return "Tree";
	}

	@ConfigItem(
		keyName = "targetAction",
		name = "Target action",
		description = "Only target objects with this action. Leave empty to ignore actions.",
		position = 4
	)
	default String targetAction()
	{
		return "Chop down";
	}

	@Range(
		min = 1,
		max = 50
	)
	@ConfigItem(
		keyName = "maxDistance",
		name = "Max distance",
		description = "Maximum distance in tiles to search for targets",
		position = 5
	)
	default int maxDistance()
	{
		return 10;
	}

	@ConfigItem(
		keyName = "customScriptClass",
		name = "Custom script class",
		description = "Fully-qualified class name of a Script subclass with a no-arg constructor",
		position = 6
	)
	default String customScriptClass()
	{
		return "net.runelite.rsbuddy.scripts.CustomScript";
	}

	@ConfigItem(
		keyName = "showXpLogger",
		name = "Show XP logger",
		description = "Display XP gained, XP per hour, XP to next level and time to level",
		position = 7
	)
	default boolean showXpLogger()
	{
		return true;
	}

	@ConfigItem(
		keyName = "fruitStallStayAtOneStall",
		name = "Fruit stall: stay at one stall",
		description = "Stay at a single fruit stall and wait for it to respawn. Uncheck to run between both stalls.",
		position = 8
	)
	default boolean fruitStallStayAtOneStall()
	{
		return true;
	}

	@ConfigItem(
		keyName = "fruitStallKeepPapaya",
		name = "Fruit stall: keep papaya fruit",
		description = "Keep papaya fruit and drop the other unwanted fruit instead.",
		position = 9
	)
	default boolean fruitStallKeepPapaya()
	{
		return true;
	}

	@ConfigItem(
		keyName = "fruitStallKeepGolovanova",
		name = "Fruit stall: keep Golovanova fruit top",
		description = "Keep Golovanova fruit tops and drop the other unwanted fruit instead.",
		position = 10
	)
	default boolean fruitStallKeepGolovanova()
	{
		return true;
	}

	@ConfigItem(
		keyName = "fruitStallKeepStrangeFruit",
		name = "Fruit stall: keep strange fruit",
		description = "Keep strange fruit and drop the other unwanted fruit instead.",
		position = 11
	)
	default boolean fruitStallKeepStrangeFruit()
	{
		return true;
	}

	@ConfigItem(
		keyName = "fruitStallKeepJangerberries",
		name = "Fruit stall: keep jangerberries",
		description = "Keep jangerberries and drop the other unwanted fruit instead.",
		position = 12
	)
	default boolean fruitStallKeepJangerberries()
	{
		return true;
	}

	@ConfigItem(
		keyName = "scriptClassesDirectory",
		name = "Script classes directory",
		description = "Directory containing freshly compiled script classes. Leave empty to auto-detect the rsbuddy build output.",
		position = 13
	)
	default String scriptClassesDirectory()
	{
		return "";
	}
}
