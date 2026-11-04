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
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.widgets.Widget;
import net.runelite.rsbuddy.input.GameInput;
import net.runelite.rsbuddy.inventory.Inventory;
import net.runelite.rsbuddy.routine.Routine;
import net.runelite.rsbuddy.routine.RoutineContext;

/**
 * Kills the Gemstone Crab (NPC 14779), then crawls through the nearby cave to
 * the crab's next spawn location, waits for it to appear, and repeats.
 * <p>
 * Start the script next to the crab or the cave. It never walks on its own
 * except for the walking the game does when a crab or the cave is clicked.
 */
public final class GemCrabKillerScript extends Routine
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GemCrabKillerScript.class);

	private static final int CRAB_ID = NpcID.GEMSTONE_CRAB; // 14779
	private static final String ATTACK_ACTION = "Attack";
	private static final String CAVE_NAME = "Cave";
	private static final String CAVE_ACTION = "Crawl-through";

	private static final int CRAB_SEARCH_DISTANCE = 20;
	private static final int CAVE_SEARCH_DISTANCE = 20;

	/** Ticks to wait after an attack click before re-evaluating. */
	private static final int ATTACK_COOLDOWN_TICKS = 3;
	/** Ticks with no interaction while the crab is alive before re-attacking. */
	private static final int REATTACK_IDLE_TICKS = 4;
	/** Ticks after the crab dies before heading for the cave. */
	private static final int POST_KILL_DELAY_TICKS = 2;
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

	/** Anti-idle camera nudges happen every 30 to 60 seconds. */
	private static final long ANTI_IDLE_MIN_MS = 30_000L;
	private static final long ANTI_IDLE_MAX_MS = 60_000L;
	/** Arrow-key hold range for a small camera nudge. */
	private static final int ANTI_IDLE_MIN_HOLD_MS = 150;
	private static final int ANTI_IDLE_MAX_HOLD_MS = 450;

	/** Default: drink another strength potion once the boost has dropped to this. */
	private static final int DEFAULT_BOOST_THRESHOLD = 3;
	/** Ticks to wait for a drink to register before trying again. */
	private static final int DRINK_TIMEOUT_TICKS = 5;

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

	/**
	 * Which potions are drunk. Matches the plugin's GemCrabPotionMode by
	 * name, so the option survives a script hot-reload.
	 */
	private enum PotionMode
	{
		STRENGTH,
		SUPER_STRENGTH,
		EITHER
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
	private long nextAntiIdleAt;
	private int drinkWaitTicks;
	private int boostBeforeDrink;
	private int potionsDrunk;

	private final PotionMode potionMode;
	private final int boostThreshold;
	/** Potions in the order they are drunk for the chosen mode. */
	private final int[] strengthPotions;

	public GemCrabKillerScript()
	{
		this(PotionMode.EITHER.name(), DEFAULT_BOOST_THRESHOLD);
	}

	/**
	 * @param potionMode STRENGTH, SUPER_STRENGTH or EITHER (super strength
	 * first, then regular); anything else means EITHER
	 * @param boostThreshold drink again once the Strength boost is this or less
	 */
	public GemCrabKillerScript(String potionMode, int boostThreshold)
	{
		super("Gem Crab Killer", "Kills the Gemstone Crab (14779), crawls through the cave, and repeats.");
		this.potionMode = parseMode(potionMode);
		this.boostThreshold = Math.max(0, boostThreshold);
		this.strengthPotions = potionOrder(this.potionMode);
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
		scheduleAntiIdle();
		drinkWaitTicks = 0;
		boostBeforeDrink = 0;
		potionsDrunk = 0;
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

		drinkStrengthIfNeeded(context);
		antiIdle(context);

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
			setState(State.GO_TO_CAVE, "crab killed (kill " + kills + "), one crawl armed");
			setStatus("Crab dead, heading to cave");
			return;
		}

		Player player = context.getPlayer();
		Actor interacting = player == null ? null : player.getInteracting();
		if (interacting == target || isAnimating(player) || isMoving(player))
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

		if (input.interact(crab, ATTACK_ACTION))
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
		boolean active = isMoving(player) || isAnimating(player);
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
	 * Drinks a strength potion whenever the Strength boost (boosted level
	 * minus real level) is the configured threshold (default 3) or less, which
	 * includes being unboosted. Does nothing when no strength potions are in
	 * the inventory. Drinking does not interrupt combat, so this runs in
	 * every state, but never while the mouse is moving, the camera is
	 * turning, or a menu is open.
	 */
	private void drinkStrengthIfNeeded(RoutineContext context)
	{
		Client client = context.getClient();
		int boost = client.getBoostedSkillLevel(Skill.STRENGTH) - client.getRealSkillLevel(Skill.STRENGTH);

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

		if (boost > boostThreshold)
		{
			return;
		}

		Inventory inventory = context.getInventory();
		int slot = findStrengthPotionSlot(inventory);
		if (slot < 0)
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
		if (widget != null && input.click(widget))
		{
			boostBeforeDrink = boost;
			drinkWaitTicks = DRINK_TIMEOUT_TICKS;
			++potionsDrunk;
			setStatus("Drinking strength potion");
		}
	}

	private int findStrengthPotionSlot(Inventory inventory)
	{
		for (int potion : strengthPotions)
		{
			int slot = inventory.findSlot(potion);
			if (slot >= 0)
			{
				return slot;
			}
		}
		return -1;
	}

	/**
	 * Keeps the client from idling out by rotating the camera a small random
	 * amount left or right every 30 to 60 seconds. It only fires while the
	 * mouse is idle and no menu is open, so it never interrupts a click; the
	 * held arrow key also makes the attack and cave logic wait until the
	 * nudge has finished.
	 */
	private void antiIdle(RoutineContext context)
	{
		if (System.currentTimeMillis() < nextAntiIdleAt)
		{
			return;
		}

		GameInput input = context.getInput();
		if (input.isBusy() || input.isCameraBusy() || context.getClient().isMenuOpen())
		{
			// Try again on a later tick once the current action is done.
			return;
		}

		ThreadLocalRandom random = ThreadLocalRandom.current();
		boolean left = random.nextBoolean();
		int holdMillis = random.nextInt(ANTI_IDLE_MIN_HOLD_MS, ANTI_IDLE_MAX_HOLD_MS + 1);
		if (input.nudgeCamera(left, holdMillis))
		{
			scheduleAntiIdle();
		}
	}

	private void scheduleAntiIdle()
	{
		nextAntiIdleAt = System.currentTimeMillis()
			+ ThreadLocalRandom.current().nextLong(ANTI_IDLE_MIN_MS, ANTI_IDLE_MAX_MS + 1);
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

	private static boolean isMoving(Player player)
	{
		return player != null && player.getPoseAnimation() != player.getIdlePoseAnimation();
	}

	@Override
	public List<String> getOverlayLines()
	{
		long elapsed = Math.max(1, System.currentTimeMillis() - startedAt);
		long perHour = kills * 3_600_000L / elapsed;
		return Arrays.asList(
			"State: " + (state == null ? "-" : state.name()),
			"Kills: " + kills + " (" + perHour + "/hr)",
			"Strength potions: " + potionsDrunk + " (" + potionMode + ", at boost <= " + boostThreshold + ")");
	}
}
