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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.widgets.Widget;
import net.runelite.rsbuddy.input.GameInput;
import net.runelite.rsbuddy.inventory.Inventory;
import net.runelite.rsbuddy.script.Script;
import net.runelite.rsbuddy.script.ScriptContext;

/**
 * Kills hill giants in the Edgeville Dungeon, picks up big bones, body
 * talismans, and uncut diamonds, and banks the loot at the west Varrock bank
 * before returning.
 */
public final class HillGiantScript extends Script
{
	private static final String NPC_NAME = "Hill Giant";
	private static final String ATTACK_ACTION = "Attack";

	private static final int MAX_DISTANCE = 25;
	private static final int COOLDOWN_TICKS = 3;

	// Picking a drop up is retried on the next tick: waiting out the full
	// interaction cooldown made a missed pickup take several seconds.
	private static final int LOOT_COOLDOWN_TICKS = 1;
	private static final int LOOT_ATTEMPT_LIMIT = 12;
	private static final int LOOT_TARGET_TIMEOUT_TICKS = 100;

	private static final WorldPoint HILL_GIANTS = new WorldPoint(3116, 9853, 0);
	private static final WorldPoint DUNGEON_LADDER = new WorldPoint(3116, 9853, 0);
	private static final WorldPoint SURFACE_DOOR = new WorldPoint(3115, 3450, 0);
	private static final WorldPoint SURFACE_LADDER = new WorldPoint(3116, 3453, 0);
	private static final WorldPoint WEST_VARROCK_BANK = new WorldPoint(3186, 3444, 0);

	private static final int DOOR_ID = 1804;
	private static final int LADDER_ID = 17384;
	private static final int BANK_BOOTH_ID = 10583;

	private static final int BRASS_KEY_ID = ItemID.EDGEVILLEDUNGEONKEY;
	private static final int BRASS_KEY_AMOUNT = 1;
	private static final int BANK_STAGE_ATTEMPTS = 20;

	// Closing the bank is retried for this many ticks before the script gives
	// up on the interface and leaves anyway, so it cannot stall at the bank.
	private static final int BANK_CLOSE_ATTEMPTS = 12;

	private static final int EAT_THRESHOLD_PERCENT = 50;

	// Loot that could not be reached is retried after this delay instead of
	// being ignored for the whole trip. This also gives drops that are still
	// private to another player time to become takeable.
	private static final long IGNORED_LOOT_TIMEOUT_MS = 60_000L;

	// A menu that stays open for more than a few ticks was not opened by the
	// current interaction. The script closes it so no state can block behind
	// it. An input click that never finishes is reset after a longer wait.
	private static final int MENU_STUCK_TICKS = 6;
	private static final int BUSY_STUCK_TICKS = 100;

	// A door click that did not actually open the door is retried after this
	// many ticks. Without it a failed click would leave the door marked open
	// and the script would keep walking into it.
	private static final int SHED_DOOR_TIMEOUT_TICKS = 20;

	// The drops of a kill appear a tick or two after the giant dies, so the
	// script waits this long for them before moving on to the next giant.

	private static final int POST_KILL_LOOT_TICKS = 3;

	/**
	 * Only the requested hill giant drops are picked up.
	 */
	private static final java.util.Set<Integer> LOOT_ITEMS = java.util.Collections.unmodifiableSet(
		new java.util.HashSet<>(java.util.Arrays.asList(
			ItemID.BIG_BONES,
			ItemID.BODY_TALISMAN,
			ItemID.UNCUT_DIAMOND,
			ItemID.UNIDENTIFIED_RANARR,
			ItemID.NATURE_RUNE,
			ItemID.GRIMY_KWUARM,
			ItemID.GRIMY_DWARF_WEED,
			ItemID.LAW_RUNE)));

	private static boolean isLootItem(int itemId)
	{
		return LOOT_ITEMS.contains(itemId);
	}

	/**
	 * While this script runs only these drops stay clickable on the ground, so
	 * walking or attacking can never pick up anything else.
	 */
	@Override
	public java.util.Set<Integer> getGroundItemPickupFilter()
	{
		return LOOT_ITEMS;
	}

	private enum State
	{
		HUNT,
		LOOT,
		WALK_TO_BANK,
		BANK,
		WALK_TO_GIANTS
	}

	private enum BankStage
	{
		DEPOSIT,
		WAIT_DEPOSIT,
		WITHDRAW_KEY,
		LEAVE
	}

	private static final int[][] WALK_DIRECTIONS = {
		{0, 1}, {1, 0}, {1, 1}, {1, -1},
		{0, -1}, {-1, 0}, {-1, -1}, {-1, 1}
	};

	private State state;
	private int cooldown;
	private WorldPoint lastWalkLocation;
	private int stationaryTicks;
	private WorldPoint lootTarget;
	private int lootTargetItemId;
	private int lootAttempts;
	private int lootTargetTicks;
	private BankStage bankStage;
	private int bankAttempts;
	private boolean returningFromBank;
	private boolean shedDoorOpen;
	private final java.util.Map<WorldPoint, Long> ignoredLoot = new java.util.HashMap<>();
	private int menuOpenTicks;
	private int busyTicks;
	private int shedDoorTicks;
	private int bankCloseTicks;
	private NPC attackTarget;
	private boolean killLootPending;
	private int killLootTicks;

	public HillGiantScript()
	{
		super("Hill Giant (Edgeville)", "Kills hill giants in the Edgeville Dungeon and banks loot.");
	}

	@Override
	protected void onStart()
	{
		// onStart runs on the Swing thread, so it must not touch the client.
		// The initial state is chosen on the first game tick instead.
		cooldown = 0;
		state = null;
		lastWalkLocation = null;
		stationaryTicks = 0;
		lootTarget = null;
		lootTargetItemId = -1;
		lootAttempts = 0;
		lootTargetTicks = 0;
		bankStage = null;
		bankAttempts = 0;
		returningFromBank = false;
		shedDoorOpen = false;
		shedDoorTicks = 0;
		ignoredLoot.clear();
		menuOpenTicks = 0;
		busyTicks = 0;
		attackTarget = null;
		killLootPending = false;
		killLootTicks = 0;
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

		recoverStuckInput(context);
		updateShedDoorState(context);

		if (state == null)
		{
			WorldPoint location = context.getPlayerLocation();
			if (location != null && location.getY() < 9000)
			{
				// Starting on the surface means the shed door is between
				// us and the ladder, so approach the door first.
				returningFromBank = true;
				state = State.WALK_TO_GIANTS;
			}
			else
			{
				state = State.HUNT;
			}
		}

		if (cooldown > 0)
		{
			--cooldown;
			return;
		}

		eatIfNeeded(context);

		switch (state)
		{
			case HUNT:
				hunt(context);
				break;
			case LOOT:
				loot(context);
				break;
			case WALK_TO_BANK:
				walkToBank(context);
				break;
			case BANK:
				bank(context);
				break;
			case WALK_TO_GIANTS:
				walkToGiants(context);
				break;
		}
	}

	/**
	 * Clears input state that would otherwise block every state forever: a
	 * menu that nobody consumed, and a simulated click that never finished.
	 */
	private void recoverStuckInput(ScriptContext context)
	{
		Client client = context.getClient();
		GameInput input = context.getInput();

		if (!client.isMenuOpen())
		{
			menuOpenTicks = 0;
		}
		else if (++menuOpenTicks > MENU_STUCK_TICKS)
		{
			menuOpenTicks = MENU_STUCK_TICKS;
			if (!input.isBusy())
			{
				setStatus("Closing stuck menu");
				input.closeMenu();
			}
		}

		if (input.isBusy())
		{
			if (++busyTicks > BUSY_STUCK_TICKS)
			{
				input.reset();
				busyTicks = 0;
				setStatus("Resetting input");
			}
		}
		else
		{
			busyTicks = 0;
		}

		// An open bank swallows every walk click. The bank state closes it
		// itself; anywhere else a leftover interface is closed here, so a
		// missed close cannot stall the script after banking.
		if (state != State.BANK
			&& context.getTargets().findWidget(InterfaceID.Bankmain.UNIVERSE).isPresent())
		{
			if (!input.isBusy())
			{
				// Alternate both ways of closing it: Escape is what the client
				// itself uses, the button click covers a widget that has taken
				// keyboard focus.
				if (++bankCloseTicks % 2 == 1)
				{
					input.pressKey(java.awt.event.KeyEvent.VK_ESCAPE);
				}
				else
				{
					input.closeInterface(InterfaceID.Bankmain.UNIVERSE);
				}
			}
		}
		else
		{
			bankCloseTicks = 0;
		}
	}

	private void hunt(ScriptContext context)
	{
		Inventory inventory = context.getInventory();

		// A menu left over from a previous loot/interaction would block
		// clicking giants and walking. Close it before hunting.
		if (context.getClient().isMenuOpen())
		{
			context.getInput().closeMenu();
			return;
		}

		if (inventory.isFull())
		{
			state = State.WALK_TO_BANK;
			setStatus("Inventory full");
			return;
		}

		// A kill was just finished. Its drops are taken before any other giant
		// is attacked, even when one has already attacked us: auto-retaliate
		// keeps that fight going while the drops are picked up, so the loot of
		// every single kill is taken.
		if (killLootPending)
		{
			GroundLoot drop = findGroundLoot(context);
			if (drop != null)
			{
				killLootPending = false;
				killLootTicks = 0;
				state = State.LOOT;
				setStatus("Looting");
				return;
			}

			// Drops spawn a tick or two after the giant dies. Wait for them
			// instead of walking off, or the kill yields nothing.
			if (++killLootTicks <= POST_KILL_LOOT_TICKS)
			{
				setStatus("Waiting for drops");
				return;
			}

			killLootPending = false;
			killLootTicks = 0;
		}

		Player player = context.getPlayer();
		NPC interacting = player != null && player.getInteracting() instanceof NPC
			? (NPC) player.getInteracting()
			: null;
		if (interacting != null)
		{
			if (interacting.isDead() || isKillFinished(context, attackTarget))
			{
				// The giant is dead. Loot its drops now rather than starting
				// another fight first.
				killLootPending = true;
				killLootTicks = 0;
				attackTarget = null;
				setStatus("Kill finished");
				return;
			}

			// Any active NPC interaction blocks a new attack. Auto-retaliate
			// may have started a fight with something other than a hill
			// giant, so wait for that fight to finish instead of spamming
			// attack clicks that the game will reject.
			String interactingName = getNpcName(interacting);
			setStatus("Fighting " + interactingName);
			return;
		}

		// Nothing is being fought any more, but the giant we attacked may have
		// died on this very tick: its drops still have to spawn before the
		// next giant is attacked.
		if (isKillFinished(context, attackTarget))
		{
			killLootPending = true;
			killLootTicks = 0;
			attackTarget = null;
			setStatus("Kill finished");
			return;
		}

		// Nothing is being fought, so any wanted drop on the ground is taken
		// before the next giant is attacked.
		GroundLoot loot = findGroundLoot(context);
		if (loot != null)
		{
			state = State.LOOT;
			setStatus("Looting");
			return;
		}

		GameInput input = context.getInput();
		if (input.isBusy())
		{
			setStatus("Moving mouse");
			return;
		}

		Optional<NPC> target = findHillGiant(context);
		if (target.isPresent())
		{
			if (input.interact(target.get(), ATTACK_ACTION))
			{
				attackTarget = target.get();
				killLootPending = false;
				killLootTicks = 0;
				cooldown = COOLDOWN_TICKS;
				setStatus("Attacking " + NPC_NAME);
			}
			else
			{
				cooldown = 2;
				setStatus("Cannot attack " + NPC_NAME);
			}
			return;
		}

		openDoor(context);
		cooldown = 2;
		setStatus("No " + NPC_NAME + " nearby");
	}

	private void loot(ScriptContext context)
	{
		Inventory inventory = context.getInventory();

		if (inventory.isFull())
		{
			state = State.WALK_TO_BANK;
			setStatus("Inventory full");
			return;
		}

		GroundLoot loot = findGroundLoot(context);
		if (loot == null)
		{
			context.getInput().closeMenu();
			state = State.HUNT;
			setStatus("Hunting");
			return;
		}

		GameInput input = context.getInput();
		if (input.isBusy())
		{
			setStatus("Moving mouse");
			return;
		}

		if (!java.util.Objects.equals(lootTarget, loot.worldLocation)
			|| lootTargetItemId != loot.itemId)
		{
			lootTarget = loot.worldLocation;
			lootTargetItemId = loot.itemId;
			lootAttempts = 0;
			lootTargetTicks = 0;
		}

		if (++lootTargetTicks > LOOT_TARGET_TIMEOUT_TICKS && loot.worldLocation != null)
		{
			// The drop is probably behind an obstacle or otherwise
			// unreachable. Ignore it so the script does not get stuck.
			ignoreLoot(loot.worldLocation);
			lootTarget = null;
			lootTargetItemId = -1;
			lootAttempts = 0;
			lootTargetTicks = 0;
			state = State.HUNT;
			setStatus("Skipping unreachable loot");
			return;
		}

		Player player = context.getPlayer();
		WorldPoint location = player == null ? null : player.getWorldLocation();
		if (location != null && loot.worldLocation != null
			&& location.distanceTo(loot.worldLocation) > 2)
		{
			walkTowards(context, loot.worldLocation);
			lootAttempts = 0;
			setStatus("Walking to loot");
			return;
		}

		Client client = context.getClient();
		String itemName = getItemName(client, loot.itemId);
		input.takeGroundItem(loot.sceneX, loot.sceneY, loot.itemId, itemName);
		cooldown = LOOT_COOLDOWN_TICKS;

		if (++lootAttempts > LOOT_ATTEMPT_LIMIT && loot.worldLocation != null)
		{
			ignoreLoot(loot.worldLocation);
			lootTarget = null;
			lootTargetItemId = -1;
			lootAttempts = 0;
			lootTargetTicks = 0;
			state = State.HUNT;
			setStatus("Skipping unreachable loot");
			return;
		}

		setStatus(itemName.isEmpty() ? "Looting" : "Looting " + itemName);
	}

	private void walkToBank(ScriptContext context)
	{
		Player player = context.getPlayer();
		WorldPoint location = player == null ? null : player.getWorldLocation();
		if (location == null)
		{
			return;
		}

		if (!isInDungeon(location))
		{
			if (location.distanceTo(WEST_VARROCK_BANK) <= 8)
			{
				state = State.BANK;
				setStatus("Banking");
				return;
			}

			// After climbing up we are inside the shed with the brass-key
			// door shut behind us. Only open it from inside: clicking the
			// door from the outside pulls the player back to it, which used to
			// turn the walk to the bank into a walk back to the shed.
			if (!shedDoorOpen
				&& location.distanceTo(SURFACE_LADDER) <= 6
				&& openDoor(context))
			{
				setStatus("Opening door");
				return;
			}

			walkTowards(context, WEST_VARROCK_BANK, true);
			setStatus("Walking to bank");
		}
		else
		{
			if (location.distanceTo(DUNGEON_LADDER) <= 8)
			{
				climb(context, true, LADDER_ID);
			}
			else
			{
				walkTowards(context, DUNGEON_LADDER);
			}
			setStatus("Walking to bank");
		}
	}

	private void bank(ScriptContext context)
	{
		Inventory inventory = context.getInventory();
		GameInput input = context.getInput();

		Widget bankUniverse = context.getTargets().findWidget(InterfaceID.Bankmain.UNIVERSE).orElse(null);
		if (bankUniverse == null)
		{
			// The leave stage just closed the bank. Banking is finished, so
			// walk back instead of reopening the booth, which used to start
			// the whole deposit/withdraw cycle over again.
			if (bankStage == BankStage.LEAVE)
			{
				leaveBank();
				return;
			}

			bankStage = null;
			bankAttempts = 0;

			if (input.isBusy())
			{
				setStatus("Moving mouse");
				return;
			}

			Optional<GameObject> booth = context.getTargets().findVisibleObject(BANK_BOOTH_ID, 10);
			if (booth.isEmpty())
			{
				booth = context.getTargets().findVisibleObject("Bank booth", "Bank", 10);
			}
			if (booth.isPresent())
			{
				if (input.interact(booth.get(), "Bank"))
				{
					cooldown = COOLDOWN_TICKS;
					setStatus("Opening bank");
				}
				return;
			}

			Optional<NPC> banker = context.getTargets().findVisibleNpc("Banker", "Bank", 10);
			if (banker.isPresent())
			{
				if (input.interact(banker.get(), "Bank"))
				{
					cooldown = COOLDOWN_TICKS;
					setStatus("Opening bank");
				}
				return;
			}

			// No booth or banker is on screen yet. Move closer instead of
			// right-clicking an off-screen object, which would hit whatever
			// interface is drawn at that spot.
			walkTowards(context, WEST_VARROCK_BANK, true);
			cooldown = 2;
			setStatus("Walking to bank");
			return;
		}

		if (bankStage == null)
		{
			bankStage = BankStage.DEPOSIT;
			bankAttempts = 0;
		}

		if (input.isBusy())
		{
			setStatus("Moving mouse");
			return;
		}

		// The key withdraw stage handles its own context menu. Any other menu
		// would swallow the click on the deposit button.
		if (context.getClient().isMenuOpen()
			&& bankStage != BankStage.WITHDRAW_KEY)
		{
			input.closeMenu();
			return;
		}

		switch (bankStage)
		{
			case DEPOSIT:
			{
				Widget deposit = context.getTargets()
					.findWidget(InterfaceID.Bankmain.DEPOSITINV)
					.orElse(null);
				if (deposit != null && input.click(deposit))
				{
					bankStage = BankStage.WAIT_DEPOSIT;
					bankAttempts = 0;
					cooldown = COOLDOWN_TICKS;
				}
				else
				{
					++bankAttempts;
					cooldown = 1;
				}
				setStatus("Depositing loot");
				return;
			}
			case WAIT_DEPOSIT:
			{
				if (inventory.getEmptySlots() >= Inventory.SIZE)
				{
					bankStage = BankStage.WITHDRAW_KEY;
					bankAttempts = 0;
					setStatus("Withdrawing brass key");
					return;
				}

				if (++bankAttempts > BANK_STAGE_ATTEMPTS)
				{
					// The deposit click did not register. Try the button
					// again instead of waiting forever.
					bankStage = BankStage.DEPOSIT;
					bankAttempts = 0;
				}
				setStatus("Depositing loot");
				return;
			}
			case WITHDRAW_KEY:
			{
				if (inventory.countItem(BRASS_KEY_ID) >= BRASS_KEY_AMOUNT)
				{
					bankStage = BankStage.LEAVE;
					bankAttempts = 0;
					setStatus("Leaving bank");
					return;
				}

				if (++bankAttempts > BANK_STAGE_ATTEMPTS)
				{
					// No key available in the bank. Leave instead of
					// retrying forever.
					bankStage = BankStage.LEAVE;
					bankAttempts = 0;
					setStatus("No brass key in bank");
					return;
				}

				if (input.withdrawBankItem(BRASS_KEY_ID, "Withdraw-1"))
				{
					cooldown = COOLDOWN_TICKS;
				}
				else
				{
					cooldown = 1;
				}
				setStatus("Withdrawing brass key");
				return;
			}
			case LEAVE:
			{
				if (context.getClient().isMenuOpen())
				{
					input.closeMenu();
					return;
				}

				// Walking clicks are swallowed while the bank interface is
				// open, so do not leave this state until it is really gone.
				// Clicking the bank's own Close button is what a player does;
				// Escape is only a fallback, because the bank ignores it while
				// its search box has focus. The attempt count is bounded so a
				// bank that refuses to close cannot stall the script forever.
				if (bankAttempts <= BANK_CLOSE_ATTEMPTS
					&& context.getTargets().findWidget(InterfaceID.Bankmain.UNIVERSE).isPresent())
				{
					// Alternate both ways of closing it so a single broken route
					// cannot keep the bank open: Escape is the client's own close
					// key, the button click works when the key is swallowed.
					if (++bankAttempts % 2 == 1)
					{
						input.pressKey(java.awt.event.KeyEvent.VK_ESCAPE);
					}
					else
					{
						input.closeInterface(InterfaceID.Bankmain.UNIVERSE);
					}

					cooldown = 1;
					setStatus("Closing bank");
					return;
				}

				leaveBank();
				return;
			}
		}
	}

	/**
	 * Ends the bank visit and heads back to the dungeon. Called once the bank
	 * interface is really closed, whether by the leave stage itself or because
	 * it disappeared on its own.
	 */
	private void leaveBank()
	{
		cooldown = COOLDOWN_TICKS;
		ignoredLoot.clear();
		bankStage = null;
		bankAttempts = 0;
		returningFromBank = true;
		shedDoorOpen = false;
		shedDoorTicks = 0;
		state = State.WALK_TO_GIANTS;
		setStatus("Returning to hill giants");
	}


	private void walkToGiants(ScriptContext context)
	{
		Player player = context.getPlayer();
		WorldPoint location = player == null ? null : player.getWorldLocation();
		if (location == null)
		{
			return;
		}

		if (isInDungeon(location))
		{
			returningFromBank = false;

			if (location.distanceTo(HILL_GIANTS) <= 8)
			{
				state = State.HUNT;
				setStatus("Hunting");
				return;
			}
			walkTowards(context, HILL_GIANTS);
			setStatus("Walking to hill giants");
		}
		else
		{
			boolean nearShed = location.distanceTo(SURFACE_DOOR) <= 3
				|| location.distanceTo(SURFACE_LADDER) <= 3;

			// Coming back from the bank the shed door is normally shut and
			// blocks the ladder inside. Always open it before targeting the
			// ladder, even when the collision map still looks walkable.
			if (!shedDoorOpen && returningFromBank && nearShed && openDoor(context))
			{
				setStatus("Opening door");
				return;
			}

			boolean ladderReachable = canReach(context, SURFACE_LADDER);
			boolean canClimb = ladderReachable || shedDoorOpen
				|| location.distanceTo(SURFACE_LADDER) <= 2;

			if (location.distanceTo(SURFACE_LADDER) <= 8 && canClimb)
			{
				climb(context, false, LADDER_ID);
				setStatus("Climbing down");
				return;
			}

			if (!shedDoorOpen
				&& !ladderReachable
				&& location.distanceTo(SURFACE_DOOR) <= 10
				&& openDoor(context))
			{
				setStatus("Opening door");
				return;
			}

			// Walk to the open door with the minimap so the walk click
			// cannot toggle it, then approach the ladder inside.
			WorldPoint target = !shedDoorOpen && (returningFromBank || !ladderReachable)
				? SURFACE_DOOR
				: SURFACE_LADDER;
			walkTowards(context, target, true);
			setStatus("Walking to hill giants");
		}
	}

	private void eatIfNeeded(ScriptContext context)
	{
		Client client = context.getClient();
		Inventory inventory = context.getInventory();

		int current = client.getBoostedSkillLevel(Skill.HITPOINTS);
		int max = client.getRealSkillLevel(Skill.HITPOINTS);
		if (max <= 0 || current * 100 > max * EAT_THRESHOLD_PERCENT)
		{
			return;
		}

		int slot = inventory.findSlot(ItemID.SARDINE);
		if (slot < 0)
		{
			return;
		}

		// Clicking a sardine while the bank is open deposits it instead of
		// eating it, so wait until the bank is closed. A context menu also
		// swallows the first click, so wait for it to close as well.
		if (context.getTargets().findWidget(InterfaceID.Bankmain.UNIVERSE).isPresent()
			|| context.getClient().isMenuOpen())
		{
			return;
		}

		GameInput input = context.getInput();
		if (input.isBusy())
		{
			return;
		}

		// The inventory tab might not be open; the food slot has no widget
		// until it is, which would otherwise make the player stand there and
		// die without eating.
		if (!inventory.isOpen())
		{
			if (inventory.open())
			{
				setStatus("Opening inventory");
			}
			return;
		}

		Widget widget = inventory.getSlotWidget(slot);
		if (widget != null && input.click(widget))
		{
			cooldown = COOLDOWN_TICKS;
			setStatus("Eating sardine");
		}
	}

	private boolean openDoor(ScriptContext context)
	{
		GameInput input = context.getInput();
		if (input.isBusy())
		{
			return false;
		}

		// A leftover menu would swallow the click, which then lands on the
		// menu instead of the door.
		if (context.getClient().isMenuOpen())
		{
			input.closeMenu();
			return false;
		}

		Optional<TileObject> door = context.getTargets().findTileObject(DOOR_ID, 10);
		if (door.isEmpty())
		{
			return false;
		}

		if (input.interact(door.get(), "Open", "Unlock"))
		{
			// The Open action was sent, so the door is open from this tick on.
			// Clicking it again toggles it shut, which used to bounce the
			// player in and out of the shed, and to drag them back to the
			// door while they were running to the bank.
			shedDoorOpen = true;
			shedDoorTicks = 0;
			cooldown = COOLDOWN_TICKS;
			setStatus("Opening door");
			return true;
		}

		return false;
	}

	/**
	 * Forgets a door that was clicked but never opened, for example because the
	 * player was not standing close enough. Only counts while the player is
	 * still at the shed, so a successful open is never forgotten mid-walk.
	 */
	private void updateShedDoorState(ScriptContext context)
	{
		if (!shedDoorOpen)
		{
			shedDoorTicks = 0;
			return;
		}

		WorldPoint location = context.getPlayerLocation();
		if (location == null)
		{
			return;
		}

		boolean atShed = location.distanceTo(SURFACE_LADDER) <= 4
			|| location.distanceTo(SURFACE_DOOR) <= 2;
		if (!atShed)
		{
			shedDoorTicks = 0;
			return;
		}

		if (++shedDoorTicks > SHED_DOOR_TIMEOUT_TICKS)
		{
			shedDoorOpen = false;
			shedDoorTicks = 0;
			setStatus("Retrying shed door");
		}
	}

	private void climb(ScriptContext context, boolean up, int objectId)
	{
		GameInput input = context.getInput();
		if (input.isBusy())
		{
			return;
		}

		Optional<TileObject> object = context.getTargets().findTileObject(objectId, 10);
		if (object.isEmpty())
		{
			// Fall back to a named ladder if the id is not present.
			object = context.getTargets().findObject("Ladder", null, 10).map(ladder -> (TileObject) ladder);
		}
		if (object.isEmpty())
		{
			return;
		}

		String[] actions = up
			? new String[] {"Climb-up", "Climb up", "Exit", "Climb"}
			: new String[] {"Climb-down", "Climb down", "Enter", "Climb"};

		if (input.interact(object.get(), actions))
		{
			// Climbing moves us to the other side of the shed door, so the
			// next surface visit has to evaluate the door again.
			shedDoorOpen = false;
			cooldown = COOLDOWN_TICKS;
			setStatus(up ? "Climbing up" : "Climbing down");
		}
	}

	private void walkTowards(ScriptContext context, WorldPoint target)
	{
		walkTowards(context, target, false);
	}

	private void walkTowards(ScriptContext context, WorldPoint target, boolean useMinimap)
	{
		GameInput input = context.getInput();
		if (input.isBusy())
		{
			return;
		}

		Player player = context.getPlayer();
		WorldPoint location = player == null ? null : player.getWorldLocation();
		if (location == null || location.distanceTo(target) <= 2)
		{
			lastWalkLocation = location;
			stationaryTicks = 0;
			return;
		}

		// Do not click again while the player is still moving. Re-clicking
		// every tick interrupts the client's own path and makes it run into
		// walls.
		if (lastWalkLocation == null || !lastWalkLocation.equals(location))
		{
			lastWalkLocation = location;
			stationaryTicks = 0;
			return;
		}

		if (++stationaryTicks < 2)
		{
			return;
		}

		List<WorldPoint> path = findPath(context, target);
		if (path.size() <= 1)
		{
			stationaryTicks = 0;
			return;
		}

		// Click a point a few tiles along the collision-checked path.
		int index = Math.min(path.size() - 1, 10);

		// Minimap clicks never hit objects, so routes that pass a door
		// (the shed door in particular) cannot accidentally toggle it.
		if (useMinimap && clickMinimap(context, path.get(index)))
		{
			stationaryTicks = 0;
			return;
		}

		for (int i = index; i >= 1; i--)
		{
			if (input.walkTo(path.get(i)))
			{
				stationaryTicks = 0;
				return;
			}
		}

		clickMinimap(context, path.get(index));
		stationaryTicks = 0;
	}

	private boolean clickMinimap(ScriptContext context, WorldPoint point)
	{
		Client client = context.getClient();
		net.runelite.api.coords.LocalPoint localPoint = net.runelite.api.coords.LocalPoint.fromWorld(client, point);
		if (localPoint == null)
		{
			return false;
		}

		net.runelite.api.Point minimapPoint = net.runelite.api.Perspective.localToMinimap(client, localPoint);
		if (minimapPoint == null)
		{
			return false;
		}

		return context.getInput().click(new java.awt.Point(minimapPoint.getX(), minimapPoint.getY()));
	}

	private List<WorldPoint> findPath(ScriptContext context, WorldPoint goal)
	{
		Client client = context.getClient();
		Player player = context.getPlayer();
		WorldPoint start = player == null ? null : player.getWorldLocation();
		WorldView worldView = client.getTopLevelWorldView();
		if (start == null || worldView == null)
		{
			return Collections.emptyList();
		}

		Scene scene = worldView.getScene();
		CollisionData[] collisionMaps = worldView.getCollisionMaps();
		int plane = worldView.getPlane();
		if (scene == null || collisionMaps == null || plane < 0 || plane >= collisionMaps.length
			|| collisionMaps[plane] == null)
		{
			return Collections.emptyList();
		}

		int[][] flags = collisionMaps[plane].getFlags();
		if (flags == null || flags.length == 0)
		{
			return Collections.emptyList();
		}

		int width = flags.length;
		int height = flags[0].length;
		int baseX = scene.getBaseX();
		int baseY = scene.getBaseY();
		int startX = start.getX() - baseX;
		int startY = start.getY() - baseY;
		if (!inBounds(startX, startY, width, height))
		{
			return Collections.emptyList();
		}

		int goalX = goal.getX() - baseX;
		int goalY = goal.getY() - baseY;
		boolean goalInScene = inBounds(goalX, goalY, width, height);

		int total = width * height;
		int[] previous = new int[total];
		Arrays.fill(previous, -1);
		boolean[] visited = new boolean[total];
		int[] queue = new int[total];
		int head = 0;
		int tail = 0;
		int startIndex = startY * width + startX;
		visited[startIndex] = true;
		queue[tail++] = startIndex;

		int bestIndex = startIndex;
		long bestDistance = distanceSquared(baseX + startX, baseY + startY, goal);
		int expansions = 0;

		while (head < tail && expansions++ < total)
		{
			int current = queue[head++];
			int x = current % width;
			int y = current / width;

			if (goalInScene && x == goalX && y == goalY)
			{
				bestIndex = current;
				break;
			}

			long distance = distanceSquared(baseX + x, baseY + y, goal);
			if (distance < bestDistance)
			{
				bestDistance = distance;
				bestIndex = current;
			}

			for (int[] direction : WALK_DIRECTIONS)
			{
				int nextX = x + direction[0];
				int nextY = y + direction[1];
				if (!inBounds(nextX, nextY, width, height))
				{
					continue;
				}

				int nextIndex = nextY * width + nextX;
				if (visited[nextIndex] || !canMove(flags, x, y, direction[0], direction[1]))
				{
					continue;
				}

				visited[nextIndex] = true;
				previous[nextIndex] = current;
				queue[tail++] = nextIndex;
			}
		}

		List<WorldPoint> path = new ArrayList<>();
		for (int index = bestIndex; index != -1; index = previous[index])
		{
			int x = index % width;
			int y = index / width;
			path.add(new WorldPoint(baseX + x, baseY + y, plane));
			if (index == startIndex)
			{
				break;
			}
		}

		Collections.reverse(path);
		return path;
	}

	private boolean canReach(ScriptContext context, WorldPoint goal)
	{
		List<WorldPoint> path = findPath(context, goal);
		if (path.isEmpty())
		{
			return false;
		}

		WorldPoint end = path.get(path.size() - 1);
		return end.distanceTo(goal) <= 1;
	}

	private static boolean inBounds(int x, int y, int width, int height)
	{
		return x >= 0 && y >= 0 && x < width && y < height;
	}

	private static boolean canMove(int[][] flags, int x, int y, int dx, int dy)
	{
		int source = flags[x][y];
		int destination = flags[x + dx][y + dy];

		// Solid objects, water, and similar tiles block movement into them.
		if ((destination & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0)
		{
			return false;
		}

		int flag;
		int reverseFlag;

		if (dx == 0)
		{
			flag = dy > 0 ? CollisionDataFlag.BLOCK_MOVEMENT_NORTH
				: CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
			reverseFlag = dy > 0 ? CollisionDataFlag.BLOCK_MOVEMENT_SOUTH
				: CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
		}
		else if (dy == 0)
		{
			flag = dx > 0 ? CollisionDataFlag.BLOCK_MOVEMENT_EAST
				: CollisionDataFlag.BLOCK_MOVEMENT_WEST;
			reverseFlag = dx > 0 ? CollisionDataFlag.BLOCK_MOVEMENT_WEST
				: CollisionDataFlag.BLOCK_MOVEMENT_EAST;
		}
		else if (dx > 0)
		{
			flag = dy > 0 ? CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST
				: CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST;
			reverseFlag = dy > 0 ? CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST
				: CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST;
		}
		else
		{
			flag = dy > 0 ? CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST
				: CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST;
			reverseFlag = dy > 0 ? CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST
				: CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST;
		}

		if ((source & flag) != 0 || (destination & reverseFlag) != 0)
		{
			return false;
		}

		// Do not cut diagonally through the corner of a wall.
		if (dx != 0 && dy != 0)
		{
			int horizontal = dx > 0 ? CollisionDataFlag.BLOCK_MOVEMENT_EAST
				: CollisionDataFlag.BLOCK_MOVEMENT_WEST;
			int vertical = dy > 0 ? CollisionDataFlag.BLOCK_MOVEMENT_NORTH
				: CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
			if ((source & horizontal) != 0 || (source & vertical) != 0)
			{
				return false;
			}
		}

		return true;
	}

	private static long distanceSquared(int x, int y, WorldPoint target)
	{
		long dx = (long) x - target.getX();
		long dy = (long) y - target.getY();
		return dx * dx + dy * dy;
	}

	private boolean isIgnoredLoot(WorldPoint location)
	{
		Long expiresAt = ignoredLoot.get(location);
		if (expiresAt == null)
		{
			return false;
		}

		if (System.currentTimeMillis() >= expiresAt)
		{
			ignoredLoot.remove(location);
			return false;
		}

		return true;
	}

	private void ignoreLoot(WorldPoint location)
	{
		if (location != null)
		{
			ignoredLoot.put(location, System.currentTimeMillis() + IGNORED_LOOT_TIMEOUT_MS);
		}
	}

	private GroundLoot findGroundLoot(ScriptContext context)
	{
		Client client = context.getClient();
		Player player = context.getPlayer();
		WorldPoint playerLocation = player == null ? null : player.getWorldLocation();
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null)
		{
			return null;
		}

		Scene scene = worldView.getScene();
		if (scene == null)
		{
			return null;
		}

		Tile[][][] tiles = scene.getTiles();
		int plane = worldView.getPlane();
		if (tiles == null || plane < 0 || plane >= tiles.length)
		{
			return null;
		}

		Tile[][] planeTiles = tiles[plane];
		if (planeTiles == null)
		{
			return null;
		}

		GroundLoot best = null;
		int bestDistance = Integer.MAX_VALUE;

		for (Tile[] row : planeTiles)
		{
			if (row == null)
			{
				continue;
			}

			for (Tile tile : row)
			{
				if (tile == null)
				{
					continue;
				}

				java.util.List<TileItem> groundItems = tile.getGroundItems();
				if (groundItems == null || groundItems.isEmpty())
				{
					continue;
				}

				for (TileItem groundItem : groundItems)
				{
					if (groundItem == null)
					{
						continue;
					}

					int id = groundItem.getId();
					if (!isLootItem(id))
					{
						continue;
					}

					// Take wanted drops even when another player killed the
					// monster. The menu can still offer them to accounts that
					// are allowed to pick them up.
					net.runelite.api.Point sceneLocation = tile.getSceneLocation();
					if (sceneLocation == null)
					{
						continue;
					}

					WorldPoint itemLocation = tile.getWorldLocation();
					int distance = playerLocation == null || itemLocation == null
						? Integer.MAX_VALUE
						: itemLocation.distanceTo(playerLocation);

					if (distance > MAX_DISTANCE || distance >= bestDistance)
					{
						continue;
					}

					if (itemLocation != null && isIgnoredLoot(itemLocation))
					{
						continue;
					}

					bestDistance = distance;
					best = new GroundLoot(id, sceneLocation.getX(),
						sceneLocation.getY(), itemLocation);
				}
			}
		}

		return best;
	}

	private Optional<NPC> findHillGiant(ScriptContext context)
	{
		Optional<NPC> byName = context.getTargets().findNpc(NPC_NAME, null, MAX_DISTANCE);
		if (byName.isPresent())
		{
			return byName;
		}

		Client client = context.getClient();
		Player player = context.getPlayer();
		WorldPoint playerLocation = player == null ? null : player.getWorldLocation();
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null)
		{
			return Optional.empty();
		}

		return worldView.npcs().stream()
			.map(npc -> (NPC) npc)
			.filter(npc -> !npc.isDead())
			.filter(npc -> isHillGiantId(npc.getId()))
			.filter(npc -> playerLocation == null || npc.getWorldLocation() == null
				|| npc.getWorldLocation().distanceTo(playerLocation) <= MAX_DISTANCE)
			.min(java.util.Comparator.comparingInt(npc ->
				playerLocation == null || npc.getWorldLocation() == null
					? Integer.MAX_VALUE
					: npc.getWorldLocation().distanceTo(playerLocation)));
	}

	/**
	 * @return true if the giant this script attacked is dead, or has already
	 * been removed from the scene, which the client does as soon as it dies
	 */
	private boolean isKillFinished(ScriptContext context, NPC target)
	{
		if (target == null)
		{
			return false;
		}

		if (target.isDead())
		{
			return true;
		}

		WorldView worldView = context.getClient().getTopLevelWorldView();
		if (worldView == null)
		{
			return false;
		}

		for (NPC npc : worldView.npcs())
		{
			if (npc == target)
			{
				return false;
			}
		}

		return true;
	}

	private static boolean isHillGiantId(int id)
	{
		return id == NpcID.GIANT || id == NpcID.GIANT2 || id == NpcID.GIANT3
			|| id == NpcID.GIANT4 || id == NpcID.GIANT5 || id == NpcID.GIANT6;
	}

	private boolean isInDungeon(WorldPoint location)
	{
		return location != null && location.getY() >= 9000;
	}

	private static String getNpcName(NPC npc)
	{
		if (npc == null)
		{
			return "";
		}

		net.runelite.api.NPCComposition composition = npc.getComposition();
		String name = composition == null ? npc.getName() : composition.getName();
		return name == null ? "" : name;
	}

	private static String getItemName(Client client, int itemId)
	{
		net.runelite.api.ItemComposition composition = client.getItemDefinition(itemId);
		String name = composition == null ? null : composition.getName();
		return name == null ? "" : name;
	}

	private static final class GroundLoot
	{
		private final int itemId;
		private final int sceneX;
		private final int sceneY;
		private final WorldPoint worldLocation;

		private GroundLoot(int itemId, int sceneX, int sceneY, WorldPoint worldLocation)
		{
			this.itemId = itemId;
			this.sceneX = sceneX;
			this.sceneY = sceneY;
			this.worldLocation = worldLocation;
		}
	}
}
