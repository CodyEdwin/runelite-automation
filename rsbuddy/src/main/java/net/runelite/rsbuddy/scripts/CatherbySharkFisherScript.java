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
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.rsbuddy.scripts;

import java.awt.Color;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.GameObject;
import net.runelite.api.Item;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
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
import net.runelite.rsbuddy.walk.Walker;

/**
 * Harpoons sharks at Catherby, banks them when the inventory is full, and walks
 * back to the fishing spots to start over.
 * <p>
 * The harpoon is kept in a blocked inventory slot, so depositing the inventory
 * banks only the sharks. The script never deposits on its own; it only clicks
 * the bank's "Deposit inventory" button.
 */
public final class CatherbySharkFisherScript extends Script
{
	private static final int FISHING_SPOT_ID = NpcID._0_44_53_MEMBERFISH;
	private static final int BANK_BOOTH_ID = ObjectID.BANKBOOTH;

	private static final String FISH_ACTION = "Harpoon";

	/**
	 * Where the fishing spots are fished from.
	 */
	private static final WorldPoint FISHING_AREA = new WorldPoint(2842, 3432, 0);

	/**
	 * Catherby bank, next to the bank booths.
	 */
	private static final WorldPoint BANK_AREA = new WorldPoint(2809, 3441, 0);

	/**
	 * Anything the script must leave in the inventory when depositing.
	 */
	private static final Set<Integer> KEEP_ITEMS = new HashSet<>(Arrays.asList(
		ItemID.HARPOON,
		ItemID.DRAGON_HARPOON,
		ItemID.INFERNAL_HARPOON,
		ItemID.CRYSTAL_HARPOON,
		ItemID.TRAILBLAZER_HARPOON));

	private static final int FISHING_DISTANCE = 20;
	private static final int BANK_DISTANCE = 12;

	// Bank booths further away than this are out of interaction range. Clicking
	// them only opens a menu with no Bank option, which stalls the script.
	private static final int BANK_REACH = 4;

	// How close the player has to be before the walking state ends.
	private static final int FISHING_ARRIVAL_DISTANCE = 8;
	private static final int BANK_ARRIVAL_DISTANCE = 3;

	private static final int CLICK_COOLDOWN = 3;
	private static final int BANK_CLICK_COOLDOWN = 2;
	private static final int MAX_CAMERA_PULSES = 40;

	// How many ticks to wait for a deposit click to empty the inventory
	// before clicking the deposit button again.
	private static final int BANK_ATTEMPTS = 20;

	// How many booth clicks to try before stepping back and trying again from a
	// different position.
	private static final int BANK_OPEN_ATTEMPTS = 3;

	// After clicking a fishing spot, wait for the fishing animation instead of
	// clicking again while the player is still walking to the spot.
	private static final long FISHING_START_TIMEOUT_MS = 8000L;
	private static final long FISHING_ANIMATION_GAP_MS = 2000L;

	private enum State
	{
		FISH,
		WALK_TO_BANK,
		BANK,
		WALK_TO_SPOT
	}

	private enum BankStage
	{
		DEPOSIT,
		WAIT_DEPOSIT,
		LEAVE
	}

	private State state;
	private BankStage bankStage;
	private int bankAttempts;
	private int bankOpenAttempts;
	private Walker walker;
	private int cooldown;
	private int cameraAttempts;

	private boolean awaitingFishingStart;
	private boolean fishingAnimationSeen;
	private long lastFishingClickAt;
	private long lastFishingAnimationAt;

	private boolean statsInitialized;
	private long statsStartedAt;
	private int lastRawSharks;
	private int sharkCaught;
	private long startFishingXp;

	public CatherbySharkFisherScript()
	{
		super("Catherby Shark Fisher",
			"Harpoons sharks at Catherby, banks the full inventory, and walks back to the spots.");
	}

	@Override
	protected void onStart()
	{
		// onStart runs on the Swing thread, so it must not touch the client.
		state = null;
		bankStage = null;
		bankAttempts = 0;
		bankOpenAttempts = 0;
		walker = null;
		cooldown = 0;
		cameraAttempts = 0;
		resetFishingAttempt();
		resetStats();
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

		if (walker == null)
		{
			walker = new Walker(context.getClient(), context.getInput());
		}

		updateStats();

		if (cooldown > 0)
		{
			--cooldown;
			return;
		}

		if (state == null)
		{
			state = State.FISH;
		}

		switch (state)
		{
			case FISH:
				fish(context);
				break;
			case WALK_TO_BANK:
				walkToBank(context);
				break;
			case BANK:
				bank(context);
				break;
			case WALK_TO_SPOT:
				walkToSpot(context);
				break;
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
		lines.add("Sharks: " + formatNumber(sharkCaught) + " (" + formatPerHour(sharkCaught, elapsed) + "/hr)");
		lines.add(formatXpToNextLevel(client));
		lines.add("XP per hour: " + formatNumber(fishingXpPerHour(client, elapsed)));
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
		int level = client.getRealSkillLevel(Skill.FISHING);
		if (level >= Experience.MAX_REAL_LEVEL)
		{
			return Collections.singletonList(new ProgressBar("Fishing", 1.0, "Max level", Color.GREEN));
		}

		long xp = client.getSkillExperience(Skill.FISHING);
		long currentLevelXp = Experience.getXpForLevel(level);
		long nextLevelXp = Experience.getXpForLevel(level + 1);
		long progressXp = Math.max(0L, xp - currentLevelXp);
		long totalXp = Math.max(1L, nextLevelXp - currentLevelXp);

		return Collections.singletonList(new ProgressBar("Fishing",
			(double) progressXp / totalXp,
			formatNumber(xp) + " / " + formatNumber(nextLevelXp),
			Color.GREEN));
	}

	private void fish(ScriptContext context)
	{
		Inventory inventory = context.getInventory();
		GameInput input = context.getInput();

		if (inventory.isFull())
		{
			state = State.WALK_TO_BANK;
			walker.reset();
			setStatus("Walking to bank");
			return;
		}

		long now = System.currentTimeMillis();
		Player player = context.getPlayer();
		Optional<NPC> visibleSpot = context.getTargets().findVisibleNpc(FISHING_SPOT_ID, FISHING_DISTANCE);

		// The spots drift around and vanish. When none is left within range the
		// player is still playing the fishing animation at nothing, so the
		// waiting guards below must not hold the next click back.
		boolean spotAvailable = visibleSpot.isPresent()
			|| context.getTargets().findNpc(FISHING_SPOT_ID, FISHING_DISTANCE).isPresent();

		if (!spotAvailable)
		{
			awaitingFishingStart = false;
			fishingAnimationSeen = false;
		}

		if (player != null && player.getAnimation() != -1 && spotAvailable)
		{
			// Fishing has actually started. Do not click the spot again until
			// the animation has been gone for a while.
			fishingAnimationSeen = true;
			awaitingFishingStart = false;
			lastFishingAnimationAt = now;
			cameraAttempts = 0;
			hoverFishingSpot(context);
			setStatus("Fishing sharks");
			return;
		}

		if (awaitingFishingStart)
		{
			// The click was just made. The player may still be walking to the
			// spot, so give it time to start fishing before clicking again.
			if (now - lastFishingClickAt < FISHING_START_TIMEOUT_MS)
			{
				hoverFishingSpot(context);
				setStatus("Fishing sharks");
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
				setStatus("Fishing sharks");
				return;
			}

			fishingAnimationSeen = false;
		}

		if (input.isBusy())
		{
			setStatus("Moving mouse");
			return;
		}

		if (visibleSpot.isPresent())
		{
			NPC spot = visibleSpot.get();
			cameraAttempts = 0;
			input.stopCamera();

			if (input.interact(spot, FISH_ACTION))
			{
				noteFishingClick();
				cooldown = CLICK_COOLDOWN;
				setStatus("Harpooning sharks");
			}
			else
			{
				cooldown = 1;
				setStatus("Waiting for fishing spot");
			}

			return;
		}

		// The spot may have drifted out of view. If the player is not at the
		// fishing area at all (for example right after banking), walk there
		// first instead of turning the camera on the spot.
		WorldPoint location = context.getPlayerLocation();
		if (location != null && location.distanceTo(FISHING_AREA) > FISHING_ARRIVAL_DISTANCE)
		{
			state = State.WALK_TO_SPOT;
			walker.reset();
			setStatus("Walking to fishing spot");
			return;
		}

		Optional<NPC> spot = context.getTargets().findNpc(FISHING_SPOT_ID, FISHING_DISTANCE);
		if (spot.isPresent() && cameraAttempts < MAX_CAMERA_PULSES
			&& (input.isCameraBusy() || input.turnCameraToward(spot.get().getWorldLocation())))
		{
			++cameraAttempts;
			setStatus("Turning camera to fishing spot");
			return;
		}

		if (cameraAttempts >= MAX_CAMERA_PULSES)
		{
			input.stopCamera();
		}

		cooldown = 2;
		setStatus("No fishing spot nearby");
	}

	private void walkToBank(ScriptContext context)
	{
		WorldPoint location = context.getPlayerLocation();
		if (location == null)
		{
			return;
		}

		Optional<GameObject> booth = context.getTargets().findObject(BANK_BOOTH_ID, BANK_DISTANCE);
		if (booth.isPresent() && location.distanceTo(booth.get().getWorldLocation()) <= BANK_REACH)
		{
			state = State.BANK;
			bankStage = null;
			bankAttempts = 0;
			bankOpenAttempts = 0;
			setStatus("Banking sharks");
			return;
		}

		if (location.distanceTo(BANK_AREA) <= BANK_ARRIVAL_DISTANCE)
		{
			state = State.BANK;
			bankStage = null;
			bankAttempts = 0;
			bankOpenAttempts = 0;
			setStatus("Banking sharks");
			return;
		}

		// Minimap clicks cannot hit a game object, so the walk to the bank
		// does not accidentally reopen a door or attack something.
		walker.walkTo(BANK_AREA, true);
		setStatus("Walking to bank");
	}

	private void bank(ScriptContext context)
	{
		Inventory inventory = context.getInventory();
		GameInput input = context.getInput();

		Widget bankUniverse = context.getTargets().findWidget(InterfaceID.Bankmain.UNIVERSE).orElse(null);

		// Once the inventory has been deposited, close the bank and only walk
		// away after the interface has actually gone.
		if (bankStage == BankStage.LEAVE)
		{
			if (bankUniverse != null)
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
			bankAttempts = 0;
			state = State.WALK_TO_SPOT;
			walker.reset();
			cooldown = CLICK_COOLDOWN;
			setStatus("Walking back to the fishing spots");
			return;
		}

		if (bankUniverse == null)
		{
			bankStage = null;
			bankAttempts = 0;

			if (input.isBusy())
			{
				setStatus("Moving mouse");
				return;
			}

			Optional<GameObject> booth = context.getTargets().findObject(BANK_BOOTH_ID, BANK_DISTANCE);
			if (booth.isEmpty())
			{
				// Probably walked past the bank. Head back toward it, but keep
				// the bank state so a nearby booth is used as soon as one
				// appears.
				walker.walkTo(BANK_AREA, true);
				setStatus("Looking for bank booth");
				return;
			}

			GameObject bankBooth = booth.get();
			WorldPoint boothLocation = bankBooth.getWorldLocation();
			WorldPoint location = context.getPlayerLocation();
			int boothDistance = location == null ? Integer.MAX_VALUE : location.distanceTo(boothLocation);

			// Walking into the bank can leave a booth on the far side of the
			// room. Clicking it from there only opens a menu with no Bank
			// option, so walk into range before clicking.
			if (boothDistance > BANK_REACH)
			{
				walker.walkTo(boothLocation, true);
				setStatus("Walking to bank booth");
				return;
			}

			if (!input.isVisible(bankBooth))
			{
				input.turnCameraToward(boothLocation);
				setStatus("Turning camera to bank booth");
				return;
			}

			input.stopCamera();

			// A bank booth's left click is "Bank", which is exactly what a player
			// uses. Right-clicking is avoided: from just outside the booth's
			// range the menu has no Bank option and the script would keep
			// right-clicking the tile instead of banking.
			if (++bankOpenAttempts > BANK_OPEN_ATTEMPTS)
			{
				bankOpenAttempts = 0;
				walker.reset();
				walker.walkTo(BANK_AREA, true);
				setStatus("Repositioning at the bank");
				return;
			}

			if (input.click(bankBooth))
			{
				cooldown = BANK_CLICK_COOLDOWN;
				setStatus("Opening bank");
			}
			else
			{
				cooldown = 1;
				setStatus("Waiting for bank");
			}

			return;
		}

		bankOpenAttempts = 0;

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

		switch (bankStage)
		{
			case DEPOSIT:
			{
				if (countCarriedItems(inventory) == 0)
				{
					bankStage = BankStage.LEAVE;
					return;
				}

				Widget deposit = context.getTargets()
					.findWidget(InterfaceID.Bankmain.DEPOSITINV)
					.orElse(null);
				if (deposit == null)
				{
					// The bank is still loading its buttons. Wait for it instead
					// of leaving with a full inventory.
					cooldown = 1;
					setStatus("Waiting for bank");
					return;
				}

				if (input.click(deposit))
				{
					bankStage = BankStage.WAIT_DEPOSIT;
					bankAttempts = 0;
					cooldown = BANK_CLICK_COOLDOWN;
				}
				else
				{
					cooldown = 1;
				}

				setStatus("Depositing sharks");
				return;
			}
			case WAIT_DEPOSIT:
			{
				if (countCarriedItems(inventory) == 0)
				{
					bankStage = BankStage.LEAVE;
					return;
				}

				if (++bankAttempts > BANK_ATTEMPTS)
				{
					// The deposit click did not register. Try the button again
					// instead of waiting forever.
					bankStage = BankStage.DEPOSIT;
					bankAttempts = 0;
				}

				setStatus("Depositing sharks");
				return;
			}
			case LEAVE:
			{
				// The bank is still open. Close it here; the next tick walks
				// back once the interface is gone.
				input.pressKey(KeyEvent.VK_ESCAPE);
				cooldown = 1;
				setStatus("Closing bank");
				return;
			}
			default:
		}
	}

	private void walkToSpot(ScriptContext context)
	{
		WorldPoint location = context.getPlayerLocation();
		if (location == null)
		{
			return;
		}

		if (location.distanceTo(FISHING_AREA) <= FISHING_ARRIVAL_DISTANCE)
		{
			state = State.FISH;
			cameraAttempts = 0;
			resetFishingAttempt();
			setStatus("Fishing sharks");
			return;
		}

		walker.walkTo(FISHING_AREA, true);
		setStatus("Walking to fishing spot");
	}

	/**
	 * Keeps the mouse over the fishing spot while the player is fishing. The
	 * spot drifts around, so the mouse is moved back when it is no longer over
	 * the spot.
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

	private static int countCarriedItems(Inventory inventory)
	{
		int count = 0;
		for (Item item : inventory.getItems())
		{
			if (item != null && item.getId() != -1 && !KEEP_ITEMS.contains(item.getId()))
			{
				++count;
			}
		}

		return count;
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

	private void resetStats()
	{
		statsInitialized = false;
		statsStartedAt = 0;
		lastRawSharks = 0;
		sharkCaught = 0;
		startFishingXp = 0;
	}

	private void updateStats()
	{
		Inventory inventory = getContext().getInventory();
		Client client = getContext().getClient();
		long now = System.currentTimeMillis();

		int rawSharks = inventory.countItem(ItemID.RAW_SHARK);

		if (!statsInitialized)
		{
			statsInitialized = true;
			statsStartedAt = now;
			lastRawSharks = rawSharks;
			startFishingXp = client.getSkillExperience(Skill.FISHING);
			return;
		}

		if (rawSharks > lastRawSharks)
		{
			sharkCaught += rawSharks - lastRawSharks;
		}

		lastRawSharks = rawSharks;
	}

	private long fishingXpPerHour(Client client, long elapsedMs)
	{
		long gained = Math.max(0L, client.getSkillExperience(Skill.FISHING) - startFishingXp);
		return elapsedMs <= 0 ? 0L : Math.round(gained * 3_600_000.0 / elapsedMs);
	}

	private static String formatXpToNextLevel(Client client)
	{
		int level = client.getRealSkillLevel(Skill.FISHING);
		if (level >= Experience.MAX_REAL_LEVEL)
		{
			return "XP to level 99: max";
		}

		long xp = client.getSkillExperience(Skill.FISHING);
		long remaining = Math.max(0L, Experience.getXpForLevel(level + 1) - xp);
		return "XP to level " + (level + 1) + ": " + formatNumber(remaining);
	}

	private static String formatPerHour(int amount, long elapsedMs)
	{
		long perHour = elapsedMs <= 0 ? 0 : Math.round(amount * 3_600_000.0 / elapsedMs);
		return formatNumber(perHour);
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
}
