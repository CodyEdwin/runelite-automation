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

import net.runelite.rsbuddy.routine.RoutineLoader;
import net.runelite.rsbuddy.routines.AnimatedSuitKillerScript;
import net.runelite.rsbuddy.routines.ArdougneBakerStallStealerScript;
import net.runelite.rsbuddy.routines.BarbarianFisherCookerScript;
import net.runelite.rsbuddy.routines.BowFletcherScript;
import net.runelite.rsbuddy.routines.CatherbySharkFisherScript;
import net.runelite.rsbuddy.routines.CombatTrainerScript;
import net.runelite.rsbuddy.routines.CustomScript;
import net.runelite.rsbuddy.routines.EdgevilleSmelterScript;
import net.runelite.rsbuddy.routines.FruitStallStealerScript;
import net.runelite.rsbuddy.routines.GemCrabKillerScript;
import net.runelite.rsbuddy.routines.GemCutterScript;
import net.runelite.rsbuddy.routines.GrandExchangeFlipperScript;
import net.runelite.rsbuddy.routines.HillGiantScript;
import net.runelite.rsbuddy.routines.MotherlodeMinerScript;
import net.runelite.rsbuddy.routines.ObjectInteractScript;
import net.runelite.rsbuddy.routines.RooftopAgilityTrainerScript;
import net.runelite.rsbuddy.routines.WestVarrockSmitherRoutine;

/**
 * Registers every built-in routine. A new routine is added by one {@code register} call.
 */
public final class RoutineLibrary
{
	private static final String APPLIED_ON_START =
		"<html>Settings are applied the next time the routine starts.</html>";

	private RoutineLibrary()
	{
	}

	public static RoutineRegistry create(RSBuddyConfig config)
	{
		RoutineRegistry registry = new RoutineRegistry();

		registry.register(ObjectInteractScript.class.getName(),
			loader -> RoutineLoader.load(ObjectInteractScript.class.getName(), loader,
				config.targetObjectName(), config.targetAction(), config.maxDistance()),
			RoutineLibrary::objectInteract);

		registry.register(ArdougneBakerStallStealerScript.class.getName(),
			loader -> RoutineLoader.load(ArdougneBakerStallStealerScript.class.getName(), loader),
			null);

		registry.register(BarbarianFisherCookerScript.class.getName(),
			loader -> RoutineLoader.load(BarbarianFisherCookerScript.class.getName(), loader),
			null);

		registry.register(CatherbySharkFisherScript.class.getName(),
			loader -> RoutineLoader.load(CatherbySharkFisherScript.class.getName(), loader),
			null);

		registry.register(CombatTrainerScript.class.getName(),
			loader -> RoutineLoader.load(CombatTrainerScript.class.getName(), loader),
			null);

		registry.register(FruitStallStealerScript.class.getName(),
			loader -> RoutineLoader.load(FruitStallStealerScript.class.getName(), loader,
				config.fruitStallStayAtOneStall(),
				config.fruitStallKeepPapaya(),
				config.fruitStallKeepGolovanova(),
				config.fruitStallKeepStrangeFruit(),
				config.fruitStallKeepJangerberries()),
			RoutineLibrary::fruitStall);

		registry.register(HillGiantScript.class.getName(),
			loader -> RoutineLoader.load(HillGiantScript.class.getName(), loader),
			null);

		registry.register(MotherlodeMinerScript.class.getName(),
			loader -> RoutineLoader.load(MotherlodeMinerScript.class.getName(), loader,
				config.motherlodeUpstairs(), config.motherlodeDragonSpec()),
			RoutineLibrary::motherlode);

		registry.register(RooftopAgilityTrainerScript.class.getName(),
			loader -> RoutineLoader.load(RooftopAgilityTrainerScript.class.getName(), loader,
				config.rooftopCourse().name()),
			RoutineLibrary::rooftop);

		registry.register(GemCrabKillerScript.class.getName(),
			loader -> RoutineLoader.load(GemCrabKillerScript.class.getName(), loader,
				config.gemCrabPotionMode().name(), config.gemCrabBoostThreshold()),
			RoutineLibrary::gemCrab);

		registry.register(GrandExchangeFlipperScript.class.getName(),
			loader -> RoutineLoader.load(GrandExchangeFlipperScript.class.getName(), loader),
			null);

		registry.register(GemCutterScript.class.getName(),
			loader -> RoutineLoader.load(GemCutterScript.class.getName(), loader,
				config.gemCutterGemType().name(),
				config.gemCutterAmount(),
				config.gemCutterClosestBooth()),
			RoutineLibrary::gemCutter);

		registry.register(BowFletcherScript.class.getName(),
			loader -> RoutineLoader.load(BowFletcherScript.class.getName(), loader,
				config.bowFletcherBowType().name(),
				config.bowFletcherAmount(),
				config.bowFletcherClosestBooth()),
			RoutineLibrary::bowFletcher);

		registry.register(EdgevilleSmelterScript.class.getName(),
			loader -> RoutineLoader.load(EdgevilleSmelterScript.class.getName(), loader,
				config.edgevilleSmelterBarType().name()),
			RoutineLibrary::edgevilleSmelter);

		registry.register(WestVarrockSmitherRoutine.class.getName(),
			loader -> RoutineLoader.load(WestVarrockSmitherRoutine.class.getName(), loader,
				config.westVarrockSmithMetal().name(),
				config.westVarrockSmithItem().name(),
				config.westVarrockSmithAmount().name()),
			RoutineLibrary::westVarrockSmither);

		registry.register(AnimatedSuitKillerScript.class.getName(),
			loader -> RoutineLoader.load(AnimatedSuitKillerScript.class.getName(), loader,
				config.animatedSuitBlack(),
				config.animatedSuitMithril(),
				config.animatedSuitAdamant(),
				config.animatedSuitRune(),
				config.animatedSuitPotionMode().name(),
				config.animatedSuitDrinkBelowBoost()),
			RoutineLibrary::animatedSuit);

		registry.register(CustomScript.class.getName(),
			loader -> RoutineLoader.load(CustomScript.class.getName(), loader),
			null);

		return registry;
	}

	private static void objectInteract(RoutineSettingsForm form)
	{
		form.addText("Target object", "targetObjectName", form.config()::targetObjectName);
		form.addText("Target action", "targetAction", form.config()::targetAction);
		form.addSpinner("Max distance", "Maximum distance in tiles to search for targets.",
			"maxDistance", 1, 50, form.config()::maxDistance);
		form.setHint(APPLIED_ON_START);
	}

	private static void fruitStall(RoutineSettingsForm form)
	{
		form.addCheck("Stay at one stall",
			"Stay at one stall and wait for it to respawn. Uncheck to run between both stalls.",
			"fruitStallStayAtOneStall", form.config()::fruitStallStayAtOneStall);
		form.addCheck("Papaya fruit", "Keep papaya fruit. Unchecked fruit is dropped.",
			"fruitStallKeepPapaya", form.config()::fruitStallKeepPapaya);
		form.addCheck("Golovanova fruit top", "Keep Golovanova fruit tops. Unchecked fruit is dropped.",
			"fruitStallKeepGolovanova", form.config()::fruitStallKeepGolovanova);
		form.addCheck("Strange fruit", "Keep strange fruit. Unchecked fruit is dropped.",
			"fruitStallKeepStrangeFruit", form.config()::fruitStallKeepStrangeFruit);
		form.addCheck("Jangerberries", "Keep jangerberries. Unchecked fruit is dropped.",
			"fruitStallKeepJangerberries", form.config()::fruitStallKeepJangerberries);
		form.setHint("<html>Checked fruit is kept. Unchecked fruit is dropped.</html>");
	}

	private static void bowFletcher(RoutineSettingsForm form)
	{
		form.addCombo("Bow to make", "Which unstrung bow to fletch; the logs follow from it.",
			BowFletcherBowType.values(), "bowFletcherBowType", form.config()::bowFletcherBowType);
		form.addSpinner("Amount (0 = unlimited)",
			"How many bows to make, then stop. 0 = until the bank runs out of logs.",
			"bowFletcherAmount", 0, 1000000, form.config()::bowFletcherAmount);
		form.addCheck("Bank at closest booth",
			"Open the closest bank: booth, chest or banker NPC. Off: prefer a banker NPC.",
			"bowFletcherClosestBooth", form.config()::bowFletcherClosestBooth);
		form.setHint("<html>Knife in locked slot 1. Applied the next time the routine starts.</html>");
	}

	private static void edgevilleSmelter(RoutineSettingsForm form)
	{
		form.addCombo("Bar to smelt", "Which bar to smelt at the Edgeville furnace; the ores follow from it.",
			EdgevilleSmelterBarType.values(), "edgevilleSmelterBarType", form.config()::edgevilleSmelterBarType);
		form.setHint("<html>Keep the ores in the first bank tab. Applied the next time the routine starts.</html>");
	}

	private static void westVarrockSmither(RoutineSettingsForm form)
	{
		form.addCombo("Metal", "Which metal's bars to withdraw for smithing.",
			WestVarrockSmithMetal.values(), "westVarrockSmithMetal", form.config()::westVarrockSmithMetal);
		form.addCombo("Item to smith", "Which item to make at the anvil; its bar cost follows from it.",
			WestVarrockSmithItem.values(), "westVarrockSmithItem", form.config()::westVarrockSmithItem);
		form.addCombo("Amount at anvil", "Quantity to choose in the anvil menu each trip.",
			WestVarrockSmithAmount.values(), "westVarrockSmithAmount", form.config()::westVarrockSmithAmount);
		form.setHint("<html>Hammer in locked slot 1. Keep the bars, or regular keel parts for large"
			+ " keel parts, in the first bank tab. Applied the next time the routine starts.</html>");
	}

	private static void gemCutter(RoutineSettingsForm form)
	{
		form.addCombo("Gem to cut", "Which uncut gem to withdraw and cut.",
			GemCutterGemType.values(), "gemCutterGemType", form.config()::gemCutterGemType);
		form.addSpinner("Amount (0 = unlimited)",
			"How many gems to cut, then stop. 0 = until the bank runs out of uncut gems.",
			"gemCutterAmount", 0, 1000000, form.config()::gemCutterAmount);
		form.addCheck("Bank at closest booth",
			"Open the closest bank: booth, chest or banker NPC. Off: prefer a banker NPC.",
			"gemCutterClosestBooth", form.config()::gemCutterClosestBooth);
		form.setHint("<html>Chisel in locked slot 1. Applied the next time the routine starts.</html>");
	}

	private static void motherlode(RoutineSettingsForm form)
	{
		form.addCheck("Upstairs mining",
			"Mine the upstairs veins and use the upstairs hopper, four loads per trip.",
			"motherlodeUpstairs", form.config()::motherlodeUpstairs);
		form.addCheck("Use dragon pickaxe special",
			"While mining, click the special attack orb every time special energy is 100%.",
			"motherlodeDragonSpec", form.config()::motherlodeDragonSpec);
		form.setHint(APPLIED_ON_START);
	}

	private static void rooftop(RoutineSettingsForm form)
	{
		form.addCombo("Course", "Which rooftop agility course to run.",
			RoofCourse.values(), "rooftopCourse", form.config()::rooftopCourse);
		form.setHint(APPLIED_ON_START);
	}

	private static void gemCrab(RoutineSettingsForm form)
	{
		form.addCombo("Strength potions",
			"Regular strength potions, super strength, or either (super first).",
			GemCrabPotionMode.values(), "gemCrabPotionMode", form.config()::gemCrabPotionMode);
		form.addSpinner("Re-drink at boost",
			"Drink another potion once the Strength boost has dropped to this many levels or less.",
			"gemCrabBoostThreshold", 0, 15, form.config()::gemCrabBoostThreshold);
		form.setHint(APPLIED_ON_START);
	}

	private static void animatedSuit(RoutineSettingsForm form)
	{
		form.addCheck("Black", "Animate black armour. Ticking one unticks the others.",
			"animatedSuitBlack", form.config()::animatedSuitBlack);
		form.addCheck("Mithril", "Animate mithril armour. Ticking one unticks the others.",
			"animatedSuitMithril", form.config()::animatedSuitMithril);
		form.addCheck("Adamant", "Animate adamant armour. Ticking one unticks the others.",
			"animatedSuitAdamant", form.config()::animatedSuitAdamant);
		form.addCheck("Rune", "Animate rune armour. Ticking one unticks the others.",
			"animatedSuitRune", form.config()::animatedSuitRune);
		form.addCombo("Strength potions",
			"Off, regular strength potions, super strength, or either (super first).",
			AnimatedSuitPotionMode.values(), "animatedSuitPotionMode", form.config()::animatedSuitPotionMode);
		form.addSpinner("Drink when boost below",
			"Drink a strength potion when the Strength boost is below this many levels.",
			"animatedSuitDrinkBelowBoost", 1, 16, form.config()::animatedSuitDrinkBelowBoost);
		form.setHint("<html>Tick exactly one armour type. Applied the next time the routine starts.</html>");
	}
}
