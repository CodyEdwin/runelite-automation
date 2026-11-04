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

import java.awt.Point;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.runelite.api.Item;
import net.runelite.api.MenuEntry;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
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
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MotherlodeMinerScript.class);

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

	private static final WorldPoint UPSTAIRS_VEIN_AREA = new WorldPoint(3760, 5673, 1);
	private static final WorldPoint UPSTAIRS_HOPPER_AREA = new WorldPoint(3755, 5677, 1);
	private static final WorldPoint LADDER_UP_AREA = new WorldPoint(3755, 5673, 0);
	private static final WorldPoint LADDER_DOWN_AREA = new WorldPoint(3755, 5674, 1);
	private static final WorldPoint CRATE_AREA = new WorldPoint(3752, 5674, 0);
	private static final WorldPoint[] UPSTAIRS_VEIN_TILES = {
		new WorldPoint(3761, 5674, 1),
		new WorldPoint(3762, 5673, 1),
		new WorldPoint(3759, 5674, 1),
		new WorldPoint(3760, 5674, 1),
		new WorldPoint(3762, 5672, 1),
		new WorldPoint(3762, 5671, 1),
		new WorldPoint(3762, 5670, 1),
		new WorldPoint(3758, 5675, 1)
	};
	private static final int[] LADDER_UP_IDS = {
		ObjectID.MOTHERLODE_LADDER_BOTTOM,
		ObjectID.MOTHERLODE_LADDER_BOTTOM_ACTIVE,
		ObjectID.MOTHERLODE_LADDER_BOTTOM_INACTIVE
	};
	private static final int[] LADDER_DOWN_IDS = {
		ObjectID.MOTHERLODE_LADDER_TOP,
		ObjectID.MOTHERLODE_LADDER_TOP_ACTIVE,
		ObjectID.MOTHERLODE_LADDER_TOP_INACTIVE
	};
	private static final String[] CLIMB_UP_ACTIONS = {"Climb-up", "Climb up", "Climb"};
	private static final String[] CLIMB_DOWN_ACTIONS = {"Climb-down", "Climb down", "Climb"};
	private static final int CRATE_ID = 357;
	// Same values the RuneLite Motherlode plugin uses for the sack HUD.
	private static final int SACK_SIZE = 108;
	private static final int SACK_LARGE_SIZE = 189;
	// The upper floor is a bridge inside the cave. Its tiles sit around
	// -470 to -700, while the lower floor is around -120 to -210. The
	// ladder-top tile itself is -488, so -490 from the upstream plugin
	// would misread the landing tile as downstairs.
	private static final int UPPER_FLOOR_HEIGHT = -460;
	private static final int LADDER_SEARCH_RADIUS = 4;
	private static final int CLIMB_WAIT_TICKS = 5;

	private static final int STRUT_BROKEN_ID = ObjectID.MOTHERLODE_WHEEL_STRUT_BROKEN;
	private static final int STRUT_RADIUS = 2;
	private static final int STRUT_REACH = 4;

	private static final int VEIN_RADIUS = 8;
	private static final int WALK_STOP_DISTANCE = 2;
	// Stand beside the hopper instead of a couple of tiles away.
	private static final int HOPPER_STOP_DISTANCE = 1;
	private static final int OBJECT_REACH = 5;
	private static final int VEIN_WALK_DISTANCE = 4;
	private static final int CLICK_COOLDOWN = 1;
	private static final int ACTION_ATTEMPTS = 8;
	private static final int SEARCH_WAIT_TICKS = 3;
	private static final int MAX_SEARCH_CLICKS = 12;
	private static final long MINE_START_TIMEOUT_MS = 3000L;
	private static final long MINE_ANIMATION_GAP_MS = 1500L;
	/** Hover checks on the sack before falling back to its menu option. */
	private static final int SACK_HOVER_ATTEMPTS = 4;
	/** Max distance (px) from the live hull centre for a verified click. */
	private static final double CENTER_TOLERANCE_PX = 6.0;
	/** Ladder clicks without a floor change before halting. */
	private static final int CLIMB_ATTEMPT_LIMIT = 8;
	/** Ticks the sack varbit must read 0 before the sack counts as empty (it lags a tick). */
	private static final int SACK_EMPTY_CONFIRM_TICKS = 2;
	/** Sack trips in a row with ore left in the sack but none coming out before halting. */
	private static final int SACK_FRUITLESS_TRIPS = 3;
	private static final long WASH_TIMEOUT_MS = 15000L;
	// Give the server a moment to remove the deposited pay-dirt from the
	// inventory before the same load is clicked into the hopper twice.
	private static final long DEPOSIT_SETTLE_MS = 1200L;

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
		CLIMB_UP,
		CLIMB_DOWN,
		SEARCH_CRATE,
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
	private boolean chestHovered;
	private boolean hammerUnavailable;
	private boolean hammerHovered;
	private boolean repairAfterDescent;
	private boolean collectingSack;
	private int crateAttempts;
	private int climbAttempts;
	private int climbWaitTicks;
	private int upstairsLoads;
	private int sackBase;
	private boolean sackBaseInit;
	private int depositedSinceEmpty;
	private int lastLiveSack;
	private int lastPayDirtCount;
	private int sackContents;
	private int sackCapacity = SACK_SIZE;
	private final boolean upstairs;

	private boolean mining;
	private boolean miningAnimationSeen;
	private long lastMiningAnimationAt;
	private TileObject currentVein;
	private int miningLevel = -1;
	private long lastMineClickAt;

	private boolean depositClickSent;
	private boolean sackKnownEmpty;
	private boolean payDirtDeposited;
	private long depositClickedAt;
	private long washXpAtDeposit;
	private int searchClicks;
	private int searchMisses;
	private int sackCameraTurns;
	private int itemsBeforeSearch;
	private int levelUpDismissAttempts;
	private String verifyKey;
	private boolean droppingExtras;
	private boolean dropVerified;
	private boolean dropHovered;
	private int payDirtAtClick;
	private boolean verifyHovered;
	private int verifyMisses;
	private int sackZeroTicks;
	private int sackFruitlessTrips;
	private boolean sackTripGotOres;

	public MotherlodeMinerScript()
	{
		this(false);
	}

	public MotherlodeMinerScript(boolean upstairs)
	{
		super("Motherlode Miner",
			"Mines pay-dirt, deposits it at the hopper, searches the sack, and banks the ores.");
		this.upstairs = upstairs;
	}

	@Override
	protected void onStart()
	{
		// Do not read the player or varbits here: onStart runs on the panel
		// thread, and the client only allows those calls from the client
		// thread. CLIMB_UP resolves to MINE on the first game tick when the
		// player is already upstairs.
		state = upstairs ? State.CLIMB_UP : State.MINE;
		bankStage = null;
		bankPurpose = BankPurpose.DEPOSIT;
		walker = null;
		cooldown = 0;
		actionAttempts = 0;
		searchWait = 0;
		wheelAttempts = 0;
		bankClicks = 0;
		chestHovered = false;
		hammerUnavailable = false;
		hammerHovered = false;
		repairAfterDescent = false;
		collectingSack = false;
		crateAttempts = 0;
		climbAttempts = 0;
		climbWaitTicks = 0;
		upstairsLoads = 0;
		sackBase = 0;
		sackBaseInit = false;
		depositedSinceEmpty = 0;
		lastLiveSack = 0;
		lastPayDirtCount = 0;
		sackContents = 0;
		sackCapacity = SACK_SIZE;
		mining = false;
		miningAnimationSeen = false;
		lastMiningAnimationAt = 0;
		currentVein = null;
		miningLevel = -1;
		lastMineClickAt = 0;
		depositClickSent = false;
		sackKnownEmpty = false;
		payDirtDeposited = false;
		depositClickedAt = 0;
		washXpAtDeposit = 0;
		searchClicks = 0;
		searchMisses = 0;
		sackCameraTurns = 0;
		itemsBeforeSearch = 0;
		levelUpDismissAttempts = 0;
		verifyKey = null;
		droppingExtras = false;
		dropVerified = false;
		dropHovered = false;
		payDirtAtClick = 0;
		verifyHovered = false;
		verifyMisses = 0;
		sackZeroTicks = 0;
		sackFruitlessTrips = 0;
		sackTripGotOres = false;
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

		// The same varbits the RuneLite Motherlode plugin uses for its HUD.
		// Reading the live value means pay-dirt left in the sack from an
		// earlier session is counted instead of assumed to be zero.
		int liveSack = context.getClient().getVarbitValue(VarbitID.MOTHERLODE_SACK_TRANSMIT);
		if (liveSack > lastLiveSack)
		{
			// Fresh pay-dirt reached the sack, so it is no longer empty.
			sackKnownEmpty = false;
		}
		lastLiveSack = liveSack;
		sackZeroTicks = liveSack == 0 ? sackZeroTicks + 1 : 0;
		sackCapacity = context.getClient().getVarbitValue(VarbitID.MOTHERLODE_BIGGERSACK) == 1
			? SACK_LARGE_SIZE : SACK_SIZE;
		if (!sackBaseInit)
		{
			sackBase = liveSack;
			sackBaseInit = true;
		}
		// The sack counter only moves once pay-dirt has washed, so deposits
		// still sitting in the hopper are invisible to it. Track them from
		// the sack value seen when it was last emptied, otherwise the script
		// keeps mining into a sack that is already full.
		sackContents = isUpstairs(context)
			? Math.min(sackCapacity, Math.max(liveSack, sackBase + depositedSinceEmpty))
			: liveSack;

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

		// Recover if a previous misread left an upper-floor state active
		// while the player is still downstairs. Never mine the upper veins
		// through the floor; climb the ladder first.
		if (upstairs && needsUpperFloor(state) && !isUpstairs(context))
		{
			state = State.CLIMB_UP;
			walker.reset();
			climbAttempts = 0;
			climbWaitTicks = 0;
			mining = false;
			miningAnimationSeen = false;
			currentVein = null;
			setStatus("Climbing up the ladder");
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
			case CLIMB_UP:
				climbUp(context);
				break;
			case CLIMB_DOWN:
				climbDown(context);
				break;
			case SEARCH_CRATE:
				searchCrate(context);
				break;
			case HALTED:
			default:
				break;
		}
	}

	private void mine(ScriptContext context)
	{
		Inventory inventory = context.getInventory();
		if (upstairs)
		{
			int capacity = inventory.countItem(ItemID.PAYDIRT) + inventory.getEmptySlots();
			// Only mine as much pay-dirt as the sack can still take, so a load
			// never has to be split once the hopper is close to full.
			int sackSpace = Math.max(0, sackCapacity - sackContents);
			int target = Math.min(capacity, sackSpace);
			if (inventory.isFull() || inventory.countItem(ItemID.PAYDIRT) >= target)
			{
				// The upper hopper is within reach of every upper vein and
				// always on screen, so deposit from where the player is
				// standing instead of walking over to it first.
				state = State.HOPPER;
				walker.reset();
				actionAttempts = 0;
				depositClickSent = false;
				mining = false;
				miningAnimationSeen = false;
				currentVein = null;
				setStatus("Depositing pay-dirt");
				return;
			}

			// Keep depositing until the sack itself is full. Only then is a
			// trip downstairs needed; a full inventory alone is not a reason
			// to leave the upper level.
			if (sackContents >= sackCapacity && !sackKnownEmpty)
			{
				state = State.CLIMB_DOWN;
				climbAttempts = 0;
				walker.reset();
				mining = false;
				miningAnimationSeen = false;
				currentVein = null;
				setStatus("Climbing down to empty the sack");
				return;
			}
		}
		else
		{
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
		}

		Player player = context.getPlayer();
		long now = System.currentTimeMillis();
		if (mining)
		{
			if (player != null && player.getAnimation() != -1)
			{
				miningAnimationSeen = true;
				lastMiningAnimationAt = now;
				setStatus("Mining pay-dirt");
				return;
			}

			// Mining continues from a single click. The animation can briefly
			// disappear between ores, so only click again once it has been
			// gone for a while, the vein is gone, or the click never started
			// an animation. Without the gap check a depleted vein that still
			// reports the same id would stall the script forever.
			boolean animationRecent = miningAnimationSeen
				&& now - lastMiningAnimationAt < MINE_ANIMATION_GAP_MS;
			if (veinStillExists(context, currentVein)
				&& (animationRecent || now - lastMineClickAt < MINE_START_TIMEOUT_MS))
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

		TileObject vein = upstairs ? findUpstairsVein(context) : findVein(context);
		if (vein == null)
		{
			WorldPoint location = context.getPlayerLocation();
			WorldPoint area = upstairs
				? onPlayerPlane(context, UPSTAIRS_VEIN_AREA)
				: VEIN_AREA;
			if (location != null && location.distanceTo(area) > VEIN_WALK_DISTANCE)
			{
				walker.walkTo(area, true, WALK_STOP_DISTANCE);
				setStatus("Walking to the veins");
			}
			else
			{
				setStatus("Waiting for a vein");
			}
			return;
		}

		WorldPoint location = context.getPlayerLocation();
		if (location != null && location.distanceTo2D(vein.getWorldLocation()) > VEIN_WALK_DISTANCE)
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

		if (input.isCameraBusy())
		{
			setStatus("Adjusting the camera");
			return;
		}

		input.stopCamera();
		if (input.clickCenter(vein) || input.interact(vein, "Mine"))
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

		WorldPoint area = upstairs
			? onPlayerPlane(context, UPSTAIRS_HOPPER_AREA)
			: HOPPER_AREA;
		TileObject hopper = findObjectAt(context, HOPPER_ID, area, OBJECT_REACH + 2);
		if (hopper != null)
		{
			WorldPoint hopperLocation = hopper.getWorldLocation();
			if (location.distanceTo2D(hopperLocation) <= HOPPER_STOP_DISTANCE)
			{
				state = State.HOPPER;
				actionAttempts = 0;
				depositClickSent = false;
				setStatus("Depositing pay-dirt");
				return;
			}

			// Walk to the hopper itself, not just the general area, so the
			// player ends up beside it instead of stopping a few tiles short.
			// The hopper tile is solid, so the path ends on the closest
			// walkable tile next to it.
			walker.walkTo(hopperLocation, true, HOPPER_STOP_DISTANCE);
			setStatus("Walking to the hopper");
			return;
		}

		walker.walkTo(area, true, HOPPER_STOP_DISTANCE);
		setStatus("Walking to the hopper");
	}

	private void hopper(ScriptContext context)
	{
		Inventory inventory = context.getInventory();

		// The sack is full: the hopper cannot take any more pay-dirt. Go
		// downstairs and empty it, then come back up. This is the only
		// reason to leave the upper level.
		int carriedPayDirt = inventory.countItem(ItemID.PAYDIRT);
		if (droppingExtras)
		{
			dropExtras(context, carriedPayDirt);
			return;
		}
		if (carriedPayDirt > 0 && sackContents >= sackCapacity && !sackKnownEmpty)
		{
			// The sack (plus what is still washing) cannot take this load:
			// drop it so the sack/bank loop is not blocked.
			startDroppingExtras(carriedPayDirt, "sack full before the hopper");
			return;
		}

		if (inventory.countItem(ItemID.PAYDIRT) == 0)
		{
			if (depositClickSent)
			{
				depositedSinceEmpty += Math.max(0, lastPayDirtCount);
				// Pay-dirt actually went into the hopper, so the sack is no
				// longer empty.
				sackKnownEmpty = false;
			}
			payDirtDeposited = depositClickSent;
			walker.reset();
			actionAttempts = 0;
			searchClicks = 0;
			if (upstairs)
			{
				++upstairsLoads;
				if (sackContents >= sackCapacity)
				{
					// That deposit filled the sack. Go down and empty it
					// instead of walking back to the veins first.
					state = State.CLIMB_DOWN;
					climbAttempts = 0;
					setStatus("Climbing down to empty the sack");
				}
				else
				{
					state = State.WALK_TO_VEIN;
					setStatus("Walking back to the veins");
				}
			}
			else
			{
				state = State.WALK_TO_SACK;
				setStatus("Walking to the sack");
			}
			return;
		}

		lastPayDirtCount = inventory.countItem(ItemID.PAYDIRT);

		if (depositClickSent
			&& System.currentTimeMillis() - depositClickedAt < DEPOSIT_SETTLE_MS)
		{
			setStatus("Depositing pay-dirt");
			return;
		}

		// The hopper took only part of the load: the sack is full. Drop the
		// rest and go empty the sack.
		if (depositClickSent && payDirtAtClick > 0 && lastPayDirtCount < payDirtAtClick)
		{
			depositedSinceEmpty += payDirtAtClick - lastPayDirtCount;
			sackKnownEmpty = false;
			payDirtDeposited = true;
			startDroppingExtras(lastPayDirtCount, "hopper took " + (payDirtAtClick - lastPayDirtCount)
				+ " of " + payDirtAtClick);
			return;
		}

		if (actionAttempts >= ACTION_ATTEMPTS)
		{
			// The sack is probably too full to accept more pay-dirt. Go and
			// empty it, then try the hopper again on a later trip.
			payDirtDeposited = false;
			walker.reset();
			actionAttempts = 0;
			searchClicks = 0;
			if (upstairs)
			{
				// Only reached when clicking from the veins did not deposit
				// anything, so close the gap instead of leaving the level.
				state = State.WALK_TO_HOPPER;
				setStatus("Walking closer to the hopper");
			}
			else
			{
				state = State.WALK_TO_SACK;
				setStatus("Walking to the sack");
			}
			return;
		}

		WorldPoint location = context.getPlayerLocation();
		WorldPoint area = upstairs
			? onPlayerPlane(context, UPSTAIRS_HOPPER_AREA)
			: HOPPER_AREA;
		TileObject hopper = findObjectAt(context, HOPPER_ID, area, OBJECT_REACH + 2);
		if (hopper == null)
		{
			state = State.WALK_TO_HOPPER;
			setStatus("Looking for the hopper");
			return;
		}

		// Upstairs the hopper is clicked from the veins; walking next to it
		// first only wastes time. Downstairs, close the last few tiles.
		WorldPoint hopperLocation = hopper.getWorldLocation();
		if (!upstairs && location != null && hopperLocation != null
			&& location.distanceTo2D(hopperLocation) > HOPPER_STOP_DISTANCE)
		{
			state = State.WALK_TO_HOPPER;
			setStatus("Walking closer to the hopper");
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
		++actionAttempts;

		int carriedNow = inventory.countItem(ItemID.PAYDIRT);
		if (input.clickCenter(hopper) || input.interact(hopper, "Deposit"))
		{
			payDirtAtClick = carriedNow;
			depositClickSent = true;
			washXpAtDeposit = context.getClient().getSkillExperience(Skill.MINING);
			depositClickedAt = System.currentTimeMillis();
			cooldown = CLICK_COOLDOWN;
			setStatus("Depositing pay-dirt");
		}
		else
		{
			cooldown = 1;
		}
	}

	private void startDroppingExtras(int count, String reason)
	{
		log.info("[MotherlodeMiner] sack full, dropping {} extra pay-dirt ({}; sack {}/{})", count, reason,
			sackContents, sackCapacity);
		droppingExtras = true;
		dropHovered = false;
		depositClickSent = false;
		payDirtAtClick = 0;
		actionAttempts = 0;
		setStatus("Sack full: dropping " + count + " extra pay-dirt");
	}

	/**
	 * Drops every pay-dirt left after the hopper is full, then goes to empty
	 * the sack. The first drop is a verified left click ("Drop Pay-dirt" on
	 * top); after that the slots are clicked directly. Capped per load.
	 */
	private void dropExtras(ScriptContext context, int carried)
	{
		if (carried == 0)
		{
			log.info("[MotherlodeMiner] extra pay-dirt dropped, going to empty the sack");
			droppingExtras = false;
			payDirtDeposited = true;
			walker.reset();
			actionAttempts = 0;
			searchClicks = 0;
			if (upstairs)
			{
				state = State.CLIMB_DOWN;
				climbAttempts = 0;
				setStatus("Climbing down to empty the sack");
			}
			else
			{
				state = State.WALK_TO_SACK;
				setStatus("Walking to the sack");
			}
			return;
		}

		GameInput input = context.getInput();
		if (input.isBusy())
		{
			return;
		}
		if (++actionAttempts > 28 * 3)
		{
			log.info("[MotherlodeMiner] could not drop the extra pay-dirt ({} left), emptying the sack anyway", carried);
			droppingExtras = false;
			state = upstairs ? State.CLIMB_DOWN : State.WALK_TO_SACK;
			climbAttempts = 0;
			walker.reset();
			return;
		}

		Inventory inventory = context.getInventory();
		if (!inventory.isOpen())
		{
			inventory.open();
			return;
		}
		int slot = inventory.findSlot(ItemID.PAYDIRT);
		Widget item = slot < 0 ? null : inventory.getSlotWidget(slot);
		if (item == null || item.isHidden())
		{
			return;
		}

		if (!dropVerified)
		{
			if (!dropHovered)
			{
				dropHovered = input.hover(item);
				return;
			}
			dropHovered = false;
			MenuEntry entry = input.getLeftClickEntry();
			String option = entry == null ? "" : GameInput.plainText(entry.getOption()).toLowerCase(Locale.ROOT);
			String target = entry == null ? "" : GameInput.plainText(entry.getTarget()).toLowerCase(Locale.ROOT);
			if (!"drop".equals(option) || !target.contains("pay-dirt"))
			{
				log.info("[MotherlodeMiner] pay-dirt left click is '{} {}', not 'Drop' - re-aiming", option, target);
				return;
			}
			Point mouse = input.getMouseLocation();
			if (mouse != null && input.click(mouse))
			{
				dropVerified = true;
			}
			setStatus("Dropping extra pay-dirt (" + carried + " left)");
			return;
		}

		// Left click is confirmed as Drop: click the next pay-dirt slot.
		input.click(item);
		setStatus("Dropping extra pay-dirt (" + carried + " left)");
	}

	private void walkToSack(ScriptContext context)
	{
		if (sackKnownEmpty)
		{
			// The sack was already emptied on this trip; do not walk back to
			// it and search it again.
			finishSackTrip(context, context.getInventory());
			return;
		}

		WorldPoint location = context.getPlayerLocation();
		if (location == null)
		{
			return;
		}

		TileObject sack = findSack(context);
		if ((sack != null && location.distanceTo2D(sack.getWorldLocation()) <= WALK_STOP_DISTANCE)
			|| location.distanceTo(SACK_AREA) <= WALK_STOP_DISTANCE)
		{
			state = State.SACK;
			searchWait = 0;
			searchClicks = 0;
			searchMisses = 0;
			sackCameraTurns = 0;
			itemsBeforeSearch = 0;
			verifyKey = null;
			setStatus("Searching the sack");
			return;
		}

		walker.walkTo(SACK_AREA, true, WALK_STOP_DISTANCE);
		setStatus("Walking to the sack");
	}

	private void sack(ScriptContext context)
	{
		Inventory inventory = context.getInventory();

		if (sackKnownEmpty && searchClicks == 0)
		{
			finishSackTrip(context, inventory);
			return;
		}

		if (searchWait > 0)
		{
			if (--searchWait > 0)
			{
				return;
			}

			if (countInventoryItems(inventory) > itemsBeforeSearch)
			{
				// Ores arrived. Bank them once the inventory is full or the
				// sack is out; the bank trip comes back here while the sack
				// varbit still shows ore.
				searchMisses = 0;
				sackTripGotOres = true;
				log.info("[MotherlodeMiner] sack gave {} items, sack varbit now {}, {} free slots",
					countInventoryItems(inventory) - itemsBeforeSearch, lastLiveSack, inventory.getEmptySlots());
				if (lastLiveSack > 0 && inventory.getEmptySlots() > 0)
				{
					searchClicks = 0;
					cooldown = 1;
					setStatus("Emptying the sack");
					return;
				}
				finishSackTrip(context, inventory);
				return;
			}

			++searchMisses;
			log.info("[MotherlodeMiner] sack search #{} gave nothing (sack varbit {}, zero for {} ticks)",
				searchClicks, lastLiveSack, sackZeroTicks);
		}

		// Empty only when the sack varbit has read 0 for a couple of ticks
		// (it updates a tick late), never because a click missed.
		boolean washed = !payDirtDeposited
			|| context.getClient().getSkillExperience(Skill.MINING) > washXpAtDeposit
			|| System.currentTimeMillis() - depositClickedAt >= WASH_TIMEOUT_MS;
		if (lastLiveSack == 0 && sackZeroTicks >= SACK_EMPTY_CONFIRM_TICKS && washed)
		{
			log.info("[MotherlodeMiner] sack is empty (varbit 0 for {} ticks), {} items to bank",
				sackZeroTicks, countInventoryItems(inventory));
			sackKnownEmpty = true;
			collectingSack = false;
			finishSackTrip(context, inventory);
			return;
		}

		boolean xpGained = context.getClient().getSkillExperience(Skill.MINING) > washXpAtDeposit;
		long now = System.currentTimeMillis();

		if (payDirtDeposited && !xpGained && now - depositClickedAt < WASH_TIMEOUT_MS)
		{
			setStatus("Waiting for pay-dirt to wash");
			return;
		}

		if (inventory.getEmptySlots() == 0)
		{
			// A full pay-dirt inventory cannot hold the ores from the sack.
			// Free one slot and try again; otherwise the trip would end
			// without emptying the sack and immediately walk back here.
			if (inventory.countItem(ItemID.PAYDIRT) > 0)
			{
				if (dropOnePayDirt(context))
				{
					cooldown = CLICK_COOLDOWN;
				}
				setStatus("Dropping pay-dirt to make room for ores");
				return;
			}

			finishSackTrip(context, inventory);
			return;
		}

		if (searchClicks >= MAX_SEARCH_CLICKS)
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
		if (clickSack(context, sack))
		{
			itemsBeforeSearch = countInventoryItems(inventory);
			++searchClicks;
			searchWait = SEARCH_WAIT_TICKS;
			cooldown = 1;
			setStatus("Searching the sack");
		}
	}

	private boolean clickSack(ScriptContext context, TileObject sack)
	{
		return verifiedObjectClick(context, sack, "sack", new String[]{"search"}, "sack", "Search");
	}

	/**
	 * Verified left click on a game object, aimed at the dead centre of its
	 * hull. Tick 1: curved move onto the centre. Tick 2: recompute the centre
	 * (the player or camera may have moved) and click only if the cursor is
	 * still inside the hull, within {@link #CENTER_TOLERANCE_PX} of the centre,
	 * and the real left-click option matches; otherwise re-aim. After
	 * {@link #SACK_HOVER_ATTEMPTS} misses it sends the object's own option.
	 *
	 * @return true when the action was sent
	 */
	private boolean verifiedObjectClick(ScriptContext context, TileObject object, String key,
		String[] optionPrefixes, String targetWord, String... fallbackActions)
	{
		GameInput input = context.getInput();
		if (!key.equals(verifyKey))
		{
			verifyKey = key;
			verifyHovered = false;
			verifyMisses = 0;
		}
		if (!verifyHovered)
		{
			if (input.hoverCenter(object))
			{
				verifyHovered = true;
			}
			else
			{
				log.info("[MotherlodeMiner] {}: no clickable centre on screen", key);
			}
			setStatus("Moving mouse to the " + key);
			return false;
		}
		verifyHovered = false;

		MenuEntry entry = input.getLeftClickEntry();
		String option = entry == null ? "" : GameInput.plainText(entry.getOption()).toLowerCase(Locale.ROOT);
		String target = entry == null ? "" : GameInput.plainText(entry.getTarget()).toLowerCase(Locale.ROOT);
		Point mouse = input.getMouseLocation();
		Point center = input.objectCenter(object);
		Shape hull = input.objectHull(object);
		boolean onHull = mouse != null && hull != null && hull.contains(mouse);
		boolean centred = mouse != null && center != null && mouse.distance(center) <= CENTER_TOLERANCE_PX;
		boolean optionOk = target.contains(targetWord);
		boolean prefixOk = false;
		for (String prefix : optionPrefixes)
		{
			prefixOk |= option.startsWith(prefix);
		}
		if (onHull && centred && optionOk && prefixOk)
		{
			if (input.click(mouse))
			{
				log.info("[MotherlodeMiner] {} click: top='{} {}' at {},{} (centre {},{})", key, option, target,
					mouse.x, mouse.y, center.x, center.y);
				verifyMisses = 0;
				verifyKey = null;
				return true;
			}
			return false;
		}

		++verifyMisses;
		log.info("[MotherlodeMiner] {} hover miss {}/{}: top='{} {}' onHull={} centred={} -> re-aiming", key,
			verifyMisses, SACK_HOVER_ATTEMPTS, option, target, onHull, centred);
		if (verifyMisses < SACK_HOVER_ATTEMPTS)
		{
			cooldown = 1;
			return false;
		}

		// Something keeps covering it: send its own option on the object
		// instead of clicking whatever is in front.
		verifyMisses = 0;
		verifyKey = null;
		boolean sent = input.invokeObjectAction(object, fallbackActions);
		log.info("[MotherlodeMiner] {} left click never verified, used its option {}: {}", key,
			Arrays.toString(fallbackActions), sent);
		return sent;
	}

	private void finishSackTrip(ScriptContext context, Inventory inventory)
	{
		payDirtDeposited = false;
		searchClicks = 0;
		// Keep coming back from the bank until the sack varbit reads 0;
		// only then is the descent collection done.
		collectingSack = lastLiveSack > 0;
		if (collectingSack)
		{
			sackKnownEmpty = false;
			sackFruitlessTrips = sackTripGotOres ? 0 : sackFruitlessTrips + 1;
			if (sackFruitlessTrips >= SACK_FRUITLESS_TRIPS)
			{
				log.info("[MotherlodeMiner] sack still shows {} but {} trips in a row got nothing, halting",
					lastLiveSack, sackFruitlessTrips);
				state = State.HALTED;
				setStatus("Stopped: the sack shows ore but searching it gives nothing");
				return;
			}
		}
		else
		{
			sackFruitlessTrips = 0;
		}
		sackTripGotOres = false;
		log.info("[MotherlodeMiner] leaving the sack: varbit {}, {} items to bank, {}", lastLiveSack,
			countInventoryItems(inventory), collectingSack ? "coming back for the rest" : "sack done");
		// The sack is emptied (or left with only what is still in it), so
		// the local deposit count starts again from the live value.
		sackBase = lastLiveSack;
		depositedSinceEmpty = 0;
		lastPayDirtCount = 0;
		bankPurpose = BankPurpose.DEPOSIT;
		if (hasBankableItems(inventory) || collectingSack)
		{
			if (!hasBankableItems(inventory))
			{
				// Ore left in the sack but nothing came out (missed clicks):
				// search again instead of walking to the bank empty.
				state = State.WALK_TO_SACK;
				searchWait = 0;
				setStatus("Back to the sack");
				return;
			}
			state = State.WALK_TO_BANK;
			walker.reset();
			actionAttempts = 0;
			setStatus("Walking to the bank");
		}
		else
		{
			state = upstairs ? State.CLIMB_UP : State.WALK_TO_VEIN;
			walker.reset();
			upstairsLoads = 0;
			collectingSack = false;
			climbAttempts = 0;
			setStatus(upstairs ? "Walking back to the ladder" : "Walking back to the veins");
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
		if (chest != null && location.distanceTo2D(chest.getWorldLocation()) <= OBJECT_REACH)
		{
			state = State.BANK;
			bankStage = null;
			actionAttempts = 0;
			bankClicks = 0;
			chestHovered = false;
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

			if (collectingSack || lastLiveSack > 0)
			{
				// The sack still has ore: empty it fully before going back
				// to mine.
				log.info("[MotherlodeMiner] banked, sack varbit {} -> back to the sack", lastLiveSack);
				collectingSack = true;
				sackKnownEmpty = false;
				state = State.WALK_TO_SACK;
				setStatus("Walking back to the sack");
				return;
			}
			log.info("[MotherlodeMiner] banked, sack empty -> back to mining");

			if (upstairs)
			{
				upstairsLoads = 0;
				climbAttempts = 0;
				state = State.CLIMB_UP;
				setStatus("Walking back to the ladder");
			}
			else
			{
				state = State.WALK_TO_VEIN;
				setStatus("Walking back to the veins");
			}
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
			if (location != null && location.distanceTo2D(chest.getWorldLocation()) > OBJECT_REACH)
			{
				walker.walkTo(chest.getWorldLocation(), true, WALK_STOP_DISTANCE);
				setStatus("Walking to the bank chest");
				return;
			}

			input.stopCamera();
			if (!input.isVisible(chest))
			{
				input.turnCameraToward(chest.getWorldLocation());
				setStatus("Turning camera to the bank chest");
				return;
			}

			// Glide the cursor onto the chest first, confirm the cursor is
			// actually over the chest, then left click in place. This keeps the
			// opening visible without a click landing on a nearby object.
			if (!chestHovered)
			{
				if (!input.isBusy() && input.hoverCenter(chest))
				{
					chestHovered = true;
				}
				setStatus("Moving mouse to the bank chest");
				return;
			}

			MenuEntry entry = input.getLeftClickEntry();
			String option = entry == null
				? "" : GameInput.plainText(entry.getOption()).toLowerCase(Locale.ROOT);
			String target = entry == null
				? "" : GameInput.plainText(entry.getTarget()).toLowerCase(Locale.ROOT);
			boolean onChest = target.contains("bank chest")
				&& (option.contains("use") || option.contains("bank"));

			boolean clicked;
			if (onChest)
			{
				Point mouse = input.getMouseLocation();
				clicked = mouse != null && input.click(mouse);
				chestHovered = false;
			}
			else
			{
				// The cursor did not land on the chest; use its exact action
				// so a bad camera angle cannot stall the trip.
				chestHovered = false;
				clicked = input.invokeObjectAction(chest, "Use", "Bank");
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
			hammerHovered = false;
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
					hammerHovered = false;
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

				// Walk the mouse over to the hammer slot first so the
				// withdrawal is visible, then send the deterministic action
				// once the pointer has actually arrived.
				Widget hammer = input.findBankItemWidget(ItemID.HAMMER);
				if (hammer != null && !hammerHovered)
				{
					if (!input.isBusy() && input.hover(hammer))
					{
						hammerHovered = true;
					}
					setStatus("Moving mouse to the hammer");
					return;
				}

				if (hammerHovered && input.isBusy())
				{
					setStatus("Moving mouse to the hammer");
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

		if (location.distanceTo2D(strut.getWorldLocation()) > STRUT_REACH)
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
		if (input.clickCenter(strut) || input.interact(strut, "Hammer", "Fix", "Use"))
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

		if (repairAfterDescent)
		{
			repairAfterDescent = false;
			state = State.WALK_TO_SACK;
			setStatus("Walking to the sack");
			return;
		}

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
				if (objectLocation == null)
				{
					continue;
				}

				int distance = objectLocation.distanceTo2D(location);
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

		WorldPoint area = upstairs
			? onPlayerPlane(context, UPSTAIRS_VEIN_AREA)
			: VEIN_AREA;
		TileObject vein = upstairs ? findUpstairsVein(context) : findVein(context);
		if (location.distanceTo(area) <= VEIN_WALK_DISTANCE || vein != null)
		{
			state = State.MINE;
			setStatus("Mining pay-dirt");
			return;
		}

		walker.walkTo(area, true, WALK_STOP_DISTANCE);
		setStatus("Walking back to the veins");
	}

	private void climbUp(ScriptContext context)
	{
		WorldPoint location = context.getPlayerLocation();
		if (location == null)
		{
			return;
		}

		if (isUpstairs(context))
		{
			walker.reset();
			climbAttempts = 0;
			climbWaitTicks = 0;
			state = State.MINE;
			setStatus("Mining pay-dirt");
			return;
		}

		if (climbWaitTicks > 0)
		{
			--climbWaitTicks;
			if (isUpstairs(context))
			{
				walker.reset();
				climbAttempts = 0;
				climbWaitTicks = 0;
				state = State.MINE;
				setStatus("Mining pay-dirt");
				return;
			}

			if (isMoving(context))
			{
				climbWaitTicks = Math.max(climbWaitTicks, 2);
			}
			setStatus("Climbing up");
			return;
		}

		TileObject ladder = findLadder(context, LADDER_UP_AREA, true);
		if (ladder == null)
		{
			walker.walkTo(onPlayerPlane(context, LADDER_UP_AREA), true, WALK_STOP_DISTANCE);
			setStatus("Walking to the ladder");
			return;
		}

		WorldPoint ladderLocation = ladder.getWorldLocation();
		if (location.distanceTo2D(ladderLocation) > WALK_STOP_DISTANCE)
		{
			walker.walkTo(onPlayerPlane(context, ladderLocation), true, WALK_STOP_DISTANCE);
			setStatus("Walking to the ladder");
			return;
		}

		GameInput input = context.getInput();
		if (input.isBusy() || isMoving(context))
		{
			setStatus("Waiting to climb");
			return;
		}

		if (clickLadder(context, ladder, true))
		{
			++climbAttempts;
			walker.reset();
			climbWaitTicks = CLIMB_WAIT_TICKS;
			cooldown = 2;
			setStatus("Climbing up");
		}
		else
		{
			cooldown = 1;
		}
	}

	private void climbDown(ScriptContext context)
	{
		WorldPoint location = context.getPlayerLocation();
		if (location == null)
		{
			return;
		}

		if (!isUpstairs(context))
		{
			climbWaitTicks = 0;
			beginAfterDescent(context);
			return;
		}

		if (climbWaitTicks > 0)
		{
			--climbWaitTicks;
			if (!isUpstairs(context))
			{
				climbWaitTicks = 0;
				beginAfterDescent(context);
				return;
			}

			if (isMoving(context))
			{
				climbWaitTicks = Math.max(climbWaitTicks, 2);
			}
			setStatus("Climbing down");
			return;
		}

		TileObject ladder = findLadder(context, LADDER_DOWN_AREA, false);
		if (ladder == null)
		{
			walker.walkTo(onPlayerPlane(context, LADDER_DOWN_AREA), true, WALK_STOP_DISTANCE);
			setStatus("Walking to the ladder");
			return;
		}

		WorldPoint ladderLocation = ladder.getWorldLocation();
		if (location.distanceTo2D(ladderLocation) > WALK_STOP_DISTANCE)
		{
			walker.walkTo(onPlayerPlane(context, ladderLocation), true, WALK_STOP_DISTANCE);
			setStatus("Walking to the ladder");
			return;
		}

		GameInput input = context.getInput();
		if (input.isBusy() || isMoving(context))
		{
			setStatus("Waiting to climb down");
			return;
		}

		if (clickLadder(context, ladder, false))
		{
			++climbAttempts;
			walker.reset();
			climbWaitTicks = CLIMB_WAIT_TICKS;
			cooldown = 2;
			setStatus("Climbing down");
		}
		else
		{
			cooldown = 1;
		}
	}

	/**
	 * Clicks a ladder, falling back to its exact menu option when the model
	 * is not on screen. A ladder is often stood on (or behind the player's
	 * own tile), in which case there is no clickbox to click and turning the
	 * camera can never help - the direct option still works.
	 */
	private boolean clickLadder(ScriptContext context, TileObject ladder, boolean up)
	{
		GameInput input = context.getInput();
		String[] actions = up ? CLIMB_UP_ACTIONS : CLIMB_DOWN_ACTIONS;

		if (!input.isVisible(ladder))
		{
			if (climbAttempts <= 2 && input.turnCameraToward(ladder.getWorldLocation()))
			{
				setStatus("Turning camera to the ladder");
				return false;
			}

			input.stopCamera();
		}
		else
		{
			input.stopCamera();
		}

		// Verified left click on the ladder's hull centre ("Climb..." on
		// "Ladder"); its own Climb option only after repeated misses.
		if (climbAttempts >= CLIMB_ATTEMPT_LIMIT)
		{
			log.info("[MotherlodeMiner] ladder: {} clicks without changing floor, halting", climbAttempts);
			state = State.HALTED;
			setStatus("Stopped: could not climb the ladder");
			return false;
		}
		boolean sent = verifiedObjectClick(context, ladder, up ? "ladder up" : "ladder down",
			new String[]{"climb"}, "ladder", actions);
		if (sent)
		{
			log.info("[MotherlodeMiner] ladder {} attempt {}: waiting {} ticks for the floor change", up ? "up" : "down",
				climbAttempts + 1, CLIMB_WAIT_TICKS);
		}
		return sent;
	}

	private static TileObject findLadder(ScriptContext context, WorldPoint area, boolean up)
	{
		int[] ids = up ? LADDER_UP_IDS : LADDER_DOWN_IDS;
		String[] actions = up ? CLIMB_UP_ACTIONS : CLIMB_DOWN_ACTIONS;
		WorldPoint location = context.getPlayerLocation();
		TileObject best = null;
		int bestDistance = Integer.MAX_VALUE;
		TileObject fallback = null;
		int fallbackDistance = Integer.MAX_VALUE;

		for (TileObject object : context.getTargets().getTileObjectsNear(area, LADDER_SEARCH_RADIUS))
		{
			if (!containsId(ids, object.getId()))
			{
				continue;
			}

			WorldPoint objectLocation = object.getWorldLocation();
			if (objectLocation == null)
			{
				continue;
			}

			int distance = location == null
				? objectLocation.distanceTo2D(area)
				: objectLocation.distanceTo2D(location);
			if (hasAction(context, object, actions))
			{
				if (distance < bestDistance)
				{
					bestDistance = distance;
					best = object;
				}
			}
			else if (distance < fallbackDistance)
			{
				fallbackDistance = distance;
				fallback = object;
			}
		}

		return best != null ? best : fallback;
	}

	private static boolean containsId(int[] ids, int id)
	{
		for (int candidate : ids)
		{
			if (candidate == id)
			{
				return true;
			}
		}
		return false;
	}

	private static boolean hasAction(ScriptContext context, TileObject object, String... actions)
	{
		ObjectComposition composition = context.getClient().getObjectDefinition(object.getId());
		if (composition == null || composition.getActions() == null)
		{
			return false;
		}

		for (String available : composition.getActions())
		{
			if (available == null)
			{
				continue;
			}

			String clean = GameInput.plainText(available).trim();
			for (String action : actions)
			{
				if (clean.equalsIgnoreCase(action))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The upper floor is a raised bridge inside the same cave, so its object
	 * plane is not always the same as the plane the player reports (the
	 * client can keep reporting plane 0 while standing on the bridge). The
	 * upstream Motherlode plugin identifies the floor by the bridge tile
	 * height instead, which is what this uses.
	 * <p>
	 * Only the player's own tile is measured. Sampling neighbouring tiles
	 * made the lower floor count as upstairs whenever the player stood near
	 * the ladder or the crate, which is directly under the edge of the
	 * upper floor. That made the script mine the upper veins without ever
	 * climbing the ladder.
	 */
	private static boolean isUpstairs(ScriptContext context)
	{
		Player player = context.getPlayer();
		if (player == null)
		{
			return false;
		}

		WorldPoint location = player.getWorldLocation();
		if (location != null && location.getPlane() > 0)
		{
			return true;
		}

		LocalPoint local = player.getLocalLocation();
		if (local == null)
		{
			return false;
		}

		return Perspective.getTileHeight(context.getClient(), local, 0) < UPPER_FLOOR_HEIGHT;
	}

	private static boolean needsUpperFloor(State state)
	{
		return state == State.MINE
			|| state == State.WALK_TO_VEIN
			|| state == State.WALK_TO_HOPPER
			|| state == State.HOPPER;
	}

	private void beginAfterDescent(ScriptContext context)
	{
		walker.reset();
		actionAttempts = 0;
		crateAttempts = 0;
		wheelAttempts = 0;
		hammerUnavailable = false;
		repairAfterDescent = true;
		collectingSack = true;

		if (findBrokenStrut(context) == null)
		{
			repairAfterDescent = false;
			state = State.WALK_TO_SACK;
			setStatus("Walking to the sack");
			return;
		}

		if (context.getInventory().countItem(ItemID.HAMMER) > 0)
		{
			state = State.FIX_WHEEL;
			setStatus("Fixing the water wheels");
		}
		else
		{
			state = State.SEARCH_CRATE;
			setStatus("Searching the crate for a hammer");
		}
	}

	private void searchCrate(ScriptContext context)
	{
		Inventory inventory = context.getInventory();
		if (inventory.countItem(ItemID.HAMMER) > 0)
		{
			state = State.FIX_WHEEL;
			wheelAttempts = 0;
			repairAfterDescent = true;
			setStatus("Fixing the water wheels");
			return;
		}

		// Searching the crate with no free slot only produces the chatbox
		// "Your inventory is full." message and never a hammer. Free a slot
		// first (or bank if the inventory is full of ores) instead of
		// clicking the crate until the attempt counter gives up.
		if (inventory.getEmptySlots() == 0)
		{
			if (inventory.countItem(ItemID.PAYDIRT) > 0)
			{
				if (dropOnePayDirt(context))
				{
					cooldown = CLICK_COOLDOWN;
				}
				setStatus("Dropping pay-dirt to make room for a hammer");
				return;
			}

			bankPurpose = BankPurpose.DEPOSIT;
			state = State.WALK_TO_BANK;
			walker.reset();
			actionAttempts = 0;
			setStatus("Walking to the bank");
			return;
		}

		if (crateAttempts > ACTION_ATTEMPTS * 2)
		{
			repairAfterDescent = false;
			state = State.WALK_TO_SACK;
			walker.reset();
			actionAttempts = 0;
			setStatus("Walking to the sack");
			return;
		}

		WorldPoint location = context.getPlayerLocation();
		if (location == null)
		{
			return;
		}

		TileObject crate = findObjectAt(context, CRATE_ID, CRATE_AREA, 2);
		if (crate == null)
		{
			walker.walkTo(onPlayerPlane(context, CRATE_AREA), true, WALK_STOP_DISTANCE);
			setStatus("Walking to the crate");
			return;
		}

		if (location.distanceTo2D(crate.getWorldLocation()) > WALK_STOP_DISTANCE)
		{
			walker.walkTo(crate.getWorldLocation(), true, WALK_STOP_DISTANCE);
			setStatus("Walking to the crate");
			return;
		}

		GameInput input = context.getInput();
		if (input.isBusy() || isMoving(context))
		{
			setStatus("Waiting to search the crate");
			return;
		}

		if (!input.isVisible(crate))
		{
			input.turnCameraToward(crate.getWorldLocation());
			setStatus("Turning camera to the crate");
			return;
		}

		input.stopCamera();
		++crateAttempts;
		if (input.clickCenter(crate) || input.interact(crate, "Search"))
		{
			cooldown = 2;
			setStatus("Searching the crate for a hammer");
		}
		else
		{
			cooldown = 1;
		}
	}

	private static WorldPoint onPlayerPlane(ScriptContext context, WorldPoint point)
	{
		WorldPoint location = context.getPlayerLocation();
		return location == null
			? point
			: new WorldPoint(point.getX(), point.getY(), location.getPlane());
	}

	private static TileObject findObjectAt(ScriptContext context, int objectId,
		WorldPoint area, int radius)
	{
		WorldPoint location = context.getPlayerLocation();
		TileObject best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (TileObject object : context.getTargets().getTileObjectsNear(area, radius))
		{
			if (object.getId() != objectId || object.getWorldLocation() == null)
			{
				continue;
			}

			int distance = location == null
				? object.getWorldLocation().distanceTo2D(area)
				: object.getWorldLocation().distanceTo2D(location);
			if (distance == Integer.MAX_VALUE)
			{
				continue;
			}

			if (distance < bestDistance)
			{
				bestDistance = distance;
				best = object;
			}
		}
		return best;
	}

	private TileObject findUpstairsVein(ScriptContext context)
	{
		Player player = context.getPlayer();
		WorldPoint location = player == null ? null : player.getWorldLocation();
		if (location == null)
		{
			return null;
		}

		TileObject best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (WorldPoint tile : UPSTAIRS_VEIN_TILES)
		{
			for (TileObject object : context.getTargets().getTileObjectsNear(tile, 0))
			{
				if (!isVein(object.getId()))
				{
					continue;
				}

				WorldPoint objectLocation = object.getWorldLocation();
				if (objectLocation == null
					|| objectLocation.getX() != tile.getX()
					|| objectLocation.getY() != tile.getY())
				{
					continue;
				}

				int distance = objectLocation.distanceTo2D(location);
				if (distance < bestDistance)
				{
					bestDistance = distance;
					best = object;
				}
			}
		}
		return best;
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
			WorldPoint objectLocation = object.getWorldLocation();
			if (object.getId() == vein.getId() && objectLocation != null
				&& objectLocation.getX() == location.getX()
				&& objectLocation.getY() == location.getY())
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
			if (objectLocation == null)
			{
				continue;
			}

			int distance = objectLocation.distanceTo2D(location);
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

	@Override
	public List<String> getOverlayLines()
	{
		ScriptContext context = getContext();
		Inventory inventory = context.getInventory();
		boolean wheelsDown = state == State.FIX_WHEEL || state == State.SEARCH_CRATE;
		return Arrays.asList(
			"Mode: " + (upstairs ? "Upstairs" : "Downstairs")
				+ " | State: " + (state == null ? "-" : state.name()),
			"Load: " + (upstairs ? String.valueOf(upstairsLoads + 1) : "-")
				+ " | Pay-dirt: " + inventory.countItem(ItemID.PAYDIRT),
			"Hopper: " + sackContents + " / " + sackCapacity,
			"Wheels: " + (wheelsDown ? "Repairing" : "Working"));
	}
}
