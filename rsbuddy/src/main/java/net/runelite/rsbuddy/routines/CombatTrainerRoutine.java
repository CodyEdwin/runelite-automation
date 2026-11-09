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
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.swing.SwingUtilities;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.rsbuddy.input.GameInput;
import net.runelite.rsbuddy.inventory.Inventory;
import net.runelite.rsbuddy.routine.Routine;
import net.runelite.rsbuddy.routine.RoutineContext;

/**
 * Trains combat on a player-selected list of nearby NPCs. When the routine
 * starts it opens a configuration window with the attackable NPCs around the
 * player and an HP percentage at which to eat food. Pressing Start in that
 * window minimises it, and the routine attacks the chosen NPCs until stopped.
 */
public final class CombatTrainerRoutine extends Routine
{
	private static final String ATTACK_ACTION = "Attack";
	private static final int ATTACK_COOLDOWN_TICKS = 3;
	private static final int SCAN_RADIUS = 20;
	private static final int MAX_TARGET_DISTANCE = 20;
	private static final int AIM_TOLERANCE_PX = 5;
	private static final Pattern TAGS = Pattern.compile("<[^>]*>");

	private final Set<String> targetNames = ConcurrentHashMap.newKeySet();
	private volatile int eatAtHpPercent = 50;
	private volatile boolean training;
	private volatile boolean refreshRequested;
	private volatile CombatTrainerWindow window;

	private int cooldown;
	private boolean loggingOut;
	private NPC hoverNpc;

	public CombatTrainerRoutine()
	{
		super("Combat Trainer", "Attacks chosen nearby NPCs and eats food below a chosen HP percentage.");
	}

	@Override
	protected void onStart()
	{
		// onStart may run on the game thread or the EDT. It must not touch
		// the client, and the configuration window is opened on the EDT.
		cooldown = 0;
		refreshRequested = true;
		training = false;
		loggingOut = false;
		hoverNpc = null;
		setStatus("Choose NPCs in the Combat Trainer window");

		SwingUtilities.invokeLater(this::showWindow);
	}

	@Override
	protected void onStop()
	{
		training = false;
		hoverNpc = null;
		getContext().getInput().follow(null);
		CombatTrainerWindow current = window;
		window = null;

		if (current != null)
		{
			SwingUtilities.invokeLater(current::dispose);
		}
	}

	@Override
	protected void onClientTick()
	{
		if (!training || loggingOut || !getContext().isLoggedIn())
		{
			return;
		}

		NPC npc = hoverNpc;
		if (npc == null || npc.isDead())
		{
			return;
		}

		GameInput input = getContext().getInput();
		Point aim = input.npcCenter(npc);
		if (aim != null)
		{
			// Re-aim every frame. The NPC and the camera keep moving while
			// the cursor travels, so a point captured once would already be
			// stale by the time the click landed.
			input.follow(aim);
		}
	}

	@Override
	protected void onGameTick()
	{
		RoutineContext context = getContext();

		if (!context.isLoggedIn())
		{
			clearHover();
			setStatus("Waiting for login");
			return;
		}

		// If the player logged back in with food, cancel the safety logout
		// instead of immediately logging out again.
		if (loggingOut && hasFood(context))
		{
			loggingOut = false;
		}

		// The nearby NPC list is refreshed on demand from the configuration
		// window. Scans happen here because reading the world is only safe
		// on the game thread.
		if (refreshRequested)
		{
			refreshRequested = false;
			refreshWindow(scanNearbyNpcs(context));
		}

		if (!training)
		{
			clearHover();
			setStatus("Choose NPCs and press Start");
			return;
		}

		if (targetNames.isEmpty())
		{
			clearHover();
			setStatus("No NPCs selected");
			return;
		}

		eatIfNeeded(context);

		if (loggingOut)
		{
			logout(context);
			return;
		}

		if (cooldown > 0)
		{
			--cooldown;
			return;
		}

		fight(context);
	}

	private void fight(RoutineContext context)
	{
		GameInput input = context.getInput();

		if (context.getClient().isMenuOpen())
		{
			input.closeMenu();
			return;
		}

		if (input.isBusy())
		{
			setStatus("Moving mouse");
			return;
		}

		Player player = context.getPlayer();
		Actor interacting = player == null ? null : player.getInteracting();
		if (interacting instanceof NPC)
		{
			// Auto-retaliate can already have us in combat with an NPC that
			// is not in the selected list, and the game refuses a new attack
			// until the current interaction ends. Keep fighting whatever we
			// are already on instead of clicking another NPC every tick.
			clearHover();
			NPC current = (NPC) interacting;
			String currentName = getNpcName(current);
			setStatus((current.isDead() ? "Finishing " : "Fighting ") + currentName);
			return;
		}

		// Once the current fight is over, do not start another one without
		// food. Logging out is safer than dying while waiting for a kill.
		if (!hasFood(context))
		{
			clearHover();
			beginLogout();
			return;
		}

		// Stay on the NPC the cursor is already travelling toward instead of
		// re-picking the nearest one every tick, which would make the cursor
		// chase different NPCs and never settle on one.
		NPC npc = hoverNpc;
		if (!isStillTarget(context, npc))
		{
			Optional<NPC> target = findTarget(context);
			if (target.isEmpty())
			{
				clearHover();
				cooldown = 2;
				setStatus("Waiting for " + describeTargets());
				return;
			}

			npc = target.get();
			hoverNpc = npc;
		}

		String name = getNpcName(npc);
		Point aim = input.npcCenter(npc);
		if (aim == null)
		{
			// The NPC is not clickable on screen (out of range, behind an
			// interface, or not drawn yet), so walk toward it instead.
			clearHover();
			if (input.walkTo(npc.getWorldLocation()))
			{
				cooldown = 2;
				setStatus("Walking to " + name);
			}
			else
			{
				cooldown = 2;
				setStatus("Cannot attack " + name);
			}
			return;
		}

		Point mouse = input.getMouseLocation();
		boolean onTarget = mouse != null
			&& mouse.distance(aim) <= AIM_TOLERANCE_PX
			&& input.isMouseOver(npc);
		if (!onTarget)
		{
			// Glide the cursor to the NPC's live screen position. It is
			// re-aimed every frame in onClientTick, so a moving camera does
			// not leave the click behind where the NPC used to be.
			input.follow(aim);
			setStatus("Lining up " + name);
			return;
		}

		// Click exactly where the cursor is now, while it is confirmed to be
		// over the NPC's model, rather than at a point computed earlier.
		hoverNpc = null;
		if (input.click(mouse))
		{
			cooldown = ATTACK_COOLDOWN_TICKS;
			setStatus("Attacking " + name);
		}
		else
		{
			cooldown = 2;
			setStatus("Cannot attack " + name);
		}
	}

	/**
	 * @return true while the given NPC is still a valid selected target that
	 * is close enough to keep aiming at
	 */
	private boolean isStillTarget(RoutineContext context, NPC npc)
	{
		if (npc == null || npc.isDead() || npc.getComposition() == null)
		{
			return false;
		}

		if (!isTargetSelected(getNpcName(npc)))
		{
			return false;
		}

		WorldPoint playerLocation = context.getPlayerLocation();
		WorldPoint npcLocation = npc.getWorldLocation();
		return playerLocation != null && npcLocation != null
			&& npcLocation.distanceTo(playerLocation) <= MAX_TARGET_DISTANCE;
	}

	/**
	 * Stops following the current NPC, if any.
	 */
	private void clearHover()
	{
		if (hoverNpc != null)
		{
			hoverNpc = null;
			getContext().getInput().follow(null);
		}
	}

	private Optional<NPC> findTarget(RoutineContext context)
	{
		WorldPoint playerLocation = context.getPlayerLocation();
		NPC best = null;
		int bestDistance = Integer.MAX_VALUE;

		for (String name : targetNames)
		{
			Optional<NPC> candidate = context.getTargets()
				.findNpc(name, ATTACK_ACTION, MAX_TARGET_DISTANCE);
			if (candidate.isEmpty())
			{
				continue;
			}

			NPC npc = candidate.get();
			WorldPoint npcLocation = npc.getWorldLocation();
			int distance = playerLocation == null || npcLocation == null
				? Integer.MAX_VALUE
				: npcLocation.distanceTo(playerLocation);

			if (distance < bestDistance)
			{
				bestDistance = distance;
				best = npc;
			}
		}

		return Optional.ofNullable(best);
	}

	private void eatIfNeeded(RoutineContext context)
	{
		Client client = context.getClient();
		Inventory inventory = context.getInventory();
		GameInput input = context.getInput();

		int current = client.getBoostedSkillLevel(Skill.HITPOINTS);
		int max = client.getRealSkillLevel(Skill.HITPOINTS);
		if (max <= 0 || current * 100 > max * eatAtHpPercent)
		{
			return;
		}

		int slot = findFoodSlot(context);
		if (slot < 0)
		{
			beginLogout();
			return;
		}

		// The inventory tab is not always open. Without opening it the food
		// slot has no widget and the player cannot eat.
		if (!inventory.isOpen())
		{
			if (!input.isBusy() && inventory.open())
			{
				setStatus("Opening inventory");
			}
			return;
		}

		// Clicking a food slot while the bank is open would deposit it
		// instead of eating it.
		if (context.getTargets().findWidget(InterfaceID.Bankmain.UNIVERSE).isPresent()
			|| client.isMenuOpen() || input.isBusy())
		{
			return;
		}

		Widget widget = inventory.getSlotWidget(slot);
		if (widget != null && input.click(widget))
		{
			cooldown = ATTACK_COOLDOWN_TICKS;
			setStatus("Eating food");
		}
	}

	private int findFoodSlot(RoutineContext context)
	{
		Inventory inventory = context.getInventory();
		Item[] items = inventory.getItems();

		for (int slot = 0; slot < items.length && slot < Inventory.SIZE; slot++)
		{
			Item item = items[slot];
			if (item == null || item.getId() <= 0)
			{
				continue;
			}

			ItemComposition composition = context.getClient().getItemDefinition(item.getId());
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

	private boolean hasFood(RoutineContext context)
	{
		return findFoodSlot(context) >= 0;
	}

	private void beginLogout()
	{
		if (!loggingOut)
		{
			loggingOut = true;
			setStatus("Out of food - logging out");
		}
	}

	/**
	 * Opens the logout tab and clicks its logout button. The click is retried
	 * every tick because logging out during combat is rejected by the game.
	 */
	private void logout(RoutineContext context)
	{
		Client client = context.getClient();
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			setStatus("Out of food - logged out");
			return;
		}

		GameInput input = context.getInput();
		if (client.isMenuOpen())
		{
			input.closeMenu();
			return;
		}

		if (input.isBusy())
		{
			setStatus("Logging out...");
			return;
		}

		Widget logoutButton = client.getWidget(InterfaceID.Logout.LOGOUT);
		if (logoutButton != null && !logoutButton.isHidden())
		{
			if (input.click(logoutButton))
			{
				setStatus("Logging out...");
			}
			return;
		}

		Widget logoutTab = findLogoutTab(client);
		if (logoutTab != null && input.click(logoutTab))
		{
			setStatus("Out of food - opening logout");
		}
		else
		{
			setStatus("Out of food - cannot find logout");
		}
	}

	private static Widget findLogoutTab(Client client)
	{
		int topLevel = client.getTopLevelInterfaceId();
		int widgetId;
		if (topLevel == InterfaceID.TOPLEVEL_OSRS_STRETCH)
		{
			widgetId = InterfaceID.ToplevelOsrsStretch.STONE10;
		}
		else if (topLevel == InterfaceID.TOPLEVEL_PRE_EOC)
		{
			widgetId = InterfaceID.ToplevelPreEoc.STONE10;
		}
		else
		{
			widgetId = InterfaceID.Toplevel.STONE10;
		}

		Widget tab = client.getWidget(widgetId);
		return tab != null && !tab.isHidden() ? tab : null;
	}

	private Map<String, Integer> scanNearbyNpcs(RoutineContext context)
	{
		Client client = context.getClient();
		Player player = context.getPlayer();
		WorldPoint playerLocation = player == null ? null : player.getWorldLocation();
		WorldView worldView = client.getTopLevelWorldView();
		if (playerLocation == null || worldView == null)
		{
			return Collections.emptyMap();
		}

		Map<String, Integer> counts = new TreeMap<>();
		for (NPC npc : worldView.npcs())
		{
			if (npc == null || npc.isDead())
			{
				continue;
			}

			NPCComposition composition = npc.getComposition();
			String name = getNpcName(npc);
			if (name.isEmpty() || !hasAttackAction(composition))
			{
				continue;
			}

			WorldPoint npcLocation = npc.getWorldLocation();
			if (npcLocation == null || npcLocation.distanceTo(playerLocation) > SCAN_RADIUS)
			{
				continue;
			}

			counts.merge(name, 1, Integer::sum);
		}

		return counts;
	}

	private void refreshWindow(Map<String, Integer> nearby)
	{
		CombatTrainerWindow current = window;
		if (current == null)
		{
			return;
		}

		current.updateNearbyNpcs(nearby);
	}

	private void showWindow()
	{
		CombatTrainerWindow current = window;
		if (current == null || !current.isDisplayable())
		{
			current = new CombatTrainerWindow(this);
			window = current;
		}

		current.setState(CombatTrainerWindow.NORMAL);
		current.setVisible(true);
		current.toFront();
	}

	void startTraining(Set<String> names, int hpPercent)
	{
		targetNames.clear();
		targetNames.addAll(names);
		eatAtHpPercent = Math.max(1, Math.min(99, hpPercent));
		cooldown = 0;
		loggingOut = false;
		hoverNpc = null;
		getContext().getInput().follow(null);
		training = true;
		setStatus("Training: " + describeTargets());
	}

	void stopTraining()
	{
		training = false;
		hoverNpc = null;
		getContext().getInput().follow(null);
		setStatus("Training stopped");
	}

	/**
	 * Asks the next game tick to rescan the NPCs around the player and push
	 * the result to the configuration window.
	 */
	void requestNpcRefresh()
	{
		refreshRequested = true;
	}

	int getEatAtHpPercent()
	{
		return eatAtHpPercent;
	}

	boolean isTargetSelected(String name)
	{
		if (name == null)
		{
			return false;
		}

		for (String target : targetNames)
		{
			if (target.equalsIgnoreCase(name))
			{
				return true;
			}
		}

		return false;
	}

	private String describeTargets()
	{
		if (targetNames.isEmpty())
		{
			return "targets";
		}

		StringBuilder builder = new StringBuilder();
		for (String name : targetNames)
		{
			if (builder.length() > 0)
			{
				builder.append(", ");
			}
			builder.append(name);
			if (builder.length() > 48)
			{
				break;
			}
		}

		return builder.toString();
	}

	private static String getNpcName(NPC npc)
	{
		NPCComposition composition = npc.getComposition();
		String name = composition == null ? npc.getName() : composition.getName();
		if (name == null)
		{
			return "";
		}

		return TAGS.matcher(name).replaceAll("").trim();
	}

	private static boolean hasAttackAction(NPCComposition composition)
	{
		String[] actions = composition == null ? null : composition.getActions();
		if (actions == null)
		{
			return false;
		}

		for (String action : actions)
		{
			if (action != null && ATTACK_ACTION.equalsIgnoreCase(action.trim()))
			{
				return true;
			}
		}

		return false;
	}
}
