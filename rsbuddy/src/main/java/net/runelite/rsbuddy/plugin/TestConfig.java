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
import net.runelite.rsbuddy.plugin.RoofCourse;

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

	@ConfigItem(
		keyName = "gemCrabPotionMode",
		name = "Gem crab: strength potions",
		description = "Which strength potions the Gem Crab Killer drinks: regular Strength potions only,"
			+ " Super strength only, or Either (super strength first, then regular). Lowest dose first.",
		position = 14
	)
	default GemCrabPotionMode gemCrabPotionMode()
	{
		return GemCrabPotionMode.EITHER;
	}

	@Range(
		min = 0,
		max = 15
	)
	@ConfigItem(
		keyName = "gemCrabBoostThreshold",
		name = "Gem crab: re-drink at boost",
		description = "Drink another potion once the Strength boost has dropped to this many levels or less.",
		position = 15
	)
	default int gemCrabBoostThreshold()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "animatedSuitBlack",
		name = "Animated suit: Black",
		description = "Animated Suit Killer animates black armour. Only one armour type can be ticked;"
			+ " ticking one unticks the others.",
		position = 16
	)
	default boolean animatedSuitBlack()
	{
		return true;
	}

	@ConfigItem(
		keyName = "animatedSuitMithril",
		name = "Animated suit: Mithril",
		description = "Animated Suit Killer animates mithril armour. Only one armour type can be ticked;"
			+ " ticking one unticks the others.",
		position = 17
	)
	default boolean animatedSuitMithril()
	{
		return false;
	}

	@ConfigItem(
		keyName = "animatedSuitAdamant",
		name = "Animated suit: Adamant",
		description = "Animated Suit Killer animates adamant armour. Only one armour type can be ticked;"
			+ " ticking one unticks the others.",
		position = 18
	)
	default boolean animatedSuitAdamant()
	{
		return false;
	}

	@ConfigItem(
		keyName = "animatedSuitRune",
		name = "Animated suit: Rune",
		description = "Animated Suit Killer animates rune armour. Only one armour type can be ticked;"
			+ " ticking one unticks the others.",
		position = 19
	)
	default boolean animatedSuitRune()
	{
		return false;
	}

	@ConfigItem(
		keyName = "animatedSuitPotionMode",
		name = "Animated suit: strength potions",
		description = "Which strength potions the Animated Suit Killer drinks: Off, regular Strength potions only,"
			+ " Super strength only, or Either (super strength first, then regular). Lowest dose first.",
		position = 20
	)
	default AnimatedSuitPotionMode animatedSuitPotionMode()
	{
		return AnimatedSuitPotionMode.EITHER;
	}

	@Range(
		min = 1,
		max = 16
	)
	@ConfigItem(
		keyName = "animatedSuitDrinkBelowBoost",
		name = "Animated suit: drink when boost below",
		description = "Drink a strength potion when the Strength boost is below this many levels"
			+ " (default 5: drinks at a boost of 4 or less, including unboosted).",
		position = 21
	)
	default int animatedSuitDrinkBelowBoost()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "bowFletcherBowType",
		name = "Bow fletcher: bow",
		description = "Which unstrung bow to fletch. The logs withdrawn follow from it (regular, oak, willow,"
			+ " maple, yew or magic).",
		position = 22
	)
	default BowFletcherBowType bowFletcherBowType()
	{
		return BowFletcherBowType.REGULAR_LONGBOW;
	}

	@Range(
		min = 0,
		max = 1000000
	)
	@ConfigItem(
		keyName = "bowFletcherAmount",
		name = "Bow fletcher: amount",
		description = "How many bows to make, then stop. 0 = until the bank runs out of logs.",
		position = 23
	)
	default int bowFletcherAmount()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "bowFletcherClosestBooth",
		name = "Bow fletcher: bank at closest booth",
		description = "Open the closest bank: booth, chest or banker NPC (Grand Exchange booths and bankers included)."
			+ " Off: prefer a banker NPC, then a booth or chest.",
		position = 24
	)
	default boolean bowFletcherClosestBooth()
	{
		return true;
	}

	@ConfigItem(
		keyName = "gemCutterGemType",
		name = "Gem cutter: gem",
		description = "Which uncut gem to cut (withdrawn from the bank with its Withdraw-All left click).",
		position = 25
	)
	default GemCutterGemType gemCutterGemType()
	{
		return GemCutterGemType.SAPPHIRE;
	}

	@Range(
		min = 0,
		max = 1000000
	)
	@ConfigItem(
		keyName = "gemCutterAmount",
		name = "Gem cutter: amount",
		description = "How many gems to cut, then stop. 0 = until the bank runs out of uncut gems.",
		position = 26
	)
	default int gemCutterAmount()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "gemCutterClosestBooth",
		name = "Gem cutter: bank at closest booth",
		description = "Open the closest bank: booth, chest or banker NPC (Grand Exchange booths and bankers included)."
			+ " Off: prefer a banker NPC, then a booth or chest.",
		position = 27
	)
	default boolean gemCutterClosestBooth()
	{
		return true;
	}

	@ConfigItem(
		keyName = "rooftopCourse",
		name = "Rooftop agility course",
		description = "Which rooftop agility course the trainer script runs.",
		position = 28
	)
	default RoofCourse rooftopCourse()
	{
		return RoofCourse.VARROCK;
	}

	@ConfigItem(
		keyName = "motherlodeUpstairs",
		name = "Motherlode: upstairs",
		description = "Mine the upstairs veins and use the upstairs hopper, four loads per trip.",
		position = 29
	)
	default boolean motherlodeUpstairs()
	{
		return false;
	}

	@ConfigItem(
		keyName = "motherlodeDragonSpec",
		name = "Motherlode: use dragon pickaxe special",
		description = "While mining pay-dirt, use the wielded dragon pickaxe's special (+3 Mining) every time"
			+ " special energy reaches 100%, by clicking the special attack orb.",
		position = 30
	)
	default boolean motherlodeDragonSpec()
	{
		return false;
	}

	@ConfigItem(
		keyName = "scriptSourceDirectory",
		name = "Script source directory",
		description = "Folder Reload compiles scripts from (rsbuddy/src/main/java, the rsbuddy module or the repo root)."
			+ " Leave empty to auto-detect from the client's working directory.",
		position = 31
	)
	default String scriptSourceDirectory()
	{
		return "";
	}
}
