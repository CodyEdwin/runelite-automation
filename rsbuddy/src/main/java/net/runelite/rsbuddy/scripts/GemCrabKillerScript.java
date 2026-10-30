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
import net.runelite.rsbuddy.script.Script;
import net.runelite.rsbuddy.script.ScriptContext;

/**
 * Kills the Gemstone Crab (NPC 14779), then crawls through the nearby cave to
 * the crab's next spawn location, waits for it to appear, and repeats.
 * <p>
 * Start the script next to the crab or the cave. It never walks on its own
 * except for the walking the game does when a crab or the cave is clicked.
 */
public final class GemCrabKillerScript extends Script
{
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
	/** Ticks to wait for the crawl to move us before clicking the cave again. */
	private static final int CRAWL_TIMEOUT_TICKS = 25;
	/** A location change at least this large means we went through the cave. */
	private static final int CRAWL_TELEPORT_DISTANCE = 10;
	/** Ticks (about 36 seconds) to wait for the crab to spawn after crawling. */
	private static final int SPAWN_TIMEOUT_TICKS = 60;

	/** Anti-idle camera nudges happen every 30 to 60 seconds. */
	private static final long ANTI_IDLE_MIN_MS = 30_000L;
	private static final long ANTI_IDLE_MAX_MS = 60_000L;
	/** Arrow-key hold range for a small camera nudge. */
	private static final int ANTI_IDLE_MIN_HOLD_MS = 150;
	private static final int ANTI_IDLE_MAX_HOLD_MS = 450;

	/** Drink another strength potion once the boost has dropped to this. */
	private static final int STRENGTH_BOOST_THRESHOLD = 3;
	/** Ticks to wait for a drink to register before trying again. */
	private static final int DRINK_TIMEOUT_TICKS = 5;

	/**
	 * Strength potions in the order they are drunk: regular strength
	 * potions lowest dose first, then super strength as a fallback.
	 */
	private static final int[] STRENGTH_POTIONS = {
		ItemID._1DOSE1STRENGTH,  // Strength potion(1)
		ItemID._2DOSE1STRENGTH,  // Strength potion(2)
		ItemID._3DOSE1STRENGTH,  // Strength potion(3)
		ItemID.STRENGTH4,        // Strength potion(4)
		ItemID._1DOSE2STRENGTH,  // Super strength(1)
		ItemID._2DOSE2STRENGTH,  // Super strength(2)
		ItemID._3DOSE2STRENGTH,  // Super strength(3)
		ItemID._4DOSE2STRENGTH   // Super strength(4)
	};

	private enum State
	{
		FIND_CRAB,
		FIGHT,
		GO_TO_CAVE,
		WAIT_FOR_SPAWN
	}

	private State state;
	private int cooldown;
	private NPC target;
	private int idleTicks;
	private int waitTicks;
	private boolean crawlClicked;
	private WorldPoint crawlStartLocation;
	private int kills;
	private long startedAt;
	private long nextAntiIdleAt;
	private int drinkWaitTicks;
	private int boostBeforeDrink;
	private int potionsDrunk;

	public GemCrabKillerScript()
	{
		super("Gem Crab Killer", "Kills the Gemstone Crab (14779), crawls through the cave, and repeats.");
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
		crawlClicked = false;
		crawlStartLocation = null;
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
		ScriptContext context = getContext();
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
			case WAIT_FOR_SPAWN:
				waitForSpawn(context);
				break;
		}
	}

	private void findCrab(ScriptContext context)
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
			state = State.FIGHT;
			setStatus("Fighting Gemstone Crab");
			return;
		}

		Optional<NPC> crab = context.getTargets().findNpc(CRAB_ID, CRAB_SEARCH_DISTANCE);
		if (crab.isEmpty())
		{
			// No live crab here. It has either already died or not spawned
			// yet, so take the cave to the next location.
			state = State.GO_TO_CAVE;
			setStatus("No crab, heading to cave");
			return;
		}

		if (input.isBusy() || input.isCameraBusy())
		{
			setStatus("Moving mouse");
			return;
		}

		attack(context, crab.get());
	}

	private void fight(ScriptContext context)
	{
		if (isCrabDead(context, target))
		{
			++kills;
			target = null;
			idleTicks = 0;
			cooldown = POST_KILL_DELAY_TICKS;
			state = State.GO_TO_CAVE;
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

	private void attack(ScriptContext context, NPC crab)
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
			state = State.FIGHT;
			setStatus("Attacking Gemstone Crab");
		}
		else
		{
			cooldown = 2;
			setStatus("Cannot attack Gemstone Crab");
		}
	}

	private void goToCave(ScriptContext context)
	{
		Player player = context.getPlayer();
		WorldPoint location = player == null ? null : player.getWorldLocation();
		GameInput input = context.getInput();

		if (crawlClicked)
		{
			if (hasTeleported(location))
			{
				crawlClicked = false;
				crawlStartLocation = null;
				waitTicks = 0;
				state = State.WAIT_FOR_SPAWN;
				setStatus("Waiting for crab to spawn");
				return;
			}

			// Walking to the cave and the crawl animation take a few ticks.
			if (++waitTicks < CRAWL_TIMEOUT_TICKS)
			{
				setStatus("Crawling through cave");
				return;
			}

			// The click did not take. Try the cave again.
			crawlClicked = false;
			crawlStartLocation = null;
		}

		// A new crab may already be up at this location (e.g. the script was
		// started while one was alive). Fight it instead of leaving.
		if (!context.getClient().isMenuOpen()
			&& context.getTargets().findNpc(CRAB_ID, CRAB_SEARCH_DISTANCE).isPresent())
		{
			state = State.FIND_CRAB;
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
		boolean menuOpen = context.getClient().isMenuOpen();
		if (!menuOpen && !input.isVisible(object))
		{
			input.turnCameraToward(object.getWorldLocation());
			cooldown = 2;
			setStatus("Turning camera to cave");
			return;
		}

		// interact() right-clicks the cave first, then picks Crawl-through
		// from the opened menu on the next call.
		if (input.interact(object, CAVE_ACTION))
		{
			if (menuOpen)
			{
				crawlClicked = true;
				crawlStartLocation = location;
				waitTicks = 0;
				cooldown = 1;
				setStatus("Crawling through cave");
			}
			else
			{
				setStatus("Opening cave menu");
			}
			return;
		}

		cooldown = 2;
		setStatus("Cannot crawl through cave");
	}

	private void waitForSpawn(ScriptContext context)
	{
		if (context.getClient().isMenuOpen())
		{
			context.getInput().closeMenu();
			return;
		}

		Optional<NPC> crab = context.getTargets().findNpc(CRAB_ID, CRAB_SEARCH_DISTANCE);
		if (crab.isPresent())
		{
			waitTicks = 0;
			state = State.FIND_CRAB;
			setStatus("Crab spawned");
			return;
		}

		if (++waitTicks >= SPAWN_TIMEOUT_TICKS)
		{
			// No crab showed up. Move on through the cave again.
			waitTicks = 0;
			state = State.GO_TO_CAVE;
			setStatus("No spawn, heading to cave");
			return;
		}

		setStatus("Waiting for crab to spawn (" + waitTicks + ")");
	}

	/**
	 * Drinks a strength potion whenever the Strength boost (boosted level
	 * minus real level) is {@value #STRENGTH_BOOST_THRESHOLD} or less, which
	 * includes being unboosted. Does nothing when no strength potions are in
	 * the inventory. Drinking does not interrupt combat, so this runs in
	 * every state, but never while the mouse is moving, the camera is
	 * turning, or a menu is open.
	 */
	private void drinkStrengthIfNeeded(ScriptContext context)
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

		if (boost > STRENGTH_BOOST_THRESHOLD)
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

	private static int findStrengthPotionSlot(Inventory inventory)
	{
		for (int potion : STRENGTH_POTIONS)
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
	private void antiIdle(ScriptContext context)
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

	private boolean hasTeleported(WorldPoint location)
	{
		if (location == null || crawlStartLocation == null)
		{
			return false;
		}

		return location.getPlane() != crawlStartLocation.getPlane()
			|| location.distanceTo(crawlStartLocation) >= CRAWL_TELEPORT_DISTANCE;
	}

	private Optional<TileObject> findCave(ScriptContext context)
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

	private static boolean isCave(ScriptContext context, TileObject object)
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

	private NPC getInteractingCrab(ScriptContext context)
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

	private static boolean isCrabDead(ScriptContext context, NPC crab)
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
			"Strength potions: " + potionsDrunk);
	}
}
