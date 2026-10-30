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

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.Item;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
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

	// How many ticks to wait for a drop to show up in the inventory before
	// trying it again.
	private static final int DROP_RETRY_TICKS = 3;

	private static final int FISHING_DISTANCE = 20;
	private static final int FIRE_DISTANCE = 20;

	private static final int CLICK_COOLDOWN = 3;
	private static final int MAX_CAMERA_PULSES = 60;
	private static final int COOK_STABLE_TICKS = 2;

	// Default camera: fully zoomed out, pitched all the way up, facing west.
	// The camera is moved with the same keys and mouse wheel a player uses.
	private static final int CAMERA_ZOOM_NOTCHES = 60;
	private static final int CAMERA_MAX_PITCH = 383;
	private static final int CAMERA_PITCH_TOLERANCE = 4;
	private static final int CAMERA_HOLD_MS = 800;
	private static final int CAMERA_WEST_YAW = 4096;
	private static final int CAMERA_YAW_TOLERANCE = 256;
	private static final int CAMERA_SCALE_TOLERANCE = 4;
	private static final long CAMERA_IDLE_RESET_MS = 10_000L;

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

	private boolean cameraSetupActive;
	private int cameraPhase;
	private boolean zoomStarted;
	private int defaultCameraScale;
	private long lastCameraMaintenanceAt;
	private int pitchKey = KeyEvent.VK_UP;
	private int pitchBeforeHold = -1;
	private boolean pitchHoldPending;
	private boolean pitchDirectionLearned;

	private boolean awaitingFishingStart;
	private boolean fishingAnimationSeen;
	private long lastFishingClickAt;
	private long lastFishingAnimationAt;

	private boolean awaitingFireInteraction;
	private long lastFireClickAt;

	private int pendingDropSlot = -1;
	private int pendingDropItemId = -1;
	private int pendingDropTicks;

	private boolean statsInitialized;
	private long statsStartedAt;
	private int lastRawCount;
	private int lastCookedCount;
	private int fishCaught;
	private int fishCooked;
	private long startFishingXp;
	private long startCookingXp;
	private int fishingXpPerHour;
	private int cookingXpPerHour;

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
		cameraSetupActive = true;
		cameraPhase = 0;
		zoomStarted = false;
		defaultCameraScale = 0;
		lastCameraMaintenanceAt = System.currentTimeMillis();
		pitchKey = KeyEvent.VK_UP;
		pitchBeforeHold = -1;
		pitchHoldPending = false;
		pitchDirectionLearned = false;
		resetFishingAttempt();
		resetFireInteraction();
		resetPendingDrop();
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

		/*if (!ensureCamera(context))
		{
			return;
		}

		if (isIdleForCameraReset(context))
		{
			if (!ensureDefaultCamera(context))
			{
				return;
			}
		}*/

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
		lines.add("Total fish: " + formatNumber(fishCaught) + " (" + formatPerHour(fishCaught, elapsed) + "/hr)");
		lines.add(formatXpToNextLevel(client, Skill.FISHING));
		lines.add("XP per hour: " + formatNumber(fishingXpPerHour));
		lines.add(formatTimeToNextLevel(client, Skill.FISHING, startFishingXp, elapsed));
		lines.add("Actions to next level: " + formatActions(fishingActionsToNextLevel(client, Skill.FISHING)));
		lines.add("");
		lines.add("Cooking");
		lines.add("Total cooked: " + formatNumber(fishCooked) + " (" + formatPerHour(fishCooked, elapsed) + "/hr)");
		lines.add(formatXpToNextLevel(client, Skill.COOKING));
		lines.add("XP per hour: " + formatNumber(cookingXpPerHour));
		lines.add(formatTimeToNextLevel(client, Skill.COOKING, startCookingXp, elapsed));
		lines.add("Actions to next level: " + formatActions(cookingActionsToNextLevel(client, Skill.COOKING)));
		lines.add("");
		lines.add("Total time: " + formatDuration(elapsed));
		return lines;
	}

	@Override
	public List<ProgressBar> getProgressBars()
	{
		if (!statsInitialized)
		{
			return Collections.emptyList();
		}

		Client client = getContext().getClient();

		List<ProgressBar> bars = new ArrayList<>();
		bars.add(fishingProgressBar(client));
		bars.add(cookingProgressBar(client));
		return bars;
	}

	private Script.ProgressBar fishingProgressBar(Client client)
	{
		int level = client.getRealSkillLevel(Skill.FISHING);
		if (level >= Experience.MAX_REAL_LEVEL)
		{
			return new Script.ProgressBar("Fishing", 1.0, "Max level", Color.GREEN);
		}

		long xp = client.getSkillExperience(Skill.FISHING);
		long currentLevelXp = Experience.getXpForLevel(level);
		long nextLevelXp = Experience.getXpForLevel(level + 1);
		long progressXp = Math.max(0L, xp - currentLevelXp);
		long totalXp = Math.max(1L, nextLevelXp - currentLevelXp);
		double progress = (double) progressXp / totalXp;
		String value = formatNumber(xp) + " / " + formatNumber(nextLevelXp);
		return new Script.ProgressBar("Fishing", progress, value, Color.GREEN);
	}

	private Script.ProgressBar cookingProgressBar(Client client)
	{
		int level = client.getRealSkillLevel(Skill.COOKING);
		if (level >= Experience.MAX_REAL_LEVEL)
		{
			return new Script.ProgressBar("Cooking", 1.0, "Max level", Color.CYAN);
		}

		long xp = client.getSkillExperience(Skill.COOKING);
		long currentLevelXp = Experience.getXpForLevel(level);
		long nextLevelXp = Experience.getXpForLevel(level + 1);
		long progressXp = Math.max(0L, xp - currentLevelXp);
		long totalXp = Math.max(1L, nextLevelXp - currentLevelXp);
		double progress = (double) progressXp / totalXp;
		String value = formatNumber(xp) + " / " + formatNumber(nextLevelXp);
		return new Script.ProgressBar("Cooking", progress, value, Color.CYAN);
	}

	private int fishingActionsToNextLevel(Client client, Skill skill)
	{
		int level = client.getRealSkillLevel(skill);
		if (level >= Experience.MAX_REAL_LEVEL)
		{
			return 0;
		}

		long xp = client.getSkillExperience(skill);
		long remaining = Math.max(0L, Experience.getXpForLevel(level + 1) - xp);
		long gained = Math.max(0L, xp - startFishingXp);
		if (fishCaught <= 0 || gained <= 0)
		{
			return -1;
		}

		long xpPerFish = gained / fishCaught;
		if (xpPerFish <= 0)
		{
			return -1;
		}

		long actions = remaining / xpPerFish;
		return actions > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) actions;
	}

	private int cookingActionsToNextLevel(Client client, Skill skill)
	{
		int level = client.getRealSkillLevel(skill);
		if (level >= Experience.MAX_REAL_LEVEL)
		{
			return 0;
		}

		long xp = client.getSkillExperience(skill);
		long remaining = Math.max(0L, Experience.getXpForLevel(level + 1) - xp);
		long gained = Math.max(0L, xp - startCookingXp);
		if (fishCooked <= 0 || gained <= 0)
		{
			return -1;
		}

		long xpPerCook = gained / fishCooked;
		if (xpPerCook <= 0)
		{
			return -1;
		}

		long actions = remaining / xpPerCook;
		return actions > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) actions;
	}

	private void fish(ScriptContext context)
	{
		GameInput input = context.getInput();
		Player player = context.getPlayer();

		long now = System.currentTimeMillis();

		if (player.getAnimation() != -1)
		{
			// Fishing has actually started. Do not click the spot again until
			// the animation has been gone for a while.
			fishingAnimationSeen = true;
			awaitingFishingStart = false;
			lastFishingAnimationAt = now;
			hoverFishingSpot(context);
			setStatus("Fishing");
			return;
		}

		if (awaitingFishingStart)
		{
			// The click was just made. The player may still be walking to the
			// spot, so give it time to start fishing before clicking again.
			if (now - lastFishingClickAt < FISHING_START_TIMEOUT_MS)
			{
				hoverFishingSpot(context);
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
				hoverFishingSpot(context);
				setStatus("Fishing");
				return;
			}

			fishingAnimationSeen = false;
		}

		if (input.isBusy())
		{
			setStatus("Moving mouse");
			return;
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
		setStatus("No fishing spot nearby");
	}

	/**
	 * Keeps the mouse over the fishing spot while the player is fishing. The
	 * spot drifts around, so this moves the mouse back whenever it is no longer
	 * over the spot.
	 */
	private void hoverFishingSpot(ScriptContext context)
	{
		GameInput input = context.getInput();
		if (input.isBusy())
		{
			return;
		}

		Optional<NPC> spot = context.getTargets().findVisibleNpc(FISHING_SPOT_ID, FISHING_DISTANCE);
		if (!spot.isPresent())
		{
			return;
		}

		NPC fishingSpot = spot.get();

		if (!input.isMouseOver(fishingSpot))
		{
			input.hover(fishingSpot);
		}
	}

	/**
	 * Keeps the mouse over the fire while the player is cooking, including
	 * after the cooking interface has been dismissed.
	 */
	private void hoverFire(ScriptContext context)
	{
		GameInput input = context.getInput();
		if (input.isBusy())
		{
			return;
		}

		Optional<TileObject> fire = context.getTargets().findVisibleTileObject(FIRE_ID, FIRE_DISTANCE);
		if (!fire.isPresent())
		{
			return;
		}

		TileObject cookingFire = fire.get();

		if (!input.isMouseOver(cookingFire))
		{
			input.hover(cookingFire);
		}
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
			hoverFire(context);
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
			hoverFire(context);
			setStatus("Cooking (" + rawFish + " raw left)");
			return;
		}

		if (rawStableTicks < COOK_STABLE_TICKS)
		{
			hoverFire(context);
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
				hoverFire(context);
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
				hoverFire(context);
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
				hoverFire(context);
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

		// Wait for the previous fish to leave the inventory before clicking the
		// next one, so the same fish is never clicked twice. Retry after a few
		// ticks in case the click did not register.
		if (pendingDropSlot >= 0)
		{
			if (itemIdAt(inventory, pendingDropSlot) == pendingDropItemId
				&& ++pendingDropTicks < DROP_RETRY_TICKS)
			{
				// Start moving onto the next fish while the current drop is
				// processed, the way a player keeps the mouse ahead of the
				// drops. Waiting for the click to finish first keeps the click
				// from being cut short by the movement.
				if (!input.isBusy())
				{
					hoverNextFish(input, inventory);
				}

				setStatus("Dropping fish");
				return;
			}

			resetPendingDrop();
		}

		if (input.isBusy())
		{
			setStatus("Moving mouse");
			return;
		}

		// Click the first fish, then move on to the next one.
		int slot = inventory.findDroppableFishSlot();
		int itemId = itemIdAt(inventory, slot);
		Widget item = inventory.getSlotWidget(slot);

		if (itemId < 0 || item == null || item.isHidden())
		{
			cooldown = 1;
			setStatus("Waiting for inventory");
			return;
		}

		Rectangle bounds = item.getBounds();
		if (bounds == null || bounds.isEmpty())
		{
			cooldown = 1;
			setStatus("Waiting for inventory");
			return;
		}

		// The click animates the mouse onto the fish and then presses, so the
		// cursor visibly travels from fish to fish instead of dropping them
		// without any mouse movement.
		if (!input.click(item))
		{
			cooldown = 1;
			setStatus("Waiting for inventory");
			return;
		}

		pendingDropSlot = slot;
		pendingDropItemId = itemId;
		pendingDropTicks = 0;
		cooldown = 0;
		setStatus("Dropping fish");
	}

	/**
	 * Moves the mouse onto a fish that is still waiting to be dropped, so the
	 * cursor arrives on the next fish while the current drop is being
	 * processed.
	 */
	private void hoverNextFish(GameInput input, Inventory inventory)
	{
		for (int slot : inventory.findDroppableFishSlots())
		{
			if (slot == pendingDropSlot)
			{
				continue;
			}

			Widget next = inventory.getSlotWidget(slot);
			if (next == null || next.isHidden())
			{
				continue;
			}

			Rectangle bounds = next.getBounds();
			if (bounds != null && !bounds.isEmpty() && !input.isMouseOver(bounds))
			{
				input.hover(next);
			}

			return;
		}
	}

	private static int itemIdAt(Inventory inventory, int slot)
	{
		Item[] items = inventory.getItems();
		if (slot < 0 || slot >= items.length)
		{
			return -1;
		}

		Item item = items[slot];
		return item == null ? -1 : item.getId();
	}

	private void resetPendingDrop()
	{
		pendingDropSlot = -1;
		pendingDropItemId = -1;
		pendingDropTicks = 0;
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

	/**
	 * Sets up the default camera angle on startup and keeps it there while
	 * idle: fully zoomed out, pitched all the way up, facing west. Uses the
	 * same inputs a real player would (mouse wheel for zoom, arrow keys for
	 * rotation).
	 */
	private boolean ensureCamera(ScriptContext context)
	{
		GameInput input = context.getInput();
		Client client = context.getClient();

		if (!cameraSetupActive)
		{
			return true;
		}

		if (!zoomStarted)
		{
			setStatus("Zooming camera out");
			zoomStarted = input.zoomOut(CAMERA_ZOOM_NOTCHES);
			return false;
		}

		if (input.isZooming())
		{
			setStatus("Zooming camera out");
			return false;
		}

		if (cameraPhase == 0)
		{
			setStatus("Pitching camera up");
			if (input.isCameraBusy())
			{
				return false;
			}

			if (client.getCameraPitchTarget() < CAMERA_MAX_PITCH - CAMERA_PITCH_TOLERANCE)
			{
				holdPitchUp(input, client);
				return false;
			}

			cameraPhase = 1;
			return true;
		}

		if (cameraPhase == 1)
		{
			setStatus("Facing camera west");
			if (input.isCameraBusy())
			{
				return false;
			}

			if (Math.abs(normalizeYaw(CAMERA_WEST_YAW - client.getCameraYawTarget())) > CAMERA_YAW_TOLERANCE)
			{
				input.turnCameraToYaw(CAMERA_WEST_YAW);
				return false;
			}

			cameraSetupActive = false;
			defaultCameraScale = client.getScale();
			lastCameraMaintenanceAt = System.currentTimeMillis();
			setStatus("Camera ready");
			return true;
		}

		return true;
	}

	/**
	 * When the script has been idle for a while, reset the camera back to the
	 * default angle so it doesn't drift.
	 */
	private boolean isIdleForCameraReset(ScriptContext context)
	{
		Player player = context.getPlayer();
		if (cameraSetupActive || (player != null && player.getAnimation() != -1))
		{
			lastCameraMaintenanceAt = System.currentTimeMillis();
			return false;
		}

		return System.currentTimeMillis() - lastCameraMaintenanceAt >= CAMERA_IDLE_RESET_MS;
	}

	private boolean ensureDefaultCamera(ScriptContext context)
	{
		GameInput input = context.getInput();
		Client client = context.getClient();

		if (input.isCameraBusy() || input.isZooming())
		{
			return false;
		}

		int pitch = client.getCameraPitchTarget();
		boolean pitchLow = pitch < CAMERA_MAX_PITCH - CAMERA_PITCH_TOLERANCE;
		boolean yawOff = Math.abs(normalizeYaw(CAMERA_WEST_YAW - client.getCameraYawTarget())) > CAMERA_YAW_TOLERANCE;
		boolean zoomedIn = defaultCameraScale > 0
			&& client.getScale() > defaultCameraScale + CAMERA_SCALE_TOLERANCE;

		if (!pitchLow && !yawOff && !zoomedIn)
		{
			lastCameraMaintenanceAt = System.currentTimeMillis();
			return true;
		}

		setStatus("Resetting camera");
		if (zoomedIn)
		{
			input.zoomOut(CAMERA_ZOOM_NOTCHES);
			return false;
		}

		if (pitchLow)
		{
			holdPitchUp(input, client);
			return false;
		}

		input.turnCameraToYaw(CAMERA_WEST_YAW);
		return false;
	}

	/**
	 * Holds the arrow key that raises the camera pitch. The key direction is
	 * confirmed from the resulting pitch change, so the script still works if
	 * the client maps the arrows the other way around.
	 */
	private void holdPitchUp(GameInput input, Client client)
	{
		int pitch = client.getCameraPitchTarget();

		if (pitchHoldPending)
		{
			pitchHoldPending = false;
			if (!pitchDirectionLearned && pitchBeforeHold >= 0)
			{
				int change = pitch - pitchBeforeHold;
				if (change < -2)
				{
					pitchKey = pitchKey == KeyEvent.VK_UP ? KeyEvent.VK_DOWN : KeyEvent.VK_UP;
				}
				else if (change > 2)
				{
					pitchDirectionLearned = true;
				}
			}
		}

		pitchBeforeHold = pitch;
		pitchHoldPending = true;
		input.holdKey(pitchKey, CAMERA_HOLD_MS);
	}

	private static int normalizeYaw(int yaw)
	{
		int normalized = yaw & 16383;
		return normalized >= 8192 ? normalized - 16384 : normalized;
	}

	private void resetStats()
	{
		statsInitialized = false;
		statsStartedAt = 0;
		lastRawCount = 0;
		lastCookedCount = 0;
		fishCaught = 0;
		fishCooked = 0;
		startFishingXp = 0;
		startCookingXp = 0;
		fishingXpPerHour = 0;
		cookingXpPerHour = 0;
	}

	private void updateStats()
	{
		Inventory inventory = getContext().getInventory();
		Client client = getContext().getClient();
		long now = System.currentTimeMillis();
		long elapsed = Math.max(1L, now - statsStartedAt);

		if (!statsInitialized)
		{
			statsInitialized = true;
			statsStartedAt = now;
			lastRawCount = inventory.countRawFish();
			lastCookedCount = inventory.countCookedFish();
			startFishingXp = client.getSkillExperience(Skill.FISHING);
			startCookingXp = client.getSkillExperience(Skill.COOKING);
			return;
		}

		int rawCount = inventory.countRawFish();
		int cookedCount = inventory.countCookedFish();

		if (rawCount > lastRawCount)
		{
			fishCaught += rawCount - lastRawCount;
		}

		if (cookedCount > lastCookedCount)
		{
			fishCooked += cookedCount - lastCookedCount;
		}

		lastRawCount = rawCount;
		lastCookedCount = cookedCount;

		long fishingXp = client.getSkillExperience(Skill.FISHING);
		long cookingXp = client.getSkillExperience(Skill.COOKING);

		fishingXpPerHour = (int) Math.round((fishingXp - startFishingXp) * 3_600_000.0 / elapsed);
		cookingXpPerHour = (int) Math.round((cookingXp - startCookingXp) * 3_600_000.0 / elapsed);
	}

	private static String formatActions(int actions)
	{
		return actions < 0 ? "--" : formatNumber(actions);
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
		return "Time to level " + (level + 1) + ": " + formatDuration(millis);
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
