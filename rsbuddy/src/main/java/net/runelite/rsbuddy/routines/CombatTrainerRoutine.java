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
import java.util.List;
import java.util.Locale;
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
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.rsbuddy.input.GameInput;
import net.runelite.rsbuddy.inventory.Inventory;
import net.runelite.rsbuddy.routine.Actions;
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
	private static final int LOOT_RADIUS = 15;
	private static final int LOOT_RECLICK_TICKS = 2;
	private static final int LOOT_ATTEMPT_LIMIT = 4;
	private static final int LOOT_TARGET_TIMEOUT_TICKS = 25;
	private static final long IGNORED_LOOT_TIMEOUT_MS = 10_000L;
	private static final Pattern TAGS = Pattern.compile("<[^>]*>");

	private final Set<String> targetNames = ConcurrentHashMap.newKeySet();
	private final Set<String> lootNames = ConcurrentHashMap.newKeySet();
	private final Set<Integer> lootItemIds = ConcurrentHashMap.newKeySet();
	private final Map<WorldPoint, Long> ignoredLoot = new ConcurrentHashMap<>();
	private volatile int eatAtHpPercent = 50;
	private volatile String lootItemsText = "";
	private volatile boolean training;
	private volatile boolean refreshRequested;
	private volatile CombatTrainerWindow window;

	private int cooldown;
	private boolean loggingOut;
	private NPC hoverNpc;
	private volatile GroundItem lootTarget;
	private int lootAttempts;
	private int lootTicks;
	private int lootClickTicks = -1;

	public CombatTrainerRoutine()
	{
		super("Combat Trainer", "Attacks chosen nearby NPCs, picks up listed items, and eats food"
			+ " below a chosen HP percentage.");
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
		lootTarget = null;
		getContext().getInput().follow(null);
		getContext().getInput().setGroundItemPickupFilter(null);
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

		GameInput input = getContext().getInput();

		// Keep the cursor on a drop while it is being picked up, for the same
		// reason as an NPC: the camera and player move while the mouse travels.
		GroundItem loot = lootTarget;
		if (loot != null)
		{
			Point aim = input.groundItemPoint(loot.sceneX, loot.sceneY);
			if (aim != null)
			{
				input.follow(aim);
			}
			return;
		}

		NPC npc = hoverNpc;
		if (npc == null || npc.isDead())
		{
			return;
		}

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

		// Pick up any listed drops before starting the next fight.
		if (lootNearby(context))
		{
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

	/**
	 * Picks up a matching item from the ground.
	 *
	 * @return true when a drop was found and this tick's action belongs to
	 * looting; false when there is nothing on the list to pick up
	 */
	private boolean lootNearby(RoutineContext context)
	{
		if (lootNames.isEmpty())
		{
			return false;
		}

		GameInput input = context.getInput();
		GroundItem loot = findGroundLoot(context);
		if (loot == null)
		{
			resetLootTracking();
			return false;
		}

		// Only one target at a time, so the NPC aim cannot fight the drop aim.
		clearHover();

		if (!loot.sameAs(lootTarget))
		{
			lootTarget = loot;
			lootAttempts = 0;
			lootTicks = 0;
			lootClickTicks = -1;
		}

		// Teach the pickup filter about this item, so its Take stays the top
		// left-click option and walking clicks cannot loot anything else.
		if (lootItemIds.add(loot.itemId))
		{
			input.setGroundItemPickupFilter(lootItemIds);
		}

		if (++lootTicks > LOOT_TARGET_TIMEOUT_TICKS
			|| lootAttempts >= LOOT_ATTEMPT_LIMIT)
		{
			// Unreachable or permanently covered: ignore the pile for a while
			// instead of blocking the next fight.
			ignoreLoot(loot.worldLocation);
			resetLootTracking();
			setStatus("Skipping unreachable loot");
			return true;
		}

		// Wait for the previous Take to play out: the player walks onto the
		// tile and picks the item up by itself.
		Player player = context.getPlayer();
		if (lootClickTicks >= 0)
		{
			++lootClickTicks;
			if (Actions.isMoving(player) || lootClickTicks < LOOT_RECLICK_TICKS)
			{
				setStatus("Looting " + loot.name);
				return true;
			}
		}

		Point aim = input.groundItemPoint(loot.sceneX, loot.sceneY);
		if (aim == null)
		{
			// The tile is off screen: walk closer and aim again next tick.
			resetLootTracking();
			if (input.walkTo(loot.worldLocation))
			{
				setStatus("Walking to " + loot.name);
			}
			return true;
		}

		Point mouse = input.getMouseLocation();
		boolean onTile = input.isOnGroundItemTile(mouse, loot.sceneX, loot.sceneY);
		int hover = input.groundItemHoverState(loot.itemId, loot.sceneX, loot.sceneY);
		if (hover == 2 && onTile && mouse != null)
		{
			// The item's Take is the top option under the cursor right now,
			// so this left click picks it up.
			if (input.click(mouse))
			{
				lootClickTicks = 0;
				++lootAttempts;
				setStatus("Looting " + loot.name);
			}
			return true;
		}

		// Still travelling, or something else is on top. Keep the cursor on
		// the item and verify again next tick rather than clicking blind.
		input.follow(aim);
		setStatus("Looting " + loot.name);
		return true;
	}

	private GroundItem findGroundLoot(RoutineContext context)
	{
		Client client = context.getClient();
		Player player = context.getPlayer();
		WorldPoint playerLocation = player == null ? null : player.getWorldLocation();
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null || playerLocation == null)
		{
			return null;
		}

		Scene scene = worldView.getScene();
		if (scene == null)
		{
			return null;
		}

		Tile[][][] tiles = scene.getTiles();
		int plane = worldView.getPlane();
		if (tiles == null || plane < 0 || plane >= tiles.length)
		{
			return null;
		}

		Tile[][] planeTiles = tiles[plane];
		if (planeTiles == null)
		{
			return null;
		}

		boolean inventoryFull = context.getInventory().isFull();
		GroundItem best = null;
		int bestScore = Integer.MAX_VALUE;

		for (Tile[] row : planeTiles)
		{
			if (row == null)
			{
				continue;
			}

			for (Tile tile : row)
			{
				if (tile == null)
				{
					continue;
				}

				List<TileItem> groundItems = tile.getGroundItems();
				if (groundItems == null || groundItems.isEmpty())
				{
					continue;
				}

				WorldPoint itemLocation = tile.getWorldLocation();
				net.runelite.api.Point sceneLocation = tile.getSceneLocation();
				if (itemLocation == null || sceneLocation == null)
				{
					continue;
				}

				int distance = itemLocation.distanceTo(playerLocation);
				if (distance > LOOT_RADIUS || isIgnoredLoot(itemLocation))
				{
					continue;
				}

				for (TileItem groundItem : groundItems)
				{
					if (groundItem == null
						|| groundItem.getOwnership() == TileItem.OWNERSHIP_OTHER)
					{
						continue;
					}

					int itemId = groundItem.getId();
					if (itemId <= 0 || !matchesLootName(getItemName(client, itemId)))
					{
						continue;
					}

					if (inventoryFull && !stacksOntoInventory(context, itemId))
					{
						continue;
					}

					int score = distance * 10;
					if (groundItem.getOwnership() == TileItem.OWNERSHIP_SELF)
					{
						score -= 5;
					}

					if (score < bestScore)
					{
						bestScore = score;
						best = new GroundItem(itemId, getItemName(client, itemId),
							sceneLocation.getX(), sceneLocation.getY(), itemLocation);
					}
				}
			}
		}

		return best;
	}

	private boolean matchesLootName(String itemName)
	{
		if (itemName == null || itemName.isEmpty())
		{
			return false;
		}

		String name = itemName.toLowerCase(Locale.ROOT).trim();
		for (String wanted : lootNames)
		{
			if (name.equals(wanted))
			{
				return true;
			}

			// Allow charged or dose variants: "amulet of glory" also matches
			// "Amulet of glory(6)", but "air" does not match "air rune".
			if (name.startsWith(wanted) && name.length() > wanted.length()
				&& !Character.isLetterOrDigit(name.charAt(wanted.length())))
			{
				return true;
			}
		}

		return false;
	}

	private static boolean stacksOntoInventory(RoutineContext context, int itemId)
	{
		if (context.getInventory().countItem(itemId) <= 0)
		{
			return false;
		}

		ItemComposition composition = context.getClient().getItemDefinition(itemId);
		return composition != null && composition.isStackable();
	}

	private boolean isIgnoredLoot(WorldPoint location)
	{
		Long expiresAt = ignoredLoot.get(location);
		if (expiresAt == null)
		{
			return false;
		}

		if (System.currentTimeMillis() >= expiresAt)
		{
			ignoredLoot.remove(location);
			return false;
		}

		return true;
	}

	private void ignoreLoot(WorldPoint location)
	{
		if (location != null)
		{
			ignoredLoot.put(location, System.currentTimeMillis() + IGNORED_LOOT_TIMEOUT_MS);
		}
	}

	private void resetLootTracking()
	{
		lootTarget = null;
		lootAttempts = 0;
		lootTicks = 0;
		lootClickTicks = -1;
	}

	private static String getItemName(Client client, int itemId)
	{
		ItemComposition composition = client.getItemDefinition(itemId);
		String name = composition == null ? null : composition.getName();
		if (name == null)
		{
			return "";
		}

		return TAGS.matcher(name).replaceAll("").trim();
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

	void startTraining(Set<String> names, int hpPercent, String lootItems)
	{
		targetNames.clear();
		targetNames.addAll(names);
		eatAtHpPercent = Math.max(1, Math.min(99, hpPercent));

		lootItemsText = lootItems == null ? "" : lootItems.trim();
		lootNames.clear();
		for (String part : lootItemsText.split(","))
		{
			String item = part.trim().toLowerCase(Locale.ROOT);
			if (!item.isEmpty())
			{
				lootNames.add(item);
			}
		}

		lootItemIds.clear();
		ignoredLoot.clear();
		resetLootTracking();
		getContext().getInput().setGroundItemPickupFilter(null);

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
		resetLootTracking();
		ignoredLoot.clear();
		getContext().getInput().follow(null);
		getContext().getInput().setGroundItemPickupFilter(null);
		setStatus("Training stopped");
	}

	/**
	 * @return the item list as it was last entered, for re-filling the window
	 */
	String getLootItemsText()
	{
		return lootItemsText;
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

	/**
	 * One ground-item stack the routine wants to pick up.
	 */
	private static final class GroundItem
	{
		private final int itemId;
		private final String name;
		private final int sceneX;
		private final int sceneY;
		private final WorldPoint worldLocation;

		private GroundItem(int itemId, String name, int sceneX, int sceneY, WorldPoint worldLocation)
		{
			this.itemId = itemId;
			this.name = name;
			this.sceneX = sceneX;
			this.sceneY = sceneY;
			this.worldLocation = worldLocation;
		}

		private boolean sameAs(GroundItem other)
		{
			return other != null
				&& itemId == other.itemId
				&& sceneX == other.sceneX
				&& sceneY == other.sceneY;
		}
	}
}
