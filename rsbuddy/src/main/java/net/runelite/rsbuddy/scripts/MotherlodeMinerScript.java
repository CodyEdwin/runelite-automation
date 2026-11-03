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
package net.runelite.rsbuddy.scripts;

import java.awt.event.KeyEvent;
import net.runelite.api.Item;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.widgets.Widget;
import net.runelite.rsbuddy.input.GameInput;
import net.runelite.rsbuddy.inventory.Inventory;
import net.runelite.rsbuddy.script.Script;
import net.runelite.rsbuddy.script.ScriptContext;
import net.runelite.rsbuddy.walk.Walker;

/**
 * Mines pay-dirt at the upper level of the Motherlode Mine, deposits it into
 * the hopper, collects the washed ores from the sack, banks them, and returns.
 * <p>
 * Walking always uses minimap left clicks. The sack is only searched after the
 * bonus experience arrives (roughly eight seconds after the hopper click),
 * which is when the pay-dirt has reached the sack.
 */
public final class MotherlodeMinerScript extends Script
{
	private static final int HOPPER_ID = ObjectID.MOTHERLODE_HOPPER;
	private static final int SACK_ID = ObjectID.MOTHERLODE_SACK;
	private static final int SACK_EMPTY_ID = ObjectID.MOTHERLODE_SACK_EMPTY;
	private static final int SACK_FULL_ID = ObjectID.MOTHERLODE_SACK_FULL;
	private static final int BANK_CHEST_ID = ObjectID.BLAST_BANK_CHEST;

	private static final int[] VEIN_IDS = {
		ObjectID.MOTHERLODE_ORE_MIDDLE,
		ObjectID.MOTHERLODE_ORE_SINGLE,
		ObjectID.MOTHERLODE_ORE_LEFT,
		ObjectID.MOTHERLODE_ORE_RIGHT
	};

	private static final WorldPoint VEIN_AREA = new WorldPoint(3748, 5646, 0);
	private static final WorldPoint HOPPER_AREA = new WorldPoint(3748, 5672, 0);
	private static final WorldPoint SACK_AREA = new WorldPoint(3748, 5659, 0);
	private static final WorldPoint BANK_AREA = new WorldPoint(3761, 5666, 0);
	private static final WorldPoint STRUT_WEST = new WorldPoint(3742, 5663, 0);
	private static final WorldPoint STRUT_EAST = new WorldPoint(3742, 5669, 0);

	private static final int STRUT_BROKEN_ID = ObjectID.MOTHERLODE_WHEEL_STRUT_BROKEN;
	private static final int STRUT_RADIUS = 2;
	private static final int STRUT_REACH = 4;

	private static final int VEIN_RADIUS = 8;
	private static final int WALK_STOP_DISTANCE = 2;
	private static final int OBJECT_REACH = 5;
	private static final int VEIN_WALK_DISTANCE = 4;
	private static final int CLICK_COOLDOWN = 2;
	private static final int ACTION_ATTEMPTS = 8;
	private static final int SEARCH_WAIT_TICKS = 3;
	private static final int MAX_SEARCH_CLICKS = 12;
	private static final long MINE_START_TIMEOUT_MS = 3000L;
	private static final long WASH_TIMEOUT_MS = 15000L;

	private enum State
	{
		MINE,
		WALK_TO_HOPPER,
		HOPPER,
		WALK_TO_SACK,
		SACK,
		WALK_TO_BANK,
		BANK,
		WALK_TO_VEIN,
		FIX_WHEEL,
		HALTED
	}

	private enum BankStage
	{
		DEPOSIT,
		WAIT_DEPOSIT,
		MAKE_ROOM,
		WITHDRAW,
		LEAVE
	}

	private enum BankPurpose
	{
		DEPOSIT,
		HAMMER
	}

	private State state;
	private BankStage bankStage;
	private BankPurpose bankPurpose;
	private Walker walker;
	private int cooldown;
	private int actionAttempts;
	private int searchWait;
	private int wheelAttempts;
	private int bankClicks;
	private boolean hammerUnavailable;

	private boolean mining;
	private boolean miningAnimationSeen;
	private TileObject currentVein;
	private int miningLevel = -1;
	private long lastMineClickAt;

	private boolean depositClickSent;
	private boolean payDirtDeposited;
	private long depositClickedAt;
	private long washXpAtDeposit;
	private int searchClicks;
	private int searchMisses;
	private int sackCameraTurns;
	private int itemsBeforeSearch;
	private int levelUpDismissAttempts;

	public MotherlodeMinerScript()
	{
		super("Motherlode Miner",
			"Mines pay-dirt, deposits it at the hopper, searches the sack, and banks the ores.");
	}

	@Override
	protected void onStart()
	{
		state = State.MINE;
		bankStage = null;
		bankPurpose = BankPurpose.DEPOSIT;
		walker = null;
		cooldown = 0;
		actionAttempts = 0;
		searchWait = 0;
		wheelAttempts = 0;
		bankClicks = 0;
		hammerUnavailable = false;
		mining = false;
		miningAnimationSeen = false;
		currentVein = null;
		miningLevel = -1;
		lastMineClickAt = 0;
		depositClickSent = false;
		payDirtDeposited = false;
		depositClickedAt = 0;
		washXpAtDeposit = 0;
		searchClicks = 0;
		searchMisses = 0;
		sackCameraTurns = 0;
		itemsBeforeSearch = 0;
		levelUpDismissAttempts = 0;
		setStatus("Ready");
	}

	@Override
	protected void onGameTick()
	{
		ScriptContext context = getContext();

		if (!context.isLoggedIn())
		{
			setStatus("Waiting for login");
			return;
		}

		if (walker == null)
		{
			walker = new Walker(context.getClient(), context.getInput());
		}

		if (dismissLevelUp(context))
		{
			return;
		}

		int level = context.getClient().getRealSkillLevel(Skill.MINING);
		if (miningLevel < 0)
		{
			miningLevel = level;
		}
		else if (level != miningLevel)
		{
			// Levelling up interrupts mining. Drop the current click and wait
			// for the level-up animation to finish before starting again.
			miningLevel = level;
			mining = false;
			miningAnimationSeen = false;
			currentVein = null;
			cooldown = Math.max(cooldown, 3);
			setStatus("Mining level up");
			return;
		}

		if (cooldown > 0)
		{
			--cooldown;
			return;
		}

		switch (state)
		{
			case MINE:
				mine(context);
				break;
			case WALK_TO_HOPPER:
				walkToHopper(context);
				break;
			case HOPPER:
				hopper(context);
				break;
			case WALK_TO_SACK:
				walkToSack(context);
				break;
			case SACK:
				sack(context);
				break;
			case WALK_TO_BANK:
				walkToBank(context);
				break;
			case BANK:
				bank(context);
				break;
			case WALK_TO_VEIN:
				walkToVein(context);
				break;
			case FIX_WHEEL:
				fixWheel(context);
				break;
			case HALTED:
			default:
				break;
		}
	}

	private void mine(ScriptContext context)
	{
		Inventory inventory = context.getInventory();
		TileObject brokenStrut = findBrokenStrut(context);
		if (inventory.isFull()
			|| (brokenStrut != null && inventory.getEmptySlots() <= 1))
		{
			if (brokenStrut != null)
			{
				// Stop one slot early so the bank hammer fits without
				// dropping any pay-dirt.
				state = State.FIX_WHEEL;
				walker.reset();
				actionAttempts = 0;
				wheelAttempts = 0;
				mining = false;
				miningAnimationSeen = false;
				currentVein = null;
				setStatus("Fixing the water wheels");
				return;
			}

			state = State.WALK_TO_HOPPER;
			walker.reset();
			actionAttempts = 0;
			mining = false;
			miningAnimationSeen = false;
			currentVein = null;
			setStatus("Walking to the hopper");
			return;
		}

		Player player = context.getPlayer();
		if (mining)
		{
			if (player != null && player.getAnimation() != -1)
			{
				miningAnimationSeen = true;
				setStatus("Mining pay-dirt");
				return;
			}

			// Mining continues from a single click. The animation can briefly
			// disappear between ores, so only click again once the vein is gone
			// or the click never started an animation.
			if (veinStillExists(context, currentVein)
				&& (miningAnimationSeen
					|| System.currentTimeMillis() - lastMineClickAt < MINE_START_TIMEOUT_MS))
			{
				setStatus("Mining pay-dirt");
				return;
			}

			mining = false;
			miningAnimationSeen = false;
			currentVein = null;
		}

		if (player != null && player.getAnimation() != -1
			&& player.getAnimation() != AnimationID.LEVELUP_ANIM)
		{
			setStatus("Waiting for animation");
			return;
		}

		TileObject vein = findVein(context);
		if (vein == null)
		{
			WorldPoint location = context.getPlayerLocation();
			if (location != null && location.distanceTo(VEIN_AREA) > VEIN_WALK_DISTANCE)
			{
				walker.walkTo(VEIN_AREA, true, WALK_STOP_DISTANCE);
				setStatus("Walking to the veins");
			}
			else
			{
				setStatus("Waiting for a vein");
			}
			return;
		}

		WorldPoint location = context.getPlayerLocation();
		if (location != null && location.distanceTo(vein.getWorldLocation()) > VEIN_WALK_DISTANCE)
		{
			walker.walkTo(vein.getWorldLocation(), true, WALK_STOP_DISTANCE);
			setStatus("Walking to a vein");
			return;
		}

		GameInput input = context.getInput();
		if (input.isBusy())
		{
			setStatus("Moving mouse");
			return;
		}

		if (isMoving(context))
		{
			setStatus("Waiting to stop");
			return;
		}

		if (!input.isVisible(vein))
		{
			input.turnCameraToward(vein.getWorldLocation());
			setStatus("Turning camera to a vein");
			return;
		}

		input.stopCamera();
		if (input.interact(vein, "Mine") || input.click(vein))
		{
			mining = true;
			miningAnimationSeen = false;
			currentVein = vein;
			lastMineClickAt = System.currentTimeMillis();
			cooldown = CLICK_COOLDOWN;
			setStatus("Mining pay-dirt");
		}
		else
		{
			cooldown = 1;
			setStatus("Waiting for a vein");
		}
	}

	private void walkToHopper(ScriptContext context)
	{
		WorldPoint location = context.getPlayerLocation();
		if (location == null)
		{
			return;
		}

		TileObject hopper = context.getTargets().findTileObject(HOPPER_ID, OBJECT_REACH + 2).orElse(null);
		if (hopper != null && location.distanceTo(hopper.getWorldLocation()) <= WALK_STOP_DISTANCE)
		{
			state = State.HOPPER;
			actionAttempts = 0;
			depositClickSent = false;
			setStatus("Depositing pay-dirt");
			return;
		}

		walker.walkTo(HOPPER_AREA, true, WALK_STOP_DISTANCE);
		setStatus("Walking to the hopper");
	}

	private void hopper(ScriptContext context)
	{
		Inventory inventory = context.getInventory();
		if (inventory.countItem(ItemID.PAYDIRT) == 0)
		{
			payDirtDeposited = depositClickSent;
			state = State.WALK_TO_SACK;
			walker.reset();
			actionAttempts = 0;
			searchClicks = 0;
			setStatus("Walking to the sack");
			return;
		}

		if (actionAttempts >= ACTION_ATTEMPTS)
		{
			// The sack is probably too full to accept more pay-dirt. Go and
			// empty it, then try the hopper again on a later trip.
			payDirtDeposited = false;
			state = State.WALK_TO_SACK;
			walker.reset();
			actionAttempts = 0;
			searchClicks = 0;
			setStatus("Walking to the sack");
			return;
		}

		TileObject hopper = context.getTargets().findTileObject(HOPPER_ID, OBJECT_REACH + 2).orElse(null);
		if (hopper == null)
		{
			state = State.WALK_TO_HOPPER;
			setStatus("Looking for the hopper");
			return;
		}

		GameInput input = context.getInput();
		if (input.isBusy())
		{
			setStatus("Moving mouse");
			return;
		}

		if (isMoving(context))
		{
			setStatus("Waiting to stop");
			return;
		}

		if (!input.isVisible(hopper))
		{
			input.turnCameraToward(hopper.getWorldLocation());
			setStatus("Turning camera to the hopper");
			return;
		}

		input.stopCamera();
		washXpAtDeposit = context.getClient().getSkillExperience(Skill.MINING);
		depositClickedAt = System.currentTimeMillis();
		++actionAttempts;

		if (input.interact(hopper, "Deposit") || input.click(hopper))
		{
			depositClickSent = true;
			cooldown = CLICK_COOLDOWN;
			setStatus("Depositing pay-dirt");
		}
		else
		{
			cooldown = 1;
		}
	}

	private void walkToSack(ScriptContext context)
	{
		WorldPoint location = context.getPlayerLocation();
		if (location == null)
		{
			return;
		}

		TileObject sack = findSack(context);
		if ((sack != null && location.distanceTo(sack.getWorldLocation()) <= WALK_STOP_DISTANCE)
			|| location.distanceTo(SACK_AREA) <= WALK_STOP_DISTANCE)
		{
			state = State.SACK;
			searchWait = 0;
			searchClicks = 0;
			searchMisses = 0;
			sackCameraTurns = 0;
			itemsBeforeSearch = 0;
			setStatus("Searching the sack");
			return;
		}

		walker.walkTo(SACK_AREA, true, WALK_STOP_DISTANCE);
		setStatus("Walking to the sack");
	}

	private void sack(ScriptContext context)
	{
		Inventory inventory = context.getInventory();

		if (searchWait > 0)
		{
			if (--searchWait > 0)
			{
				return;
			}

			if (countInventoryItems(inventory) > itemsBeforeSearch)
			{
				finishSackTrip(context, inventory);
				return;
			}

			++searchMisses;
		}

		boolean xpGained = context.getClient().getSkillExperience(Skill.MINING) > washXpAtDeposit;
		long now = System.currentTimeMillis();

		if (payDirtDeposited && !xpGained && now - depositClickedAt < WASH_TIMEOUT_MS)
		{
			setStatus("Waiting for pay-dirt to wash");
			return;
		}

		if (inventory.getEmptySlots() == 0 || searchClicks >= MAX_SEARCH_CLICKS)
		{
			finishSackTrip(context, inventory);
			return;
		}

		TileObject sack = findSack(context);
		if (sack == null)
		{
			state = State.WALK_TO_SACK;
			setStatus("Looking for the sack");
			return;
		}

		GameInput input = context.getInput();
		if (input.isBusy())
		{
			setStatus("Moving mouse");
			return;
		}

		if (isMoving(context))
		{
			setStatus("Waiting to stop");
			return;
		}

		if (!input.isVisible(sack))
		{
			if (++sackCameraTurns <= 3)
			{
				input.turnCameraToward(sack.getWorldLocation());
				setStatus("Turning camera to the sack");
				return;
			}
		}

		input.stopCamera();
		itemsBeforeSearch = countInventoryItems(inventory);

		// The sack is sometimes hidden behind the water wheel. If a normal
		// click did not collect anything, invoke its Search option directly.
		boolean clicked = searchMisses > 0 || sackCameraTurns > 3
			? input.invokeObjectAction(sack, "Search")
			: input.interact(sack, "Search") || input.click(sack);

		if (clicked)
		{
			++searchClicks;
			searchWait = SEARCH_WAIT_TICKS;
			cooldown = 1;
			setStatus("Searching the sack");
		}
		else
		{
			cooldown = 1;
		}
	}

	private void finishSackTrip(ScriptContext context, Inventory inventory)
	{
		payDirtDeposited = false;
		searchClicks = 0;
		bankPurpose = BankPurpose.DEPOSIT;
		if (hasBankableItems(inventory))
		{
			state = State.WALK_TO_BANK;
			walker.reset();
			actionAttempts = 0;
			setStatus("Walking to the bank");
		}
		else
		{
			state = State.WALK_TO_VEIN;
			walker.reset();
			setStatus("Walking back to the veins");
		}
	}

	private void walkToBank(ScriptContext context)
	{
		WorldPoint location = context.getPlayerLocation();
		if (location == null)
		{
			return;
		}

		// Drop exactly one pay-dirt before heading to the bank for a hammer:
		// pay-dirt cannot be banked, so this is the only way to free a slot.
		Inventory inventory = context.getInventory();
		if (bankPurpose == BankPurpose.HAMMER && inventory.getEmptySlots() == 0
			&& inventory.countItem(ItemID.PAYDIRT) > 0)
		{
			GameInput input = context.getInput();
			if (!input.isBusy() && dropOnePayDirt(context))
			{
				cooldown = 1;
			}
			setStatus("Dropping pay-dirt to make room for the hammer");
			return;
		}

		TileObject chest = context.getTargets().findTileObject(BANK_CHEST_ID, OBJECT_REACH + 3).orElse(null);
		if (chest != null && location.distanceTo(chest.getWorldLocation()) <= OBJECT_REACH)
		{
			state = State.BANK;
			bankStage = null;
			actionAttempts = 0;
			bankClicks = 0;
			setStatus("Banking ores");
			return;
		}

		walker.walkTo(BANK_AREA, true, WALK_STOP_DISTANCE);
		setStatus("Walking to the bank");
	}

	private void bank(ScriptContext context)
	{
		Inventory inventory = context.getInventory();
		GameInput input = context.getInput();
		Widget bank = context.getTargets().findWidget(InterfaceID.Bankmain.UNIVERSE).orElse(null);

		// Never open the bank for a hammer with a full inventory: pay-dirt
		// cannot be banked, so drop one here first and only then click the
		// chest. A full inventory of ores is fine because those deposit.
		if (bank == null && bankStage == null && bankPurpose == BankPurpose.HAMMER
			&& inventory.getEmptySlots() == 0 && inventory.countItem(ItemID.PAYDIRT) > 0)
		{
			if (!input.isBusy() && dropOnePayDirt(context))
			{
				cooldown = 1;
			}
			setStatus("Dropping pay-dirt to make room for the hammer");
			return;
		}

		if (bankStage == BankStage.LEAVE)
		{
			if (bank != null)
			{
				if (!input.isBusy())
				{
					input.pressKey(KeyEvent.VK_ESCAPE);
					cooldown = 1;
				}
				setStatus("Closing bank");
				return;
			}

			bankStage = null;
			actionAttempts = 0;
			payDirtDeposited = false;
			walker.reset();

			if (bankPurpose == BankPurpose.HAMMER)
			{
				bankPurpose = BankPurpose.DEPOSIT;
				hammerUnavailable = inventory.countItem(ItemID.HAMMER) == 0;
				if (!hammerUnavailable && findBrokenStrut(context) != null)
				{
					state = State.FIX_WHEEL;
					wheelAttempts = 0;
					setStatus("Walking to the broken water wheel");
				}
				else
				{
					finishWheelTrip(inventory);
				}
				return;
			}

			state = State.WALK_TO_VEIN;
			setStatus("Walking back to the veins");
			return;
		}

		if (bank == null)
		{
			bankStage = null;
			actionAttempts = 0;

			if (input.isBusy())
			{
				setStatus("Moving mouse");
				return;
			}

			if (isMoving(context))
			{
				setStatus("Waiting to stop");
				return;
			}

			TileObject chest = context.getTargets().findTileObject(BANK_CHEST_ID, OBJECT_REACH + 3).orElse(null);
			if (chest == null)
			{
				walker.walkTo(BANK_AREA, true, WALK_STOP_DISTANCE);
				setStatus("Looking for the bank chest");
				return;
			}

			WorldPoint location = context.getPlayerLocation();
			if (location != null && location.distanceTo(chest.getWorldLocation()) > OBJECT_REACH)
			{
				walker.walkTo(chest.getWorldLocation(), true, WALK_STOP_DISTANCE);
				setStatus("Walking to the bank chest");
				return;
			}

			input.stopCamera();
			boolean clicked;
			if (bankClicks < 3)
			{
				++bankClicks;
				if (!input.isVisible(chest))
				{
					input.turnCameraToward(chest.getWorldLocation());
					setStatus("Turning camera to the bank chest");
					return;
				}

				// Move the mouse onto the chest and click it like a player
				// would, instead of running the action invisibly.
				// Run the chest's exact Use/Bank action instead of a raw
				// left click. A left click can land on another object that
				// overlaps the chest's clickbox and walk or mine instead.
				clicked = input.invokeObjectAction(chest, "Use", "Bank");
			}
			else
			{
				// Physical clicks keep missing; use the chest's menu action
				// so a bad camera angle cannot stall the trip.
				clicked = input.interact(chest, "Use", "Bank")
					|| input.invokeObjectAction(chest, "Use", "Bank");
			}

			if (clicked)
			{
				cooldown = CLICK_COOLDOWN;
				setStatus("Opening bank");
			}
			else
			{
				cooldown = 1;
			}
			return;
		}

		if (bankStage == null)
		{
			bankStage = bankPurpose == BankPurpose.HAMMER
				? BankStage.WITHDRAW
				: BankStage.DEPOSIT;
			actionAttempts = 0;
		}

		if (input.isBusy())
		{
			setStatus("Moving mouse");
			return;
		}

		switch (bankStage)
		{
			case DEPOSIT:
			{
				if (countInventoryItems(inventory) == 0)
				{
					bankStage = BankStage.LEAVE;
					return;
				}

				Widget deposit = context.getTargets().findWidget(InterfaceID.Bankmain.DEPOSITINV).orElse(null);
				if (deposit == null)
				{
					cooldown = 1;
					setStatus("Waiting for bank");
					return;
				}

				if (input.click(deposit))
				{
					bankStage = BankStage.WAIT_DEPOSIT;
					actionAttempts = 0;
					cooldown = CLICK_COOLDOWN;
				}
				else
				{
					cooldown = 1;
				}
				setStatus("Depositing ores");
				return;
			}
			case WAIT_DEPOSIT:
			{
				if (countInventoryItems(inventory) == 0)
				{
					bankStage = BankStage.LEAVE;
					return;
				}

				if (++actionAttempts > ACTION_ATTEMPTS)
				{
					bankStage = BankStage.DEPOSIT;
					actionAttempts = 0;
				}
				setStatus("Depositing ores");
				return;
			}
			case MAKE_ROOM:
			{
				if (inventory.getEmptySlots() > 0)
				{
					bankStage = BankStage.WITHDRAW;
					actionAttempts = 0;
					cooldown = 1;
					return;
				}

				if (++actionAttempts > ACTION_ATTEMPTS)
				{
					hammerUnavailable = true;
					bankStage = BankStage.LEAVE;
					return;
				}

				// Pay-dirt cannot be stored in the bank, so drop one to
				// free a slot for the hammer.
				if (inventory.countItem(ItemID.PAYDIRT) > 0)
				{
					if (dropOnePayDirt(context))
					{
						cooldown = CLICK_COOLDOWN;
					}
					else
					{
						cooldown = 1;
					}
					setStatus("Dropping pay-dirt to make room for the hammer");
					return;
				}

				// No pay-dirt left: the inventory is full of bankable ores.
				Widget deposit = context.getTargets().findWidget(InterfaceID.Bankmain.DEPOSITINV).orElse(null);
				if (deposit == null)
				{
					cooldown = 1;
					setStatus("Waiting for bank");
					return;
				}

				if (input.click(deposit))
				{
					cooldown = CLICK_COOLDOWN;
				}
				else
				{
					cooldown = 1;
				}
				setStatus("Depositing ores to make room for the hammer");
				return;
			}
			case WITHDRAW:
			{
				if (inventory.countItem(ItemID.HAMMER) > 0)
				{
					bankStage = BankStage.LEAVE;
					return;
				}

				if (inventory.getEmptySlots() == 0)
				{
					// Already at the bank with a full inventory: deposit
					// everything so there is a slot for the hammer instead
					// of dropping items or bouncing back to the veins.
					bankStage = BankStage.MAKE_ROOM;
					actionAttempts = 0;
					cooldown = 1;
					setStatus("Making room for the hammer");
					return;
				}

				// Withdraw by item id and bank slot. The old physical
				// click used the first widget that reported the hammer id,
				// which could be a stale or off-tab slot and click the
				// wrong item.
				boolean withdrew = input.withdrawBankItem(ItemID.HAMMER, "Withdraw-1");
				if (withdrew)
				{
					// The action was sent but the hammer may still not
					// arrive (wrong tab/scroll state). Stop retrying instead
					// of looping on the bank forever.
					if (++actionAttempts > ACTION_ATTEMPTS * 2)
					{
						hammerUnavailable = true;
						bankStage = BankStage.LEAVE;
					}
					cooldown = CLICK_COOLDOWN;
				}
				else if (++actionAttempts > ACTION_ATTEMPTS)
				{
					// No hammer in the bank. Skip the repair instead of
					// bouncing between the bank and the wheels forever.
					hammerUnavailable = true;
					bankStage = BankStage.LEAVE;
				}
				else
				{
					cooldown = 1;
				}
				setStatus("Withdrawing a hammer");
				return;
			}
			case LEAVE:
			default:
				return;
		}
	}

	private void fixWheel(ScriptContext context)
	{
		Inventory inventory = context.getInventory();
		TileObject strut = findBrokenStrut(context);
		if (strut == null)
		{
			finishWheelTrip(inventory);
			return;
		}

		if (inventory.countItem(ItemID.HAMMER) == 0)
		{
			if (hammerUnavailable)
			{
				finishWheelTrip(inventory);
				return;
			}

			if (inventory.getEmptySlots() == 0)
			{
				if (dropOnePayDirt(context))
				{
					cooldown = 1;
					setStatus("Dropping pay-dirt for the hammer");
				}
				else
				{
					setStatus("Making room for the hammer");
				}
				return;
			}

			bankPurpose = BankPurpose.HAMMER;
			bankStage = null;
			state = State.WALK_TO_BANK;
			walker.reset();
			actionAttempts = 0;
			setStatus("Walking to the bank for a hammer");
			return;
		}

		hammerUnavailable = false;

		WorldPoint location = context.getPlayerLocation();
		if (location == null)
		{
			return;
		}

		if (location.distanceTo(strut.getWorldLocation()) > STRUT_REACH)
		{
			walker.walkTo(strut.getWorldLocation(), true, WALK_STOP_DISTANCE);
			setStatus("Walking to the broken water wheel");
			return;
		}

		GameInput input = context.getInput();
		if (input.isBusy())
		{
			setStatus("Moving mouse");
			return;
		}

		if (isMoving(context))
		{
			setStatus("Waiting to stop");
			return;
		}

		Player player = context.getPlayer();
		if (player != null && player.getAnimation() != -1
			&& player.getAnimation() != AnimationID.LEVELUP_ANIM)
		{
			setStatus("Fixing the water wheel");
			return;
		}

		if (!input.isVisible(strut))
		{
			input.turnCameraToward(strut.getWorldLocation());
			setStatus("Turning camera to the broken water wheel");
			return;
		}

		if (++wheelAttempts > ACTION_ATTEMPTS * 2)
		{
			finishWheelTrip(inventory);
			return;
		}

		input.stopCamera();
		if (input.interact(strut, "Hammer", "Fix", "Use") || input.click(strut))
		{
			cooldown = CLICK_COOLDOWN;
			setStatus("Fixing the water wheel");
		}
		else
		{
			cooldown = 1;
		}
	}

	private void finishWheelTrip(Inventory inventory)
	{
		walker.reset();
		actionAttempts = 0;
		wheelAttempts = 0;
		bankPurpose = BankPurpose.DEPOSIT;

		if (inventory.countItem(ItemID.PAYDIRT) > 0)
		{
			state = State.WALK_TO_HOPPER;
			setStatus("Walking to the hopper");
		}
		else
		{
			state = State.WALK_TO_VEIN;
			setStatus("Walking back to the veins");
		}
	}

	private static boolean dropOnePayDirt(ScriptContext context)
	{
		Inventory inventory = context.getInventory();
		if (!inventory.isOpen())
		{
			inventory.open();
			return false;
		}

		int slot = inventory.findSlot(ItemID.PAYDIRT);
		Widget item = slot < 0 ? null : inventory.getSlotWidget(slot);
		if (item == null || item.isHidden())
		{
			return false;
		}

		GameInput input = context.getInput();
		// Pay-dirt's left-click option is set to Drop, so a plain click on the
		// slot drops it, just like a player would.
		return input.click(item);
	}

	private TileObject findBrokenStrut(ScriptContext context)
	{
		Player player = context.getPlayer();
		WorldPoint location = player == null ? null : player.getWorldLocation();
		if (location == null)
		{
			return null;
		}

		TileObject best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (WorldPoint area : new WorldPoint[]{STRUT_WEST, STRUT_EAST})
		{
			for (TileObject object : context.getTargets().getTileObjectsNear(area, STRUT_RADIUS))
			{
				if (object.getId() != STRUT_BROKEN_ID)
				{
					continue;
				}

				WorldPoint objectLocation = object.getWorldLocation();
				if (objectLocation == null || objectLocation.getPlane() != location.getPlane())
				{
					continue;
				}

				int distance = objectLocation.distanceTo(location);
				if (distance < bestDistance)
				{
					bestDistance = distance;
					best = object;
				}
			}
		}
		return best;
	}

	private void walkToVein(ScriptContext context)
	{
		WorldPoint location = context.getPlayerLocation();
		if (location == null)
		{
			return;
		}

		if (location.distanceTo(VEIN_AREA) <= VEIN_WALK_DISTANCE || findVein(context) != null)
		{
			state = State.MINE;
			setStatus("Mining pay-dirt");
			return;
		}

		walker.walkTo(VEIN_AREA, true, WALK_STOP_DISTANCE);
		setStatus("Walking back to the veins");
	}

	/**
	 * The level-up scroll stays open until it is dismissed, and the client
	 * keeps the character in the level-up animation while it is open, which
	 * would otherwise leave the script standing at the vein forever.
	 */
	private boolean dismissLevelUp(ScriptContext context)
	{
		Widget dialog = context.getClient().getWidget(InterfaceID.LevelupDisplay.UNIVERSE);
		if (dialog == null || dialog.isHidden())
		{
			levelUpDismissAttempts = 0;
			return false;
		}

		GameInput input = context.getInput();
		if (!input.isBusy())
		{
			Widget cont = context.getClient().getWidget(InterfaceID.LevelupDisplay.CONTINUE);
			boolean clicked = ++levelUpDismissAttempts <= 3
				&& cont != null && !cont.isHidden() && input.click(cont);
			if (!clicked)
			{
				input.pressKey(KeyEvent.VK_SPACE);
			}
			cooldown = Math.max(cooldown, 1);
		}
		setStatus("Dismissing level up");
		return true;
	}

	private static boolean isMoving(ScriptContext context)
	{
		Player player = context.getPlayer();
		return player != null && player.getPoseAnimation() != player.getIdlePoseAnimation();
	}

	private static boolean veinStillExists(ScriptContext context, TileObject vein)
	{
		if (vein == null)
		{
			return false;
		}

		WorldPoint location = vein.getWorldLocation();
		for (TileObject object : context.getTargets().getTileObjectsNear(location, 1))
		{
			if (object.getId() == vein.getId() && location.equals(object.getWorldLocation()))
			{
				return true;
			}
		}
		return false;
	}

	private TileObject findVein(ScriptContext context)
	{
		Player player = context.getPlayer();
		WorldPoint location = player == null ? null : player.getWorldLocation();
		if (location == null)
		{
			return null;
		}

		TileObject best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (TileObject object : context.getTargets().getTileObjectsNear(VEIN_AREA, VEIN_RADIUS))
		{
			if (!isVein(object.getId()))
			{
				continue;
			}

			WorldPoint objectLocation = object.getWorldLocation();
			if (objectLocation == null || objectLocation.getPlane() != location.getPlane())
			{
				continue;
			}

			int distance = objectLocation.distanceTo(location);
			if (distance < bestDistance)
			{
				bestDistance = distance;
				best = object;
			}
		}
		return best;
	}

	private TileObject findSack(ScriptContext context)
	{
		TileObject sack = context.getTargets().findTileObject(SACK_ID, OBJECT_REACH + 2).orElse(null);
		if (sack != null)
		{
			return sack;
		}

		TileObject full = context.getTargets().findTileObject(SACK_FULL_ID, OBJECT_REACH + 2).orElse(null);
		return full != null
			? full
			: context.getTargets().findTileObject(SACK_EMPTY_ID, OBJECT_REACH + 2).orElse(null);
	}

	private static boolean isVein(int objectId)
	{
		for (int veinId : VEIN_IDS)
		{
			if (veinId == objectId)
			{
				return true;
			}
		}
		return false;
	}

	private static int countInventoryItems(Inventory inventory)
	{
		int count = 0;
		for (Item item : inventory.getItems())
		{
			if (item != null && item.getId() != -1 && item.getId() != ItemID.PAYDIRT)
			{
				++count;
			}
		}
		return count;
	}

	private static boolean hasBankableItems(Inventory inventory)
	{
		return countInventoryItems(inventory) > 0;
	}
}
