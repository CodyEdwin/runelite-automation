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

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.EnumID;
import net.runelite.api.ParamID;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.rsbuddy.input.GameInput;
import net.runelite.rsbuddy.inventory.Inventory;
import net.runelite.rsbuddy.routine.Actions;
import net.runelite.rsbuddy.routine.Routine;
import net.runelite.rsbuddy.routine.RoutineContext;

/**
 * Kills the Gemstone Crab (NPC 14779), then crawls through the nearby cave to
 * the crab's next spawn location, waits for it to appear, and repeats.
 * <p>
 * Start the routine next to the crab or the cave. It never walks on its own
 * except for the walking the game does when a crab or the cave is clicked.
 */
public final class GemCrabKillerRoutine extends Routine
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GemCrabKillerRoutine.class);

	private static final int CRAB_ID = NpcID.GEMSTONE_CRAB; // 14779
	private static final String CAVE_NAME = "Cave";
	private static final String CAVE_ACTION = "Crawl-through";

	private static final int CRAB_SEARCH_DISTANCE = 20;
	private static final int CAVE_SEARCH_DISTANCE = 20;

	/** Ticks to wait after an attack click before re-evaluating. */
	private static final int ATTACK_COOLDOWN_TICKS = 3;
	/** Ticks with no interaction while the crab is alive before re-attacking. */
	private static final int REATTACK_IDLE_TICKS = 4;
	/** Ticks after the crab dies before heading for the cave (8 ticks ≈ 5 seconds). */
	private static final int POST_KILL_DELAY_TICKS = 8;
	/** Ticks with no movement or animation at all after a cave click: the click did not take. */
	private static final int CRAWL_NO_START_TICKS = 8;
	/** Hard cap on one crawl; after this it is treated as done. */
	private static final int CRAWL_MAX_TICKS = 30;
	/** Idle ticks after moving or animating that mean the crawl has finished. */
	private static final int CRAWL_SETTLE_TICKS = 2;
	/** A location change at least this large is the crawl's teleport. */
	private static final int CRAWL_TELEPORT_DISTANCE = 10;
	/** Cave clicks per kill before giving up and waiting for a spawn instead. */
	private static final int CRAWL_CLICK_LIMIT = 3;
	/** Ticks (about 36 seconds) to wait for the crab to spawn after crawling. */
	private static final int SPAWN_TIMEOUT_TICKS = 60;

	/** Every click waits a random delay strictly between 1000 and 3000 ms (never a whole second). */
	private static final long ACTION_DELAY_MIN_MS = 1000L;
	private static final long ACTION_DELAY_MAX_MS = 3000L;
	/** Wait this long once a potion is needed before actually drinking it. */
	private static final long POTION_DELAY_MIN_MS = 5_000L;
	private static final long POTION_DELAY_MAX_MS = 30_000L;
	/** Random gap between skill-tab XP checks while fighting. */
	private static final long SKILL_CHECK_MIN_MS = 120_000L;
	private static final long SKILL_CHECK_MAX_MS = 600_000L;
	/** How long to keep hovering the trained skill during a check. */
	private static final long SKILL_HOVER_MIN_MS = 5_000L;
	private static final long SKILL_HOVER_MAX_MS = 30_000L;
	/** After 1 in 4 kills: a short pause doing nothing. */
	private static final long SHORT_BREAK_MIN_MS = 6_000L;
	private static final long SHORT_BREAK_MAX_MS = 25_000L;
	/** After 1 in 20 kills: a longer pause doing nothing. */
	private static final long LONG_BREAK_MIN_MS = 30_000L;
	private static final long LONG_BREAK_MAX_MS = 600_000L;
	/** A delay that elapsed this long ago without its action firing is stale and restarts. */
	private static final long ACTION_DELAY_STALE_NS = 1_500_000_000L;

	/** Actions that each get their own random pre-click delay. */
	private enum Action
	{
		CRAB,
		CAVE,
		LEVEL_UP,
		POTION
	}

	/** Default: drink another strength potion once the boost has dropped to this. */
	private static final int DEFAULT_BOOST_THRESHOLD = 3;
	/** Ticks to wait for a drink to register before trying again. */
	private static final int DRINK_TIMEOUT_TICKS = 5;
	/** Divine potions hold their boost for exactly five minutes. */
	private static final long DIVINE_POTION_MS = 5 * 60 * 1000L;

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

	/** Ranging potions, lowest dose first. */
	private static final int[] RANGING_POTIONS = {
		ItemID._1DOSERANGERSPOTION,  // Ranging potion(1) 173
		ItemID._2DOSERANGERSPOTION,  // Ranging potion(2) 171
		ItemID._3DOSERANGERSPOTION,  // Ranging potion(3) 169
		ItemID._4DOSERANGERSPOTION   // Ranging potion(4) 2444
	};

	/** Divine ranging potions, lowest dose first. */
	private static final int[] DIVINE_RANGING_POTIONS = {
		ItemID._1DOSEDIVINERANGE,  // Divine ranging potion(1) 23742
		ItemID._2DOSEDIVINERANGE,  // Divine ranging potion(2) 23739
		ItemID._3DOSEDIVINERANGE,  // Divine ranging potion(3) 23736
		ItemID._4DOSEDIVINERANGE   // Divine ranging potion(4) 23733
	};

	/**
	 * Which potions are drunk. Matches the plugin's GemCrabPotionMode by
	 * name, so the option survives a script hot-reload.
	 */
	private enum PotionMode
	{
		STRENGTH(Skill.STRENGTH),
		SUPER_STRENGTH(Skill.STRENGTH),
		EITHER(Skill.STRENGTH),
		RANGING(Skill.RANGED),
		DIVINE_RANGING(Skill.RANGED);

		private final Skill skill;

		PotionMode(Skill skill)
		{
			this.skill = skill;
		}
	}

	private enum State
	{
		FIND_CRAB,
		FIGHT,
		GO_TO_CAVE,
		CRAWLING,
		WAIT_FOR_SPAWN
	}

	private State state;
	private int cooldown;
	private NPC target;
	private int idleTicks;
	private int waitTicks;
	private WorldPoint crawlStartLocation;
	// One crawl is allowed after each kill (and once as recovery after a
	// spawn timeout). Never set anywhere else.
	private boolean crawlArmed;
	private int crawlClicks;
	private boolean crawlStarted;
	private int crawlIdleTicks;
	private int kills;
	private long startedAt;
	private final java.util.EnumMap<Action, Long> actionReadyAt = new java.util.EnumMap<>(Action.class);
	private NPC pendingCrab;
	private int drinkWaitTicks;
	private int boostBeforeDrink;
	private int potionsDrunk;
	private long nextDivineDrinkAt;
	private int levelUpWaitTicks;
	private volatile int lastBoost;
	private volatile String lastStyle = "-";
	private volatile Skill lastSkill;
	private int potionThreshold;
	private long idleUntil;
	private long nextSkillCheckAt;
	private long skillHoverUntil;
	private int skillTabClicks;

	private final PotionMode potionMode;
	private final int boostMin;
	private final int boostMax;
	/** Potions in the order they are drunk for the chosen mode. */
	private final int[] potions;

	public GemCrabKillerRoutine()
	{
		this(PotionMode.EITHER.name(), DEFAULT_BOOST_THRESHOLD);
	}

	/**
	 * @param potionMode STRENGTH, SUPER_STRENGTH, EITHER (super strength
	 * first, then regular), RANGING or DIVINE_RANGING; anything else means EITHER
	 * @param boostThreshold drink again once the matching boost is this or less
	 */
	public GemCrabKillerRoutine(String potionMode, int boostThreshold)
	{
		this(potionMode, boostThreshold, boostThreshold);
	}

	/**
	 * @param boostMin,boostMax each drink happens once the boost drops to a
	 * new random value in this range (or less)
	 */
	public GemCrabKillerRoutine(String potionMode, int boostMin, int boostMax)
	{
		super("Gem Crab Killer", "Kills the Gemstone Crab (14779), crawls through the cave, and repeats.");
		this.potionMode = parseMode(potionMode);
		this.boostMin = Math.max(0, Math.min(boostMin, boostMax));
		this.boostMax = Math.max(0, Math.max(boostMin, boostMax));
		this.potions = potionOrder(this.potionMode);
	}

	private int rollThreshold()
	{
		return ThreadLocalRandom.current().nextInt(boostMin, boostMax + 1);
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
			case STRENGTH:
				return REGULAR_STRENGTH;
			case SUPER_STRENGTH:
				return SUPER_STRENGTH;
			case RANGING:
				return RANGING_POTIONS;
			case DIVINE_RANGING:
				return DIVINE_RANGING_POTIONS;
			default:
				int[] both = new int[SUPER_STRENGTH.length + REGULAR_STRENGTH.length];
				System.arraycopy(SUPER_STRENGTH, 0, both, 0, SUPER_STRENGTH.length);
				System.arraycopy(REGULAR_STRENGTH, 0, both, SUPER_STRENGTH.length, REGULAR_STRENGTH.length);
				return both;
		}
	}

	@Override
	protected void onStart()
	{
		// onStart runs on the Swing thread, so it must not touch the client.
		state = State.FIND_CRAB;
		cooldown = 0;
		target = null;
		idleTicks = 0;
		waitTicks = 0;
		crawlStartLocation = null;
		crawlArmed = false;
		crawlClicks = 0;
		crawlStarted = false;
		crawlIdleTicks = 0;
		kills = 0;
		startedAt = System.currentTimeMillis();
		actionReadyAt.clear();
		pendingCrab = null;
		drinkWaitTicks = 0;
		boostBeforeDrink = 0;
		potionsDrunk = 0;
		nextDivineDrinkAt = 0;
		levelUpWaitTicks = 0;
		potionThreshold = rollThreshold();
		idleUntil = 0;
		nextSkillCheckAt = System.nanoTime() + randomDelayNanos(SKILL_CHECK_MIN_MS, SKILL_CHECK_MAX_MS);
		skillHoverUntil = 0;
		skillTabClicks = 0;
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

		if (dismissLevelUp(context))
		{
			return;
		}

		if (onBreak())
		{
			setStatus("Taking a break (" + (idleUntil - System.nanoTime()) / 1_000_000_000L + "s)");
			return;
		}

		drinkPotionIfNeeded(context);
		skillCheck(context);

		if (cooldown > 0)
		{
			--cooldown;
			return;
		}

		switch (state)
		{
			case FIND_CRAB:
				findCrab(context);
				break;
			case FIGHT:
				fight(context);
				break;
			case GO_TO_CAVE:
				goToCave(context);
				break;
			case CRAWLING:
				crawling(context);
				break;
			case WAIT_FOR_SPAWN:
				waitForSpawn(context);
				break;
		}
	}

	/**
	 * Runs a delayed click the frame its delay ends, so the real delay is not
	 * rounded to the 600 ms game tick.
	 */
	@Override
	protected void onClientTick()
	{
		RoutineContext context = getContext();
		if (actionReadyAt.isEmpty() || !context.isLoggedIn() || onBreak())
		{
			return;
		}
		GameInput input = context.getInput();
		if (input.isBusy() || input.isCameraBusy())
		{
			return;
		}

		if (isDue(Action.LEVEL_UP))
		{
			dismissLevelUp(context);
			cancelIfStillDue(Action.LEVEL_UP);
			return;
		}
		if (isDue(Action.POTION))
		{
			drinkPotionIfNeeded(context);
			if (input.isBusy())
			{
				return;
			}
		}
		if (cooldown > 0)
		{
			return;
		}
		if (isDue(Action.CAVE))
		{
			if (state == State.GO_TO_CAVE)
			{
				goToCave(context);
			}
			cancelIfStillDue(Action.CAVE);
		}
		else if (isDue(Action.CRAB) && state != State.GO_TO_CAVE && state != State.CRAWLING)
		{
			NPC crab = pendingCrab;
			if (crab == null || isCrabDead(context, crab) || getInteractingCrab(context) != null)
			{
				actionReadyAt.remove(Action.CRAB);
				pendingCrab = null;
				return;
			}
			attack(context, crab);
			cancelIfStillDue(Action.CRAB);
		}
	}

	/** The action was no longer wanted when its delay ended; its next attempt waits a new delay. */
	private void cancelIfStillDue(Action action)
	{
		if (isDue(action))
		{
			actionReadyAt.remove(action);
		}
	}

	private boolean isDue(Action action)
	{
		Long readyAt = actionReadyAt.get(action);
		return readyAt != null && System.nanoTime() >= readyAt;
	}

	/**
	 * The first call for an action starts its random delay; it returns true
	 * once that delay has passed (and clears it, so the next click waits again).
	 */
	private boolean actionDelayDone(Action action)
	{
		long now = System.nanoTime();
		Long readyAt = actionReadyAt.get(action);
		if (readyAt == null || now - readyAt > ACTION_DELAY_STALE_NS)
		{
			actionReadyAt.put(action, now + randomDelayNanos(ACTION_DELAY_MIN_MS, ACTION_DELAY_MAX_MS));
			return false;
		}
		if (now < readyAt)
		{
			return false;
		}
		actionReadyAt.remove(action);
		return true;
	}

	/** Random delay strictly inside (minMs, maxMs) with sub-millisecond precision, never a round value. */
	private static long randomDelayNanos(long minMs, long maxMs)
	{
		ThreadLocalRandom random = ThreadLocalRandom.current();
		long nanos;
		do
		{
			nanos = random.nextLong(minMs * 1_000_000L + 1, maxMs * 1_000_000L);
		}
		while (nanos % 100_000_000L == 0 || (nanos / 1_000_000L) % 100 == 0);
		return nanos;
	}

	private boolean onBreak()
	{
		if (idleUntil == 0)
		{
			return false;
		}
		if (System.nanoTime() < idleUntil)
		{
			return true;
		}
		idleUntil = 0;
		log.info("[GemCrab] break over");
		return false;
	}

	/**
	 * Every 2-10 minutes while fighting: opens the skills tab and hovers the
	 * skill the current attack style trains for 5-30 seconds. Stops early when
	 * the fight ends or a potion is due.
	 */
	private void skillCheck(RoutineContext context)
	{
		long now = System.nanoTime();
		if (skillHoverUntil != 0)
		{
			if (state != State.FIGHT || isDue(Action.POTION) || now >= skillHoverUntil)
			{
				endSkillCheck(now);
			}
			return;
		}
		if (now < nextSkillCheckAt || state != State.FIGHT || isDue(Action.POTION))
		{
			return;
		}
		Client client = context.getClient();
		GameInput input = context.getInput();
		if (input.isBusy() || input.isCameraBusy() || client.isMenuOpen())
		{
			return;
		}

		Skill skill = trainedSkill(client);
		lastSkill = skill;
		Widget skillWidget = client.getWidget(skillWidgetId(skill));
		if (skillWidget == null || skillWidget.isHidden() || skillWidget.getBounds() == null
			|| skillWidget.getBounds().isEmpty())
		{
			Widget tab = client.getWidget(skillsTabId(client));
			if (++skillTabClicks > 3 || tab == null || tab.isHidden())
			{
				endSkillCheck(now);
				return;
			}
			input.click(tab);
			setStatus("Opening skills tab");
			return;
		}
		if (input.hover(skillWidget))
		{
			skillHoverUntil = now + randomDelayNanos(SKILL_HOVER_MIN_MS, SKILL_HOVER_MAX_MS);
			log.info("[GemCrab] checking {} XP for {} ms", skill.getName(), (skillHoverUntil - now) / 1_000_000L);
			setStatus("Checking " + skill.getName() + " XP");
		}
	}

	private void endSkillCheck(long now)
	{
		skillHoverUntil = 0;
		skillTabClicks = 0;
		nextSkillCheckAt = now + randomDelayNanos(SKILL_CHECK_MIN_MS, SKILL_CHECK_MAX_MS);
	}

	/** The skill the selected attack style trains (Controlled picks one of the three at random). */
	private Skill trainedSkill(Client client)
	{
		try
		{
			int weaponType = client.getVarbitValue(VarbitID.COMBAT_WEAPON_CATEGORY);
			int styleIndex = client.getVarpValue(VarPlayerID.COM_MODE);
			int styleEnum = client.getEnum(EnumID.WEAPON_STYLES).getIntValue(weaponType);
			if (styleEnum != -1)
			{
				int[] styles = client.getEnum(styleEnum).getIntVals();
				if (styleIndex >= 0 && styleIndex < styles.length)
				{
					String name = client.getStructComposition(styles[styleIndex]).getStringValue(ParamID.ATTACK_STYLE_NAME);
					lastStyle = name == null ? "?" : name;
					switch (name == null ? "" : name.toLowerCase(java.util.Locale.ROOT))
					{
						case "accurate":
							return Skill.ATTACK;
						case "aggressive":
							return Skill.STRENGTH;
						case "defensive":
							return Skill.DEFENCE;
						case "controlled":
							Skill[] shared = {Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE};
							return shared[ThreadLocalRandom.current().nextInt(shared.length)];
						case "ranging":
						case "longrange":
							return Skill.RANGED;
						case "casting":
						case "defensive casting":
							return Skill.MAGIC;
						default:
							break;
					}
				}
			}
		}
		catch (RuntimeException ex)
		{
			log.debug("[GemCrab] attack style lookup failed", ex);
		}
		return potionMode.skill;
	}

	private static int skillWidgetId(Skill skill)
	{
		switch (skill)
		{
			case ATTACK:
				return InterfaceID.Stats.ATTACK;
			case DEFENCE:
				return InterfaceID.Stats.DEFENCE;
			case RANGED:
				return InterfaceID.Stats.RANGED;
			case MAGIC:
				return InterfaceID.Stats.MAGIC;
			default:
				return InterfaceID.Stats.STRENGTH;
		}
	}

	private static int skillsTabId(Client client)
	{
		int topLevel = client.getTopLevelInterfaceId();
		if (topLevel == InterfaceID.TOPLEVEL_OSRS_STRETCH)
		{
			return InterfaceID.ToplevelOsrsStretch.STONE1;
		}
		if (topLevel == InterfaceID.TOPLEVEL_PRE_EOC)
		{
			return InterfaceID.ToplevelPreEoc.STONE1;
		}
		return InterfaceID.Toplevel.STONE1;
	}

	private void findCrab(RoutineContext context)
	{
		GameInput input = context.getInput();

		// A menu left over from the cave would swallow the attack click.
		if (context.getClient().isMenuOpen())
		{
			input.closeMenu();
			return;
		}

		NPC fighting = getInteractingCrab(context);
		if (fighting != null)
		{
			target = fighting;
			idleTicks = 0;
			setState(State.FIGHT, "already fighting a crab");
			setStatus("Fighting Gemstone Crab");
			return;
		}

		Optional<NPC> crab = findLiveCrab(context);
		if (crab.isEmpty())
		{
			// No live crab here. Only a kill (or a spawn timeout) allows a
			// crawl; otherwise the crab just has not spawned yet.
			if (crawlArmed)
			{
				setState(State.GO_TO_CAVE, "no crab and a crawl is armed");
				setStatus("Heading to cave");
			}
			else
			{
				waitTicks = 0;
				setState(State.WAIT_FOR_SPAWN, "no live crab yet");
				setStatus("Waiting for crab to spawn");
			}
			return;
		}

		if (input.isBusy() || input.isCameraBusy())
		{
			setStatus("Moving mouse");
			return;
		}

		attack(context, crab.get());
	}

	private void fight(RoutineContext context)
	{
		if (isCrabDead(context, target))
		{
			++kills;
			target = null;
			idleTicks = 0;
			cooldown = POST_KILL_DELAY_TICKS;
			crawlArmed = true;
			crawlClicks = 0;
			ThreadLocalRandom random = ThreadLocalRandom.current();
			if (random.nextInt(20) == 0)
			{
				idleUntil = System.nanoTime() + randomDelayNanos(LONG_BREAK_MIN_MS, LONG_BREAK_MAX_MS);
			}
			else if (random.nextInt(4) == 0)
			{
				idleUntil = System.nanoTime() + randomDelayNanos(SHORT_BREAK_MIN_MS, SHORT_BREAK_MAX_MS);
			}
			if (idleUntil != 0)
			{
				log.info("[GemCrab] break for {} ms after kill {}", (idleUntil - System.nanoTime()) / 1_000_000L, kills);
			}
			setState(State.GO_TO_CAVE, "crab killed (kill " + kills + "), one crawl armed");
			setStatus("Crab dead, heading to cave");
			return;
		}

		Player player = context.getPlayer();
		Actor interacting = player == null ? null : player.getInteracting();
		if (interacting == target || isAnimating(player) || Actions.isMoving(player))
		{
			// Still attacking (or walking up to it). Do not click again.
			idleTicks = 0;
			setStatus("Fighting Gemstone Crab");
			return;
		}

		// The crab is alive but we stopped attacking it, for example after
		// being knocked out of combat. Re-attack after a short grace period.
		if (++idleTicks < REATTACK_IDLE_TICKS)
		{
			setStatus("Fighting Gemstone Crab");
			return;
		}

		GameInput input = context.getInput();
		if (input.isBusy() || input.isCameraBusy())
		{
			setStatus("Moving mouse");
			return;
		}

		idleTicks = 0;
		attack(context, target);
	}

	private void attack(RoutineContext context, NPC crab)
	{
		GameInput input = context.getInput();

		if (!input.isVisible(crab))
		{
			input.turnCameraToward(crab.getWorldLocation());
			cooldown = 2;
			setStatus("Turning camera to crab");
			return;
		}

		if (!actionDelayDone(Action.CRAB))
		{
			pendingCrab = crab;
			setStatus("Attacking Gemstone Crab (delay)");
			return;
		}
		pendingCrab = null;

		// Attack is the crab's left-click option.
		if (input.click(crab))
		{
			target = crab;
			idleTicks = 0;
			cooldown = ATTACK_COOLDOWN_TICKS;
			setState(State.FIGHT, "attack click on crab");
			setStatus("Attacking Gemstone Crab");
		}
		else
		{
			cooldown = 2;
			setStatus("Cannot attack Gemstone Crab");
		}
	}

	/**
	 * Clicks the cave exactly once per armed crawl, then hands over to
	 * {@link State#CRAWLING}, which never clicks.
	 */
	private void goToCave(RoutineContext context)
	{
		Player player = context.getPlayer();
		WorldPoint location = player == null ? null : player.getWorldLocation();
		GameInput input = context.getInput();

		if (!crawlArmed)
		{
			waitTicks = 0;
			setState(State.WAIT_FOR_SPAWN, "no crawl armed");
			return;
		}

		// A live crab is already up here (for example the script was started
		// next to one): fight it instead of leaving.
		if (!context.getClient().isMenuOpen() && findLiveCrab(context).isPresent())
		{
			crawlArmed = false;
			setState(State.FIND_CRAB, "live crab present, crawl not needed");
			return;
		}

		if (crawlClicks >= CRAWL_CLICK_LIMIT)
		{
			crawlArmed = false;
			waitTicks = 0;
			log.info("[GemCrab] {} cave clicks did not start a crawl, waiting for a spawn instead", crawlClicks);
			setState(State.WAIT_FOR_SPAWN, "cave click limit reached");
			return;
		}

		if (input.isBusy() || input.isCameraBusy())
		{
			setStatus("Moving mouse");
			return;
		}

		Optional<TileObject> cave = findCave(context);
		if (cave.isEmpty())
		{
			if (context.getClient().isMenuOpen())
			{
				input.closeMenu();
			}
			cooldown = 2;
			setStatus("Looking for cave");
			return;
		}

		TileObject object = cave.get();
		if (!context.getClient().isMenuOpen() && !input.isVisible(object))
		{
			input.turnCameraToward(object.getWorldLocation());
			cooldown = 2;
			setStatus("Turning camera to cave");
			return;
		}

		// Crawl-through is the cave's left-click option, so this is one
		// click (or one direct menu action), never a menu that needs a
		// second call. Either way the crawl counts as sent right now.
		if (!actionDelayDone(Action.CAVE))
		{
			setStatus("Crawling through cave (delay)");
			return;
		}
		if (input.interact(object, CAVE_ACTION))
		{
			++crawlClicks;
			crawlArmed = false;
			crawlStartLocation = location;
			crawlStarted = false;
			crawlIdleTicks = 0;
			waitTicks = 0;
			cooldown = 1;
			setState(State.CRAWLING, "Crawl-through clicked (click " + crawlClicks + ") from " + location);
			setStatus("Crawling through cave");
			return;
		}

		cooldown = 2;
		setStatus("Cannot crawl through cave");
	}

	/**
	 * Waits for the crawl to finish. Never clicks the cave. Done when the
	 * player teleported (plane change or a big jump), or moved/animated and
	 * then stood still for {@value #CRAWL_SETTLE_TICKS} ticks.
	 */
	private void crawling(RoutineContext context)
	{
		Player player = context.getPlayer();
		WorldPoint location = player == null ? null : player.getWorldLocation();
		++waitTicks;

		boolean teleported = location != null && crawlStartLocation != null
			&& (location.getPlane() != crawlStartLocation.getPlane()
				|| location.distanceTo(crawlStartLocation) >= CRAWL_TELEPORT_DISTANCE);
		boolean active = Actions.isMoving(player) || isAnimating(player);
		if (active || location != null && !location.equals(crawlStartLocation))
		{
			crawlStarted = true;
		}
		crawlIdleTicks = active ? 0 : crawlIdleTicks + 1;

		if (teleported || crawlStarted && crawlIdleTicks >= CRAWL_SETTLE_TICKS || waitTicks >= CRAWL_MAX_TICKS)
		{
			String reason = teleported ? "teleported to " + location
				: crawlStarted ? "moved and settled at " + location : "crawl time cap reached";
			crawlStartLocation = null;
			waitTicks = 0;
			setState(State.WAIT_FOR_SPAWN, "crawl complete: " + reason);
			setStatus("Waiting for crab to spawn");
			return;
		}

		if (!crawlStarted && waitTicks >= CRAWL_NO_START_TICKS)
		{
			// Nothing happened at all: the click did not take. Re-arm the
			// same crawl (capped by CRAWL_CLICK_LIMIT).
			crawlArmed = true;
			crawlStartLocation = null;
			waitTicks = 0;
			setState(State.GO_TO_CAVE, "cave click did nothing, retrying");
			return;
		}

		setStatus("Crawling through cave");
	}

	/**
	 * After a crawl (or at start with no crab): never clicks the cave. Attacks
	 * the crab as soon as a live one appears. After a long wait with no crab,
	 * arms one recovery crawl.
	 */
	private void waitForSpawn(RoutineContext context)
	{
		if (context.getClient().isMenuOpen())
		{
			context.getInput().closeMenu();
			return;
		}

		Optional<NPC> crab = findLiveCrab(context);
		if (crab.isPresent())
		{
			waitTicks = 0;
			crawlClicks = 0;
			setState(State.FIND_CRAB, "crab spawned at " + crab.get().getWorldLocation());
			setStatus("Crab spawned");
			// React right away instead of a tick later.
			GameInput input = context.getInput();
			if (!input.isBusy() && !input.isCameraBusy())
			{
				attack(context, crab.get());
			}
			return;
		}

		if (++waitTicks >= SPAWN_TIMEOUT_TICKS)
		{
			log.info("[GemCrab] no crab spawned within {} ticks, allowing one recovery crawl", SPAWN_TIMEOUT_TICKS);
			waitTicks = 0;
			crawlArmed = true;
			crawlClicks = 0;
			setState(State.GO_TO_CAVE, "spawn timeout, one recovery crawl");
			setStatus("No spawn, heading to cave");
			return;
		}

		setStatus("Waiting for crab to spawn (" + waitTicks + ")");
	}

	private void setState(State next, String reason)
	{
		if (next != state)
		{
			log.info("[GemCrab] {} -> {}: {}", state, next, reason);
		}
		state = next;
	}

	/** @return the nearest Gemstone Crab that is alive (not dying) */
	private static Optional<NPC> findLiveCrab(RoutineContext context)
	{
		WorldPoint location = context.getPlayerLocation();
		WorldView worldView = context.getClient().getTopLevelWorldView();
		if (location == null || worldView == null)
		{
			return Optional.empty();
		}

		java.util.stream.Stream<NPC> npcs = worldView.npcs().stream().map(npc -> (NPC) npc);
		return npcs
			.filter(npc -> npc != null && npc.getId() == CRAB_ID && !npc.isDead())
			.filter(npc -> npc.getWorldLocation() != null
				&& npc.getWorldLocation().distanceTo(location) <= CRAB_SEARCH_DISTANCE)
			.min(Comparator.comparingInt((NPC npc) -> npc.getWorldLocation().distanceTo(location)));
	}

	/**
	 * Presses Space to close the level-up scroll. While it is open the game
	 * keeps the player in the level-up animation, which stops combat.
	 * @return true while the scroll is open, so nothing else runs this tick
	 */
	private boolean dismissLevelUp(RoutineContext context)
	{
		Widget dialog = context.getClient().getWidget(InterfaceID.LevelupDisplay.UNIVERSE);
		if (dialog == null || dialog.isHidden())
		{
			levelUpWaitTicks = 0;
			return false;
		}

		if (levelUpWaitTicks > 0)
		{
			--levelUpWaitTicks;
			return true;
		}

		GameInput input = context.getInput();
		if (!input.isBusy() && !input.isCameraBusy() && actionDelayDone(Action.LEVEL_UP) && input.pressSpace())
		{
			levelUpWaitTicks = 1;
		}
		setStatus("Dismissing level up");
		return true;
	}

	/**
	 * Drinks a potion whenever the matching boost (boosted level minus real
	 * level) is the configured threshold (default 3) or less, which includes
	 * being unboosted. Does nothing when the selected potions are not in the
	 * inventory. Drinking does not interrupt combat, so this runs in every
	 * state, but never while the mouse is moving, the camera is turning, or a
	 * menu is open.
	 */
	private void drinkPotionIfNeeded(RoutineContext context)
	{
		Client client = context.getClient();
		Skill skill = potionMode.skill;
		int boost = client.getBoostedSkillLevel(skill) - client.getRealSkillLevel(skill);
		lastBoost = boost;

		// A divine potion holds its boost for five minutes. Never drink
		// another one during that window, even if the boost reading is odd.
		if (potionMode == PotionMode.DIVINE_RANGING
			&& System.currentTimeMillis() < nextDivineDrinkAt)
		{
			return;
		}

		if (drinkWaitTicks > 0)
		{
			// Wait for the last drink to show up in the boosted level so the
			// same potion is not drunk twice.
			if (boost > boostBeforeDrink)
			{
				drinkWaitTicks = 0;
			}
			else
			{
				--drinkWaitTicks;
				return;
			}
		}

		if (boost > potionThreshold)
		{
			actionReadyAt.remove(Action.POTION);
			return;
		}

		Inventory inventory = context.getInventory();
		int slot = findPotionSlot(inventory);
		if (slot < 0)
		{
			actionReadyAt.remove(Action.POTION);
			return;
		}

		// A human notices the boost has dropped a while before drinking.
		Long readyAt = actionReadyAt.get(Action.POTION);
		if (readyAt == null)
		{
			long delay = randomDelayNanos(POTION_DELAY_MIN_MS, POTION_DELAY_MAX_MS);
			actionReadyAt.put(Action.POTION, System.nanoTime() + delay);
			log.info("[GemCrab] boost {} <= {}, drinking in {} ms", boost, potionThreshold, delay / 1_000_000L);
			return;
		}
		if (System.nanoTime() < readyAt)
		{
			return;
		}

		GameInput input = context.getInput();
		if (input.isBusy() || input.isCameraBusy() || client.isMenuOpen())
		{
			return;
		}

		// The potion slot has no widget until the inventory tab is open.
		if (!inventory.isOpen())
		{
			if (inventory.open())
			{
				setStatus("Opening inventory");
			}
			return;
		}

		// Drink is the potion's left-click option.
		Widget widget = inventory.getSlotWidget(slot);
		if (widget == null)
		{
			return;
		}
		if (input.click(widget))
		{
			actionReadyAt.remove(Action.POTION);
			boostBeforeDrink = boost;
			drinkWaitTicks = DRINK_TIMEOUT_TICKS;
			++potionsDrunk;
			potionThreshold = rollThreshold();
			if (potionMode == PotionMode.DIVINE_RANGING)
			{
				nextDivineDrinkAt = System.currentTimeMillis() + DIVINE_POTION_MS;
			}
			setStatus("Drinking " + potionMode);
		}
	}

	private int findPotionSlot(Inventory inventory)
	{
		for (int potion : potions)
		{
			int slot = inventory.findSlot(potion);
			if (slot >= 0)
			{
				return slot;
			}
		}
		return -1;
	}

	private Optional<TileObject> findCave(RoutineContext context)
	{
		WorldPoint location = context.getPlayerLocation();
		if (location == null)
		{
			return Optional.empty();
		}

		Optional<TileObject> byName = context.getTargets()
			.findObject(CAVE_NAME, CAVE_ACTION, CAVE_SEARCH_DISTANCE)
			.map(object -> (TileObject) object);
		if (byName.isPresent())
		{
			return byName;
		}

		// The cave may be a wall or decorative object rather than a game
		// object, so also search every tile-object layer for its action.
		List<TileObject> nearby = context.getTargets().getTileObjects(location, CAVE_SEARCH_DISTANCE);
		return nearby.stream()
			.filter(object -> isCave(context, object))
			.min(Comparator.comparingInt(object -> object.getWorldLocation() == null
				? Integer.MAX_VALUE
				: object.getWorldLocation().distanceTo(location)));
	}

	private static boolean isCave(RoutineContext context, TileObject object)
	{
		ObjectComposition composition = context.getClient().getObjectDefinition(object.getId());
		if (composition != null && composition.getImpostorIds() != null)
		{
			ObjectComposition impostor = composition.getImpostor();
			if (impostor != null)
			{
				composition = impostor;
			}
		}

		if (composition == null || composition.getActions() == null)
		{
			return false;
		}

		return Arrays.stream(composition.getActions())
			.anyMatch(action -> action != null && action.equalsIgnoreCase(CAVE_ACTION));
	}

	private NPC getInteractingCrab(RoutineContext context)
	{
		Player player = context.getPlayer();
		Actor interacting = player == null ? null : player.getInteracting();
		if (interacting instanceof NPC)
		{
			NPC npc = (NPC) interacting;
			if (npc.getId() == CRAB_ID && !npc.isDead())
			{
				return npc;
			}
		}
		return null;
	}

	private static boolean isCrabDead(RoutineContext context, NPC crab)
	{
		if (crab == null || crab.isDead() || crab.getId() != CRAB_ID)
		{
			// The crab turns into its remains (a different id) when it dies.
			return true;
		}

		WorldView worldView = context.getClient().getTopLevelWorldView();
		if (worldView == null)
		{
			return false;
		}

		// A despawned crab is no longer in the NPC list.
		return worldView.npcs().stream().noneMatch(npc -> npc == crab);
	}

	private static boolean isAnimating(Player player)
	{
		return player != null && player.getAnimation() != -1;
	}


	@Override
	public List<String> getOverlayLines()
	{
		long elapsed = Math.max(1, System.currentTimeMillis() - startedAt);
		long perHour = kills * 3_600_000L / elapsed;
		long now = System.nanoTime();
		List<String> lines = new java.util.ArrayList<>();
		lines.add("State: " + (state == null ? "-" : state.name()) + " | " + getStatus());
		lines.add("Kills: " + kills + " (" + perHour + "/hr), potions: " + potionsDrunk);
		for (Action action : Action.values())
		{
			Long readyAt = actionReadyAt.get(action);
			if (readyAt != null)
			{
				long left = (readyAt - now) / 1_000_000L;
				lines.add("Delay " + action + ": " + (left > 0 ? left + " ms" : "ready"));
			}
		}
		lines.add("Boost: " + potionMode.skill.getName() + " +" + lastBoost + ", drink at <= " + potionThreshold
			+ " (range " + boostMin + "-" + boostMax + ", " + potionMode + ")");
		if (drinkWaitTicks > 0)
		{
			lines.add("Waiting for drink to apply: " + drinkWaitTicks + " ticks");
		}
		if (nextDivineDrinkAt > System.currentTimeMillis())
		{
			lines.add("Divine active: " + (nextDivineDrinkAt - System.currentTimeMillis()) / 1000L + " s left");
		}
		lines.add("Style: " + lastStyle + " -> " + (lastSkill == null ? "-" : lastSkill.getName()));
		lines.add(skillHoverUntil != 0
			? "Hovering skill: " + Math.max(0, (skillHoverUntil - now) / 1_000_000L) + " ms left"
			: "Next skill check: " + Math.max(0, (nextSkillCheckAt - now) / 1_000_000L) + " ms");
		lines.add(idleUntil != 0
			? "Break: " + Math.max(0, (idleUntil - now) / 1_000_000L) + " ms left"
			: "Break: none");
		lines.add("Ticks: cooldown " + cooldown + ", idle " + idleTicks + ", wait " + waitTicks
			+ ", crawl clicks " + crawlClicks);
		return lines;
	}
}
