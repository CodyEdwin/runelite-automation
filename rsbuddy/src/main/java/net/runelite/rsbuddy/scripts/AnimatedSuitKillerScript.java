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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.widgets.Widget;
import net.runelite.rsbuddy.input.GameInput;
import net.runelite.rsbuddy.inventory.Inventory;
import net.runelite.rsbuddy.script.Script;
import net.runelite.rsbuddy.script.ScriptContext;

/**
 * Warriors' Guild animated armour killer. With the chosen metal's full helm,
 * platebody and platelegs in the inventory it clicks Animate on the Magical
 * Animator, kills the animated armour, picks the three pieces and the
 * Warrior guild tokens back up, and repeats.
 * <p>
 * Start it next to a Magical Animator. Exactly one armour type must be ticked
 * in the plugin config.
 */
public final class AnimatedSuitKillerScript extends Script
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AnimatedSuitKillerScript.class);

	/** The animator platform that has the Animate option. */
	private static final int ANIMATOR_ID = ObjectID.WARGUILD_ANIMATOR_CENT; // 23955
	private static final String ANIMATOR_NAME = "Magical Animator";
	private static final String ANIMATE_ACTION = "Animate";
	private static final String ATTACK_ACTION = "Attack";
	private static final int TOKEN_ID = ItemID.WARGUILD_TOKENS; // 8851, stackable
	/**
	 * 8851 is the real token item. 8852-8855 are its stack-size appearance
	 * variants; they are accepted too in case a ground stack reports one.
	 */
	private static final Set<Integer> TOKEN_IDS = Set.of(ItemID.WARGUILD_TOKENS, ItemID.WARGUILD_TOKENS_2,
		ItemID.WARGUILD_TOKENS_3, ItemID.WARGUILD_TOKENS_4, ItemID.WARGUILD_TOKENS_5);
	/** Re-loot rounds of skipped piles before stopping with missing pieces. */
	private static final int RELOOT_ROUND_LIMIT = 3;
	/** Ticks to keep waiting for tokens after the armour pieces are back. */
	private static final int TOKEN_WAIT_TICKS = 4;

	private static final int SEARCH_DISTANCE = 10;
	/** Loot this far from the death tile (or the player when it is unknown). */
	private static final int LOOT_RADIUS = 3;
	/** Missing pieces this close are picked up before stopping. */
	private static final int MISSING_PIECE_RADIUS = 8;

	/** Ticks to wait for the armour to appear after clicking Animate. */
	private static final int SPAWN_TIMEOUT_TICKS = 20;
	/** Animate clicks in a row without a spawn before stopping. */
	private static final int ANIMATE_ATTEMPT_LIMIT = 3;
	/** Ticks after an attack click before re-evaluating. */
	private static final int ATTACK_COOLDOWN_TICKS = 3;
	/** Ticks out of combat with the armour still alive before re-attacking. */
	private static final int REATTACK_IDLE_TICKS = 3;
	/** Hard cap on one fight (about two minutes). */
	private static final int FIGHT_MAX_TICKS = 200;
	/** Ticks to wait for the drop to show up after the kill. */
	private static final int DROP_WAIT_TICKS = 4;
	/** Take attempts per item before skipping it. */
	private static final int LOOT_ATTEMPT_LIMIT = 3;
	/** Ticks without any pickup before giving up on the pile. */
	private static final int LOOT_STATE_MAX_TICKS = 25;
	/** Ticks one loot phase may wait on a busy mouse. */
	private static final int LOOT_PHASE_MAX_TICKS = 6;

	private static final int BUSY_STUCK_TICKS = 15;
	private static final int CAMERA_STUCK_TICKS = 10;
	private static final int MENU_STUCK_TICKS = 4;
	private static final int STALL_LOG_TICKS = 5;

	/** Eat below this share of max hitpoints. */
	private static final double EAT_BELOW_HP = 0.5;
	/** Ticks to wait for an eat to register (HP up or food gone) before eating again. */
	private static final int EAT_WAIT_TICKS = 4;
	/** Ticks to wait for a drink to register before trying again. */
	private static final int DRINK_WAIT_TICKS = 5;
	/** Default: drink when the Strength boost is below 5 (at 4 or less). */
	private static final int DEFAULT_DRINK_BELOW_BOOST = 5;

	/** Regular strength potions, lowest dose first. */
	private static final int[] REGULAR_STRENGTH = {
		ItemID._1DOSE1STRENGTH,  // Strength potion(1) 119
		ItemID._2DOSE1STRENGTH,  // Strength potion(2) 117
		ItemID._3DOSE1STRENGTH,  // Strength potion(3) 115
		ItemID.STRENGTH4         // Strength potion(4) 113
	};

	/** Super strength potions, lowest dose first. */
	private static final int[] SUPER_STRENGTH = {
		ItemID._1DOSE2STRENGTH,  // Super strength(1) 161
		ItemID._2DOSE2STRENGTH,  // Super strength(2) 159
		ItemID._3DOSE2STRENGTH,  // Super strength(3) 157
		ItemID._4DOSE2STRENGTH   // Super strength(4) 2440
	};

	/** Matches the plugin's AnimatedSuitPotionMode by name (survives hot reload). */
	private enum PotionMode
	{
		OFF,
		STRENGTH,
		SUPER_STRENGTH,
		EITHER
	}


	/** Armour types, with the gameval ids of the animated NPC and its pieces. */
	enum ArmourType
	{
		BLACK("Black", NpcID.WARGUILD_BLACK_ARMOUR,
			ItemID.BLACK_FULL_HELM, ItemID.BLACK_PLATEBODY, ItemID.BLACK_PLATELEGS),
		MITHRIL("Mithril", NpcID.WARGUILD_MITHRIL_ARMOUR,
			ItemID.MITHRIL_FULL_HELM, ItemID.MITHRIL_PLATEBODY, ItemID.MITHRIL_PLATELEGS),
		ADAMANT("Adamant", NpcID.WARGUILD_ADAMANT_ARMOUR,
			ItemID.ADAMANT_FULL_HELM, ItemID.ADAMANT_PLATEBODY, ItemID.ADAMANT_PLATELEGS),
		RUNE("Rune", NpcID.WARGUILD_RUNE_ARMOUR,
			ItemID.RUNE_FULL_HELM, ItemID.RUNE_PLATEBODY, ItemID.RUNE_PLATELEGS);

		private final String label;
		private final int npcId;
		private final int[] pieces;

		ArmourType(String label, int npcId, int helm, int body, int legs)
		{
			this.label = label;
			this.npcId = npcId;
			this.pieces = new int[]{helm, body, legs};
		}
	}

	private enum State
	{
		CHECK_ARMOUR,
		ANIMATE,
		WAIT_FOR_SPAWN,
		FIGHT,
		LOOT,
		HALTED
	}


	private final ArmourType armour;
	private final String configError;
	private final Set<Integer> lootIds;

	private State state;
	private int stateTicks;
	private int cooldown;
	private int busyTicks;
	private int cameraBusyTicks;
	private int menuOpenTicks;
	private int exceptionCount;

	private int animateAttempts;
	private int spawnTicks;
	private WorldPoint animatorLocation;
	private NPC target;
	private int idleTicks;
	private WorldPoint deathTile;
	private int dropWaitTicks;

	private int tokensAtKill;
	private int relootRounds;
	private int tokenWaitTicks;
	private boolean lootListed;
	private int lootPhaseTicks;
	private int lootTargetItemId = -1;
	private WorldPoint lootTargetTile;
	private int lootAttempts;
	private int lootStateTicks;
	private int lootInventoryCount = -1;
	private final Set<String> skippedLoot = new HashSet<>();

	private int kills;
	private int tokensAtStart = -1;
	private int tokens;
	private int eatWaitTicks;
	private int eatHpBefore;
	private int eatItemId = -1;
	private int eatItemCountBefore;
	private int foodEaten;
	private int noFoodWarnTicks;
	private int drinkWaitTicks;
	private int boostBeforeDrink;
	private int potionsDrunk;

	private final PotionMode potionMode;
	/** Drink when the Strength boost is strictly below this. */
	private final int drinkBelowBoost;
	/** Potions in drinking order for the chosen mode (empty when OFF). */
	private final int[] strengthPotions;
	private long startedAt;

	public AnimatedSuitKillerScript()
	{
		this(true, false, false, false, PotionMode.EITHER.name(), DEFAULT_DRINK_BELOW_BOOST);
	}

	public AnimatedSuitKillerScript(boolean black, boolean mithril, boolean adamant, boolean rune)
	{
		this(black, mithril, adamant, rune, PotionMode.EITHER.name(), DEFAULT_DRINK_BELOW_BOOST);
	}

	/**
	 * One flag per armour type, exactly as ticked in the config. Anything
	 * other than exactly one ticked refuses to run.
	 */
	/**
	 * @param potionMode OFF, STRENGTH, SUPER_STRENGTH or EITHER (super first);
	 * anything else means EITHER
	 * @param drinkBelowBoost drink when the Strength boost is below this (5 = at 4 or less)
	 */
	public AnimatedSuitKillerScript(boolean black, boolean mithril, boolean adamant, boolean rune,
		String potionMode, int drinkBelowBoost)
	{
		super("Animated Suit Killer", "Animates armour on the Warriors' Guild Magical Animator, kills it, loots the"
			+ " pieces and tokens, and repeats.");
		this.potionMode = parseMode(potionMode);
		this.drinkBelowBoost = Math.max(1, drinkBelowBoost);
		this.strengthPotions = potionOrder(this.potionMode);
		List<ArmourType> ticked = new ArrayList<>();
		if (black)
		{
			ticked.add(ArmourType.BLACK);
		}
		if (mithril)
		{
			ticked.add(ArmourType.MITHRIL);
		}
		if (adamant)
		{
			ticked.add(ArmourType.ADAMANT);
		}
		if (rune)
		{
			ticked.add(ArmourType.RUNE);
		}

		if (ticked.size() == 1)
		{
			armour = ticked.get(0);
			configError = null;
			Set<Integer> ids = new HashSet<>();
			for (int piece : armour.pieces)
			{
				ids.add(piece);
			}
			ids.addAll(TOKEN_IDS);
			lootIds = Collections.unmodifiableSet(ids);
		}
		else
		{
			armour = null;
			configError = "Tick exactly one armour type (Black, Mithril, Adamant or Rune) in Test Plugin config"
				+ " (" + ticked.size() + " ticked)";
			lootIds = Collections.emptySet();
		}
	}

	private static PotionMode parseMode(String name)
	{
		try
		{
			return name == null ? PotionMode.EITHER : PotionMode.valueOf(name);
		}
		catch (IllegalArgumentException ex)
		{
			return PotionMode.EITHER;
		}
	}

	private static int[] potionOrder(PotionMode mode)
	{
		switch (mode)
		{
			case OFF:
				return new int[0];
			case STRENGTH:
				return REGULAR_STRENGTH;
			case SUPER_STRENGTH:
				return SUPER_STRENGTH;
			default:
				int[] both = new int[SUPER_STRENGTH.length + REGULAR_STRENGTH.length];
				System.arraycopy(SUPER_STRENGTH, 0, both, 0, SUPER_STRENGTH.length);
				System.arraycopy(REGULAR_STRENGTH, 0, both, SUPER_STRENGTH.length, REGULAR_STRENGTH.length);
				return both;
		}
	}

	@Override
	public Set<Integer> getGroundItemPickupFilter()
	{
		return lootIds;
	}

	@Override
	public boolean useFastMouse()
	{
		return true;
	}

	@Override
	protected void onStart()
	{
		// Runs on the Swing thread: no client reads here.
		state = null;
		setState(configError == null ? State.CHECK_ARMOUR : State.HALTED,
			configError == null ? "started, armour=" + armour.label : configError);
		cooldown = 0;
		busyTicks = 0;
		cameraBusyTicks = 0;
		menuOpenTicks = 0;
		exceptionCount = 0;
		animateAttempts = 0;
		spawnTicks = 0;
		animatorLocation = null;
		target = null;
		idleTicks = 0;
		deathTile = null;
		dropWaitTicks = 0;
		resetLoot();
		skippedLoot.clear();
		kills = 0;
		tokensAtStart = -1;
		tokens = 0;
		eatWaitTicks = 0;
		eatItemId = -1;
		foodEaten = 0;
		noFoodWarnTicks = 0;
		drinkWaitTicks = 0;
		boostBeforeDrink = 0;
		potionsDrunk = 0;
		startedAt = System.currentTimeMillis();
		setStatus(configError == null ? "Ready" : configError);
	}

	@Override
	protected void onStop()
	{
		getContext().getInput().stopPileTracking();
	}

	@Override
	protected void onGameTick()
	{
		try
		{
			tickSafely();
			exceptionCount = 0;
		}
		catch (RuntimeException | AssertionError ex)
		{
			++exceptionCount;
			log.error("[AnimatedSuit] exception #{} in state={} status={}", exceptionCount, state, getStatus(), ex);
			resetLoot();
			if (exceptionCount >= 20)
			{
				throw ex;
			}
		}
	}

	private void tickSafely()
	{
		ScriptContext context = getContext();
		if (state == State.HALTED)
		{
			return;
		}
		if (!context.isLoggedIn())
		{
			setStatus("Waiting for login");
			return;
		}

		++stateTicks;
		if (stateTicks == STALL_LOG_TICKS + 1 || stateTicks > STALL_LOG_TICKS && stateTicks % 20 == 0)
		{
			log.info("[AnimatedSuit] stall? {} ticks in state={} status={} busy={} camera={} menu={}",
				stateTicks, state, getStatus(), context.getInput().isBusy(), context.getInput().isCameraBusy(),
				context.getClient().isMenuOpen());
		}

		recoverStuckInput(context);
		updateTokens(context);
		// Priority: eat > drink > everything else. A tick that eats or
		// drinks (or is waiting to) sends no other click.
		if (eatIfNeeded(context) || drinkIfNeeded(context))
		{
			return;
		}

		if (cooldown > 0)
		{
			--cooldown;
			return;
		}

		switch (state)
		{
			case CHECK_ARMOUR:
				checkArmour(context);
				break;
			case ANIMATE:
				animate(context);
				break;
			case WAIT_FOR_SPAWN:
				waitForSpawn(context);
				break;
			case FIGHT:
				fight(context);
				break;
			case LOOT:
				loot(context);
				break;
			default:
				break;
		}
	}

	private void setState(State next, String reason)
	{
		if (next != state)
		{
			if (state == State.LOOT && getContext() != null)
			{
				// The mouse lets go of the pile whenever looting ends.
				getContext().getInput().stopPileTracking();
			}
			log.info("[AnimatedSuit] {} -> {}: {}", state, next, reason);
			state = next;
			stateTicks = 0;
		}
	}

	private void halt(String reason)
	{
		setState(State.HALTED, reason);
		setStatus("Stopped: " + reason);
	}

	// ---------------------------------------------------------------- states

	private void checkArmour(ScriptContext context)
	{
		NPC mine = findOurArmour(context, false);
		if (mine != null)
		{
			target = mine;
			idleTicks = 0;
			setState(State.FIGHT, "our animated armour is already up");
			return;
		}

		List<Integer> missing = missingPieces(context.getInventory());
		if (missing.isEmpty())
		{
			relootRounds = 0;
			setState(State.ANIMATE, "all 3 " + armour.label + " pieces in inventory");
			return;
		}

		GroundLoot onGround = findLoot(context, null, MISSING_PIECE_RADIUS);
		if (onGround == null && !skippedLoot.isEmpty() && relootRounds < RELOOT_ROUND_LIMIT)
		{
			// The pieces may be on a pile that looting gave up on. They are
			// right there, so forget the skip and loot again (capped).
			++relootRounds;
			log.info("[AnimatedSuit] missing pieces may be on a skipped pile, re-looting (round {}/{})",
				relootRounds, RELOOT_ROUND_LIMIT);
			skippedLoot.clear();
			onGround = findLoot(context, null, MISSING_PIECE_RADIUS);
		}
		if (onGround != null)
		{
			deathTile = null;
			resetLoot();
			setState(State.LOOT, "missing " + names(context, missing) + ", picking up from the ground");
			return;
		}

		halt("missing " + names(context, missing) + " (need " + armour.label + " full helm, platebody, platelegs)");
	}

	private void animate(ScriptContext context)
	{
		GameInput input = context.getInput();
		if (input.isBusy() || input.isCameraBusy())
		{
			setStatus("Moving mouse");
			return;
		}
		if (context.getClient().isMenuOpen())
		{
			input.closeMenu();
			return;
		}
		if (!missingPieces(context.getInventory()).isEmpty())
		{
			setState(State.CHECK_ARMOUR, "a piece is missing before animating");
			return;
		}
		if (isLowHp(context.getClient()) && findFoodSlot(context.getClient(), context.getInventory()) < 0)
		{
			// Never start a new fight below half HP without food: wait for
			// HP to regenerate (or for food to be added).
			if (noFoodWarnTicks++ % 20 == 0)
			{
				log.warn("[AnimatedSuit] HP below 50% and no food: waiting to regenerate before animating");
			}
			setStatus("Low HP, no food: waiting to regenerate");
			return;
		}
		noFoodWarnTicks = 0;
		if (animateAttempts >= ANIMATE_ATTEMPT_LIMIT)
		{
			halt(ANIMATE_ATTEMPT_LIMIT + " Animate clicks without a spawn; is a Magical Animator next to you?");
			return;
		}

		Optional<TileObject> animator = findAnimator(context);
		if (animator.isEmpty())
		{
			halt("no Magical Animator within " + SEARCH_DISTANCE + " tiles");
			return;
		}

		TileObject object = animator.get();
		if (!input.isVisible(object))
		{
			input.turnCameraToward(object.getWorldLocation());
			cooldown = 1;
			setStatus("Turning camera to animator");
			return;
		}

		// Exact object action; a left click when Animate is the default.
		if (input.interact(object, ANIMATE_ACTION))
		{
			++animateAttempts;
			animatorLocation = object.getWorldLocation();
			spawnTicks = 0;
			cooldown = 1;
			setState(State.WAIT_FOR_SPAWN, "Animate clicked (attempt " + animateAttempts + ") at " + animatorLocation);
			setStatus("Animating " + armour.label + " armour");
			return;
		}

		cooldown = 2;
		setStatus("Cannot click Animate");
	}

	private void waitForSpawn(ScriptContext context)
	{
		NPC mine = findOurArmour(context, true);
		if (mine != null)
		{
			animateAttempts = 0;
			target = mine;
			idleTicks = 0;
			setState(State.FIGHT, "armour spawned at " + mine.getWorldLocation());
			setStatus("Fighting " + armour.label + " armour");
			// It attacks by itself; only attack if it is not already on us.
			fight(context);
			return;
		}

		if (++spawnTicks >= SPAWN_TIMEOUT_TICKS)
		{
			log.info("[AnimatedSuit] no {} armour within {} ticks of Animate", armour.label, SPAWN_TIMEOUT_TICKS);
			setState(State.CHECK_ARMOUR, "spawn timeout");
			return;
		}
		setStatus("Waiting for armour to spawn (" + spawnTicks + ")");
	}

	private void fight(ScriptContext context)
	{
		if (isDead(context, target))
		{
			++kills;
			deathTile = target == null ? null : target.getWorldLocation();
			target = null;
			dropWaitTicks = 0;
			tokenWaitTicks = 0;
			tokensAtKill = tokens;
			lootListed = false;
			// A pile given up on last round must not block this round's drop.
			skippedLoot.clear();
			resetLoot();
			setState(State.LOOT, "armour killed (kill " + kills + ") at " + deathTile);
			setStatus("Looting");
			return;
		}

		if (stateTicks > FIGHT_MAX_TICKS)
		{
			log.info("[AnimatedSuit] fight passed {} ticks, re-checking", FIGHT_MAX_TICKS);
			target = null;
			setState(State.CHECK_ARMOUR, "fight time cap");
			return;
		}

		Player player = context.getPlayer();
		Actor ours = player == null ? null : player.getInteracting();
		if (ours == target || isMoving(player))
		{
			idleTicks = 0;
			setStatus("Fighting " + armour.label + " armour");
			return;
		}

		// Not attacking it ourselves yet. It normally attacks us and auto
		// retaliate takes over; give that a short, attentive grace period,
		// then attack it ourselves.
		if (++idleTicks < REATTACK_IDLE_TICKS)
		{
			setStatus("Fighting " + armour.label + " armour");
			return;
		}

		GameInput input = context.getInput();
		if (input.isBusy() || input.isCameraBusy())
		{
			return;
		}
		if (context.getClient().isMenuOpen())
		{
			input.closeMenu();
			return;
		}
		if (!input.isVisible(target))
		{
			input.turnCameraToward(target.getWorldLocation());
			cooldown = 1;
			return;
		}
		if (input.interact(target, ATTACK_ACTION))
		{
			idleTicks = 0;
			cooldown = ATTACK_COOLDOWN_TICKS;
			log.info("[AnimatedSuit fight] attack click on {} armour at {}", armour.label, target.getWorldLocation());
			setStatus("Attacking " + armour.label + " armour");
		}
	}

	private void loot(ScriptContext context)
	{
		Inventory inventory = context.getInventory();
		GameInput input = context.getInput();
		WorldPoint center = deathTile;
		GroundLoot loot = findLoot(context, center, center == null ? MISSING_PIECE_RADIUS : LOOT_RADIUS);

		if (!lootListed && (loot != null || dropWaitTicks >= DROP_WAIT_TICKS))
		{
			lootListed = true;
			logGroundItems(context, center);
		}

		if (loot == null)
		{
			if (center != null && ++dropWaitTicks <= DROP_WAIT_TICKS && missingPieces(inventory).size() == 3)
			{
				setStatus("Waiting for the drop");
				return;
			}
			// The pieces are back, but the round is not over until the
			// tokens are in too.
			if (center != null && tokens <= tokensAtKill && ++tokenWaitTicks <= TOKEN_WAIT_TICKS)
			{
				setStatus("Waiting for tokens");
				return;
			}
			if (center != null && tokens <= tokensAtKill)
			{
				log.info("[AnimatedSuit loot] no Warrior guild tokens found after the kill (have {})", tokens);
			}
			resetLoot();
			setState(State.CHECK_ARMOUR, "nothing left to loot");
			checkArmour(context);
			return;
		}

		if (inventory.isFull() && !(TOKEN_IDS.contains(loot.itemId) && inventory.findSlot(TOKEN_ID) >= 0))
		{
			halt("inventory full, cannot pick up " + itemName(context.getClient(), loot.itemId)
				+ (TOKEN_IDS.contains(loot.itemId) ? " (no token stack yet; free one slot)" : ""));
			return;
		}

		// Whole-state cap: no pickup for a while means give up on this pile.
		int count = countItems(inventory);
		if (count != lootInventoryCount)
		{
			lootInventoryCount = count;
			lootStateTicks = 0;
		}
		if (++lootStateTicks > LOOT_STATE_MAX_TICKS)
		{
			log.info("[AnimatedSuit loot] nothing picked up for {} ticks, re-checking", LOOT_STATE_MAX_TICKS);
			skippedLoot.add(key(loot));
			resetLoot();
			setState(State.CHECK_ARMOUR, "loot time cap");
			return;
		}

		String itemName = itemName(context.getClient(), loot.itemId);
		String label = String.format("pile=%d,%d (scene %d,%d) next=%s(%d)", loot.worldLocation.getX(),
			loot.worldLocation.getY(), loot.sceneX, loot.sceneY, itemName, loot.itemId);

		if (input.isBusy() || input.isCameraBusy())
		{
			if (++lootPhaseTicks > LOOT_PHASE_MAX_TICKS)
			{
				log.info("[AnimatedSuit loot] input busy for {} ticks, resetting input", lootPhaseTicks);
				input.reset();
				lootPhaseTicks = 0;
			}
			return;
		}
		lootPhaseTicks = 0;

		if (!Objects.equals(loot.worldLocation, lootTargetTile))
		{
			// New pile: fresh per-pile counters.
			lootTargetTile = loot.worldLocation;
			lootTargetItemId = loot.itemId;
			lootAttempts = 0;
		}

		boolean tracking = input.isPileTracking(loot.sceneX, loot.sceneY);
		int failed = lootAttempts + (tracking ? input.getPileFailedClicks() : 0);
		if (failed >= LOOT_ATTEMPT_LIMIT)
		{
			log.info("[AnimatedSuit loot] {} gave up after {} failed tries, skipping this pile", label, failed);
			skippedLoot.add(tileKey(loot.worldLocation));
			resetLoot();
			return;
		}

		if (!tracking)
		{
			if (context.getClient().isMenuOpen())
			{
				input.closeMenu();
				return;
			}
			if (input.groundItemPoint(loot.sceneX, loot.sceneY) == null)
			{
				// Off screen: one small purposeful camera turn toward it.
				++lootAttempts;
				input.turnCameraToward(loot.worldLocation);
				cooldown = 1;
				log.info("[AnimatedSuit loot] {} tile off screen, turning camera", label);
				return;
			}
			if (input.startPileTracking(loot.sceneX, loot.sceneY, lootIds, "[AnimatedSuit loot]"))
			{
				log.info("[AnimatedSuit loot] {} start tracking pile", label);
				setStatus("Looting " + itemName);
			}
			return;
		}

		GameInput.PileStatus status = input.getPileStatus();
		if (status == GameInput.PileStatus.OFF_SCREEN)
		{
			setStatus("Looting (pile off screen)");
			return;
		}

		setStatus("Looting " + itemName + " (" + input.getPileSuccesses() + " picked)");
	}

	private void resetLoot()
	{
		ScriptContext context = getContext();
		if (context != null)
		{
			context.getInput().stopPileTracking();
		}
		lootPhaseTicks = 0;
		lootTargetItemId = -1;
		lootTargetTile = null;
		lootAttempts = 0;
		lootStateTicks = 0;
		lootInventoryCount = -1;
	}

	// --------------------------------------------------------------- helpers

	/**
	 * @return the chosen type's animated armour that is fighting us, or with
	 * {@code allowFresh}, one that just appeared next to the animator and is
	 * not fighting anyone else
	 */
	private NPC findOurArmour(ScriptContext context, boolean allowFresh)
	{
		Player player = context.getPlayer();
		WorldView worldView = context.getClient().getTopLevelWorldView();
		if (player == null || worldView == null || player.getWorldLocation() == null)
		{
			return null;
		}

		NPC fresh = null;
		for (NPC npc : worldView.npcs())
		{
			if (npc == null || npc.getId() != armour.npcId || npc.isDead() || npc.getWorldLocation() == null)
			{
				continue;
			}
			if (npc.getInteracting() == player || player.getInteracting() == npc)
			{
				return npc;
			}
			if (allowFresh && npc.getInteracting() == null && animatorLocation != null
				&& npc.getWorldLocation().distanceTo(animatorLocation) <= 2)
			{
				fresh = npc;
			}
		}
		return fresh;
	}

	private static boolean isDead(ScriptContext context, NPC npc)
	{
		if (npc == null || npc.isDead())
		{
			return true;
		}
		WorldView worldView = context.getClient().getTopLevelWorldView();
		return worldView != null && worldView.npcs().stream().noneMatch(candidate -> candidate == npc);
	}

	private Optional<TileObject> findAnimator(ScriptContext context)
	{
		Optional<TileObject> byId = context.getTargets().findTileObject(ANIMATOR_ID, SEARCH_DISTANCE);
		if (byId.isPresent())
		{
			return byId;
		}
		return context.getTargets().findObject(ANIMATOR_NAME, ANIMATE_ACTION, SEARCH_DISTANCE)
			.map(object -> (TileObject) object);
	}

	private List<Integer> missingPieces(Inventory inventory)
	{
		List<Integer> missing = new ArrayList<>();
		for (int piece : armour.pieces)
		{
			if (inventory.findSlot(piece) < 0)
			{
				missing.add(piece);
			}
		}
		return missing;
	}

	/**
	 * Nearest wanted drop (our pieces or tokens) within {@code radius} of
	 * {@code center} (the player when null). Drops owned by other players
	 * and items given up on are ignored.
	 */
	private GroundLoot findLoot(ScriptContext context, WorldPoint center, int radius)
	{
		Client client = context.getClient();
		WorldPoint playerLocation = context.getPlayerLocation();
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null || playerLocation == null)
		{
			return null;
		}
		Scene scene = worldView.getScene();
		Tile[][][] tiles = scene == null ? null : scene.getTiles();
		int plane = worldView.getPlane();
		if (tiles == null || plane < 0 || plane >= tiles.length || tiles[plane] == null)
		{
			return null;
		}

		WorldPoint origin = center == null ? playerLocation : center;
		GroundLoot best = null;
		int bestScore = Integer.MAX_VALUE;
		for (Tile[] row : tiles[plane])
		{
			if (row == null)
			{
				continue;
			}
			for (Tile tile : row)
			{
				List<TileItem> items = tile == null ? null : tile.getGroundItems();
				if (items == null || items.isEmpty())
				{
					continue;
				}
				WorldPoint location = tile.getWorldLocation();
				net.runelite.api.Point sceneLocation = tile.getSceneLocation();
				// Around the death tile and around the player: a drop may land
				// on either.
				if (location == null || sceneLocation == null
					|| location.distanceTo(origin) > radius && location.distanceTo(playerLocation) > LOOT_RADIUS)
				{
					continue;
				}
				for (TileItem item : items)
				{
					// Only other players' drops are skipped; NONE, SELF and
					// GROUP are all ours to take.
					if (item == null || !lootIds.contains(item.getId())
						|| item.getOwnership() == TileItem.OWNERSHIP_OTHER)
					{
						continue;
					}
					GroundLoot candidate = new GroundLoot(item.getId(), sceneLocation.getX(), sceneLocation.getY(),
						location);
					if (skippedLoot.contains(key(candidate)) || skippedLoot.contains(tileKey(location)))
					{
						continue;
					}
					// Nearest first; armour before tokens on the same tile.
					int score = location.distanceTo(playerLocation) * 10 + (TOKEN_IDS.contains(item.getId()) ? 1 : 0);
					if (score < bestScore)
					{
						bestScore = score;
						best = candidate;
					}
				}
			}
		}
		return best;
	}

	/** Logs every ground item near the death tile and the player: id, qty, tile, ownership. */
	private void logGroundItems(ScriptContext context, WorldPoint center)
	{
		WorldPoint playerLocation = context.getPlayerLocation();
		WorldView worldView = context.getClient().getTopLevelWorldView();
		Scene scene = worldView == null ? null : worldView.getScene();
		Tile[][][] tiles = scene == null ? null : scene.getTiles();
		int plane = worldView == null ? -1 : worldView.getPlane();
		if (playerLocation == null || tiles == null || plane < 0 || plane >= tiles.length || tiles[plane] == null)
		{
			return;
		}
		WorldPoint origin = center == null ? playerLocation : center;
		List<String> found = new ArrayList<>();
		for (Tile[] row : tiles[plane])
		{
			for (Tile tile : row == null ? new Tile[0] : row)
			{
				List<TileItem> items = tile == null ? null : tile.getGroundItems();
				WorldPoint location = tile == null ? null : tile.getWorldLocation();
				if (items == null || location == null
					|| location.distanceTo(origin) > 5 && location.distanceTo(playerLocation) > 5)
				{
					continue;
				}
				for (TileItem item : items)
				{
					if (item != null)
					{
						found.add(item.getId() + "x" + item.getQuantity() + "@" + location.getX() + "," + location.getY()
							+ " own=" + item.getOwnership() + (lootIds.contains(item.getId()) ? " (loot)" : ""));
					}
				}
			}
		}
		log.info("[AnimatedSuit loot] ground items at loot start (death tile {}, player {}): {}", center,
			playerLocation, found.isEmpty() ? "none" : String.join("; ", found));
	}

	private static String tileKey(WorldPoint tile)
	{
		return "tile@" + tile;
	}

	private static String key(GroundLoot loot)
	{
		return loot.itemId + "@" + loot.worldLocation;
	}

	private static int countItems(Inventory inventory)
	{
		int count = 0;
		for (Item item : inventory.getItems())
		{
			if (item != null && item.getId() != -1)
			{
				count += Math.max(1, item.getQuantity());
			}
		}
		return count;
	}

	private void updateTokens(ScriptContext context)
	{
		int now = 0;
		for (Item item : context.getInventory().getItems())
		{
			if (item != null && TOKEN_IDS.contains(item.getId()))
			{
				now += item.getQuantity();
			}
		}
		if (tokensAtStart < 0)
		{
			tokensAtStart = now;
		}
		tokens = now;
	}

	private static boolean isLowHp(Client client)
	{
		int max = client.getRealSkillLevel(Skill.HITPOINTS);
		return max > 0 && client.getBoostedSkillLevel(Skill.HITPOINTS) < max * EAT_BELOW_HP;
	}

	/**
	 * Eats any inventory item that has an Eat option once HP is below half,
	 * in any state, including mid-fight. After an eat it waits until HP went
	 * up or the food left the inventory (capped) before eating again.
	 *
	 * @return true if this tick was spent eating or waiting on an eat
	 */
	private boolean eatIfNeeded(ScriptContext context)
	{
		Client client = context.getClient();
		Inventory inventory = context.getInventory();
		int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);

		if (eatWaitTicks > 0)
		{
			boolean registered = hp > eatHpBefore || inventory.countItem(eatItemId) < eatItemCountBefore;
			if (!registered && --eatWaitTicks > 0)
			{
				setStatus("Eating");
				return true;
			}
			if (!registered)
			{
				log.info("[AnimatedSuit eat] eat of item {} did not register within {} ticks", eatItemId, EAT_WAIT_TICKS);
			}
			eatWaitTicks = 0;
		}

		if (!isLowHp(client))
		{
			return false;
		}

		int slot = findFoodSlot(client, inventory);
		if (slot < 0)
		{
			// Keep fighting; the animate step refuses to start a new suit.
			if (noFoodWarnTicks++ % 20 == 0)
			{
				log.warn("[AnimatedSuit eat] HP {}/{} below 50% and no food in inventory, continuing",
					hp, client.getRealSkillLevel(Skill.HITPOINTS));
			}
			return false;
		}

		GameInput input = context.getInput();
		if (input.isBusy() || input.isCameraBusy())
		{
			return true;
		}
		if (client.isMenuOpen())
		{
			input.closeMenu();
			return true;
		}
		if (!inventory.isOpen())
		{
			inventory.open();
			setStatus("Opening inventory to eat");
			return true;
		}

		Widget widget = inventory.getSlotWidget(slot);
		if (widget == null)
		{
			return true;
		}
		int itemId = inventory.getItems()[slot].getId();
		ItemComposition composition = client.getItemDefinition(itemId);
		String[] actions = composition == null ? null : composition.getInventoryActions();
		// Eat is food's left-click option; if an item has it elsewhere, run
		// the exact Eat action instead of a left click.
		boolean sent = actions != null && actions.length > 0 && "Eat".equalsIgnoreCase(actions[0])
			? input.click(widget)
			: input.interact(widget, "Eat");
		if (sent)
		{
			++foodEaten;
			eatHpBefore = hp;
			eatItemId = itemId;
			eatItemCountBefore = inventory.countItem(itemId);
			eatWaitTicks = EAT_WAIT_TICKS;
			log.info("[AnimatedSuit eat] eating {} at hp {}/{} in state={}", itemName(client, itemId), hp,
				client.getRealSkillLevel(Skill.HITPOINTS), state);
			setStatus("Eating " + itemName(client, itemId));
		}
		return true;
	}

	/** @return the first inventory slot holding food (an item with an Eat option), or -1 */
	private int findFoodSlot(Client client, Inventory inventory)
	{
		Item[] items = inventory.getItems();
		for (int slot = 0; slot < Math.min(items.length, Inventory.SIZE); slot++)
		{
			Item item = items[slot];
			// Never the armour pieces or tokens.
			if (item == null || item.getId() == -1 || lootIds.contains(item.getId()) || isArmourPiece(item.getId()))
			{
				continue;
			}
			ItemComposition composition = client.getItemDefinition(item.getId());
			String[] actions = composition == null ? null : composition.getInventoryActions();
			if (actions == null)
			{
				continue;
			}
			for (String action : actions)
			{
				if ("Eat".equalsIgnoreCase(action))
				{
					return slot;
				}
			}
		}
		return -1;
	}

	private static boolean isArmourPiece(int itemId)
	{
		for (ArmourType type : ArmourType.values())
		{
			for (int piece : type.pieces)
			{
				if (piece == itemId)
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Drinks a strength potion (lowest dose first) when the Strength boost is
	 * below the configured value (default 5), in any state. Off when the mode is OFF or
	 * no matching potion is carried.
	 *
	 * @return true if this tick was spent drinking or waiting on a drink
	 */
	private boolean drinkIfNeeded(ScriptContext context)
	{
		if (strengthPotions.length == 0)
		{
			return false;
		}
		Client client = context.getClient();
		int boost = client.getBoostedSkillLevel(Skill.STRENGTH) - client.getRealSkillLevel(Skill.STRENGTH);

		if (drinkWaitTicks > 0)
		{
			if (boost > boostBeforeDrink)
			{
				drinkWaitTicks = 0;
			}
			else
			{
				// Waiting for the boost to show; fighting goes on meanwhile.
				--drinkWaitTicks;
				return false;
			}
		}

		if (boost >= drinkBelowBoost)
		{
			return false;
		}

		Inventory inventory = context.getInventory();
		int slot = -1;
		for (int potion : strengthPotions)
		{
			slot = inventory.findSlot(potion);
			if (slot >= 0)
			{
				break;
			}
		}
		if (slot < 0)
		{
			return false;
		}

		GameInput input = context.getInput();
		if (input.isBusy() || input.isCameraBusy() || client.isMenuOpen())
		{
			// Let the current click finish; never steal a menu.
			return false;
		}
		if (!inventory.isOpen())
		{
			inventory.open();
			setStatus("Opening inventory to drink");
			return true;
		}

		// Drink is the potion's left-click option.
		Widget widget = inventory.getSlotWidget(slot);
		if (widget != null && input.click(widget))
		{
			int itemId = inventory.getItems()[slot].getId();
			boostBeforeDrink = boost;
			drinkWaitTicks = DRINK_WAIT_TICKS;
			++potionsDrunk;
			log.info("[AnimatedSuit drink] {} at strength boost {} (drinks below {})", itemName(client, itemId), boost,
				drinkBelowBoost);
			setStatus("Drinking " + itemName(client, itemId));
			return true;
		}
		return false;
	}

	private void recoverStuckInput(ScriptContext context)
	{
		Client client = context.getClient();
		GameInput input = context.getInput();

		if (!client.isMenuOpen())
		{
			menuOpenTicks = 0;
		}
		else if (++menuOpenTicks > MENU_STUCK_TICKS && !input.isBusy())
		{
			menuOpenTicks = 0;
			input.closeMenu();
		}

		busyTicks = input.isBusy() ? busyTicks + 1 : 0;
		if (busyTicks > BUSY_STUCK_TICKS)
		{
			log.info("[AnimatedSuit] input busy for {} ticks in state={}, resetting input", busyTicks, state);
			input.reset();
			busyTicks = 0;
		}

		cameraBusyTicks = input.isCameraBusy() ? cameraBusyTicks + 1 : 0;
		if (cameraBusyTicks > CAMERA_STUCK_TICKS)
		{
			log.info("[AnimatedSuit] camera busy for {} ticks in state={}, resetting input", cameraBusyTicks, state);
			input.reset();
			cameraBusyTicks = 0;
		}
	}

	private static boolean isMoving(Player player)
	{
		return player != null && player.getPoseAnimation() != player.getIdlePoseAnimation();
	}

	private static String itemName(Client client, int itemId)
	{
		ItemComposition composition = client.getItemDefinition(itemId);
		String name = composition == null ? null : composition.getName();
		return name == null ? String.valueOf(itemId) : name;
	}

	private static String names(ScriptContext context, List<Integer> ids)
	{
		List<String> out = new ArrayList<>();
		for (int id : ids)
		{
			out.add(itemName(context.getClient(), id));
		}
		return String.join(", ", out);
	}

	@Override
	public List<String> getOverlayLines()
	{
		long elapsed = Math.max(1, System.currentTimeMillis() - startedAt);
		long perHour = kills * 3_600_000L / elapsed;
		int gained = tokensAtStart < 0 ? 0 : Math.max(0, tokens - tokensAtStart);
		return Arrays.asList(
			"Status: " + getStatus(),
			"Armour: " + (armour == null ? "none (tick exactly one)" : armour.label)
				+ " | State: " + (state == null ? "-" : state.name()),
			"Kills: " + kills + " (" + perHour + "/hr)",
			"Tokens: " + tokens + " (+" + gained + ")" + (foodEaten > 0 ? " | Food eaten: " + foodEaten : ""),
			"Strength potions: " + potionsDrunk + " (" + potionMode
				+ (potionMode == PotionMode.OFF ? ")" : ", drinks when boost < " + drinkBelowBoost + ")"));
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
