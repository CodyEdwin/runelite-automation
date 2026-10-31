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
import net.runelite.api.WallObject;
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
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HillGiantScript.class);

	private static final String NPC_NAME = "Hill Giant";
	private static final String ATTACK_ACTION = "Attack";

	private static final int MAX_DISTANCE = 25;
	// Ticks to wait after opening the bank (the walk to the booth) and after a
	// withdraw (the inventory update). Was a flat 3 everywhere.
	private static final int BANK_OPEN_COOLDOWN_TICKS = 2;
	private static final int BANK_ACTION_COOLDOWN_TICKS = 1;
	private static final int EAT_COOLDOWN_TICKS = 1;

	// After an attack click, wait this long for the fight to register before
	// treating the click as missed. Replaces the old fixed 3-tick cooldown.
	private static final int ATTACK_CONFIRM_TICKS = 2;

	// A giant's drops spawn when its corpse despawns. Watch the death for at
	// most this long, then for this many ticks after the despawn.
	private static final int DEATH_TIMEOUT_TICKS = 8;
	private static final int DROP_WAIT_TICKS = 1;

	// A Take click is only repeated when the player stood still for this many
	// ticks without the item disappearing.
	private static final int LOOT_RECLICK_TICKS = 2;
	private static final int LOOT_ATTEMPT_LIMIT = 3;
	// Hard cap on the whole LOOT state without a pickup landing; then back to
	// fighting.
	private static final int LOOT_STATE_MAX_TICKS = 25;
	private static final int LOOT_TARGET_TIMEOUT_TICKS = 40;

	// Walking: click this far ahead along the path, and chain the next click
	// when within this distance of the last clicked tile.
	private static final int WALK_CLICK_AHEAD = 10;
	private static final int WALK_CHAIN_DISTANCE = 3;

	// Shed door and ladder: wait this long for a click to play out.
	private static final int DOOR_WAIT_TICKS = 4;
	private static final int LADDER_WAIT_TICKS = 5;
	private static final int LADDER_ATTEMPTS = 3;

	// The door latch is only released by climbing down, by walking more than
	// this many tiles from the ladder, or after this many ladder clicks that
	// did not move the player at all.
	private static final int SHED_LATCH_DISTANCE = 8;
	private static final int LADDER_NO_MOVE_LIMIT = 3;
	// On the way out, standing still inside the shed this long while trying to
	// walk to the bank releases the exit latch once.
	private static final int WATCHDOG_TICKS = 10;

	// Brass-key shed footprint from the OSRS wiki: x 3113-3117, y 3450-3453,
	// door on the south row, ladder on the north row. The player stands on
	// DOOR_OUTSIDE (just south of the door) to open it.
	// Walkable interior of the shed, from Cody's client logs plus the wiki.
	// Inside seen: 3117,3451 (arrival tile after climbing up), 3116,3451,
	// 3116,3450, 3115,3450 (the door row is inside; the door's wall edge is
	// between y 3450 and 3449). Outside seen walking around the shed:
	// x 3112 (y 3449-3454), y 3454 (x 3112-3117), 3116,3455, x 3118
	// (y 3452-3453), and y 3449. So the walls lie between x 3112|3113,
	// x 3117|3118 and y 3453|3454: the interior is x 3113-3117,
	// y 3450-3453, the same as the wiki footprint.
	private static final int SHED_MIN_X = 3113;
	private static final int SHED_MAX_X = 3117;
	private static final int SHED_MIN_Y = 3450;
	private static final int SHED_MAX_Y = 3453;
	private static final WorldPoint DOOR_OUTSIDE = new WorldPoint(3115, 3449, 0);

	private static final String[] DOOR_OPEN_ACTIONS = {"Open", "Unlock"};
	private static final String[] CLIMB_DOWN_ACTIONS = {"Climb-down", "Climb down", "Enter"};
	private static final String[] CLIMB_UP_ACTIONS = {"Climb-up", "Climb up", "Exit"};

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
	// A simulated mouse move or click takes well under 3 ticks; anything
	// still "busy" after this is a stuck input, not a slow one.
	private static final int BUSY_STUCK_TICKS = 15;
	private static final int CAMERA_STUCK_TICKS = 10;


	// Giant key (Obor's key), item 20754; the gameval name is HILLGIANT_BOSS_KEY.
	private static final int GIANT_KEY_ID = ItemID.HILLGIANT_BOSS_KEY;
	// Ensouled giant head as dropped, item 13474; the gameval name is
	// ARCEUUS_CORPSE_GIANT_INITIAL.
	private static final int ENSOULED_GIANT_HEAD_ID = ItemID.ARCEUUS_CORPSE_GIANT_INITIAL;

	/**
	 * Only the requested hill giant drops are picked up.
	 */
	private static final java.util.Set<Integer> LOOT_ITEMS = java.util.Collections.unmodifiableSet(
		new java.util.HashSet<>(java.util.Arrays.asList(
			ItemID.BIG_BONES,
			ItemID.BODY_TALISMAN,
			ItemID.UNCUT_DIAMOND,
			ItemID.UNCUT_EMERALD,
			ItemID.UNIDENTIFIED_RANARR,
			ItemID.NATURERUNE,
			ItemID.UNIDENTIFIED_KWUARM,
			ItemID.UNIDENTIFIED_DWARF_WEED,
			ItemID.LAWRUNE,
			ItemID.UNIDENTIFIED_IRIT,
			ItemID.SNAPE_GRASS_SEED,
			GIANT_KEY_ID,
			ENSOULED_GIANT_HEAD_ID)));

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

	/**
	 * Hill giants use the fast, attentive mouse profile.
	 */
	@Override
	public boolean useFastMouse()
	{
		return true;
	}

	@Override
	public List<String> getOverlayLines()
	{
		return Arrays.asList(
			"State: " + (state == null ? "-" : state.name())
				+ (shedStep == null || state != State.WALK_TO_GIANTS ? "" : " / " + shedStep.name()),
			"Kills: " + kills,
			"Shed: passedDoor=" + passedDoorThisTrip + " exitedDoor=" + exitedDoorThisTrip
				+ (shedDebugLine == null ? "" : " | " + shedDebugLine));
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

	/**
	 * Committed steps of the trip from the shed door down the ladder.
	 */
	private enum ShedStep
	{
		APPROACH_DOOR,
		OPEN_DOOR,
		CLIMB
	}

	private static final int[][] WALK_DIRECTIONS = {
		{0, 1}, {1, 0}, {1, 1}, {1, -1},
		{0, -1}, {-1, 0}, {-1, -1}, {-1, 1}
	};

	private State state;
	private int cooldown;
	private WorldPoint lastWalkLocation;
	private WorldPoint lastWalkClick;
	private int walkClickTicks;
	private WorldPoint lootTarget;
	private int lootTargetItemId;
	private int lootAttempts;
	private int lootTargetTicks;
	private BankStage bankStage;
	private int bankAttempts;
	private final java.util.Map<WorldPoint, Long> ignoredLoot = new java.util.HashMap<>();
	private int menuOpenTicks;
	private int busyTicks;
	private int cameraBusyTicks;
	private int exceptionCount;
	// Set when the brass key is missing from both the inventory and the
	// bank: the script stays at the bank instead of walking back keyless.
	private boolean haltedNoKey;
	// Bank deposit tracking: inventory snapshot and how long it has not
	// changed since the deposit click.
	private String depositSnapshot;
	private int depositStillTicks;
	private int depositRetries;
	// Looting: ticks spent waiting for the player to settle before a new
	// pickup, and a flag the click result sets when a pickup clearly failed.
	private int lootSettleTicks;
	private volatile boolean lootClickFailed;
	// Pickup phases, all driven from the tick (client thread): hover the
	// item, then on the next tick read the hover menu and press (left click,
	// or right click + exact Take row, or the exact Take action).
	private enum LootPhase
	{
		IDLE,
		HOVERED,
		MENU
	}

	private LootPhase lootPhase = LootPhase.IDLE;
	private int lootPhaseTicks;
	private int lootStateTicks;
	private int lootInventoryCount = -1;
	// [HillGiant state] logging when the state has not changed for a while.
	private State loggedState;
	private int stateTicks;
	private int bankCloseTicks;
	private NPC attackTarget;
	private int attackPendingTicks;
	private NPC dyingNpc;
	private NPC lastKilledNpc;
	private WorldPoint lastWalkTarget;
	private WorldPoint deathTile;
	private WorldPoint lastDeathTile;
	private int deathTicks;
	private int despawnTicks;
	private int lootClickTicks;
	private int kills;
	private ShedStep shedStep;
	private boolean passedDoorThisTrip;
	private boolean exitedDoorThisTrip;
	private int ladderNoMoveFails;
	private String shedDebugLine;
	private int doorMenuMisses;
	private int doorStepAttempts;
	// Trip out: set while in the dungeon, consumed on the first surface tick.
	// Arriving on the surface by the ladder is proof of being inside the
	// shed, with no box check.
	private boolean climbedUpPending;
	private boolean insideShedByEvent;
	private int exitDoorAttempts;
	// Shed watchdog: no position change and no new action for this long.
	private WorldPoint watchdogLocation;
	private int watchdogTicks;
	private boolean actionThisTick;
	private int recentActionTicks;
	private int stuckRecoveries;
	private int ladderMenuMisses;
	private WorldPoint doorClickFrom;
	private WorldPoint ladderClickFrom;
	private boolean ladderBlocked;
	private int doorWaitTicks;
	private int ladderWaitTicks;
	private int ladderAttempts;

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
		lastWalkClick = null;
		walkClickTicks = 0;
		lootTarget = null;
		lootTargetItemId = -1;
		lootAttempts = 0;
		lootTargetTicks = 0;
		bankStage = null;
		bankAttempts = 0;
		ignoredLoot.clear();
		menuOpenTicks = 0;
		busyTicks = 0;
		attackTarget = null;
		attackPendingTicks = 0;
		dyingNpc = null;
		lastKilledNpc = null;
		lastWalkTarget = null;
		deathTile = null;
		lastDeathTile = null;
		deathTicks = 0;
		despawnTicks = 0;
		lootClickTicks = -1;
		kills = 0;
		shedStep = null;
		passedDoorThisTrip = false;
		exitedDoorThisTrip = false;
		ladderNoMoveFails = 0;
		shedDebugLine = null;
		doorMenuMisses = 0;
		ladderMenuMisses = 0;
		doorStepAttempts = 0;
		climbedUpPending = false;
		insideShedByEvent = false;
		exitDoorAttempts = 0;
		watchdogLocation = null;
		watchdogTicks = 0;
		recentActionTicks = 0;
		actionThisTick = false;
		stuckRecoveries = 0;
		haltedNoKey = false;
		doorClickFrom = null;
		ladderClickFrom = null;
		ladderBlocked = false;
		doorWaitTicks = 0;
		ladderWaitTicks = 0;
		ladderAttempts = 0;
		setStatus("Ready");
	}

	@Override
	protected void onGameTick()
	{
		try
		{
			tickSafely();
		}
		catch (RuntimeException | AssertionError ex)
		{
			// Log with the stack trace and keep the script alive; the
			// watchdog recovers whatever state the exception left behind.
			++exceptionCount;
			WorldPoint location = getContext().getPlayerLocation();
			log.error("[HillGiant] exception #{} in state={} step={} loc={} status={}",
				exceptionCount, state, shedStep, location, getStatus(), ex);
			shedDebugLine = "exception: " + ex;
			if (exceptionCount >= 20)
			{
				// Twenty in a row means something is badly wrong: let the
				// script manager stop the script with the error.
				throw ex;
			}
		}
	}

	private void tickSafely()
	{
		ScriptContext context = getContext();

		if (haltedNoKey)
		{
			setStatus("Stopped: no brass key in inventory or bank");
			return;
		}

		if (!context.isLoggedIn())
		{
			setStatus("Waiting for login");
			return;
		}

		recoverStuckInput(context);

		if (state == null)
		{
			WorldPoint location = context.getPlayerLocation();
			if (location != null && location.getY() < 9000)
			{
				// Starting on the surface: the inventory decides the trip
				// direction, never inside/outside geometry. Full means the
				// trip out to the bank; free space means the return trip.
				state = context.getInventory().isFull() ? State.WALK_TO_BANK : State.WALK_TO_GIANTS;
			}
			else
			{
				state = State.HUNT;
			}
		}

		if (cooldown > 0)
		{
			--cooldown;
			if (state == State.WALK_TO_BANK)
			{
				logTrip(context, "cooldown " + cooldown);
			}
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
				logTrip(context, "tick");
				shedWatchdog(context);
				break;
			case BANK:
				bank(context);
				break;
			case WALK_TO_GIANTS:
				walkToGiants(context);
				shedWatchdog(context);
				break;
		}
		exceptionCount = 0;
		logStateIfStale(context);
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
				log.info("[HillGiant] input busy for {} ticks in state={}, resetting input", busyTicks, state);
				input.reset();
				busyTicks = 0;
				setStatus("Resetting input");
			}
		}
		else
		{
			busyTicks = 0;
		}

		if (input.isCameraBusy())
		{
			if (++cameraBusyTicks > CAMERA_STUCK_TICKS)
			{
				log.info("[HillGiant] camera turning for {} ticks in state={}, resetting input", cameraBusyTicks, state);
				input.reset();
				cameraBusyTicks = 0;
			}
		}
		else
		{
			cameraBusyTicks = 0;
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
		Client client = context.getClient();
		GameInput input = context.getInput();
		Inventory inventory = context.getInventory();

		// A menu left over from a previous loot/interaction would block
		// clicking giants and walking. Close it before hunting.
		if (client.isMenuOpen())
		{
			input.closeMenu();
			return;
		}

		if (inventory.isFull())
		{
			// A full inventory can still take stackable drops we already hold
			// (snape grass seeds, runes): grab those before banking.
			if (findGroundLoot(context) != null)
			{
				state = State.LOOT;
				setStatus("Looting stackables");
				return;
			}
			state = State.WALK_TO_BANK;
			setStatus("Inventory full");
			return;
		}

		Player player = context.getPlayer();
		NPC interacting = player != null && player.getInteracting() instanceof NPC
			? (NPC) player.getInteracting()
			: null;

		// React to a kill on the very tick the death animation starts instead
		// of waiting out a fixed delay.
		if (deathTile == null)
		{
			if (interacting != null && interacting.isDead() && interacting != lastKilledNpc)
			{
				beginDeathWatch(interacting);
			}
			else if (attackTarget != null && attackTarget != lastKilledNpc
				&& isKillFinished(context, attackTarget))
			{
				beginDeathWatch(attackTarget);
			}
		}

		if (deathTile != null)
		{
			if (watchDeath(context))
			{
				return;
			}
		}

		if (interacting != null && !interacting.isDead())
		{
			// Already fighting (or auto-retaliating). Never click again while
			// the fight is on; just remember who we are fighting so its death
			// is noticed immediately.
			attackTarget = interacting;
			attackPendingTicks = 0;
			setStatus("Fighting " + getNpcName(interacting));
			return;
		}

		// An attack click was sent but the game has not registered the
		// interaction yet. Wait a moment rather than clicking again, unless the
		// giant was taken by someone else or died in the meantime.
		if (attackTarget != null && attackPendingTicks > 0)
		{
			--attackPendingTicks;
			if (!attackTarget.isDead() && !isTakenByOther(context, attackTarget)
				&& isInScene(context, attackTarget))
			{
				setStatus("Attacking " + NPC_NAME);
				return;
			}
			attackTarget = null;
			attackPendingTicks = 0;
		}

		// Nothing is being fought, so any wanted drop on the ground is taken
		// before the next giant is attacked. Switch and loot on this same tick.
		if (findGroundLoot(context) != null)
		{
			state = State.LOOT;
			loot(context);
			return;
		}

		if (input.isBusy() || input.isCameraBusy())
		{
			setStatus("Moving mouse");
			return;
		}

		Optional<NPC> target = findHillGiant(context);
		if (target.isEmpty())
		{
			attackTarget = null;
			WorldPoint location = context.getPlayerLocation();
			if (location != null && location.distanceTo(HILL_GIANTS) > 12)
			{
				walkTowards(context, HILL_GIANTS);
				setStatus("Walking to hill giants");
				return;
			}
			setStatus("Waiting for a free " + NPC_NAME);
			return;
		}

		NPC npc = target.get();
		if (!input.isVisible(npc))
		{
			WorldPoint location = context.getPlayerLocation();
			WorldPoint npcLocation = npc.getWorldLocation();
			if (location != null && npcLocation != null && location.distanceTo(npcLocation) > 7)
			{
				walkTowards(context, npcLocation);
				setStatus("Walking to " + NPC_NAME);
			}
			else
			{
				input.turnCameraToward(npcLocation);
				setStatus("Turning camera to " + NPC_NAME);
			}
			return;
		}

		if (input.interact(npc, ATTACK_ACTION))
		{
			attackTarget = npc;
			attackPendingTicks = ATTACK_CONFIRM_TICKS;
			setStatus("Attacking " + NPC_NAME);
		}
		else
		{
			setStatus("Cannot attack " + NPC_NAME);
		}
	}

	private void beginDeathWatch(NPC npc)
	{
		dyingNpc = npc;
		lastKilledNpc = npc;
		deathTile = npc.getWorldLocation();
		lastDeathTile = deathTile;
		deathTicks = 0;
		despawnTicks = 0;
		attackTarget = null;
		attackPendingTicks = 0;
		++kills;
	}

	/**
	 * Follows a giant from its death animation to its drops appearing. The
	 * drops spawn when the corpse despawns, so this waits for exactly that,
	 * then loots right away. While waiting the mouse is already moved onto
	 * the next giant, like a player getting ready for the next kill.
	 *
	 * @return true while still waiting this tick
	 */
	private boolean watchDeath(ScriptContext context)
	{
		GameInput input = context.getInput();

		GroundLoot drop = findGroundLoot(context);
		if (drop != null && drop.worldLocation != null && deathTile != null
			&& drop.worldLocation.distanceTo(deathTile) <= 2)
		{
			clearDeathWatch();
			state = State.LOOT;
			loot(context);
			return true;
		}

		boolean dying = dyingNpc != null && isInScene(context, dyingNpc);
		boolean waiting = dying
			? ++deathTicks < DEATH_TIMEOUT_TICKS
			: ++despawnTicks <= DROP_WAIT_TICKS;

		if (!waiting)
		{
			// No wanted drop from this kill. Move straight on.
			clearDeathWatch();
			return false;
		}

		if (!input.isBusy() && !input.isCameraBusy())
		{
			findHillGiant(context)
				.filter(input::isVisible)
				.filter(next -> !input.isMouseOver(next))
				.ifPresent(input::hover);
		}

		setStatus(dying ? "Kill finished" : "Waiting for drops");
		return true;
	}

	private void clearDeathWatch()
	{
		dyingNpc = null;
		deathTile = null;
		deathTicks = 0;
		despawnTicks = 0;
	}

	private void loot(ScriptContext context)
	{
		Inventory inventory = context.getInventory();
		GameInput input = context.getInput();

		GroundLoot loot = findGroundLoot(context);
		if (loot == null && inventory.isFull())
		{
			// Full, and no stackable drop that fits onto a stack we hold.
			resetLootTracking();
			state = State.WALK_TO_BANK;
			setStatus("Inventory full");
			return;
		}
		if (loot == null)
		{
			// Everything is picked up. Go straight back to hunting on this
			// tick so the next attack is not delayed.
			resetLootTracking();
			lastDeathTile = null;
			state = State.HUNT;
			hunt(context);
			return;
		}

		// Whole-state cap: if nothing has been picked up for a while, stop
		// looting and get back to fighting. Counted before any busy check so
		// a stuck input can never hold the script here.
		int itemCount = countInventoryItems(inventory);
		if (itemCount != lootInventoryCount)
		{
			lootInventoryCount = itemCount;
			lootStateTicks = 0;
		}
		if (++lootStateTicks > LOOT_STATE_MAX_TICKS)
		{
			log.info("[HillGiant loot] nothing picked up for {} ticks, giving up on this pile and attacking",
				LOOT_STATE_MAX_TICKS);
			skipLoot(loot);
			return;
		}

		if (input.isBusy() || input.isCameraBusy())
		{
			if (++lootPhaseTicks > 6)
			{
				log.info("[HillGiant loot] input busy for {} ticks during a pickup, resetting input", lootPhaseTicks);
				input.reset();
				lootPhase = LootPhase.IDLE;
				lootPhaseTicks = 0;
			}
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
			lootClickTicks = -1;
			lootSettleTicks = 0;
			lootPhase = LootPhase.IDLE;
			lootPhaseTicks = 0;
		}

		if (++lootTargetTicks > LOOT_TARGET_TIMEOUT_TICKS && loot.worldLocation != null)
		{
			// The drop is probably behind an obstacle or otherwise
			// unreachable. Ignore it for a while so the script does not get
			// stuck.
			skipLoot(loot);
			return;
		}

		// A Take click was sent. The player walks to the item and picks it up
		// by itself, so do not click again while that is happening. Only a
		// click that clearly did nothing is repeated.
		Player player = context.getPlayer();
		if (lootClickTicks >= 0)
		{
			++lootClickTicks;
			if (lootClickFailed)
			{
				// The click result said it missed: retry now, not after the
				// re-click timeout.
				lootClickFailed = false;
				lootClickTicks = LOOT_RECLICK_TICKS;
			}
			if (isMoving(player) || lootClickTicks < LOOT_RECLICK_TICKS)
			{
				setStatus("Looting " + getItemName(context.getClient(), loot.itemId));
				return;
			}
		}
		else if (lootPhase == LootPhase.IDLE && !isSettled(context, player) && lootSettleTicks++ < 2)
		{
			// The previous pickup just landed and the player (and with it the
			// camera) is still sliding onto that tile. Start the next pickup
			// once it has settled, so the item is not a moving target. At
			// most two ticks; the tracked click re-aims anyway.
			setStatus("Looting " + getItemName(context.getClient(), loot.itemId));
			return;
		}

		if (lootAttempts >= LOOT_ATTEMPT_LIMIT && loot.worldLocation != null)
		{
			skipLoot(loot);
			return;
		}

		String itemName = getItemName(context.getClient(), loot.itemId);
		String lootLabel = String.format("item=%s(%d) tile=%d,%d attempt=%d/%d",
			itemName, loot.itemId, loot.worldLocation == null ? -1 : loot.worldLocation.getX(),
			loot.worldLocation == null ? -1 : loot.worldLocation.getY(), lootAttempts + 1, LOOT_ATTEMPT_LIMIT);

		if (lootPhase == LootPhase.MENU)
		{
			// Right click was sent last tick: click this item's exact row.
			lootPhase = LootPhase.IDLE;
			boolean sent = input.clickGroundItemMenuRow(loot.itemId, loot.sceneX, loot.sceneY);
			if (!sent)
			{
				sent = input.invokeGroundItemTake(loot.sceneX, loot.sceneY, loot.itemId, itemName);
				log.info("[HillGiant loot] {} result=menu had no Take row, ran exact Take directly", lootLabel);
			}
			else
			{
				log.info("[HillGiant loot] {} result=right-click menu, clicked exact Take row", lootLabel);
			}
			lootSent();
			return;
		}

		if (lootPhase == LootPhase.HOVERED)
		{
			lootPhase = LootPhase.IDLE;
			java.awt.Point mouse = input.getMouseLocation();
			java.awt.Point fresh = input.groundItemPoint(loot.sceneX, loot.sceneY);
			int hover = input.groundItemHoverState(loot.itemId, loot.sceneX, loot.sceneY);
			boolean onTile = input.isOnGroundItemTile(mouse, loot.sceneX, loot.sceneY);
			String where = "mouse=" + fmt(mouse) + " fresh=" + fmt(fresh) + " onTile=" + onTile + " hover=" + hover;

			if (hover == 2 && onTile && mouse != null)
			{
				// The item's Take is what a left click does right here.
				input.click(mouse);
				log.info("[HillGiant loot] {} {} result=left-click Take (top option)", lootLabel, where);
				lootSent();
				return;
			}

			if (hover == 1 && mouse != null)
			{
				// Another item or an NPC is on top: use the real menu.
				input.rightClick(mouse);
				lootPhase = LootPhase.MENU;
				lootPhaseTicks = 0;
				log.info("[HillGiant loot] {} {} result=right-click, picking Take row next tick", lootLabel, where);
				return;
			}

			// Not under the cursor (the tile moved): run the exact Take for
			// this item and tile instead of clicking blind.
			input.invokeGroundItemTake(loot.sceneX, loot.sceneY, loot.itemId, itemName);
			log.info("[HillGiant loot] {} {} result=item not under cursor, ran exact Take directly", lootLabel, where);
			lootSent();
			return;
		}

		// IDLE: start an attempt. Logged before any mouse work.
		java.awt.Point point = input.groundItemPoint(loot.sceneX, loot.sceneY);
		log.info("[HillGiant loot] {} start: aim={} settled={}", lootLabel, fmt(point), isSettled(context, player));
		if (point != null && input.moveTo(point))
		{
			lootPhase = LootPhase.HOVERED;
			lootPhaseTicks = 0;
			setStatus(itemName.isEmpty() ? "Looting" : "Looting " + itemName);
			return;
		}
		log.info("[HillGiant loot] {} result=tile not on screen, walking closer", lootLabel);

		// The tile is not on screen (or a menu was just closed). Get closer.
		if (loot.worldLocation != null && !context.getClient().isMenuOpen())
		{
			walkTowards(context, loot.worldLocation);
			setStatus("Walking to loot");
		}
	}

	/** A pickup was sent: wait for it to land (re-click timeout applies). */
	private void lootSent()
	{
		++lootAttempts;
		lootClickTicks = 0;
		lootPhaseTicks = 0;
	}

	private static String fmt(java.awt.Point point)
	{
		return point == null ? "none" : point.x + "," + point.y;
	}

	private static int countInventoryItems(Inventory inventory)
	{
		int count = 0;
		for (net.runelite.api.Item item : inventory.getItems())
		{
			if (item != null && item.getId() != -1)
			{
				count += Math.max(1, item.getQuantity());
			}
		}
		return count;
	}

	private void skipLoot(GroundLoot loot)
	{
		log.info("[HillGiant loot] skipping loot at {} (attempts={}), back to attacking", loot.worldLocation, lootAttempts);
		ignoreLoot(loot.worldLocation);
		resetLootTracking();
		state = State.HUNT;
		setStatus("Skipping unreachable loot");
	}

	private void resetLootTracking()
	{
		lootTarget = null;
		lootTargetItemId = -1;
		lootAttempts = 0;
		lootTargetTicks = 0;
		lootClickTicks = -1;
		lootSettleTicks = 0;
		lootClickFailed = false;
		lootPhase = LootPhase.IDLE;
		lootPhaseTicks = 0;
		lootStateTicks = 0;
		lootInventoryCount = -1;
	}

	private void walkToBank(ScriptContext context)
	{
		Player player = context.getPlayer();
		WorldPoint location = player == null ? null : player.getWorldLocation();
		if (location == null)
		{
			return;
		}

		if (isInDungeon(location))
		{
			shedStep = null;
			exitedDoorThisTrip = false;
			// Whatever happens next on the surface, it starts inside the shed.
			climbedUpPending = true;
			if (location.distanceTo(DUNGEON_LADDER) <= 6)
			{
				climbLadder(context, true, "trip out: in dungeon next to the ladder");
			}
			else
			{
				walkTowards(context, DUNGEON_LADDER);
				setStatus("Walking to ladder");
			}
			return;
		}

		ladderWaitTicks = 0;
		ladderAttempts = 0;
		ladderClickFrom = null;

		if (climbedUpPending)
		{
			// First surface tick after climbing up: inside by that event alone.
			climbedUpPending = false;
			insideShedByEvent = true;
			exitedDoorThisTrip = false;
			doorClickFrom = null;
			doorWaitTicks = 0;
			exitDoorAttempts = 0;
			lastWalkClick = null;
			lastWalkTarget = null;
			logShed(context, location, "trip out: arrived on surface by the ladder, inside by event");
		}

		if (location.distanceTo(WEST_VARROCK_BANK) <= 8)
		{
			shedStep = null;
			exitedDoorThisTrip = false;
			insideShedByEvent = false;
			state = State.BANK;
			setStatus("Banking");
			return;
		}

		if (exitedDoorThisTrip && isInShedInterior(location))
		{
			// The out latch says we left, but we are standing in the shed:
			// the latch is wrong. Drop it so the door gets clicked.
			exitedDoorThisTrip = false;
			insideShedByEvent = true;
			doorClickFrom = null;
			doorWaitTicks = 0;
			logShed(context, location, "exit: out latch set but still inside the shed, latch cleared, clicking door");
		}

		boolean nearShed = location.distanceTo(SURFACE_LADDER) <= SHED_LATCH_DISTANCE;
		if (nearShed && !exitedDoorThisTrip)
		{
			exitShed(context, location);
			return;
		}

		if (nearShed)
		{
			logShed(context, location, "exit: latched (outside), walking to bank");
		}
		walkTowards(context, WEST_VARROCK_BANK, true);
		setStatus("Walking to bank");
	}

	/**
	 * Trip out only (inventory full). Leaves the shed: one door click, then
	 * nothing but waiting until the player is south of the door's wall line
	 * (y 3449 or less). Only that crossing sets the exit latch; stepping
	 * towards the door inside the shed does not, and no walk click is sent
	 * while the door action is still playing out (a walk click would cancel
	 * it).
	 */
	private void exitShed(ScriptContext context, WorldPoint location)
	{
		Player player = context.getPlayer();
		boolean inside = isInShedInterior(location)
			|| insideShedByEvent && location.getY() >= SURFACE_DOOR.getY()
				&& location.getX() >= SHED_MIN_X - 1 && location.getX() <= SHED_MAX_X + 1;

		if (!inside)
		{
			exitedDoorThisTrip = true;
			insideShedByEvent = false;
			doorClickFrom = null;
			doorWaitTicks = 0;
			exitDoorAttempts = 0;
			logShed(context, location, "exit: south of the door line, latch set, walking to bank");
			walkTowards(context, WEST_VARROCK_BANK, true);
			setStatus("Walking to bank");
			return;
		}

		if (doorClickFrom != null)
		{
			--doorWaitTicks;
			// Let the door action play out; give a moving player a little
			// longer, but never wait forever.
			if (doorWaitTicks > 0 || (isMoving(player) && doorWaitTicks > -DOOR_WAIT_TICKS))
			{
				logShed(context, location, "exit: waiting for the door to let us out");
				setStatus("Leaving shed");
				return;
			}
			doorClickFrom = null;
			logShed(context, location, "exit: door click did not take us out, clicking door again");
		}

		GameInput input = context.getInput();
		if (input.isBusy() || input.isCameraBusy())
		{
			return;
		}

		Optional<TileObject> door = findShedDoor(context);
		if (door.isEmpty())
		{
			logShed(context, location, "exit: door not found, stepping towards it");
			if (clickMinimap(context, SURFACE_DOOR))
			{
				doorClickFrom = location;
				doorWaitTicks = DOOR_WAIT_TICKS;
			}
			return;
		}

		if (!hasAction(context, door.get(), DOOR_OPEN_ACTIONS))
		{
			// Door stands open: just walk out through it.
			logShed(context, location, "exit: door is open, walking out");
			if (clickMinimap(context, DOOR_OUTSIDE))
			{
				doorClickFrom = location;
				doorWaitTicks = DOOR_WAIT_TICKS;
			}
			return;
		}

		++exitDoorAttempts;
		logShed(context, location, "exit: inside, clicking door (attempt " + exitDoorAttempts + ")");
		if (clickDoor(context, door.get(), location))
		{
			setStatus("Leaving shed");
		}
	}

	/**
	 * @return true if the tile is on or inside the shed's wiki footprint
	 * (x 3113-3117, y 3450-3453). Only used on the trip out when the script
	 * started on the surface without having climbed up.
	 */
	private static boolean isInShedFootprint(WorldPoint location)
	{
		return isInShedInterior(location);
	}

	/**
	 * Generic watchdog for the shed sequence on both trips. If the player
	 * has not moved, is not animating and the script sent no new action for
	 * {@link #WATCHDOG_TICKS} ticks while at the shed (surface) or the
	 * dungeon ladder, it logs a STUCK line with all state and recovers:
	 * input reset, wait/attempt counters cleared, and the trip's door latch
	 * released if the player is still on the wrong side.
	 */
	private void shedWatchdog(ScriptContext context)
	{
		boolean acted = actionThisTick;
		actionThisTick = false;

		Player player = context.getPlayer();
		WorldPoint location = player == null ? null : player.getWorldLocation();
		boolean atShed = location != null && (isInDungeon(location)
			? location.distanceTo(DUNGEON_LADDER) <= 8
			: location.distanceTo(SURFACE_LADDER) <= SHED_LATCH_DISTANCE + 4);
		// The whole bank trip is watched, wherever the player is. The return
		// trip only at the shed and the dungeon ladder.
		boolean watched = location != null
			&& (state == State.WALK_TO_BANK || state == State.WALK_TO_GIANTS && atShed);
		if (!watched)
		{
			watchdogLocation = location;
			watchdogTicks = 0;
			return;
		}

		// Progress means the player moved or is animating (climbing). Clicks
		// that never move the player (for example a minimap click on the
		// tile we already stand on) do not count as progress; they only
		// delay the watchdog while they are recent.
		boolean progress = !location.equals(watchdogLocation) || isAnimating(player);
		watchdogLocation = location;
		if (progress)
		{
			watchdogTicks = 0;
			recentActionTicks = 0;
			return;
		}

		recentActionTicks = acted || context.getInput().isCameraBusy() ? 3 : Math.max(0, recentActionTicks - 1);
		++watchdogTicks;
		boolean fire = watchdogTicks >= 2 * WATCHDOG_TICKS
			|| watchdogTicks >= WATCHDOG_TICKS && recentActionTicks == 0;
		if (!fire)
		{
			return;
		}

		watchdogTicks = 0;
		++stuckRecoveries;
		logShed(context, location, "STUCK for " + WATCHDOG_TICKS + " ticks (recovery " + stuckRecoveries
			+ ") doorClickFrom=" + doorClickFrom + " doorWait=" + doorWaitTicks + " ladderWait=" + ladderWaitTicks
			+ " ladderClickFrom=" + ladderClickFrom + " ladderBlocked=" + ladderBlocked
			+ " insideByEvent=" + insideShedByEvent + " climbedUpPending=" + climbedUpPending
			+ " doorStep=" + doorStepAttempts + " exitAttempts=" + exitDoorAttempts
			+ " menuOpen=" + context.getClient().isMenuOpen() + " inputBusy=" + context.getInput().isBusy()
			+ " lastWalkClick=" + lastWalkClick + " status=" + getStatus());

		GameInput input = context.getInput();
		input.reset();
		if (context.getClient().isMenuOpen())
		{
			input.closeMenu();
		}
		doorClickFrom = null;
		doorWaitTicks = 0;
		ladderWaitTicks = 0;
		ladderAttempts = 0;
		ladderClickFrom = null;
		ladderBlocked = false;
		ladderNoMoveFails = 0;
		// The next door/ladder click skips the right-click menu and runs the
		// option directly, in case the menu was what kept failing.
		doorMenuMisses = 2;
		ladderMenuMisses = 2;
		lastWalkClick = null;
		lastWalkTarget = null;
		// Arrived on the surface without the dungeon->surface event being
		// seen: decide from the y coordinate alone.
		if (state == State.WALK_TO_BANK && climbedUpPending && !isInDungeon(location))
		{
			climbedUpPending = false;
			insideShedByEvent = true;
		}

		if (state == State.WALK_TO_BANK && !isInDungeon(location))
		{
			if (isInShedInterior(location))
			{
				// Still in the shed: the door has to be used again.
				exitedDoorThisTrip = false;
				insideShedByEvent = true;
				logShed(context, location, "STUCK recovery: still inside, door will be clicked again");
			}
		}
		else if (state == State.WALK_TO_GIANTS && !isInDungeon(location))
		{
			if (!isInShedInterior(location))
			{
				passedDoorThisTrip = false;
				doorStepAttempts = 0;
				logShed(context, location, "STUCK recovery: outside, back to the door's outside tile");
			}
			else
			{
				logShed(context, location, "STUCK recovery: inside, retrying the ladder");
			}
		}
	}

	/**
	 * The return trip (inventory has free space, so we just banked). The
	 * direction comes from the state, which comes from the inventory: on the
	 * way back the first thing at the shed is the door, then the ladder.
	 * <p>
	 * Before the door: walk to the tile in front of it and
	 * click it. The moment the player moves after that click (they were let
	 * through), {@link #passedDoorThisTrip} latches and from then on the only
	 * action is the ladder. No per-tick inside/outside check can clear the
	 * latch; only climbing down, walking well away from the shed, or three
	 * ladder clicks that did not move the player at all release it.
	 */
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
			if (passedDoorThisTrip || shedStep != null)
			{
				logShed(context, location, "climbed down, latch cleared");
			}
			shedStep = null;
			passedDoorThisTrip = false;
			ladderNoMoveFails = 0;
			ladderWaitTicks = 0;
			ladderAttempts = 0;
			ladderClickFrom = null;
			doorClickFrom = null;
			if (location.distanceTo(HILL_GIANTS) <= 8)
			{
				state = State.HUNT;
				setStatus("Hunting");
				return;
			}
			walkTowards(context, HILL_GIANTS);
			setStatus("Walking to hill giants");
			return;
		}

		if (location.distanceTo(SURFACE_LADDER) > SHED_LATCH_DISTANCE)
		{
			// Clearly away from the shed (for example still walking from the
			// bank). Nothing is latched out here.
			passedDoorThisTrip = false;
			ladderNoMoveFails = 0;
			doorClickFrom = null;
			doorStepAttempts = 0;
			shedStep = ShedStep.APPROACH_DOOR;
			walkTowards(context, DOOR_OUTSIDE, true);
			setStatus("Walking to shed door");
			return;
		}

		// Standing on the shed's own floor (north of the door row) is proof
		// of being inside, from the real footprint.
		if (!passedDoorThisTrip && isInsideShedArea(location))
		{
			passedDoorThisTrip = true;
			logShed(context, location, "inside shed footprint, latch set");
		}

		// A door click was sent: any movement at all means we were let
		// through. Latch immediately.
		if (doorClickFrom != null)
		{
			if (crossedDoorLine(location) && !isInShedInterior(doorClickFrom))
			{
				passedDoorThisTrip = true;
				doorClickFrom = null;
				doorWaitTicks = 0;
				logShed(context, location, "moved through the door after door click, latch set");
			}
			else if (!location.equals(doorClickFrom) && --doorWaitTicks > 0)
			{
				// Moving, but still on the outside (for example stepping up
				// to the door first). Not proof of anything yet.
				logShed(context, location, "moving after door click, still outside, no latch");
				setStatus("Opening door");
				return;
			}
			else if (--doorWaitTicks > 0)
			{
				logShed(context, location, "waiting for door click");
				setStatus("Opening door");
				return;
			}
			else
			{
				doorClickFrom = null;
				logShed(context, location, "door click did nothing, will retry");
			}
		}

		if (passedDoorThisTrip && !isInShedInterior(location))
		{
			// Latched but standing outside the shed: the latch is wrong.
			// Drop it and start again from the door's outside tile.
			passedDoorThisTrip = false;
			ladderWaitTicks = 0;
			ladderAttempts = 0;
			ladderClickFrom = null;
			ladderBlocked = false;
			doorStepAttempts = 0;
			logShed(context, location, "latched but outside the shed interior, latch cleared, back to door");
		}

		if (passedDoorThisTrip)
		{
			shedStep = ShedStep.CLIMB;
			climbDownLatched(context, location);
			return;
		}

		openShedDoor(context, location);
	}

	private void climbDownLatched(ScriptContext context, WorldPoint location)
	{
		if (ladderBlocked)
		{
			ladderBlocked = false;
			++ladderNoMoveFails;
			logShed(context, location, "ladder click did not move us (" + ladderNoMoveFails + "/"
				+ LADDER_NO_MOVE_LIMIT + ")");

			if (ladderNoMoveFails >= LADDER_NO_MOVE_LIMIT)
			{
				// Three clean ladder clicks with zero movement: we really are
				// on the wrong side. Release the latch and open the door once.
				ladderNoMoveFails = 0;
				passedDoorThisTrip = false;
				ladderAttempts = 0;
				shedStep = ShedStep.APPROACH_DOOR;
				logShed(context, location, "releasing latch, opening door once");
				setStatus("Ladder unreachable, opening door");
				return;
			}
		}

		if (ladderWaitTicks <= 0 && ladderAttempts >= LADDER_ATTEMPTS)
		{
			// Clicks that moved us but did not climb: step onto the ladder
			// and keep trying. The door stays untouched.
			ladderAttempts = 0;
			logShed(context, location, "ladder attempts used up, stepping to ladder");
			walkTowards(context, SURFACE_LADDER);
			setStatus("Stepping to ladder");
			return;
		}

		climbLadder(context, false, "door latch set, climbing down");
	}

	private void openShedDoor(ScriptContext context, WorldPoint location)
	{
		// The door is only ever clicked from its exact outside tile, so the
		// click itself makes the player walk through it, not up to it.
		boolean onDoorTile = location.equals(DOOR_OUTSIDE);
		if (!onDoorTile && location.distanceTo(DOOR_OUTSIDE) <= 1 && doorStepAttempts < 3)
		{
			shedStep = ShedStep.APPROACH_DOOR;
			GameInput input = context.getInput();
			if (input.isBusy() || isMoving(context.getPlayer()))
			{
				return;
			}
			// walkTowards stops one tile short, so take the last step with
			// a minimap click on the exact tile.
			if (clickMinimap(context, DOOR_OUTSIDE))
			{
				++doorStepAttempts;
				logShed(context, location, "stepping onto the door's outside tile");
				setStatus("Walking to shed door");
			}
			return;
		}

		shedStep = location.distanceTo(DOOR_OUTSIDE) <= 1 ? ShedStep.OPEN_DOOR : ShedStep.APPROACH_DOOR;

		if (shedStep == ShedStep.APPROACH_DOOR)
		{
			logShed(context, location, "walking to the tile in front of the door");
			walkTowards(context, DOOR_OUTSIDE, true);
			setStatus("Walking to shed door");
			return;
		}

		GameInput input = context.getInput();
		if (input.isBusy() || input.isCameraBusy())
		{
			return;
		}

		Optional<TileObject> door = findShedDoor(context);
		if (door.isEmpty())
		{
			logShed(context, location, "door not found, walking to it");
			walkTowards(context, DOOR_OUTSIDE, true);
			setStatus("Looking for shed door");
			return;
		}

		if (!hasAction(context, door.get(), DOOR_OPEN_ACTIONS)
			&& hasAction(context, door.get(), new String[] {"Close"}))
		{
			// The door really is open (it offers Close). Walk in on the
			// minimap; the shed footprint check sets the latch once inside.
			// Not latched here: the player is still outside.
			logShed(context, location, "door already open, walking in (no latch until inside)");
			if (!context.getInput().isBusy() && !isMoving(context.getPlayer()))
			{
				clickMinimap(context, new WorldPoint(SURFACE_DOOR.getX(), SURFACE_DOOR.getY() + 1, 0));
			}
			setStatus("Walking into shed");
			return;
		}

		logShed(context, location, "outside and not latched, clicking door");
		if (clickDoor(context, door.get(), location))
		{
			setStatus("Opening door");
		}
	}

	/**
	 * Opens the shed door with one click. Turns the camera first if the door
	 * is off screen, so the click always lands on the door itself.
	 */
	private boolean clickDoor(ScriptContext context, TileObject door, WorldPoint location)
	{
		switch (exactObjectClick(context, door, DOOR_OPEN_ACTIONS, true))
		{
			case SENT:
				actionThisTick = true;
				doorWaitTicks = DOOR_WAIT_TICKS;
				doorClickFrom = location;
				logShed(context, location, "door Open sent (exact menu entry)");
				return true;
			case MENU_OPENED:
				actionThisTick = true;
				logShed(context, location, "door right-clicked, picking Open next tick");
				return true;
			case CAMERA:
				setStatus("Turning camera to door");
				return false;
			default:
				return false;
		}
	}

	/**
	 * @return true if the tile is on the shed's own floor, north of the door
	 * row. The OSRS wiki gives the shed's footprint as x 3113-3117,
	 * y 3450-3453 with the door on the south row at (3115, 3450) and the
	 * ladder on the north row at (3116, 3453). The door row itself is left
	 * out so standing in the doorway never counts as proof.
	 */
	/**
	 * @return true once the player stands on the door row or further north,
	 * within the shed's x range: only a door that let the player through can
	 * put them there from the outside tile
	 */
	private static boolean crossedDoorLine(WorldPoint location)
	{
		return isInShedInterior(location);
	}

	/**
	 * @return true if the tile is in the shed's walkable interior box,
	 * x 3113-3117, y 3450-3453, plane 0 (bounded on both axes)
	 */
	private static boolean isInShedInterior(WorldPoint location)
	{
		return location != null
			&& location.getPlane() == 0
			&& location.getX() >= SHED_MIN_X && location.getX() <= SHED_MAX_X
			&& location.getY() >= SHED_MIN_Y && location.getY() <= SHED_MAX_Y;
	}

	private static boolean isInsideShedArea(WorldPoint location)
	{
		return location != null
			&& location.getPlane() == 0
			&& location.getX() >= SHED_MIN_X && location.getX() <= SHED_MAX_X
			&& location.getY() > SHED_MIN_Y && location.getY() <= SHED_MAX_Y;
	}

	/**
	 * Writes one line to the client log (~/.runelite/logs/client.log) for the
	 * shed sequence, and keeps the latest line for the overlay.
	 */
	private void logShed(ScriptContext context, WorldPoint location, String reason)
	{
		Optional<TileObject> door = findShedDoor(context);
		String doorInfo = door.map(object -> object.getId() + describeActions(context, object)).orElse("none");
		String line = String.format("[HillGiant shed] %s | state=%s step=%s loc=%d,%d,%d passedDoor=%s exitedDoor=%s"
				+ " insideByEvent=%s door=%s ladderFails=%d ladderAttempts=%d watchdog=%d",
			reason, state, shedStep,
			location == null ? -1 : location.getX(),
			location == null ? -1 : location.getY(),
			location == null ? -1 : location.getPlane(),
			passedDoorThisTrip, exitedDoorThisTrip, insideShedByEvent, doorInfo, ladderNoMoveFails, ladderAttempts,
			watchdogTicks);
		log.info("tick={} {}", context.getClient().getTickCount(), line);
		shedDebugLine = reason;
	}

	/**
	 * One [HillGiant state] line per tick once the state has not changed for
	 * more than 5 ticks, so a freeze always shows what the script is doing.
	 */
	private void logStateIfStale(ScriptContext context)
	{
		if (state != loggedState)
		{
			loggedState = state;
			stateTicks = 0;
			return;
		}
		if (++stateTicks <= 5)
		{
			return;
		}
		GameInput input = context.getInput();
		Player player = context.getPlayer();
		log.info("tick={} [HillGiant state] state={} for {} ticks | status={} loc={} inputBusy={} cameraBusy={}"
				+ " menuOpen={} moving={} animating={} interacting={} lootPhase={} lootTarget={} lootAttempts={}"
				+ " lootClickTicks={} cooldown={}",
			context.getClient().getTickCount(), state, stateTicks, getStatus(), context.getPlayerLocation(),
			input.isBusy(), input.isCameraBusy(), context.getClient().isMenuOpen(), isMoving(player),
			isAnimating(player), player == null ? null : player.getInteracting() != null, lootPhase, lootTarget,
			lootAttempts, lootClickTicks, cooldown);
	}

	/**
	 * One line per tick of the bank trip, wherever the player is, so a hang
	 * anywhere between the dungeon and the bank shows up in the log.
	 */
	private void logTrip(ScriptContext context, String reason)
	{
		WorldPoint location = context.getPlayerLocation();
		GameInput input = context.getInput();
		log.info("tick={} [HillGiant trip] {} | state={} step={} loc={} dungeon={} climbedUpPending={} insideByEvent={}"
				+ " exitedDoor={} doorClickFrom={} doorWait={} exitAttempts={} ladderWait={} ladderAttempts={}"
				+ " cooldown={} inputBusy={} cameraBusy={} menuOpen={} watchdog={} status={}",
			context.getClient().getTickCount(), reason, state, shedStep, location, isInDungeon(location), climbedUpPending,
			insideShedByEvent, exitedDoorThisTrip, doorClickFrom, doorWaitTicks, exitDoorAttempts, ladderWaitTicks,
			ladderAttempts, cooldown, input.isBusy(), input.isCameraBusy(), context.getClient().isMenuOpen(),
			watchdogTicks, getStatus());
	}

	private static String describeActions(ScriptContext context, TileObject object)
	{
		net.runelite.api.ObjectComposition composition = context.getClient().getObjectDefinition(object.getId());
		if (composition == null || composition.getActions() == null)
		{
			return "[]";
		}
		return Arrays.toString(Arrays.stream(composition.getActions())
			.filter(java.util.Objects::nonNull)
			.toArray());
	}

	/**
	 * Clicks exactly one option of one object. A plain left click lands on
	 * whatever is drawn on top at that pixel; around the shed that can be the
	 * ladder in front of the door (or the door in front of the ladder), so the
	 * shed objects are never left-clicked. The option is picked from the real
	 * right-click menu by object id and option, and after two menus that did
	 * not offer it, it is run directly without a mouse click.
	 */
	private ObjectClick exactObjectClick(ScriptContext context, TileObject object, String[] actions, boolean door)
	{
		int misses = door ? doorMenuMisses : ladderMenuMisses;
		if (misses >= 2)
		{
			if (context.getClient().isMenuOpen())
			{
				context.getInput().closeMenu();
				return ObjectClick.NONE;
			}

			if (door) doorMenuMisses = 0; else ladderMenuMisses = 0;
			return context.getInput().invokeObjectAction(object, actions) ? ObjectClick.SENT : ObjectClick.NONE;
		}

		boolean menuOpen = context.getClient().isMenuOpen();
		ObjectClick result = interactObject(context, object, actions, true);
		if (menuOpen && result == ObjectClick.NONE)
		{
			// The open menu did not contain the option for this object.
			if (door) ++doorMenuMisses; else ++ladderMenuMisses;
		}
		else if (result == ObjectClick.SENT)
		{
			if (door) doorMenuMisses = 0; else ladderMenuMisses = 0;
		}
		return result;
	}

	private enum ObjectClick
	{
		NONE,
		CAMERA,
		MENU_OPENED,
		SENT
	}

	/**
	 * One interaction with a door or ladder. Normally the option is used
	 * directly; after a couple of attempts that did nothing it falls back to
	 * the real right-click menu, which also works for wall objects whose menu
	 * identifier is packed differently.
	 */
	private ObjectClick interactObject(ScriptContext context, TileObject object, String[] actions, boolean viaMenu)
	{
		GameInput input = context.getInput();
		boolean menuOpen = context.getClient().isMenuOpen();

		if (menuOpen && !viaMenu)
		{
			input.closeMenu();
			return ObjectClick.NONE;
		}

		if (!menuOpen && !input.isVisible(object))
		{
			if (input.turnCameraToward(object.getWorldLocation()))
			{
				return ObjectClick.CAMERA;
			}

			// The camera cannot help: it already faces the object, or the
			// player stands on its tile (a wall door seen from right next to
			// it often has no clickbox on screen). Waiting for the camera
			// here used to hang forever, so run the option directly.
			boolean sent = input.invokeObjectAction(object, actions);
			log.info("[HillGiant shed] {} {} not on screen and camera cannot turn to it; direct option {}",
				object.getId(), Arrays.toString(actions), sent ? "sent" : "FAILED");
			actionThisTick |= sent;
			return sent ? ObjectClick.SENT : ObjectClick.NONE;
		}

		if (viaMenu)
		{
			if (!input.interactViaMenu(object, actions))
			{
				return ObjectClick.NONE;
			}
			return menuOpen ? ObjectClick.SENT : ObjectClick.MENU_OPENED;
		}

		return input.interact(object, actions) ? ObjectClick.SENT : ObjectClick.NONE;
	}

	/**
	 * Climbs the shed ladder (down) or the dungeon ladder (up) with a plain
	 * click on its climb option (left click when it is the default). Each
	 * click is given time to play out; a click after which the player did
	 * not move at all is reported through {@link #ladderBlocked} so the caller
	 * can deal with the door instead of clicking the ladder again.
	 */
	private void climbLadder(ScriptContext context, boolean up, String reason)
	{
		GameInput input = context.getInput();
		Player player = context.getPlayer();
		WorldPoint location = player == null ? null : player.getWorldLocation();

		// Hard guard: the shed ladder can only ever be clicked once the door
		// latch is set on the return trip.
		if (!up && (!passedDoorThisTrip || !isInShedInterior(location)))
		{
			logShed(context, location, "REFUSED ladder click (latch=" + passedDoorThisTrip
				+ ", inside=" + isInShedInterior(location) + "): " + reason);
			return;
		}
		WorldPoint ladderTile = up ? DUNGEON_LADDER : SURFACE_LADDER;
		String[] actions = up ? CLIMB_UP_ACTIONS : CLIMB_DOWN_ACTIONS;

		if (ladderWaitTicks > 0)
		{
			--ladderWaitTicks;
			boolean busy = isMoving(player) || isAnimating(player);
			if (busy || ladderWaitTicks > LADDER_WAIT_TICKS - 2)
			{
				setStatus(up ? "Climbing up" : "Climbing down");
				return;
			}

			// The click finished without taking us anywhere.
			if (ladderClickFrom != null && ladderClickFrom.equals(location))
			{
				ladderBlocked = !up;
			}
			ladderClickFrom = null;
			ladderWaitTicks = 0;
			if (ladderBlocked)
			{
				return;
			}
		}

		if (input.isBusy() || input.isCameraBusy())
		{
			return;
		}

		if (up && ladderAttempts >= LADDER_ATTEMPTS)
		{
			// Hard cap for the dungeon side too: step back to the ladder tile
			// and start counting again.
			ladderAttempts = 0;
			walkTowards(context, ladderTile);
			setStatus("Ladder did not work, re-approaching");
			return;
		}

		Optional<TileObject> ladder = findLadder(context, ladderTile, actions);
		if (ladder.isEmpty())
		{
			log.info("[HillGiant] ladder {} not found near {} (player at {}), walking to it",
				up ? "up" : "down", ladderTile, location);
			walkTowards(context, ladderTile, false);
			setStatus("Walking to ladder");
			return;
		}

		TileObject object = ladder.get();
		if (!input.isVisible(object) && location != null && location.distanceTo(ladderTile) > 6)
		{
			walkTowards(context, ladderTile, false);
			setStatus("Walking to ladder");
			return;
		}

		switch (exactObjectClick(context, object, actions, false))
		{
			case SENT:
				actionThisTick = true;
				++ladderAttempts;
				ladderWaitTicks = LADDER_WAIT_TICKS;
				ladderClickFrom = location;
				logShed(context, location, (up ? "ladder Climb-up" : "ladder Climb-down") + " sent (exact menu entry, id "
					+ object.getId() + "): " + reason);
				setStatus(up ? "Climbing up" : "Climbing down");
				break;
			case MENU_OPENED:
				actionThisTick = true;
				logShed(context, location, "ladder right-clicked, picking climb next tick: " + reason);
				break;
			case CAMERA:
				setStatus("Turning camera to ladder");
				break;
			default:
				break;
		}
	}

	private Optional<TileObject> findLadder(ScriptContext context, WorldPoint ladderTile, String[] actions)
	{
		Optional<TileObject> near = context.getTargets().getTileObjects(ladderTile, 3).stream()
			.filter(object -> hasAction(context, object, actions))
			.min(java.util.Comparator.comparingInt(object -> object.getWorldLocation() == null
				? Integer.MAX_VALUE
				: object.getWorldLocation().distanceTo(ladderTile)));
		if (near.isPresent())
		{
			return near;
		}

		return context.getTargets().findTileObject(LADDER_ID, 12)
			.filter(object -> hasAction(context, object, actions));
	}

	private Optional<TileObject> findShedDoor(ScriptContext context)
	{
		Optional<TileObject> byId = context.getTargets().getTileObjects(SURFACE_DOOR, 3).stream()
			.filter(object -> object.getId() == DOOR_ID)
			.findFirst();
		if (byId.isPresent())
		{
			return byId;
		}

		return context.getTargets().getTileObjects(SURFACE_DOOR, 2).stream()
			.filter(object -> object instanceof WallObject)
			.filter(object -> hasAction(context, object, DOOR_OPEN_ACTIONS)
				|| hasAction(context, object, new String[] {"Close"}))
			.findFirst();
	}

	private static boolean hasAction(ScriptContext context, TileObject object, String[] actions)
	{
		net.runelite.api.ObjectComposition composition = context.getClient().getObjectDefinition(object.getId());
		if (composition != null && composition.getImpostorIds() != null)
		{
			net.runelite.api.ObjectComposition impostor = composition.getImpostor();
			if (impostor != null)
			{
				composition = impostor;
			}
		}

		if (composition == null || composition.getActions() == null)
		{
			return false;
		}

		for (String candidate : composition.getActions())
		{
			if (candidate == null)
			{
				continue;
			}

			for (String action : actions)
			{
				if (candidate.equalsIgnoreCase(action))
				{
					return true;
				}
			}
		}

		return false;
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
					cooldown = BANK_OPEN_COOLDOWN_TICKS;
					setStatus("Opening bank");
				}
				return;
			}

			Optional<NPC> banker = context.getTargets().findVisibleNpc("Banker", "Bank", 10);
			if (banker.isPresent())
			{
				if (input.interact(banker.get(), "Bank"))
				{
					cooldown = BANK_OPEN_COOLDOWN_TICKS;
					setStatus("Opening bank");
				}
				return;
			}

			// No booth or banker is on screen yet. Move closer instead of
			// right-clicking an off-screen object, which would hit whatever
			// interface is drawn at that spot.
			walkTowards(context, WEST_VARROCK_BANK, true);
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
				if (!hasLootInInventory(inventory))
				{
					// Nothing to deposit (only the key, food or locked items).
					logBank(context, "deposit: no loot in inventory, skipping deposit");
					finishDeposit(context, inventory);
					return;
				}

				Widget deposit = context.getTargets()
					.findWidget(InterfaceID.Bankmain.DEPOSITINV)
					.orElse(null);
				if (deposit != null && input.click(deposit))
				{
					bankStage = BankStage.WAIT_DEPOSIT;
					bankAttempts = 0;
					depositSnapshot = inventorySnapshot(inventory);
					depositStillTicks = 0;
					cooldown = BANK_ACTION_COOLDOWN_TICKS;
					logBank(context, "deposit: clicked Deposit inventory");
				}
				else
				{
					++bankAttempts;
					cooldown = 1;
					logBank(context, "deposit: Deposit inventory button not clickable (attempt " + bankAttempts + ")");
					if (bankAttempts > BANK_STAGE_ATTEMPTS)
					{
						logBank(context, "deposit: button never became clickable, leaving anyway");
						finishDeposit(context, inventory);
						return;
					}
				}
				setStatus("Depositing loot");
				return;
			}
			case WAIT_DEPOSIT:
			{
				// Done as soon as no loot is left. The brass key, food and
				// anything in a locked slot are ignored: Deposit inventory
				// skips locked slots, so a "fully empty" or "only the key
				// left" check can never pass if anything else is locked.
				if (!hasLootInInventory(inventory))
				{
					logBank(context, "deposit: done, no loot left");
					finishDeposit(context, inventory);
					return;
				}

				// Loot still there. If the inventory stopped changing, the
				// click did not register (or the rest cannot be deposited):
				// click again a few times, then move on rather than hang.
				String snapshot = inventorySnapshot(inventory);
				depositStillTicks = snapshot.equals(depositSnapshot) ? depositStillTicks + 1 : 0;
				depositSnapshot = snapshot;
				if (depositStillTicks >= 3)
				{
					bankAttempts = depositRetries + 1;
					if (bankAttempts > 3)
					{
						logBank(context, "deposit: loot still in inventory after 3 deposit clicks, leaving anyway");
						finishDeposit(context, inventory);
						return;
					}
					logBank(context, "deposit: inventory unchanged, clicking Deposit inventory again");
					depositRetries = bankAttempts;
					bankStage = BankStage.DEPOSIT;
				}
				setStatus("Depositing loot");
				return;
			}
			case WITHDRAW_KEY:
			{
				if (inventory.countItem(BRASS_KEY_ID) >= BRASS_KEY_AMOUNT)
				{
					logBank(context, "fallback: brass key withdrawn, leaving");
					bankStage = BankStage.LEAVE;
					bankAttempts = 0;
					setStatus("Leaving bank");
					return;
				}

				if (++bankAttempts > BANK_STAGE_ATTEMPTS)
				{
					// No key in the inventory and none in the bank. Walking
					// back would only get stuck at the shed door, so stop here.
					log.error("[HillGiant] no brass key in the inventory or the bank; stopping."
						+ " Put a brass key in inventory slot 1 (locked) and restart the script.");
					haltedNoKey = true;
					setStatus("Stopped: no brass key in inventory or bank");
					return;
				}

				if (input.withdrawBankItem(BRASS_KEY_ID, "Withdraw-1"))
				{
					logBank(context, "fallback: Withdraw-1 brass key sent (attempt " + bankAttempts + ")");
					cooldown = BANK_OPEN_COOLDOWN_TICKS;
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
					logBank(context, "close: closing bank (attempt " + (bankAttempts + 1) + ")");
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
	 * After the deposit: keep the brass key (normally in the locked slot 1)
	 * and leave, or fall back to withdrawing one if it is really missing.
	 */
	private void finishDeposit(ScriptContext context, Inventory inventory)
	{
		bankAttempts = 0;
		depositRetries = 0;
		if (inventory.countItem(BRASS_KEY_ID) >= BRASS_KEY_AMOUNT)
		{
			bankStage = BankStage.LEAVE;
			logBank(context, "deposit finished, brass key kept in inventory, closing bank");
			setStatus("Leaving bank");
			return;
		}

		// Safety fallback only: the locked slot did not keep the key.
		log.warn("[HillGiant bank] brass key NOT in inventory after depositing (inventory: {});"
			+ " withdrawing one from the bank.", inventorySnapshot(inventory));
		bankStage = BankStage.WITHDRAW_KEY;
		setStatus("Brass key missing, withdrawing one");
	}

	private static boolean hasLootInInventory(Inventory inventory)
	{
		for (net.runelite.api.Item item : inventory.getItems())
		{
			if (item != null && isLootItem(item.getId()))
			{
				return true;
			}
		}
		return false;
	}

	private static String inventorySnapshot(Inventory inventory)
	{
		StringBuilder builder = new StringBuilder("[");
		for (net.runelite.api.Item item : inventory.getItems())
		{
			if (item != null && item.getId() != -1)
			{
				if (builder.length() > 1)
				{
					builder.append(',');
				}
				builder.append(item.getId());
				if (item.getQuantity() > 1)
				{
					builder.append('x').append(item.getQuantity());
				}
			}
		}
		return builder.append(']').toString();
	}

	private void logBank(ScriptContext context, String reason)
	{
		Inventory inventory = context.getInventory();
		log.info("tick={} [HillGiant bank] {} | stage={} attempts={} inventory={} brassKeys={} empty={}",
			context.getClient().getTickCount(), reason, bankStage, bankAttempts, inventorySnapshot(inventory),
			inventory.countItem(BRASS_KEY_ID), inventory.getEmptySlots());
	}

	/**
	 * Ends the bank visit and heads back to the dungeon. Called once the bank
	 * interface is really closed, whether by the leave stage itself or because
	 * it disappeared on its own.
	 */
	private void leaveBank()
	{
		log.info("[HillGiant bank] bank closed, starting the return walk (state WALK_TO_GIANTS)");
		cooldown = BANK_ACTION_COOLDOWN_TICKS;
		ignoredLoot.clear();
		bankStage = null;
		bankAttempts = 0;
		shedStep = null;
		passedDoorThisTrip = false;
		exitedDoorThisTrip = false;
		ladderNoMoveFails = 0;
		doorClickFrom = null;
		state = State.WALK_TO_GIANTS;
		setStatus("Returning to hill giants");
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
			cooldown = EAT_COOLDOWN_TICKS;
			setStatus("Eating sardine");
		}
	}

	private void walkTowards(ScriptContext context, WorldPoint target)
	{
		walkTowards(context, target, false);
	}

	/**
	 * Walks along a collision-checked path. The first click goes out right
	 * away, and the next one is chained when the player gets close to the
	 * last clicked tile (or stops), like a player clicking ahead, instead of
	 * standing still for a couple of ticks between legs. It never re-clicks
	 * while the player is still well on the way.
	 */
	private void walkTowards(ScriptContext context, WorldPoint target, boolean useMinimap)
	{
		GameInput input = context.getInput();
		if (input.isBusy())
		{
			return;
		}

		Player player = context.getPlayer();
		WorldPoint location = player == null ? null : player.getWorldLocation();
		if (location == null || location.distanceTo(target) <= 1)
		{
			lastWalkLocation = location;
			lastWalkClick = null;
			walkClickTicks = 0;
			return;
		}

		if (!target.equals(lastWalkTarget))
		{
			// A new destination: click right away instead of waiting on the
			// previous walk's chaining state.
			lastWalkTarget = target;
			lastWalkClick = null;
			walkClickTicks = 0;
		}

		boolean moved = lastWalkLocation != null && !lastWalkLocation.equals(location);
		lastWalkLocation = location;
		++walkClickTicks;

		if (lastWalkClick != null)
		{
			boolean nearClick = location.distanceTo(lastWalkClick) <= WALK_CHAIN_DISTANCE;
			if (moved && !nearClick)
			{
				return;
			}

			// The game needs a tick to start moving after a click.
			if (!moved && walkClickTicks < 2)
			{
				return;
			}
		}

		List<WorldPoint> path = findPath(context, target);
		if (path.size() <= 1)
		{
			lastWalkClick = null;
			return;
		}

		// Click a point a few tiles along the collision-checked path.
		int index = Math.min(path.size() - 1, WALK_CLICK_AHEAD);
		WorldPoint next = path.get(index);
		if (moved && next.equals(lastWalkClick))
		{
			// Already heading to the final tile. Nothing to add.
			return;
		}

		// Near the shed every walk click goes on the minimap. A click in the
		// game view lands on whatever is drawn at that pixel, and there that
		// can be the ladder (Climb-down) or the door (Open).
		boolean nearShed = !isInDungeon(location)
			&& location.distanceTo(SURFACE_LADDER) <= SHED_LATCH_DISTANCE + 4;

		// Minimap clicks never hit objects, so routes that pass a door
		// (the shed door in particular) cannot accidentally toggle it.
		if ((useMinimap || nearShed) && clickMinimap(context, next))
		{
			lastWalkClick = next;
			walkClickTicks = 0;
			return;
		}

		if (nearShed)
		{
			// Never fall back to a game-view click here.
			log.info("[HillGiant shed] minimap walk click to {} failed near the shed", next);
			return;
		}

		for (int i = index; i >= 1; i--)
		{
			if (input.walkTo(path.get(i)))
			{
				actionThisTick = true;
				lastWalkClick = path.get(i);
				walkClickTicks = 0;
				return;
			}
		}

		if (clickMinimap(context, next))
		{
			lastWalkClick = next;
			walkClickTicks = 0;
		}
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

		boolean clicked = context.getInput().click(new java.awt.Point(minimapPoint.getX(), minimapPoint.getY()));
		actionThisTick |= clicked;
		return clicked;
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

	/**
	 * @return true if the item is stackable and a stack of it is already in
	 * the inventory, so it can be picked up even with no free slot
	 */
	private static boolean stacksOntoInventory(ScriptContext context, int itemId)
	{
		if (context.getInventory().countItem(itemId) <= 0)
		{
			return false;
		}
		net.runelite.api.ItemComposition composition = context.getClient().getItemDefinition(itemId);
		return composition != null && composition.isStackable();
	}

	private GroundLoot findGroundLoot(ScriptContext context)
	{
		Client client = context.getClient();
		boolean inventoryFull = context.getInventory().isFull();
		Player player = context.getPlayer();
		WorldPoint playerLocation = player == null ? null : player.getWorldLocation();
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null || playerLocation == null)
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
		int bestScore = Integer.MAX_VALUE;

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

				WorldPoint itemLocation = tile.getWorldLocation();
				net.runelite.api.Point sceneLocation = tile.getSceneLocation();
				if (itemLocation == null || sceneLocation == null)
				{
					continue;
				}

				int distance = itemLocation.distanceTo(playerLocation);
				if (distance > MAX_DISTANCE || isIgnoredLoot(itemLocation))
				{
					continue;
				}

				for (TileItem groundItem : groundItems)
				{
					if (groundItem == null || !isLootItem(groundItem.getId())
						|| inventoryFull && !stacksOntoInventory(context, groundItem.getId()))
					{
						continue;
					}

					// Nearest first, so a pile is cleared tile by tile with
					// no back-and-forth. Our own kill's tile and our own drops
					// come before anything else at a similar distance, and
					// valuables before bones on the same tile.
					int score = distance * 10;
					if (lastDeathTile != null && itemLocation.distanceTo(lastDeathTile) <= 1)
					{
						score -= 30;
					}
					if (groundItem.getOwnership() == TileItem.OWNERSHIP_SELF)
					{
						score -= 5;
					}
					if (groundItem.getId() != ItemID.BIG_BONES)
					{
						score -= 1;
					}
					if (groundItem.getId() == GIANT_KEY_ID || groundItem.getId() == ENSOULED_GIANT_HEAD_ID)
					{
						// The rarest drop: take it before anything else
						// within a few tiles.
						score -= 25;
					}

					if (score < bestScore)
					{
						bestScore = score;
						best = new GroundLoot(groundItem.getId(), sceneLocation.getX(),
							sceneLocation.getY(), itemLocation);
					}
				}
			}
		}

		return best;
	}

	/**
	 * Picks the next giant to fight: one already attacking us first, then the
	 * nearest giant nobody else is fighting, preferring one that is on screen.
	 */
	private Optional<NPC> findHillGiant(ScriptContext context)
	{
		Client client = context.getClient();
		Player player = context.getPlayer();
		WorldPoint playerLocation = player == null ? null : player.getWorldLocation();
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null || playerLocation == null)
		{
			return Optional.empty();
		}

		GameInput input = context.getInput();
		NPC best = null;
		int bestScore = Integer.MAX_VALUE;

		for (NPC npc : worldView.npcs())
		{
			if (npc == null || npc.isDead() || npc == dyingNpc || !isHillGiant(npc))
			{
				continue;
			}

			WorldPoint npcLocation = npc.getWorldLocation();
			if (npcLocation == null || npcLocation.getPlane() != playerLocation.getPlane())
			{
				continue;
			}

			int distance = npcLocation.distanceTo(playerLocation);
			if (distance > MAX_DISTANCE)
			{
				continue;
			}

			boolean targetingUs = npc.getInteracting() == player;
			if (!targetingUs && isTakenByOther(context, npc))
			{
				continue;
			}

			int score = distance * 10;
			if (targetingUs)
			{
				score -= 1000;
			}
			if (input.isVisible(npc))
			{
				score -= 25;
			}

			if (score < bestScore)
			{
				bestScore = score;
				best = npc;
			}
		}

		return Optional.ofNullable(best);
	}

	/**
	 * @return true if another player is fighting this NPC or has just clicked
	 * it, so attacking it would only steal or waste the kill
	 */
	private static boolean isTakenByOther(ScriptContext context, NPC npc)
	{
		Player player = context.getPlayer();
		net.runelite.api.Actor target = npc.getInteracting();
		if (target != null && target != player)
		{
			return true;
		}

		WorldView worldView = context.getClient().getTopLevelWorldView();
		if (worldView == null)
		{
			return false;
		}

		for (Player other : worldView.players())
		{
			if (other != null && other != player && other.getInteracting() == npc)
			{
				return true;
			}
		}

		return false;
	}

	private static boolean isInScene(ScriptContext context, NPC target)
	{
		WorldView worldView = context.getClient().getTopLevelWorldView();
		if (worldView == null || target == null)
		{
			return false;
		}

		for (NPC npc : worldView.npcs())
		{
			if (npc == target)
			{
				return true;
			}
		}

		return false;
	}

	/**
	 * @return true once the player has stopped and its model has finished
	 * sliding onto its tile (the camera follows the model, so until then
	 * every tile on screen is still moving)
	 */
	private static boolean isSettled(ScriptContext context, Player player)
	{
		if (player == null || isMoving(player))
		{
			return false;
		}
		net.runelite.api.coords.LocalPoint local = player.getLocalLocation();
		net.runelite.api.coords.LocalPoint tile = net.runelite.api.coords.LocalPoint.fromWorld(
			context.getClient(), player.getWorldLocation());
		return local == null || tile == null || local.equals(tile);
	}

	private static boolean isMoving(Player player)
	{
		return player != null && player.getPoseAnimation() != player.getIdlePoseAnimation();
	}

	private static boolean isAnimating(Player player)
	{
		return player != null && player.getAnimation() != -1;
	}

	private boolean isHillGiant(NPC npc)
	{
		if (isHillGiantId(npc.getId()))
		{
			return true;
		}

		return NPC_NAME.equalsIgnoreCase(getNpcName(npc));
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
