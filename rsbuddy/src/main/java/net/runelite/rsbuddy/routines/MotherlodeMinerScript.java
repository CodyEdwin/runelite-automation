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
package net.runelite.rsbuddy.routines;

import java.awt.Point;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
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
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.rsbuddy.input.GameInput;
import net.runelite.rsbuddy.inventory.Inventory;
import net.runelite.rsbuddy.routine.Actions;
import net.runelite.rsbuddy.routine.Routine;
import net.runelite.rsbuddy.routine.RoutineContext;
import net.runelite.rsbuddy.routine.RoutineSetting;
import net.runelite.rsbuddy.walk.Walker;

/**
 * Mines pay-dirt at the upper level of the Motherlode Mine, deposits it into
 * the hopper, collects the washed ores from the sack, banks them, and returns.
 * <p>
 * Walking uses minimap left clicks. After the sack, the bank chest is clicked
 * directly when it is on screen (camera north keeps it in view). The sack is
 * only searched after the bonus experience arrives (roughly eight seconds after
 * the hopper click), which is when the pay-dirt has reached the sack.
 */
public final class MotherlodeMinerScript extends Routine implements Actions.Host
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
	/** Sack to bank chest is ~15 tiles; a north camera still leaves the chest on screen. */
	private static final int BANK_VIEW_DISTANCE = 24;
	private static final int VEIN_WALK_DISTANCE = 4;
	private static final int CLICK_COOLDOWN = 0;
	/** After a click, wait this long for the walk to start before aiming at the same target again. */
	private static final long POST_CLICK_GRACE_MS = 1300L;
	private static final int ACTION_ATTEMPTS = 8;
	private static final int SEARCH_WAIT_TICKS = 3;
	private static final int MAX_SEARCH_CLICKS = 12;
	private static final long MINE_START_TIMEOUT_MS = 3000L;
	private static final long MINE_ANIMATION_GAP_MS = 1500L;
	/** Hover checks on the sack before falling back to its menu option. */
	private static final int SACK_HOVER_ATTEMPTS = 4;
	/** Start aiming at the sack (and click it) once within this many tiles and on screen. */
	private static final int SACK_PREHOVER_DISTANCE = 8;
	/** Special energy varp value for 100%. */
	private static final int SPEC_FULL = 1000;
	private static final int SPEC_WAIT_TICKS = 3;
	private static final int SPEC_CLICK_LIMIT = 6;
	/** Skip the special on a run when the sack has room for this many pay-dirt or fewer. */
	private static final int SPEC_SAVE_FREE_SPACE = 12;
	/** Dragon pickaxe, upgraded, (or) (Zalcano), (or) (Trailblazer). */
	private static final java.util.Set<Integer> DRAGON_PICKAXES = java.util.Set.of(
		ItemID.DRAGON_PICKAXE, ItemID.DRAGON_PICKAXE_PRETTY, ItemID.ZALCANO_PICKAXE,
		ItemID.TRAILBLAZER_PICKAXE_NO_INFERNAL);
	/** Max distance (px) from the live hull centre for a verified click. */
	private static final double CENTER_TOLERANCE_PX = 6.0;
	/** Camera/walk recoveries for one target before halting. */
	private static final int VERIFY_RECOVERIES = 3;
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
		DROP_HAMMER,
		PICKUP_PAYDIRT,
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
	private boolean repairFromUpstairs;
	private boolean repairReturn;
	private boolean upperDropPending;
	private WorldPoint droppedPayDirtAt;
	private int upperRepairAttempts;
	private int pickupAttempts;
	private int pickupStartCount;
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
	private final boolean dragonSpec;
	private State lastDispatchState;
	/** Shared action layer: verified clicks, walking, aim/drop logic, gap logs. */
	private Actions actions;
	private boolean specHovered;
	private int specClicks;
	private int specWaitTicks;
	private boolean specSkipLogged;
	private boolean specSavedLogged;

	private boolean mining;
	private boolean miningAnimationSeen;
	private long lastMiningAnimationAt;
	private TileObject currentVein;
	private int miningLevel = -1;
	private long lastMineClickAt;
	private long mineFastUntil;

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
	private int payDirtAtClick;
	private int sackZeroTicks;
	private int sackFruitlessTrips;
	private boolean sackTripGotOres;

	public MotherlodeMinerScript()
	{
		this(false);
	}

	public MotherlodeMinerScript(boolean upstairs)
	{
		this(upstairs, false);
	}

	/**
	 * @param upstairs mine the upper level
	 * @param dragonSpec use the dragon pickaxe special whenever energy is 100%
	 */
	public MotherlodeMinerScript(boolean upstairs, boolean dragonSpec)
	{
		super("Motherlode Miner",
			"Mines pay-dirt, deposits it at the hopper, searches the sack, and banks the ores.");
		this.upstairs = upstairs;
		this.dragonSpec = dragonSpec;
	}

	@Override
	public List<RoutineSetting> getRoutineSettings()
	{
		return List.of(RoutineSetting.integer("motherlodeSackCapacity",
			"Sack capacity",
			"Pay-dirt the sack holds: 108, 189, or 0 to auto-detect from the bigger-sack varbit", 189));
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
		repairFromUpstairs = false;
		repairReturn = false;
		upperDropPending = false;
		droppedPayDirtAt = null;
		upperRepairAttempts = 0;
		pickupAttempts = 0;
		pickupStartCount = 0;
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
		mineFastUntil = 0;
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
		specHovered = false;
		specClicks = 0;
		specWaitTicks = 0;
		specSkipLogged = false;
		lastDispatchState = null;
		actions = null;
		specSavedLogged = false;
		payDirtAtClick = 0;
		sackZeroTicks = 0;
		sackFruitlessTrips = 0;
		sackTripGotOres = false;
		setStatus("Ready");
	}

	@Override
	protected void onGameTick()
	{
		RoutineContext context = getContext();

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
		else if (liveSack < lastLiveSack)
		{
			// The sack was emptied (or lost pay-dirt) outside the script's
			// own sack trip. Re-anchor the estimate instead of assuming the
			// old contents are still there.
			sackBase = liveSack;
			depositedSinceEmpty = 0;
		}
		lastLiveSack = liveSack;
		sackZeroTicks = liveSack == 0 ? sackZeroTicks + 1 : 0;
		int biggerSack = context.getClient().getVarbitValue(VarbitID.MOTHERLODE_BIGGERSACK);
		// The script setting defaults to the large sack, so it works even if
		// the upgrade varbit is missing or late; set it to 108 for a normal
		// sack, or 0 to auto-detect.
		int requested = SACK_LARGE_SIZE;
		String sackSetting = context.getSetting("motherlodeSackCapacity");
		if (sackSetting != null && !sackSetting.trim().isEmpty())
		{
			try
			{
				requested = Integer.parseInt(sackSetting.trim());
			}
			catch (NumberFormatException ignored)
			{
				requested = SACK_LARGE_SIZE;
			}
		}
		int capacity;
		if (requested == SACK_SIZE || requested == SACK_LARGE_SIZE)
		{
			capacity = requested;
		}
		else
		{
			capacity = biggerSack == 1 ? SACK_LARGE_SIZE : SACK_SIZE;
			if (liveSack > SACK_SIZE)
			{
				// The upgrade varbit can arrive a tick late (or be missing
				// right after buying the upgrade); the live sack is
				// authoritative once it is above the normal capacity.
				capacity = SACK_LARGE_SIZE;
			}
		}
		if (capacity != sackCapacity)
		{
			log.info("[MotherlodeMiner] sack capacity {} (setting {}, bigger-sack varbit {}, live {})",
				capacity, requested, biggerSack, liveSack);
			sackCapacity = capacity;
		}
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

		if (actions == null || walker == null)
		{
			actions = new Actions(context, this, "[MotherlodeMiner]").allowWithBankOpen("bank chest");
			walker = actions.walker();
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

		dispatch(context);
	}

	/**
	 * Per-frame (client thread) fast path: run the current step the moment
	 * its trigger is met instead of on the next game tick - a verified
	 * hover has arrived (after a short 80-200 ms human pause), the bank just
	 * opened, the deposit just emptied the inventory, or ores just came out
	 * of the sack. Tick caps stay in the game-tick path as timeouts.
	 */
	@Override
	protected void onClientTick()
	{
		RoutineContext context = getContext();
		if (context == null || state == null || state == State.HALTED || walker == null || !context.isLoggedIn())
		{
			return;
		}
		try
		{
			GameInput input = context.getInput();
			if (input.isBusy())
			{
				actions.onClientTick();
				return;
			}
			boolean trigger;
			// Shared per-frame aim handling: drop targets while moving, glide
			// onto a shifted centre, human pause, then ready to click.
			boolean aimReady = actions.onClientTick();
			if (actions.isAiming())
			{
				trigger = aimReady;
			}
			else if (state == State.BANK)
			{
				boolean open = context.getTargets().findWidget(InterfaceID.Bankmain.UNIVERSE).isPresent();
				trigger = open && (bankStage == null
					|| bankStage == BankStage.WAIT_DEPOSIT && countInventoryItems(context.getInventory()) == 0);
			}
			else if (state == State.MINE && System.currentTimeMillis() < mineFastUntil)
			{
				trigger = true;
			}
			else
			{
				trigger = state == State.SACK && searchWait > 0
					&& countInventoryItems(context.getInventory()) > itemsBeforeSearch;
			}
			if (trigger)
			{
				cooldown = 0;
				dispatch(context);
			}
		}
		catch (RuntimeException ex)
		{
			log.error("[MotherlodeMiner] fast step failed in state={}", state, ex);
		}
	}

	private void dispatch(RoutineContext context)
	{
		if (state != lastDispatchState)
		{
			if (lastDispatchState == State.SACK)
			{
				actions.dropAim("sack search finished");
			}
			lastDispatchState = state;
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
			case DROP_HAMMER:
				dropHammer(context);
				break;
			case PICKUP_PAYDIRT:
				pickUpPayDirt(context);
				break;
			case HALTED:
			default:
				break;
		}
	}

	private void mine(RoutineContext context)
	{
		if (dragonSpec && mining && maybeUseSpecial(context))
		{
			return;
		}
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
				walkLogged(area, true, WALK_STOP_DISTANCE);
				setStatus("Walking to the veins");
			}
			else
			{
				setStatus("Waiting for a vein");
			}
			return;
		}

		WorldPoint location = context.getPlayerLocation();
		if (location != null && location.distanceTo2D(vein.getWorldLocation()) > VEIN_WALK_DISTANCE
			&& !clickableOnScreen(context, vein))
		{
			walkLogged(vein.getWorldLocation(), true, WALK_STOP_DISTANCE);
			setStatus("Walking to a vein");
			return;
		}

		GameInput input = context.getInput();
		if (input.isBusy())
		{
			setStatus("Moving mouse");
			return;
		}

		if (Actions.isMoving(context))
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
		if (actions.clickObject(vein, "vein", new String[]{"mine"}, "vein"))
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
			cooldown = 0;
			setStatus("Waiting for a vein");
		}
	}

	private void walkToHopper(RoutineContext context)
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
			if (location.distanceTo2D(hopperLocation) <= HOPPER_STOP_DISTANCE || clickableOnScreen(context, hopper))
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
			walkLogged(hopperLocation, true, HOPPER_STOP_DISTANCE);
			setStatus("Walking to the hopper");
			return;
		}

		walkLogged(area, true, HOPPER_STOP_DISTANCE);
		setStatus("Walking to the hopper");
	}

	private void hopper(RoutineContext context)
	{
		Inventory inventory = context.getInventory();

		// A broken water wheel makes the hopper refuse pay-dirt. Go down,
		// fetch a hammer, fix the wheels, then come back up for the dropped
		// pay-dirt instead of retrying an unresponsive hopper forever.
		if (upstairs && !repairFromUpstairs)
		{
			if (!bothWheelsStopped(context))
			{
				upperRepairAttempts = 0;
			}
			else if (upperRepairAttempts < 3)
			{
				beginUpperRepair(context, inventory);
				return;
			}
		}

		// The sack is full: the hopper cannot take any more pay-dirt. Go
		// downstairs and empty it, then come back up. This is the only
		// reason to leave the upper level.
		if (inventory.countItem(ItemID.PAYDIRT) == 0)
		{
			boolean tookAll = depositClickSent;
			if (tookAll)
			{
				log.info("[MotherlodeMiner] hopper took all {} pay-dirt", payDirtAtClick);
				// The whole load left the inventory, so that is exactly what
				// went into the sack. Do not use lastPayDirtCount here: the
				// count can tick down in steps during the deposit and would
				// leave the sack estimate short.
				depositedSinceEmpty += Math.max(0, payDirtAtClick);
				depositClickSent = false;
				payDirtAtClick = 0;
				// Pay-dirt actually went into the hopper, so the sack is no
				// longer empty.
				sackKnownEmpty = false;
			}
			payDirtDeposited = tookAll;
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
					state = State.MINE;
					mineFastUntil = System.currentTimeMillis() + 2000L;
					setStatus("Mining pay-dirt");
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

		// The hopper took only part of the load: the sack is full. Keep the
		// rest in the inventory and go empty the sack; never drop it here.
		// (A short settle window can also catch a deposit that is still being
		// processed, so dropping the "rest" could throw away the whole load.)
		if (depositClickSent && payDirtAtClick > 0 && lastPayDirtCount < payDirtAtClick)
		{
			int remaining = lastPayDirtCount;
			int taken = payDirtAtClick - remaining;
			depositedSinceEmpty += taken;
			sackKnownEmpty = false;
			payDirtDeposited = true;
			depositClickSent = false;
			payDirtAtClick = 0;
			actionAttempts = 0;
			walker.reset();
			log.info("[MotherlodeMiner] hopper took {} of {} pay-dirt; keeping the {} left and emptying the sack",
				taken, remaining + taken, remaining);
			if (upstairs)
			{
				state = State.CLIMB_DOWN;
				climbAttempts = 0;
				setStatus("Sack full: climbing down to empty it");
			}
			else
			{
				state = State.WALK_TO_SACK;
				setStatus("Sack full: walking to the sack");
			}
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
				// The hopper refused the load even after several attempts,
				// so the sack really is full. Go down and empty it.
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
			&& location.distanceTo2D(hopperLocation) > HOPPER_STOP_DISTANCE
			&& !clickableOnScreen(context, hopper))
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

		if (!input.isVisible(hopper))
		{
			input.turnCameraToward(hopper.getWorldLocation());
			setStatus("Turning camera to the hopper");
			return;
		}

		input.stopCamera();
		int carriedNow = inventory.countItem(ItemID.PAYDIRT);
		if (!actions.isAiming())
		{
			Point centre = input.objectCenter(hopper);
			log.info("[MotherlodeMiner] hopper id={} at {} centre={} pay-dirt={} attempt {}", hopper.getId(),
				hopperLocation, centre == null ? "-" : centre.x + "," + centre.y, carriedNow, actionAttempts + 1);
		}
		if (actions.clickObject(hopper, "hopper", new String[]{"deposit"}, "hopper"))
		{
			log.info("[MotherlodeMiner] hopper deposit click sent with {} pay-dirt", carriedNow);
			++actionAttempts;
			payDirtAtClick = carriedNow;
			depositClickSent = true;
			washXpAtDeposit = context.getClient().getSkillExperience(Skill.MINING);
			depositClickedAt = System.currentTimeMillis();
			cooldown = CLICK_COOLDOWN;
			setStatus("Depositing pay-dirt");
		}
		else
		{
			cooldown = 0;
		}
	}

	private void beginUpperRepair(RoutineContext context, Inventory inventory)
	{
		GameInput input = context.getInput();
		if (inventory.getEmptySlots() == 0)
		{
			if (upperDropPending)
			{
				if (++actionAttempts > 10)
				{
					upperDropPending = false;
					actionAttempts = 0;
				}
				setStatus("Waiting for pay-dirt to drop");
				return;
			}

			WorldPoint location = context.getPlayerLocation();
			if (inventory.countItem(ItemID.PAYDIRT) > 0 && dropOnePayDirt(context))
			{
				upperDropPending = true;
				droppedPayDirtAt = location;
				cooldown = CLICK_COOLDOWN;
			}
			setStatus("Dropping pay-dirt to make room for a hammer");
			return;
		}

		upperDropPending = false;
		actionAttempts = 0;
		++upperRepairAttempts;
		repairFromUpstairs = true;
		repairAfterDescent = true;
		collectingSack = false;
		state = State.CLIMB_DOWN;
		climbAttempts = 0;
		climbWaitTicks = 0;
		walker.reset();
		setStatus("Climbing down to fix the water wheels");
	}

	/**
	 * Dragon pickaxe special (Rock Knocker, +3 Mining): when energy is 100%
	 * and a vein is being mined, hover the special attack orb's centre,
	 * verify its left click is the special attack, click it for real, then
	 * wait for the energy to drop. Mining resumes by itself; if the
	 * animation stopped, the normal mine logic re-clicks the vein.
	 *
	 * @return true while the special owns this tick
	 */
	private boolean maybeUseSpecial(RoutineContext context)
	{
		Client client = context.getClient();
		int energy = client.getVarpValue(VarPlayerID.SA_ENERGY);
		if (specWaitTicks > 0)
		{
			if (energy < SPEC_FULL)
			{
				log.info("[MotherlodeMiner] dragon pickaxe special used (energy now {}%), back to the vein", energy / 10);
				specWaitTicks = 0;
				specClicks = 0;
				return false;
			}
			if (--specWaitTicks > 0)
			{
				return true;
			}
			log.info("[MotherlodeMiner] special click {} did not use energy, re-aiming", specClicks);
		}
		if (energy < SPEC_FULL)
		{
			specClicks = 0;
			return false;
		}
		if (!dragonPickaxeWielded(context) || specClicks >= SPEC_CLICK_LIMIT)
		{
			return false;
		}
		// Free sack space without this inventory: sackContents is the sack
		// varbit plus pay-dirt deposited and still washing, and only changes
		// at the hopper, so it is fixed for the whole run.
		int free = Math.max(0, sackCapacity - sackContents);
		if (free <= SPEC_SAVE_FREE_SPACE)
		{
			if (!specSavedLogged)
			{
				specSavedLogged = true;
				log.info("[MotherlodeMiner] sack has room for only {} pay-dirt (12 or fewer), saving dragon pickaxe"
					+ " special for next run", free);
			}
			return false;
		}
		specSavedLogged = false;

		GameInput input = context.getInput();
		if (input.isBusy())
		{
			return true;
		}
		Widget orb = context.getTargets().findWidget(InterfaceID.Orbs.SPECBUTTON).orElse(null);
		if (orb == null)
		{
			orb = context.getTargets().findWidget(InterfaceID.Orbs.ORB_SPECENERGY).orElse(null);
		}
		java.awt.Rectangle bounds = orb == null ? null : orb.getBounds();
		if (bounds == null || bounds.isEmpty())
		{
			++specClicks;
			log.info("[MotherlodeMiner] special attack orb not visible ({}/{})", specClicks, SPEC_CLICK_LIMIT);
			return false;
		}
		Point centre = new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
		if (!specHovered)
		{
			log.info("[MotherlodeMiner] spec at 100%, using dragon pickaxe special");
			specHovered = input.moveTo(centre);
			setStatus("Using the dragon pickaxe special");
			return true;
		}
		specHovered = false;
		MenuEntry entry = input.getLeftClickEntry();
		String option = entry == null ? "" : GameInput.plainText(entry.getOption()).toLowerCase(Locale.ROOT);
		String target = entry == null ? "" : GameInput.plainText(entry.getTarget()).toLowerCase(Locale.ROOT);
		Point mouse = input.getMouseLocation();
		boolean onOrb = mouse != null && bounds.contains(mouse);
		++specClicks;
		if (onOrb && (option + " " + target).contains("special") && input.click(mouse))
		{
			log.info("[MotherlodeMiner] special orb click {}: top='{} {}' at {},{}", specClicks, option, target,
				mouse.x, mouse.y);
			specWaitTicks = SPEC_WAIT_TICKS;
			return true;
		}
		log.info("[MotherlodeMiner] special orb hover {}/{}: top='{} {}' onOrb={}, re-aiming", specClicks,
			SPEC_CLICK_LIMIT, option, target, onOrb);
		return true;
	}

	private boolean dragonPickaxeWielded(RoutineContext context)
	{
		ItemContainer worn = context.getClient().getItemContainer(InventoryID.WORN);
		Item weapon = worn == null ? null : worn.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		if (weapon != null && DRAGON_PICKAXES.contains(weapon.getId()))
		{
			return true;
		}
		if (!specSkipLogged)
		{
			specSkipLogged = true;
			boolean inInventory = DRAGON_PICKAXES.stream().anyMatch(id -> context.getInventory().countItem(id) > 0);
			log.info(inInventory
				? "[MotherlodeMiner] dragon pickaxe is in the inventory, not wielded: the special only works when"
					+ " wielded, skipping it"
				: "[MotherlodeMiner] no dragon pickaxe wielded, skipping the special");
		}
		return false;
	}

	/** Walker click with a gap log, so Cody can see the time between actions. */
	private boolean walkLogged(WorldPoint target, boolean minimap, int stopDistance)
	{
		return actions.walkTo(target, minimap, stopDistance);
	}

	private void markAction(String action)
	{
		actions.markAction(action);
	}

	private void walkToSack(RoutineContext context)
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
		boolean bankOpen = context.getTargets().findWidget(InterfaceID.Bankmain.UNIVERSE).isPresent();
		// Standing still with the sack clickable on screen: click it
		// directly (the click walks there). While a minimap walk is running
		// the mouse stays idle until the player arrives.
		boolean nearAndVisible = sack != null && !bankOpen && !Actions.isMoving(context)
			&& location.distanceTo2D(sack.getWorldLocation()) <= SACK_PREHOVER_DISTANCE
			&& clickableOnScreen(context, sack);
		if (nearAndVisible || (sack != null && location.distanceTo2D(sack.getWorldLocation()) <= WALK_STOP_DISTANCE)
			|| location.distanceTo(SACK_AREA) <= WALK_STOP_DISTANCE)
		{
			state = State.SACK;
			searchWait = 0;
			searchClicks = 0;
			searchMisses = 0;
			sackCameraTurns = 0;
			itemsBeforeSearch = 0;
			actions.forgetKey();
			setStatus("Searching the sack");
			sack(context);
			return;
		}

		walkLogged(SACK_AREA, true, WALK_STOP_DISTANCE);
		setStatus("Walking to the sack");
	}

	private void sack(RoutineContext context)
	{
		Inventory inventory = context.getInventory();

		if (sackKnownEmpty && searchClicks == 0)
		{
			finishSackTrip(context, inventory);
			return;
		}

		if (searchWait > 0)
		{
			// Act on the tick the ores arrive; the wait is only a timeout.
			if (countInventoryItems(inventory) > itemsBeforeSearch)
			{
				searchWait = 1;
			}
			else if (searchWait == 1 && Actions.isMoving(context))
			{
				// Clicked the sack while running: still on the way to it.
				searchWait = 2;
			}
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
					cooldown = 0;
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
			cooldown = 0;
			setStatus("Searching the sack");
		}
	}

	private boolean clickSack(RoutineContext context, TileObject sack)
	{
		return actions.clickObject(sack, "sack", new String[]{"search"}, "sack");
	}

	private void finishSackTrip(RoutineContext context, Inventory inventory)
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
		sackBase = sackKnownEmpty ? 0 : lastLiveSack;
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
			markAction("sack trip done, walking to the bank");
			walkToBank(context);
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

	private void walkToBank(RoutineContext context)
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
				cooldown = 0;
			}
			setStatus("Dropping pay-dirt to make room for the hammer");
			return;
		}

		TileObject chest = findBankChest(context);
		if (chest != null && location.distanceTo2D(chest.getWorldLocation()) <= OBJECT_REACH
			|| chest != null && clickableOnScreen(context, chest))
		{
			state = State.BANK;
			bankStage = null;
			actionAttempts = 0;
			bankClicks = 0;
			chestHovered = false;
			setStatus("Banking ores");
			bank(context);
			return;
		}

		walkLogged(BANK_AREA, true, WALK_STOP_DISTANCE);
		setStatus("Walking to the bank");
	}

	private void bank(RoutineContext context)
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
				cooldown = 0;
			}
			setStatus("Dropping pay-dirt to make room for the hammer");
			return;
		}

		if (bankStage == BankStage.LEAVE)
		{
			// No Escape: the next minimap click closes the bank by itself.
			markAction("bank done, leaving without Escape");
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
				walkToSack(context);
				return;
			}
			log.info("[MotherlodeMiner] banked, sack empty -> back to mining");

			// Sack empty: leave the open bank straight for the ladder / veins
			// (minimap walk; the bank closes itself). Previously the ladder
			// counted as on screen behind the bank, and the verified click
			// refuses while the bank is open, so it sat in the bank.
			log.info("[MotherlodeMiner] last deposit done, sack empty -> walking to the {} with the bank open",
				upstairs ? "ladder" : "veins");
			if (upstairs)
			{
				upstairsLoads = 0;
				climbAttempts = 0;
				state = State.CLIMB_UP;
				setStatus("Walking back to the ladder");
				climbUp(context);
			}
			else
			{
				state = State.WALK_TO_VEIN;
				setStatus("Walking back to the veins");
				walkToVein(context);
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

			TileObject chest = findBankChest(context);
			if (chest == null)
			{
				walkLogged(BANK_AREA, true, WALK_STOP_DISTANCE);
				setStatus("Looking for the bank chest");
				return;
			}

			WorldPoint location = context.getPlayerLocation();
			if (location != null && location.distanceTo2D(chest.getWorldLocation()) > OBJECT_REACH
				&& !clickableOnScreen(context, chest))
			{
				walkLogged(chest.getWorldLocation(), true, WALK_STOP_DISTANCE);
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

			// Real click only: curved move to the hull centre, wait for the
			// mouse to arrive, verify 'Use'/'Bank' on 'Bank chest', click.
			Point before = input.getMouseLocation();
			Point centre = input.objectCenter(chest);
			boolean clicked = actions.clickObject(chest, "bank chest", new String[]{"use", "bank"},
				"bank chest");
			log.info("[MotherlodeMiner] bank-open attempt: cursor {} vs hull centre {} -> {}",
				before == null ? "-" : before.x + "," + before.y,
				centre == null ? "-" : centre.x + "," + centre.y, clicked ? "clicked" : "aiming");
			if (clicked)
			{
				cooldown = CLICK_COOLDOWN;
				setStatus("Opening bank");
			}
			return;
		}

		if (bankStage == null)
		{
			markAction("bank open");
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
					cooldown = 0;
					setStatus("Waiting for bank");
					return;
				}

				if (input.click(deposit))
				{
					markAction("click deposit inventory");
					bankStage = BankStage.WAIT_DEPOSIT;
					actionAttempts = 0;
					cooldown = CLICK_COOLDOWN;
				}
				else
				{
					cooldown = 0;
				}
				setStatus("Depositing ores");
				return;
			}
			case WAIT_DEPOSIT:
			{
				if (countInventoryItems(inventory) == 0)
				{
					markAction("deposit done");
					bankStage = BankStage.LEAVE;
					bank(context);
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
					cooldown = 0;
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
						cooldown = 0;
					}
					setStatus("Dropping pay-dirt to make room for the hammer");
					return;
				}

				// No pay-dirt left: the inventory is full of bankable ores.
				Widget deposit = context.getTargets().findWidget(InterfaceID.Bankmain.DEPOSITINV).orElse(null);
				if (deposit == null)
				{
					cooldown = 0;
					setStatus("Waiting for bank");
					return;
				}

				if (input.click(deposit))
				{
					cooldown = CLICK_COOLDOWN;
				}
				else
				{
					cooldown = 0;
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
					cooldown = 0;
					setStatus("Making room for the hammer");
					return;
				}

				// Real click only: hover the visible hammer slot, verify the
				// left click is "Withdraw-1 Hammer", then click it.
				Widget hammer = input.findBankItemWidget(ItemID.HAMMER);
				if (hammer == null || hammer.isHidden())
				{
					if (++actionAttempts > ACTION_ATTEMPTS)
					{
						log.info("[MotherlodeMiner] no visible hammer in the bank, skipping the repair");
						hammerUnavailable = true;
						bankStage = BankStage.LEAVE;
					}
					setStatus("Looking for a hammer in the bank");
					return;
				}
				if (!hammerHovered)
				{
					if (input.hover(hammer))
					{
						hammerHovered = true;
					}
					setStatus("Moving mouse to the hammer");
					return;
				}
				hammerHovered = false;
				MenuEntry top = input.getLeftClickEntry();
				String topOption = top == null ? "" : GameInput.plainText(top.getOption()).toLowerCase(Locale.ROOT);
				String topTarget = top == null ? "" : GameInput.plainText(top.getTarget()).toLowerCase(Locale.ROOT);
				Point mouse = input.getMouseLocation();
				if (topOption.startsWith("withdraw") && topTarget.contains("hammer") && mouse != null && input.click(mouse))
				{
					log.info("[MotherlodeMiner] hammer click: top='{} {}' at {},{}", topOption, topTarget, mouse.x, mouse.y);
					cooldown = CLICK_COOLDOWN;
				}
				else
				{
					log.info("[MotherlodeMiner] hammer hover: top='{} {}', re-aiming", topOption, topTarget);
				}
				if (++actionAttempts > ACTION_ATTEMPTS * 2)
				{
					log.info("[MotherlodeMiner] hammer never withdrew after {} tries, skipping the repair", actionAttempts);
					hammerUnavailable = true;
					bankStage = BankStage.LEAVE;
				}
				setStatus("Withdrawing a hammer");
				return;
			}
			case LEAVE:
			default:
				return;
		}
	}

	private void fixWheel(RoutineContext context)
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
					cooldown = 0;
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

		if (location.distanceTo2D(strut.getWorldLocation()) > STRUT_REACH && !clickableOnScreen(context, strut))
		{
			walkLogged(strut.getWorldLocation(), true, WALK_STOP_DISTANCE);
			setStatus("Walking to the broken water wheel");
			return;
		}

		GameInput input = context.getInput();
		if (input.isBusy())
		{
			setStatus("Moving mouse");
			return;
		}

		if (Actions.isMoving(context))
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
		if (actions.clickObject(strut, "strut", new String[]{"hammer", "fix", "use"}, "strut"))
		{
			cooldown = CLICK_COOLDOWN;
			setStatus("Fixing the water wheel");
		}
		else
		{
			cooldown = 0;
		}
	}

	private void finishWheelTrip(Inventory inventory)
	{
		walker.reset();
		actionAttempts = 0;
		wheelAttempts = 0;
		bankPurpose = BankPurpose.DEPOSIT;

		if (repairFromUpstairs)
		{
			repairAfterDescent = false;
			state = State.DROP_HAMMER;
			setStatus("Dropping the hammer");
			return;
		}

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

	private static boolean dropOnePayDirt(RoutineContext context)
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

	private void dropHammer(RoutineContext context)
	{
		Inventory inventory = context.getInventory();
		if (inventory.countItem(ItemID.HAMMER) == 0 || ++actionAttempts > 20)
		{
			actionAttempts = 0;
			repairFromUpstairs = false;
			repairReturn = true;
			state = State.CLIMB_UP;
			climbAttempts = 0;
			climbWaitTicks = 0;
			walker.reset();
			setStatus("Climbing up to pick up the dropped pay-dirt");
			return;
		}

		GameInput input = context.getInput();
		if (input.isBusy())
		{
			setStatus("Moving mouse");
			return;
		}
		if (!inventory.isOpen())
		{
			inventory.open();
			return;
		}

		int slot = inventory.findSlot(ItemID.HAMMER);
		Widget hammer = slot < 0 ? null : inventory.getSlotWidget(slot);
		if (hammer == null || hammer.isHidden())
		{
			return;
		}

		// The hammer's left-click option is Drop, so a plain click frees the
		// slot without a menu.
		if (input.click(hammer))
		{
			cooldown = CLICK_COOLDOWN;
		}
		setStatus("Dropping the hammer");
	}

	private void pickUpPayDirt(RoutineContext context)
	{
		Inventory inventory = context.getInventory();
		if (droppedPayDirtAt == null
			|| inventory.countItem(ItemID.PAYDIRT) > pickupStartCount
			|| ++pickupAttempts > 30)
		{
			droppedPayDirtAt = null;
			pickupAttempts = 0;
			state = State.HOPPER;
			setStatus("Depositing pay-dirt");
			return;
		}

		GameInput input = context.getInput();
		if (input.isBusy())
		{
			setStatus("Moving mouse");
			return;
		}

		WorldPoint location = context.getPlayerLocation();
		if (location == null)
		{
			return;
		}

		if (location.distanceTo2D(droppedPayDirtAt) > 1)
		{
			walkLogged(droppedPayDirtAt, true, 1);
			setStatus("Walking to the dropped pay-dirt");
			return;
		}

		LocalPoint local = LocalPoint.fromWorld(context.getClient(), droppedPayDirtAt);
		if (local == null)
		{
			droppedPayDirtAt = null;
			state = State.HOPPER;
			setStatus("Depositing pay-dirt");
			return;
		}

		if (!input.takeGroundItem(local.getSceneX(), local.getSceneY(), ItemID.PAYDIRT, "Pay-dirt"))
		{
			if (!input.isCameraBusy())
			{
				input.turnCameraToward(droppedPayDirtAt);
			}
			setStatus("Looking for the dropped pay-dirt");
			return;
		}

		cooldown = 1;
		setStatus("Picking up the dropped pay-dirt");
	}

	private static boolean bothWheelsStopped(RoutineContext context)
	{
		return hasBrokenStrutNear(context, STRUT_WEST) && hasBrokenStrutNear(context, STRUT_EAST);
	}

	private static boolean hasBrokenStrutNear(RoutineContext context, WorldPoint area)
	{
		for (TileObject object : context.getTargets().getTileObjectsNear(area, STRUT_RADIUS))
		{
			if (object.getId() == STRUT_BROKEN_ID)
			{
				return true;
			}
		}
		return false;
	}

	private TileObject findBrokenStrut(RoutineContext context)
	{		Player player = context.getPlayer();
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

	private void walkToVein(RoutineContext context)
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

		walkLogged(area, true, WALK_STOP_DISTANCE);
		setStatus("Walking back to the veins");
	}

	private void arriveUpstairs(RoutineContext context)
	{
		walker.reset();
		climbAttempts = 0;
		climbWaitTicks = 0;

		if (repairReturn)
		{
			repairReturn = false;
			state = State.HOPPER;
			setStatus("Depositing pay-dirt");
			return;
		}

		state = State.MINE;
		setStatus("Mining pay-dirt");
	}

	private void climbUp(RoutineContext context)
	{
		WorldPoint location = context.getPlayerLocation();
		if (location == null)
		{
			return;
		}

		if (isUpstairs(context))
		{
			arriveUpstairs(context);
			return;
		}

		if (climbWaitTicks > 0)
		{
			--climbWaitTicks;
			if (isUpstairs(context))
			{
				arriveUpstairs(context);
				return;
			}

			if (Actions.isMoving(context))
			{
				climbWaitTicks = Math.max(climbWaitTicks, 2);
			}
			setStatus("Climbing up");
			return;
		}

		TileObject ladder = findLadder(context, LADDER_UP_AREA, true);
		if (ladder == null)
		{
			walkLogged(onPlayerPlane(context, LADDER_UP_AREA), true, WALK_STOP_DISTANCE);
			setStatus("Walking to the ladder");
			return;
		}

		WorldPoint ladderLocation = ladder.getWorldLocation();
		if (location.distanceTo2D(ladderLocation) > WALK_STOP_DISTANCE && !clickableOnScreen(context, ladder))
		{
			walkLogged(onPlayerPlane(context, ladderLocation), true, WALK_STOP_DISTANCE);
			setStatus("Walking to the ladder");
			return;
		}

		GameInput input = context.getInput();
		if (input.isBusy() || Actions.isMoving(context))
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
			cooldown = 0;
		}
	}

	private void climbDown(RoutineContext context)
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

			if (Actions.isMoving(context))
			{
				climbWaitTicks = Math.max(climbWaitTicks, 2);
			}
			setStatus("Climbing down");
			return;
		}

		TileObject ladder = findLadder(context, LADDER_DOWN_AREA, false);
		if (ladder == null)
		{
			walkLogged(onPlayerPlane(context, LADDER_DOWN_AREA), true, WALK_STOP_DISTANCE);
			setStatus("Walking to the ladder");
			return;
		}

		WorldPoint ladderLocation = ladder.getWorldLocation();
		if (location.distanceTo2D(ladderLocation) > WALK_STOP_DISTANCE && !clickableOnScreen(context, ladder))
		{
			walkLogged(onPlayerPlane(context, ladderLocation), true, WALK_STOP_DISTANCE);
			setStatus("Walking to the ladder");
			return;
		}

		GameInput input = context.getInput();
		if (input.isBusy() || Actions.isMoving(context))
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
			cooldown = 0;
		}
	}

	/**
	 * Clicks a ladder, falling back to its exact menu option when the model
	 * is not on screen. A ladder is often stood on (or behind the player's
	 * own tile), in which case there is no clickbox to click and turning the
	 * camera can never help - the direct option still works.
	 */
	private boolean clickLadder(RoutineContext context, TileObject ladder, boolean up)
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

		if (climbAttempts >= CLIMB_ATTEMPT_LIMIT)
		{
			log.info("[MotherlodeMiner] ladder: {} clicks without changing floor, halting", climbAttempts);
			state = State.HALTED;
			setStatus("Stopped: could not climb the ladder");
			return false;
		}

		// One movement click on the ladder itself. The verified hover path
		// used to make a near miss and then glide/correct onto the centre,
		// which looked like two separate mouse movements on every climb.
		boolean sent;
		if (climbAttempts < 2 && input.isVisible(ladder) && input.click(ladder))
		{
			sent = true;
		}
		else
		{
			sent = this.actions.clickObject(ladder, up ? "ladder up" : "ladder down",
				new String[]{"climb"}, "ladder");
		}
		if (sent)
		{
			log.info("[MotherlodeMiner] ladder {} attempt {}: waiting {} ticks for the floor change", up ? "up" : "down",
				climbAttempts + 1, CLIMB_WAIT_TICKS);
		}
		return sent;
	}

	private static TileObject findLadder(RoutineContext context, WorldPoint area, boolean up)
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

	private static boolean hasAction(RoutineContext context, TileObject object, String... actions)
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
	private static boolean isUpstairs(RoutineContext context)
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
			|| state == State.HOPPER
			|| state == State.PICKUP_PAYDIRT;
	}

	private void beginAfterDescent(RoutineContext context)
	{
		walker.reset();
		actionAttempts = 0;
		crateAttempts = 0;
		wheelAttempts = 0;
		hammerUnavailable = false;
		repairAfterDescent = true;
		collectingSack = !repairFromUpstairs;

		if (findBrokenStrut(context) == null)
		{
			repairAfterDescent = false;
			if (repairFromUpstairs)
			{
				repairReturn = true;
				repairFromUpstairs = false;
				state = State.CLIMB_UP;
				climbAttempts = 0;
				climbWaitTicks = 0;
				setStatus("Climbing back up");
			}
			else
			{
				state = State.WALK_TO_SACK;
				setStatus("Walking to the sack");
			}
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

	private void searchCrate(RoutineContext context)
	{
		Inventory inventory = context.getInventory();
		if (inventory.countItem(ItemID.HAMMER) > 0)
		{
			// The hammer is in: drop the crate hover at once. Otherwise the
			// per-frame hook keeps following the crate centre (a re-aim
			// started while the search was resolving) all the way to the wheel.
			actions.dropAim("hammer received, heading to the strut");
			actions.forgetKey();
			walker.reset();
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
			if (repairFromUpstairs)
			{
				repairFromUpstairs = false;
				repairReturn = true;
				state = State.CLIMB_UP;
				climbAttempts = 0;
				climbWaitTicks = 0;
				walker.reset();
				setStatus("Climbing back up");
				return;
			}
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
			crate = context.getTargets().findTileObject(CRATE_ID, OBJECT_REACH + 5).orElse(null);
		}
		if (crate == null)
		{
			walkLogged(onPlayerPlane(context, CRATE_AREA), true, WALK_STOP_DISTANCE);
			setStatus("Walking to the crate");
			return;
		}

		// The crate is right by the ladder: click it directly (which walks
		// there by itself); only walk when it is genuinely off screen.
		if (location.distanceTo2D(crate.getWorldLocation()) > WALK_STOP_DISTANCE && !clickableOnScreen(context, crate))
		{
			walkLogged(crate.getWorldLocation(), true, WALK_STOP_DISTANCE);
			setStatus("Walking to the crate");
			return;
		}

		GameInput input = context.getInput();
		if (input.isBusy())
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
		if (actions.clickObject(crate, "crate", new String[]{"search"}, "crate"))
		{
			++crateAttempts;
			cooldown = 2;
			setStatus("Searching the crate for a hammer");
		}
		else
		{
			cooldown = 0;
		}
	}

	private boolean clickableOnScreen(RoutineContext context, TileObject object)
	{
		return actions.clickableOnScreen(object);
	}

	private static WorldPoint onPlayerPlane(RoutineContext context, WorldPoint point)
	{
		WorldPoint location = context.getPlayerLocation();
		return location == null
			? point
			: new WorldPoint(point.getX(), point.getY(), location.getPlane());
	}

	private static TileObject findObjectAt(RoutineContext context, int objectId,
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

	private TileObject findUpstairsVein(RoutineContext context)
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
	private boolean dismissLevelUp(RoutineContext context)
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


	private static boolean veinStillExists(RoutineContext context, TileObject vein)
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

	private TileObject findVein(RoutineContext context)
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

	/** Bank chest from the sack: far enough to see it, then the on-screen click walks there. */
	private TileObject findBankChest(RoutineContext context)
	{
		return context.getTargets().findTileObject(BANK_CHEST_ID, BANK_VIEW_DISTANCE).orElse(null);
	}

	private TileObject findSack(RoutineContext context)
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
		RoutineContext context = getContext();
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

	// Actions.Host

	@Override
	public void status(String status)
	{
		setStatus(status);
	}

	@Override
	public void halt(String status)
	{
		state = State.HALTED;
		setStatus(status);
	}

	@Override
	public void cooldown(int ticks)
	{
		cooldown = ticks;
	}

	@Override
	public String stepName()
	{
		return String.valueOf(state);
	}
}
