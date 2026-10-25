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

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.widgets.Widget;
import net.runelite.rsbuddy.input.GameInput;
import net.runelite.rsbuddy.inventory.Inventory;
import net.runelite.rsbuddy.script.Script;
import net.runelite.rsbuddy.script.ScriptContext;

/**
 * Fishes trout and salmon at Barbarian Village, cooks them on the nearby fire,
 * and drops the results.
 * <p>
 * The script expects the player to already be at the Barbarian Village fishing
 * spot. It never walks. If the fishing spot or fire is off screen, the camera
 * is turned toward it before clicking.
 */
public final class BarbarianFisherCookerScript extends Script
{
	private static final int FISHING_SPOT_ID = NpcID._0_48_53_FRESHFISH;
	private static final int FIRE_ID = ObjectID.FIRE_COOK;

	private static final String FISH_ACTION = "Lure";
	private static final String COOK_ACTION = "Cook";

	private static final int FISHING_DISTANCE = 20;
	private static final int FIRE_DISTANCE = 20;

	private static final int CLICK_COOLDOWN = 3;
	private static final int MAX_CAMERA_PULSES = 60;
	private static final int COOK_STABLE_TICKS = 2;
	private static final int DROP_BATCH_SIZE = 2;

	// After clicking a fishing spot, wait for the fishing animation instead of
	// clicking again while the player is still walking to the spot.
	private static final long FISHING_START_TIMEOUT_MS = 8000L;
	private static final long FISHING_ANIMATION_GAP_MS = 4000L;

	// After clicking the fire, wait for the cooking interface or animation
	// instead of clicking the fire again while the player is walking to it.
	private static final long FIRE_INTERACTION_TIMEOUT_MS = 8000L;

	private int cooldown;
	private int lastRawFish;
	private int rawStableTicks;
	private boolean spacePressed;
	private int cameraAttempts;
	private int lastCameraTarget = -1;

	private boolean awaitingFishingStart;
	private boolean fishingAnimationSeen;
	private long lastFishingClickAt;
	private long lastFishingAnimationAt;

	private boolean awaitingFireInteraction;
	private long lastFireClickAt;

	private boolean statsInitialized;
	private long statsStartedAt;
	private int lastRawTrout;
	private int lastRawSalmon;
	private int lastCookedTrout;
	private int lastCookedSalmon;
	private int troutCaught;
	private int salmonCaught;
	private int troutCooked;
	private int salmonCooked;
	private long startFishingXp;
	private long startCookingXp;

	public BarbarianFisherCookerScript()
	{
		super("Barbarian Fisher & Cooker",
			"Fishes trout and salmon at Barbarian Village, cooks them on the fire, and drops them.");
	}

	@Override
	protected void onStart()
	{
		cooldown = 0;
		lastRawFish = -1;
		rawStableTicks = 0;
		spacePressed = false;
		cameraAttempts = 0;
		lastCameraTarget = -1;
		resetFishingAttempt();
		resetFireInteraction();
		resetStats();
		setStatus("Ready");
	}

	@Override
	protected void onGameTick()
	{
		ScriptContext context = getContext();
		Inventory inventory = context.getInventory();

		if (!context.isLoggedIn())
		{
			setStatus("Waiting for login");
			return;
		}

		updateStats();

		if (cooldown > 0)
		{
			--cooldown;
			return;
		}

		int rawFish = inventory.countRawFish();
		int droppableFish = inventory.countDroppableFish();
		int emptySlots = inventory.getEmptySlots();

		if (rawFish == 0)
		{
			lastRawFish = 0;
			rawStableTicks = 0;
			spacePressed = false;
			cameraAttempts = 0;
			lastCameraTarget = -1;
		}

		if (rawFish > 0 && (emptySlots == 0 || inventory.hasCookedFish()))
		{
			cook(context, rawFish);
		}
		else if (rawFish > 0)
		{
			fish(context);
		}
		else if (droppableFish > 0)
		{
			drop(context, inventory);
		}
		else
		{
			fish(context);
		}
	}

	@Override
	public List<String> getOverlayLines()
	{
		if (!statsInitialized)
		{
			return Collections.emptyList();
		}

		Client client = getContext().getClient();
		long elapsed = Math.max(1L, System.currentTimeMillis() - statsStartedAt);

		List<String> lines = new ArrayList<>();
		lines.add("");
		lines.add("Fishing");
		lines.add("Trout caught: " + troutCaught + " (" + formatPerHour(troutCaught, elapsed) + "/hr)");
		lines.add("Salmon caught: " + salmonCaught + " (" + formatPerHour(salmonCaught, elapsed) + "/hr)");
		lines.add(formatXpToNextLevel(client, Skill.FISHING));
		lines.add(formatTimeToNextLevel(client, Skill.FISHING, startFishingXp, elapsed));
		lines.add("");
		lines.add("Cooking");
		lines.add("Trout cooked: " + troutCooked + " (" + formatPerHour(troutCooked, elapsed) + "/hr)");
		lines.add("Salmon cooked: " + salmonCooked + " (" + formatPerHour(salmonCooked, elapsed) + "/hr)");
		lines.add(formatXpToNextLevel(client, Skill.COOKING));
		lines.add(formatTimeToNextLevel(client, Skill.COOKING, startCookingXp, elapsed));
		return lines;
	}

	private void fish(ScriptContext context)
	{
		GameInput input = context.getInput();
		Player player = context.getPlayer();

		if (input.isBusy())
		{
			setStatus("Moving mouse");
			return;
		}

		long now = System.currentTimeMillis();

		if (player.getAnimation() != -1)
		{
			// Fishing has actually started. Do not click the spot again until
			// the animation has been gone for a while.
			fishingAnimationSeen = true;
			awaitingFishingStart = false;
			lastFishingAnimationAt = now;
			setStatus("Fishing");
			return;
		}

		if (awaitingFishingStart)
		{
			// The click was just made. The player may still be walking to the
			// spot, so give it time to start fishing before clicking again.
			if (now - lastFishingClickAt < FISHING_START_TIMEOUT_MS)
			{
				setStatus("Fishing");
				return;
			}

			awaitingFishingStart = false;
		}
		else if (fishingAnimationSeen)
		{
			// The animation briefly disappears between fishing cycles. Only
			// treat a longer gap as the end of the fishing session.
			if (now - lastFishingAnimationAt < FISHING_ANIMATION_GAP_MS)
			{
				setStatus("Fishing");
				return;
			}

			fishingAnimationSeen = false;
		}

		Optional<NPC> visibleSpot = context.getTargets().findVisibleNpc(FISHING_SPOT_ID, FISHING_DISTANCE);
		if (visibleSpot.isPresent())
		{
			NPC spot = visibleSpot.get();
			input.stopCamera();
			cameraAttempts = 0;
			lastCameraTarget = -1;

			boolean clicked;
			if (isActionFirst(spot, FISH_ACTION))
			{
				clicked = input.click(spot);
			}
			else
			{
				clicked = input.interact(spot, FISH_ACTION);
			}

			if (clicked)
			{
				noteFishingClick();
				cooldown = CLICK_COOLDOWN;
				setStatus("Fishing");
			}
			else
			{
				cooldown = 1;
				setStatus("Waiting for fishing spot");
			}

			return;
		}

		Optional<NPC> spot = context.getTargets().findNpc(FISHING_SPOT_ID, FISHING_DISTANCE);
		if (spot.isPresent())
		{
			NPC fishingSpot = spot.get();
			int target = worldHash(fishingSpot.getWorldLocation());
			if (target != lastCameraTarget)
			{
				lastCameraTarget = target;
				cameraAttempts = 0;
			}

			if (cameraAttempts < MAX_CAMERA_PULSES
				&& (input.isCameraBusy() || input.turnCameraToward(fishingSpot.getWorldLocation())))
			{
				// Hold an arrow key to rotate toward the spot, and re-check
				// visibility every tick while the camera turns.
				++cameraAttempts;
				setStatus("Turning camera to fishing spot");
				return;
			}

			if (cameraAttempts >= MAX_CAMERA_PULSES)
			{
				input.stopCamera();
			}

			if (input.interact(fishingSpot, FISH_ACTION))
			{
				noteFishingClick();
				cameraAttempts = 0;
				cooldown = CLICK_COOLDOWN;
				setStatus("Fishing");
			}
			else
			{
				cooldown = 2;
				setStatus("Cannot see fishing spot");
			}

			return;
		}

		cameraAttempts = 0;
		lastCameraTarget = -1;
		cooldown = 2;
		setStatus("No fishing spot nearby - move to Barbarian Village");
	}

	private void cook(ScriptContext context, int rawFish)
	{
		// Any fishing session is over once cooking starts.
		resetFishingAttempt();

		GameInput input = context.getInput();
		Widget skillMulti = context.getTargets().findWidget(InterfaceID.Skillmulti.UNIVERSE).orElse(null);

		if (skillMulti != null)
		{
			// The interface opened, so the fire click worked. Keep the guard
			// alive until cooking actually starts, so the fire is not clicked
			// again the moment the interface closes.
			extendFireInteraction();

			// The cooking interface lists the raw fish and quantity buttons.
			// Space selects the first fish. If the interface is still open on
			// the next tick, press the All quantity button to finish the batch.
			if (!spacePressed)
			{
				if (input.pressSpace())
				{
					spacePressed = true;
					cooldown = 1;
					setStatus("Pressing space to select fish");
				}
				return;
			}

			Widget allButton = context.getTargets().findWidget(InterfaceID.Skillmulti.ALL).orElse(null);
			if (allButton != null && input.click(allButton))
			{
				cooldown = 2;
				setStatus("Choosing Cook All");
			}
			return;
		}

		Widget options = context.getTargets().findWidget(InterfaceID.Chatmenu.OPTIONS).orElse(null);

		if (options != null)
		{
			// The cooking dialog opened, so the fire click worked. Keep the
			// guard alive until the fish selection actually starts cooking.
			extendFireInteraction();
			handleCookDialog(context, options);
			return;
		}

		spacePressed = false;

		if (rawFish != lastRawFish)
		{
			awaitingFireInteraction = false;
			lastRawFish = rawFish;
			rawStableTicks = 0;
			setStatus("Cooking (" + rawFish + " raw left)");
			return;
		}

		if (rawStableTicks < Integer.MAX_VALUE)
		{
			++rawStableTicks;
		}

		if (context.getPlayer().getAnimation() != -1)
		{
			awaitingFireInteraction = false;
			setStatus("Cooking (" + rawFish + " raw left)");
			return;
		}

		if (rawStableTicks < COOK_STABLE_TICKS)
		{
			setStatus("Waiting for cooking");
			return;
		}

		if (input.isBusy())
		{
			setStatus("Moving mouse");
			return;
		}

		if (awaitingFireInteraction)
		{
			// The fire was just clicked. It may take a moment for the player
			// to walk over or for the cooking interface to open, so give it
			// time before clicking the fire again.
			if (System.currentTimeMillis() - lastFireClickAt < FIRE_INTERACTION_TIMEOUT_MS)
			{
				setStatus("Waiting for fire");
				return;
			}

			awaitingFireInteraction = false;
		}

		Optional<TileObject> visibleFire = context.getTargets().findVisibleTileObject(FIRE_ID, FIRE_DISTANCE);
		if (visibleFire.isPresent())
		{
			TileObject fire = visibleFire.get();
			input.stopCamera();
			cameraAttempts = 0;
			lastCameraTarget = -1;

			boolean clicked;
			if (isActionFirst(fire, COOK_ACTION, context.getClient()))
			{
				clicked = input.click(fire);
			}
			else
			{
				clicked = input.interact(fire, COOK_ACTION);
			}

			if (clicked)
			{
				noteFireClick();
				lastRawFish = rawFish;
				rawStableTicks = 0;
				spacePressed = false;
				cooldown = CLICK_COOLDOWN;
				setStatus("Clicking fire");
			}
			else
			{
				cooldown = 1;
				setStatus("Waiting for fire");
			}

			return;
		}

		Optional<TileObject> fire = context.getTargets().findTileObject(FIRE_ID, FIRE_DISTANCE);
		if (fire.isPresent())
		{
			TileObject cookingFire = fire.get();
			int target = worldHash(cookingFire.getWorldLocation());
			if (target != lastCameraTarget)
			{
				lastCameraTarget = target;
				cameraAttempts = 0;
			}

			if (cameraAttempts < MAX_CAMERA_PULSES
				&& (input.isCameraBusy() || input.turnCameraToward(cookingFire.getWorldLocation())))
			{
				++cameraAttempts;
				setStatus("Turning camera to fire");
				return;
			}

			if (cameraAttempts >= MAX_CAMERA_PULSES)
			{
				input.stopCamera();
			}

			if (input.interact(cookingFire, COOK_ACTION))
			{
				noteFireClick();
				cameraAttempts = 0;
				lastRawFish = rawFish;
				rawStableTicks = 0;
				spacePressed = false;
				cooldown = CLICK_COOLDOWN;
				setStatus("Clicking fire");
			}
			else
			{
				cooldown = 2;
				setStatus("Cannot see fire");
			}

			return;
		}

		cameraAttempts = 0;
		lastCameraTarget = -1;
		cooldown = 2;
		setStatus("No fire nearby");
	}

	private void handleCookDialog(ScriptContext context, Widget options)
	{
		GameInput input = context.getInput();
		Widget[] children = options.getChildren();
		Widget firstOption = null;
		Widget allOption = null;

		if (children != null)
		{
			for (Widget child : children)
			{
				if (child == null || child.isHidden())
				{
					continue;
				}

				Rectangle bounds = child.getBounds();
				String text = cleanText(child.getText());
				if (bounds == null || bounds.isEmpty() || text.isEmpty())
				{
					continue;
				}

				if (firstOption == null)
				{
					firstOption = child;
				}

				if (text.toLowerCase().contains("all"))
				{
					allOption = child;
					break;
				}
			}
		}

		if (firstOption == null)
		{
			cooldown = 1;
			setStatus("Waiting for cooking options");
			return;
		}

		if (allOption != null)
		{
			// If Cook All is the first option, the space bar selects it. If it
			// is not first, click it directly so we do not cook one fish at a
			// time.
			if (allOption == firstOption && !spacePressed)
			{
				if (input.pressSpace())
				{
					spacePressed = true;
					cooldown = 1;
					setStatus("Pressing space for Cook All");
				}
				return;
			}

			if (input.click(allOption))
			{
				spacePressed = true;
				cooldown = 2;
				setStatus("Choosing Cook All");
			}
			return;
		}

		// No All option yet. Press space to advance to the next selection
		// dialog (for example, choosing which raw fish to cook).
		if (input.pressSpace())
		{
			spacePressed = true;
			cooldown = 1;
			setStatus("Pressing space");
		}
	}

	private void drop(ScriptContext context, Inventory inventory)
	{
		// Dropping happens after fishing, so start fresh next time.
		resetFishingAttempt();

		GameInput input = context.getInput();

		if (!inventory.isOpen())
		{
			if (inventory.open())
			{
				cooldown = 2;
				setStatus("Opening inventory");
			}
			else
			{
				cooldown = 2;
				setStatus("Open your inventory");
			}
			return;
		}

		if (input.isBusy())
		{
			setStatus("Moving mouse");
			return;
		}

		List<Integer> slots = inventory.findDroppableFishSlots();
		List<Widget> items = new ArrayList<>();

		for (int slot : slots)
		{
			if (items.size() >= DROP_BATCH_SIZE)
			{
				break;
			}

			Widget item = inventory.getSlotWidget(slot);
			if (item == null || item.isHidden() || item.getBounds().isEmpty())
			{
				continue;
			}

			items.add(item);
		}

		int dropped = input.fastClick(items);

		if (dropped > 0)
		{
			// Two drops per tick is about twice as fast as dropping one fish
			// per tick.
			cooldown = 0;
			setStatus("Dropping fish");
		}
		else
		{
			cooldown = 1;
			setStatus("Waiting for inventory");
		}
	}

	private void noteFishingClick()
	{
		awaitingFishingStart = true;
		fishingAnimationSeen = false;
		lastFishingClickAt = System.currentTimeMillis();
	}

	private void resetFishingAttempt()
	{
		awaitingFishingStart = false;
		fishingAnimationSeen = false;
		lastFishingClickAt = 0;
		lastFishingAnimationAt = 0;
	}

	private void noteFireClick()
	{
		awaitingFireInteraction = true;
		lastFireClickAt = System.currentTimeMillis();
	}

	private void extendFireInteraction()
	{
		// Called while the cooking menus are open. Refresh the timestamp so
		// the timeout only counts time spent without any cooking feedback.
		awaitingFireInteraction = true;
		lastFireClickAt = System.currentTimeMillis();
	}

	private void resetFireInteraction()
	{
		awaitingFireInteraction = false;
		lastFireClickAt = 0;
	}

	private void resetStats()
	{
		statsInitialized = false;
		statsStartedAt = 0;
		lastRawTrout = 0;
		lastRawSalmon = 0;
		lastCookedTrout = 0;
		lastCookedSalmon = 0;
		troutCaught = 0;
		salmonCaught = 0;
		troutCooked = 0;
		salmonCooked = 0;
		startFishingXp = 0;
		startCookingXp = 0;
	}

	private void updateStats()
	{
		Inventory inventory = getContext().getInventory();
		Client client = getContext().getClient();

		if (!statsInitialized)
		{
			statsInitialized = true;
			statsStartedAt = System.currentTimeMillis();
			lastRawTrout = inventory.countItem(ItemID.RAW_TROUT);
			lastRawSalmon = inventory.countItem(ItemID.RAW_SALMON);
			lastCookedTrout = inventory.countItem(ItemID.TROUT);
			lastCookedSalmon = inventory.countItem(ItemID.SALMON);
			startFishingXp = client.getSkillExperience(Skill.FISHING);
			startCookingXp = client.getSkillExperience(Skill.COOKING);
			return;
		}

		int rawTrout = inventory.countItem(ItemID.RAW_TROUT);
		int rawSalmon = inventory.countItem(ItemID.RAW_SALMON);
		int cookedTrout = inventory.countItem(ItemID.TROUT);
		int cookedSalmon = inventory.countItem(ItemID.SALMON);

		if (rawTrout > lastRawTrout)
		{
			troutCaught += rawTrout - lastRawTrout;
		}

		if (rawSalmon > lastRawSalmon)
		{
			salmonCaught += rawSalmon - lastRawSalmon;
		}

		if (cookedTrout > lastCookedTrout)
		{
			troutCooked += cookedTrout - lastCookedTrout;
		}

		if (cookedSalmon > lastCookedSalmon)
		{
			salmonCooked += cookedSalmon - lastCookedSalmon;
		}

		lastRawTrout = rawTrout;
		lastRawSalmon = rawSalmon;
		lastCookedTrout = cookedTrout;
		lastCookedSalmon = cookedSalmon;
	}

	private static String formatPerHour(int amount, long elapsedMs)
	{
		long perHour = elapsedMs <= 0 ? 0 : Math.round(amount * 3_600_000.0 / elapsedMs);
		return formatNumber(perHour);
	}

	private static String formatXpToNextLevel(Client client, Skill skill)
	{
		int level = client.getRealSkillLevel(skill);
		if (level >= Experience.MAX_REAL_LEVEL)
		{
			return "XP to level 99: max";
		}

		long xp = client.getSkillExperience(skill);
		long remaining = Math.max(0L, Experience.getXpForLevel(level + 1) - xp);
		return "XP to level " + (level + 1) + ": " + formatNumber(remaining);
	}

	private static String formatTimeToNextLevel(Client client, Skill skill, long startXp, long elapsedMs)
	{
		int level = client.getRealSkillLevel(skill);
		if (level >= Experience.MAX_REAL_LEVEL)
		{
			return "Time to level 99: max";
		}

		long xp = client.getSkillExperience(skill);
		long remaining = Math.max(0L, Experience.getXpForLevel(level + 1) - xp);
		long gained = Math.max(0L, xp - startXp);
		if (gained <= 0 || elapsedMs < 10_000L)
		{
			return "Time to level " + (level + 1) + ": --";
		}

		long xpPerHour = Math.round(gained * 3_600_000.0 / elapsedMs);
		if (xpPerHour <= 0)
		{
			return "Time to level " + (level + 1) + ": --";
		}

		long millis = Math.round(remaining * 3_600_000.0 / xpPerHour);
		return "Time to level " + (level + 1) + ": " + formatDuration(millis)
			+ " (" + formatNumber(xpPerHour) + " xp/hr)";
	}

	private static String formatDuration(long millis)
	{
		long seconds = Math.max(0L, millis / 1000L);
		long hours = seconds / 3600L;
		long minutes = (seconds % 3600L) / 60L;
		long remainingSeconds = seconds % 60L;

		if (hours > 0)
		{
			return String.format(Locale.ROOT, "%dh %02dm", hours, minutes);
		}

		if (minutes > 0)
		{
			return String.format(Locale.ROOT, "%dm %02ds", minutes, remainingSeconds);
		}

		return remainingSeconds + "s";
	}

	private static String formatNumber(long value)
	{
		return String.format(Locale.ROOT, "%,d", value);
	}

	private static boolean isActionFirst(TileObject object, String action, Client client)
	{
		ObjectComposition composition = client.getObjectDefinition(object.getId());
		return composition != null && isActionFirst(composition.getActions(), action);
	}

	private static boolean isActionFirst(NPC npc, String action)
	{
		NPCComposition composition = npc.getComposition();
		return composition != null && isActionFirst(composition.getActions(), action);
	}

	private static boolean isActionFirst(String[] actions, String action)
	{
		if (actions == null)
		{
			return false;
		}

		for (String candidate : actions)
		{
			if (candidate == null || candidate.trim().isEmpty())
			{
				continue;
			}

			return cleanText(candidate).equalsIgnoreCase(action);
		}

		return false;
	}

	private static String cleanText(String text)
	{
		if (text == null)
		{
			return "";
		}

		return text.replaceAll("<[^>]*>", "")
			.replace('\u00a0', ' ')
			.trim();
	}

	private static int worldHash(WorldPoint point)
	{
		return point == null ? 0 : (point.getX() << 16) ^ point.getY() ^ (point.getPlane() << 24);
	}
}
