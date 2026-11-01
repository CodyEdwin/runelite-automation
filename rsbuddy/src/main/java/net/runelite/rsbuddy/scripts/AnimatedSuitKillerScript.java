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
import java.util.concurrent.ThreadLocalRandom;
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
	/** Ticks after a Take click before it may be repeated (if nothing moved). */
	private static final int LOOT_RECLICK_TICKS = 3;
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
	private static final int EAT_COOLDOWN_TICKS = 3;

	private static final long ANTI_IDLE_MIN_MS = 30_000L;
	private static final long ANTI_IDLE_MAX_MS = 60_000L;
	private static final int ANTI_IDLE_MIN_HOLD_MS = 150;
	private static final int ANTI_IDLE_MAX_HOLD_MS = 450;

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

	private enum LootPhase
	{
		IDLE,
		HOVERED,
		MENU
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

	private LootPhase lootPhase = LootPhase.IDLE;
	private int lootPhaseTicks;
	private int lootTargetItemId = -1;
	private WorldPoint lootTargetTile;
	private int lootAttempts;
	private int lootClickTicks = -1;
	private int lootStateTicks;
	private int lootInventoryCount = -1;
	private final Set<String> skippedLoot = new HashSet<>();

	private int kills;
	private int tokensAtStart = -1;
	private int tokens;
	private int eatCooldown;
	private int foodEaten;
	private long startedAt;
	private long nextAntiIdleAt;

	public AnimatedSuitKillerScript()
	{
		this(false, false, false, false);
	}

	/**
	 * One flag per armour type, exactly as ticked in the config. Anything
	 * other than exactly one ticked refuses to run.
	 */
	public AnimatedSuitKillerScript(boolean black, boolean mithril, boolean adamant, boolean rune)
	{
		super("Animated Suit Killer", "Animates armour on the Warriors' Guild Magical Animator, kills it, loots the"
			+ " pieces and tokens, and repeats.");
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
			ids.add(TOKEN_ID);
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
		eatCooldown = 0;
		foodEaten = 0;
		startedAt = System.currentTimeMillis();
		scheduleAntiIdle();
		setStatus(configError == null ? "Ready" : configError);
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
		eatIfNeeded(context);
		antiIdle(context);

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
			setState(State.ANIMATE, "all 3 " + armour.label + " pieces in inventory");
			return;
		}

		GroundLoot onGround = findLoot(context, null, MISSING_PIECE_RADIUS);
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

		if (loot == null)
		{
			if (center != null && ++dropWaitTicks <= DROP_WAIT_TICKS && missingPieces(inventory).size() == 3)
			{
				setStatus("Waiting for the drop");
				return;
			}
			resetLoot();
			setState(State.CHECK_ARMOUR, "nothing left to loot");
			checkArmour(context);
			return;
		}

		if (inventory.isFull() && !(loot.itemId == TOKEN_ID && inventory.findSlot(TOKEN_ID) >= 0))
		{
			halt("inventory full, cannot pick up the armour");
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

		if (input.isBusy() || input.isCameraBusy())
		{
			if (++lootPhaseTicks > LOOT_PHASE_MAX_TICKS)
			{
				log.info("[AnimatedSuit loot] input busy for {} ticks, resetting input", lootPhaseTicks);
				input.reset();
				lootPhase = LootPhase.IDLE;
				lootPhaseTicks = 0;
			}
			return;
		}

		if (loot.itemId != lootTargetItemId || !Objects.equals(loot.worldLocation, lootTargetTile))
		{
			lootTargetItemId = loot.itemId;
			lootTargetTile = loot.worldLocation;
			lootAttempts = 0;
			lootClickTicks = -1;
			lootPhase = LootPhase.IDLE;
			lootPhaseTicks = 0;
		}

		Player player = context.getPlayer();
		String itemName = itemName(context.getClient(), loot.itemId);

		// A Take was sent: let the player walk over and pick it up.
		if (lootClickTicks >= 0)
		{
			++lootClickTicks;
			if (isMoving(player) && lootClickTicks < LOOT_RECLICK_TICKS * 3 || lootClickTicks < LOOT_RECLICK_TICKS)
			{
				setStatus("Looting " + itemName);
				return;
			}
			lootClickTicks = -1;
		}

		if (lootAttempts >= LOOT_ATTEMPT_LIMIT)
		{
			log.info("[AnimatedSuit loot] item={}({}) tile={} gave up after {} attempts", itemName, loot.itemId,
				loot.worldLocation, lootAttempts);
			skippedLoot.add(key(loot));
			resetLoot();
			return;
		}

		String label = String.format("item=%s(%d) tile=%d,%d attempt=%d/%d", itemName, loot.itemId,
			loot.worldLocation.getX(), loot.worldLocation.getY(), lootAttempts + 1, LOOT_ATTEMPT_LIMIT);

		if (lootPhase == LootPhase.MENU)
		{
			lootPhase = LootPhase.IDLE;
			boolean sent = input.clickGroundItemMenuRow(loot.itemId, loot.sceneX, loot.sceneY);
			if (!sent)
			{
				input.invokeGroundItemTake(loot.sceneX, loot.sceneY, loot.itemId, itemName);
				log.info("[AnimatedSuit loot] {} result=menu had no Take row, ran exact Take directly", label);
			}
			else
			{
				log.info("[AnimatedSuit loot] {} result=clicked exact Take row in menu", label);
			}
			lootSent();
			return;
		}

		if (lootPhase == LootPhase.HOVERED)
		{
			lootPhase = LootPhase.IDLE;
			java.awt.Point mouse = input.getMouseLocation();
			int hover = input.groundItemHoverState(loot.itemId, loot.sceneX, loot.sceneY);
			boolean onTile = input.isOnGroundItemTile(mouse, loot.sceneX, loot.sceneY);
			if (hover == 2 && onTile && mouse != null)
			{
				input.click(mouse);
				log.info("[AnimatedSuit loot] {} result=left-click Take (top option)", label);
				lootSent();
				return;
			}
			if (hover == 1 && mouse != null)
			{
				input.rightClick(mouse);
				lootPhase = LootPhase.MENU;
				lootPhaseTicks = 0;
				log.info("[AnimatedSuit loot] {} result=right-click, picking Take row next tick", label);
				return;
			}
			input.invokeGroundItemTake(loot.sceneX, loot.sceneY, loot.itemId, itemName);
			log.info("[AnimatedSuit loot] {} result=item not under cursor (hover={} onTile={}), ran exact Take",
				label, hover, onTile);
			lootSent();
			return;
		}

		if (context.getClient().isMenuOpen())
		{
			input.closeMenu();
			return;
		}

		// IDLE: aim at the tile as it is right now; it is re-checked under
		// the cursor before clicking.
		java.awt.Point point = input.groundItemPoint(loot.sceneX, loot.sceneY);
		log.info("[AnimatedSuit loot] {} start: aim={}", label, point == null ? "none" : point.x + "," + point.y);
		if (point != null && input.moveTo(point))
		{
			lootPhase = LootPhase.HOVERED;
			lootPhaseTicks = 0;
			setStatus("Looting " + itemName);
			return;
		}

		// Off screen: the exact Take walks there by itself.
		input.invokeGroundItemTake(loot.sceneX, loot.sceneY, loot.itemId, itemName);
		log.info("[AnimatedSuit loot] {} result=tile off screen, ran exact Take", label);
		lootSent();
	}

	private void lootSent()
	{
		++lootAttempts;
		lootClickTicks = 0;
		lootPhaseTicks = 0;
	}

	private void resetLoot()
	{
		lootPhase = LootPhase.IDLE;
		lootPhaseTicks = 0;
		lootTargetItemId = -1;
		lootTargetTile = null;
		lootAttempts = 0;
		lootClickTicks = -1;
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
				if (location == null || sceneLocation == null || location.distanceTo(origin) > radius)
				{
					continue;
				}
				for (TileItem item : items)
				{
					if (item == null || !lootIds.contains(item.getId())
						|| item.getOwnership() == TileItem.OWNERSHIP_OTHER)
					{
						continue;
					}
					GroundLoot candidate = new GroundLoot(item.getId(), sceneLocation.getX(), sceneLocation.getY(),
						location);
					if (skippedLoot.contains(key(candidate)))
					{
						continue;
					}
					// Nearest first; armour before tokens on the same tile.
					int score = location.distanceTo(playerLocation) * 10 + (item.getId() == TOKEN_ID ? 1 : 0);
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
			if (item != null && item.getId() == TOKEN_ID)
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

	/** Eats any inventory item with an Eat option once HP is below half. */
	private void eatIfNeeded(ScriptContext context)
	{
		if (eatCooldown > 0)
		{
			--eatCooldown;
			return;
		}
		Client client = context.getClient();
		int max = client.getRealSkillLevel(Skill.HITPOINTS);
		int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
		if (max <= 0 || hp >= max * EAT_BELOW_HP)
		{
			return;
		}

		Inventory inventory = context.getInventory();
		int slot = findFoodSlot(client, inventory);
		GameInput input = context.getInput();
		if (slot < 0 || input.isBusy() || input.isCameraBusy() || client.isMenuOpen())
		{
			return;
		}
		if (!inventory.isOpen())
		{
			inventory.open();
			return;
		}
		Widget widget = inventory.getSlotWidget(slot);
		if (widget != null && input.click(widget))
		{
			++foodEaten;
			eatCooldown = EAT_COOLDOWN_TICKS;
			log.info("[AnimatedSuit] eating {} at hp {}/{}", itemName(client, inventory.getItems()[slot].getId()), hp, max);
			setStatus("Eating");
		}
	}

	private int findFoodSlot(Client client, Inventory inventory)
	{
		Item[] items = inventory.getItems();
		for (int slot = 0; slot < Math.min(items.length, Inventory.SIZE); slot++)
		{
			Item item = items[slot];
			if (item == null || item.getId() == -1 || lootIds.contains(item.getId()))
			{
				continue;
			}
			ItemComposition composition = client.getItemDefinition(item.getId());
			String[] actions = composition == null ? null : composition.getInventoryActions();
			if (actions != null && actions.length > 0 && "Eat".equalsIgnoreCase(actions[0]))
			{
				return slot;
			}
		}
		return -1;
	}

	private void recoverStuckInput(ScriptContext context)
	{
		Client client = context.getClient();
		GameInput input = context.getInput();

		if (!client.isMenuOpen())
		{
			menuOpenTicks = 0;
		}
		else if (++menuOpenTicks > MENU_STUCK_TICKS && !input.isBusy() && lootPhase != LootPhase.MENU)
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

	private void antiIdle(ScriptContext context)
	{
		if (System.currentTimeMillis() < nextAntiIdleAt)
		{
			return;
		}
		GameInput input = context.getInput();
		if (input.isBusy() || input.isCameraBusy() || context.getClient().isMenuOpen()
			|| state == State.LOOT || state == State.ANIMATE)
		{
			return;
		}
		ThreadLocalRandom random = ThreadLocalRandom.current();
		if (input.nudgeCamera(random.nextBoolean(), random.nextInt(ANTI_IDLE_MIN_HOLD_MS, ANTI_IDLE_MAX_HOLD_MS + 1)))
		{
			scheduleAntiIdle();
		}
	}

	private void scheduleAntiIdle()
	{
		nextAntiIdleAt = System.currentTimeMillis()
			+ ThreadLocalRandom.current().nextLong(ANTI_IDLE_MIN_MS, ANTI_IDLE_MAX_MS + 1);
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
			"Tokens: " + tokens + " (+" + gained + ")" + (foodEaten > 0 ? " | Food eaten: " + foodEaten : ""));
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
