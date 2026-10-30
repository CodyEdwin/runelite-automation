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
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.Item;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.rsbuddy.input.GameInput;
import net.runelite.rsbuddy.inventory.Inventory;
import net.runelite.rsbuddy.script.Script;
import net.runelite.rsbuddy.script.ScriptContext;
import net.runelite.rsbuddy.walk.Walker;

/**
 * Steals from the two fruit stalls in the Hosidius house east of the market
 * until the inventory is full, then drops the fruit that is not configured to
 * be kept and repeats.
 * <p>
 * The stalls are six tiles apart, so a single click point cannot cover both.
 * The script walks to one stall, steals from it, immediately heads to the
 * other, and only goes back once the first stall has had time to respawn.
 */
public final class FruitStallStealerScript extends Script
{
	// The left stall is the one at 1795, 3607 and the right stall at
	// 1801, 3607. Both use the normal fruit stall object; the extra id is
	// included because the client has reported the left stall with it.
	private static final WorldPoint[] STALL_LOCATIONS = {
		new WorldPoint(1795, 3607, 0),
		new WorldPoint(1801, 3607, 0)
	};

	private static final int LEFT_STALL_ID = 28832;
	private static final int RIGHT_STALL_ID = 28823;

	private static final String STEAL_ACTION = "Steal-from";
	private static final String STALL_NAME = "Fruit Stall";

	// Both stalls are six tiles apart, so this radius can never pick up the
	// other stall.
	private static final int STALL_RADIUS = 2;

	// Stalls are stolen from while standing next to them.
	private static final int STALL_INTERACT_DISTANCE = 1;

	// After a steal click, give the game a few ticks to process the steal and
	// turn the stall into its empty version.
	private static final int STEAL_COOLDOWN_TICKS = 3;

	// Drop two items per game tick. Each batch uses the normal animated mouse
	// path at double speed, so the inventory empties exactly twice as fast as
	// clicking one item per tick.
	private static final int DROP_BATCH_SIZE = 2;
	private static final double DROP_SPEED_MULTIPLIER = 2.0;

	private final boolean stayAtOneStall;
	private final Set<Integer> keptItems;

	private enum State
	{
		STEAL,
		DROP
	}

	private State state;
	private int cooldown;

	/**
	 * Index of the stall currently being stolen from.
	 */
	private int stallIndex = -1;

	/**
	 * Centre of the current stall's clickbox, computed when arriving at the
	 * stall and reused for its clicks.
	 */
	private Point stallClickPoint;

	private Walker walker;

	private int lastItemCount;
	private int steals;
	private int itemsDropped;

	private boolean statsInitialized;
	private long statsStartedAt;
	private long startThievingXp;

	public FruitStallStealerScript()
	{
		this(false);
	}

	public FruitStallStealerScript(boolean stayAtOneStall)
	{
		this(stayAtOneStall, true, true, true, true);
	}

	public FruitStallStealerScript(boolean stayAtOneStall, boolean keepPapaya,
		boolean keepGolovanova, boolean keepStrangeFruit, boolean keepJangerberries)
	{
		super("Fruit Stall Stealer",
			"Steals from the Hosidius fruit stalls and drops unwanted fruit when the inventory is full.");
		this.stayAtOneStall = stayAtOneStall;

		Set<Integer> kept = new HashSet<>();
		if (keepPapaya)
		{
			kept.add(ItemID.PAPAYA);
		}
		if (keepGolovanova)
		{
			kept.add(ItemID.GOLOVANOVA_TOP);
		}
		if (keepStrangeFruit)
		{
			kept.add(ItemID.MACRO_TRIFFIDFRUIT); // Strange fruit
		}
		if (keepJangerberries)
		{
			kept.add(ItemID.JANGERBERRIES);
		}

		this.keptItems = Collections.unmodifiableSet(kept);
	}

	@Override
	protected void onStart()
	{
		// onStart runs on the Swing thread, so it must not touch the client.
		state = State.STEAL;
		cooldown = 0;
		stallIndex = -1;
		stallClickPoint = null;
		walker = null;
		lastItemCount = 0;
		steals = 0;
		itemsDropped = 0;
		statsInitialized = false;
		statsStartedAt = 0;
		startThievingXp = 0;
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

		if (state == null)
		{
			state = State.STEAL;
		}

		if (state == State.DROP)
		{
			if (cooldown > 0)
			{
				--cooldown;
				return;
			}

			drop(context);
			return;
		}

		// The cooldown only prevents another steal click. Walking is still
		// allowed so the player can start moving to the other stall straight
		// after stealing.
		if (cooldown > 0)
		{
			--cooldown;
		}

		steal(context);
	}

	@Override
	public List<String> getOverlayLines()
	{
		if (!statsInitialized)
		{
			return Collections.emptyList();
		}

		long elapsed = Math.max(1L, System.currentTimeMillis() - statsStartedAt);
		Client client = getContext().getClient();
		long gainedXp = Math.max(0L, client.getSkillExperience(Skill.THIEVING) - startThievingXp);

		List<String> lines = new ArrayList<>();
		lines.add("");
		lines.add("Hosidius fruit stalls");
		lines.add("Mode: " + (stayAtOneStall ? "One stall" : "Both stalls"));
		lines.add("Steals: " + formatNumber(steals) + " (" + formatPerHour(steals, elapsed) + "/hr)");
		lines.add("Dropped: " + formatNumber(itemsDropped));
		lines.add("XP per hour: " + formatNumber(perHour(gainedXp, elapsed)));
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
		int level = client.getRealSkillLevel(Skill.THIEVING);
		if (level >= Experience.MAX_REAL_LEVEL)
		{
			return Collections.singletonList(new ProgressBar("Thieving", 1.0, "Max level", Color.GREEN));
		}

		long xp = client.getSkillExperience(Skill.THIEVING);
		long currentLevelXp = Experience.getXpForLevel(level);
		long nextLevelXp = Experience.getXpForLevel(level + 1);
		long progressXp = Math.max(0L, xp - currentLevelXp);
		long totalXp = Math.max(1L, nextLevelXp - currentLevelXp);

		return Collections.singletonList(new ProgressBar("Thieving",
			(double) progressXp / totalXp,
			formatNumber(xp) + " / " + formatNumber(nextLevelXp),
			Color.GREEN));
	}

	private void steal(ScriptContext context)
	{
		Inventory inventory = context.getInventory();
		GameInput input = context.getInput();
		Client client = context.getClient();

		if (inventory.isFull())
		{
			if (countDroppableItems(inventory) > 0)
			{
				state = State.DROP;
				setStatus("Inventory full, dropping unwanted fruit");
			}
			else
			{
				setStatus("Inventory full of kept fruit - bank now");
			}
			return;
		}

		if (input.isBusy())
		{
			setStatus("Moving");
			return;
		}

		if (stallIndex < 0)
		{
			stallIndex = nearestStall(context);
		}

		WorldPoint stallLocation = STALL_LOCATIONS[stallIndex];
		Optional<TileObject> target = findFullStall(context, stallLocation);

		// If the stall we were heading to is still empty, use the other one so
		// the player keeps stealing instead of standing around. In one-stall
		// mode the script waits here for this stall to respawn instead.
		if (target.isEmpty() && !stayAtOneStall)
		{
			int otherIndex = 1 - stallIndex;
			Optional<TileObject> other = findFullStall(context, STALL_LOCATIONS[otherIndex]);
			if (other.isPresent())
			{
				stallIndex = otherIndex;
				stallLocation = STALL_LOCATIONS[stallIndex];
				stallClickPoint = null;
				target = other;
			}
		}

		if (target.isEmpty())
		{
			setStatus("Waiting for fruit stall to respawn");
			return;
		}

		TileObject stall = target.get();

		// If a right-click menu is already open (for example because the
		// stall's steal option is not its default action), finish the
		// interaction by clicking the steal entry instead of closing it.
		if (client.isMenuOpen())
		{
			if (input.interact(stall, STEAL_ACTION))
			{
				noteSteal();
			}
			else
			{
				cooldown = 1;
				setStatus("Closing menu");
			}
			return;
		}

		WorldPoint playerLocation = context.getPlayerLocation();
		WorldPoint objectLocation = stall.getWorldLocation();
		if (playerLocation == null || objectLocation == null
			|| playerLocation.distanceTo(objectLocation) > STALL_INTERACT_DISTANCE)
		{
			if (objectLocation != null)
			{
				walker.walkTo(objectLocation, false, STALL_INTERACT_DISTANCE);
			}

			setStatus("Walking to fruit stall");
			return;
		}

		if (cooldown > 0)
		{
			setStatus("Waiting for fruit stall");
			return;
		}

		// Lock onto the middle of this stall, then reuse that exact point. If
		// the camera moves, the point is re-aimed before the next click.
		if (stallClickPoint == null || !isPointOnStall(stall, stallClickPoint))
		{
			if (!input.isVisible(stall))
			{
				if (input.isCameraBusy() || input.turnCameraToward(stall.getWorldLocation()))
				{
					setStatus("Turning camera to fruit stall");
					return;
				}
			}

			input.stopCamera();

			Point point = centerOf(stall);
			if (point == null || !isPointOnStall(stall, point))
			{
				stallClickPoint = null;
				cooldown = 1;
				setStatus("Waiting for fruit stall");
				return;
			}

			stallClickPoint = point;
		}

		// Steal-from is the stall's only action, so a normal left click is the
		// fastest way to start the steal. Fall back to the context menu if the
		// action has been rearranged.
		boolean clicked;
		if (isFirstAction(client, stall, STEAL_ACTION))
		{
			clicked = input.click(stallClickPoint);
		}
		else
		{
			clicked = input.interact(stall, STEAL_ACTION);
		}

		if (clicked)
		{
			noteSteal();
		}
		else
		{
			cooldown = 1;
			setStatus("Waiting for fruit stall");
		}
	}

	private void noteSteal()
	{
		cooldown = STEAL_COOLDOWN_TICKS;
		setStatus("Stealing from fruit stall");

		if (stayAtOneStall)
		{
			// Keep the same stall and the same click point, then wait for it to
			// turn back into a full stall.
			return;
		}

		stallClickPoint = null;
		stallIndex = 1 - stallIndex;

		if (walker != null)
		{
			walker.reset();
		}
	}

	private Optional<TileObject> findFullStall(ScriptContext context, WorldPoint location)
	{
		Client client = context.getClient();
		TileObject best = null;
		int bestDistance = Integer.MAX_VALUE;

		for (TileObject object : context.getTargets().getTileObjects(location, STALL_RADIUS))
		{
			if (!hasAction(client, object, STEAL_ACTION) || !isFruitStall(client, object))
			{
				continue;
			}

			WorldPoint objectLocation = object.getWorldLocation();
			if (objectLocation == null)
			{
				continue;
			}

			int distance = objectLocation.distanceTo(location);
			if (distance < bestDistance)
			{
				bestDistance = distance;
				best = object;
			}
		}

		return Optional.ofNullable(best);
	}

	private static int nearestStall(ScriptContext context)
	{
		WorldPoint playerLocation = context.getPlayerLocation();
		if (playerLocation == null)
		{
			return 0;
		}

		int leftDistance = playerLocation.distanceTo(STALL_LOCATIONS[0]);
		int rightDistance = playerLocation.distanceTo(STALL_LOCATIONS[1]);
		return rightDistance < leftDistance ? 1 : 0;
	}

	private static boolean isFruitStall(Client client, TileObject object)
	{
		if (object.getId() == LEFT_STALL_ID || object.getId() == RIGHT_STALL_ID)
		{
			return true;
		}

		ObjectComposition composition = client.getObjectDefinition(object.getId());
		return composition != null && cleanText(composition.getName()).equalsIgnoreCase(STALL_NAME);
	}

	private static boolean hasAction(Client client, TileObject object, String action)
	{
		ObjectComposition composition = client.getObjectDefinition(object.getId());
		String[] actions = composition == null ? null : composition.getActions();

		if (actions == null)
		{
			return false;
		}

		for (String candidate : actions)
		{
			if (candidate != null && cleanText(candidate).equalsIgnoreCase(action))
			{
				return true;
			}
		}

		return false;
	}

	private void drop(ScriptContext context)
	{
		Inventory inventory = context.getInventory();
		GameInput input = context.getInput();

		if (!inventory.isOpen())
		{
			if (input.isBusy())
			{
				setStatus("Opening inventory");
				return;
			}

			if (inventory.open())
			{
				cooldown = 1;
				setStatus("Opening inventory");
			}
			else
			{
				cooldown = 2;
				setStatus("Open your inventory");
			}
			return;
		}

		if (context.getClient().isMenuOpen())
		{
			if (!input.isBusy())
			{
				input.closeMenu();
			}
			setStatus("Closing menu");
			return;
		}

		int itemCount = countItems(inventory);
		int droppableCount = countDroppableItems(inventory);

		if (itemCount == 0)
		{
			state = State.STEAL;
			cooldown = 1;
			setStatus("Inventory empty, stealing again");
			return;
		}

		// The selected fruits are kept for banking. If they are the only
		// things left, there is nothing to drop and the player needs to bank
		// the inventory.
		if (droppableCount == 0)
		{
			state = State.STEAL;
			cooldown = 1;
			setStatus("Only kept fruit left, stealing again");
			return;
		}

		if (input.isBusy())
		{
			setStatus("Dropping inventory (" + droppableCount + " left)");
			return;
		}

		List<Widget> itemsToDrop = new ArrayList<>();
		Item[] items = inventory.getItems();

		for (int slot = 0; slot < Math.min(items.length, Inventory.SIZE)
			&& itemsToDrop.size() < DROP_BATCH_SIZE; slot++)
		{
			Item item = items[slot];
			if (item == null || item.getId() == -1 || isKeptItem(item.getId()))
			{
				continue;
			}

			Widget widget = inventory.getSlotWidget(slot);
			if (widget == null || widget.isHidden())
			{
				continue;
			}

			Rectangle bounds = widget.getBounds();
			if (bounds == null || bounds.isEmpty())
			{
				continue;
			}

			itemsToDrop.add(widget);
		}

		if (itemsToDrop.isEmpty())
		{
			cooldown = 1;
			setStatus("Waiting for inventory");
			return;
		}

		// Queue the items into one animated sequence that runs at double the
		// normal mouse speed. Two items fit inside a single game tick, so the
		// inventory empties in half the time while the cursor still visibly
		// moves from item to item.
		if (!input.clickEach(itemsToDrop, DROP_SPEED_MULTIPLIER))
		{
			cooldown = 1;
			setStatus("Waiting for inventory");
			return;
		}

		cooldown = 0;
		setStatus("Dropping inventory (" + droppableCount + " left)");
	}

	/**
	 * @return a point inside the stall's clickable hull, as close to its centre
	 * as possible, or its canvas location when the hull is unavailable
	 */
	private static Point centerOf(TileObject object)
	{
		Shape clickbox = object.getClickbox();
		if (clickbox != null)
		{
			Point point = pointInside(clickbox);
			if (point != null)
			{
				return point;
			}
		}

		Polygon tilePoly = object.getCanvasTilePoly();
		if (tilePoly != null)
		{
			Point point = pointInside(tilePoly);
			if (point != null)
			{
				return point;
			}
		}

		net.runelite.api.Point location = object.getCanvasLocation();
		return location == null ? null : new Point(location.getX(), location.getY());
	}

	/**
	 * The centre of a hull's bounding box can fall outside a non-rectangular
	 * hull. Start at that centre and search outwards for the nearest point
	 * that is actually inside the shape.
	 */
	private static Point pointInside(Shape shape)
	{
		Rectangle bounds = shape == null ? null : shape.getBounds();
		if (bounds == null || bounds.isEmpty())
		{
			return null;
		}

		int centerX = (int) Math.round(bounds.getCenterX());
		int centerY = (int) Math.round(bounds.getCenterY());

		if (shape.contains(centerX, centerY))
		{
			return new Point(centerX, centerY);
		}

		int maxRadius = Math.max(bounds.width, bounds.height) / 2;
		for (int radius = 1; radius <= maxRadius; radius++)
		{
			for (int dx = -radius; dx <= radius; dx++)
			{
				for (int dy = -radius; dy <= radius; dy++)
				{
					if (Math.max(Math.abs(dx), Math.abs(dy)) != radius)
					{
						continue;
					}

					int x = centerX + dx;
					int y = centerY + dy;
					if (shape.contains(x, y))
					{
						return new Point(x, y);
					}
				}
			}
		}

		return null;
	}

	private static boolean isPointOnStall(TileObject stall, Point point)
	{
		if (point == null)
		{
			return false;
		}

		Shape clickbox = stall.getClickbox();
		if (clickbox != null)
		{
			return clickbox.contains(point);
		}

		Polygon tilePoly = stall.getCanvasTilePoly();
		return tilePoly != null && tilePoly.contains(point);
	}

	private static boolean isFirstAction(Client client, TileObject object, String action)
	{
		ObjectComposition composition = client.getObjectDefinition(object.getId());
		return composition != null && isFirstAction(composition.getActions(), action);
	}

	private static boolean isFirstAction(String[] actions, String action)
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

	private static int countItems(Inventory inventory)
	{
		int count = 0;
		for (Item item : inventory.getItems())
		{
			if (item != null && item.getId() != -1)
			{
				++count;
			}
		}

		return count;
	}

	private int countDroppableItems(Inventory inventory)
	{
		int count = 0;
		for (Item item : inventory.getItems())
		{
			if (item != null && item.getId() != -1 && !isKeptItem(item.getId()))
			{
				++count;
			}
		}

		return count;
	}

	private boolean isKeptItem(int itemId)
	{
		return keptItems.contains(itemId);
	}

	private void updateStats()
	{
		Inventory inventory = getContext().getInventory();
		Client client = getContext().getClient();
		long now = System.currentTimeMillis();
		int itemCount = countItems(inventory);

		if (!statsInitialized)
		{
			statsInitialized = true;
			statsStartedAt = now;
			lastItemCount = itemCount;
			startThievingXp = client.getSkillExperience(Skill.THIEVING);
			return;
		}

		if (itemCount > lastItemCount)
		{
			steals += itemCount - lastItemCount;
		}
		else if (itemCount < lastItemCount)
		{
			itemsDropped += lastItemCount - itemCount;
		}

		lastItemCount = itemCount;
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

	private static String formatPerHour(int amount, long elapsedMs)
	{
		return formatNumber(perHour(amount, elapsedMs));
	}

	private static long perHour(long amount, long elapsedMs)
	{
		return elapsedMs <= 0 ? 0L : Math.round(amount * 3_600_000.0 / elapsedMs);
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
