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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.Item;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.widgets.Widget;
import net.runelite.rsbuddy.input.GameInput;
import net.runelite.rsbuddy.inventory.Inventory;
import net.runelite.rsbuddy.script.Script;
import net.runelite.rsbuddy.script.ScriptContext;

/**
 * Steals from the eastern baker's stall in the East Ardougne market, waits for
 * the empty stall to turn back into the baker's stall, and repeats. When the
 * inventory fills up, every item is dropped with the inventory's left-click
 * action before stealing continues.
 * <p>
 * The player is expected to stand at the stall, around {@code 2669, 3310}.
 * There is a second baker's stall further west, so the target is restricted to
 * the stall at that spot instead of simply taking the nearest one.
 */
public final class ArdougneBakerStallStealerScript extends Script
{
	private static final int BAKER_STALL_ID = ObjectID.CAKETHIEFSTALL;
	private static final int EMPTY_STALL_ID = ObjectID.MARKET;

	private static final String STEAL_ACTION = "Steal-from";

	/**
	 * The eastern baker's stall. The object sits on or right next to the tile
	 * the player stands on.
	 */
	private static final WorldPoint STALL_LOCATION = new WorldPoint(2669, 3310, 0);

	// The empty market stall is on the same tile as the baker's stall. A small
	// radius allows for the stall model sitting one tile away from the tile the
	// player was told to stand on, while still excluding the other bakery stall
	// fourteen tiles west.
	private static final int STALL_RADIUS = 3;

	// Only look for stalls close to the player. At the target stall this is more
	// than enough, and it avoids picking up a bakery stall elsewhere in the
	// market if the player has wandered off.
	private static final int STALL_DISTANCE = 10;

	// After a steal click, give the game a few ticks to turn the stall into the
	// empty market stall. This also stops a second click being sent while the
	// first one is still being processed.
	private static final int STEAL_COOLDOWN_TICKS = 3;

	// Drop two items per game tick. Each batch uses the normal animated mouse
	// path at double speed, so the inventory empties exactly twice as fast as
	// clicking one item per tick.
	private static final int DROP_BATCH_SIZE = 2;
	private static final double DROP_SPEED_MULTIPLIER = 2.0;

	private enum State
	{
		STEAL,
		DROP
	}

	private State state;
	private int cooldown;

	/**
	 * Centre of the stall clickbox, computed once and reused for every steal.
	 * Keeping this point means the mouse stays parked on the stall instead of
	 * jumping to a new random spot on each click.
	 */
	private Point stallClickPoint;

	private int lastItemCount;
	private int steals;
	private int itemsDropped;

	private boolean statsInitialized;
	private long statsStartedAt;
	private long startThievingXp;

	public ArdougneBakerStallStealerScript()
	{
		super("Ardougne Baker Stall Stealer",
			"Steals from the East Ardougne baker's stall and drops the inventory when full.");
	}

	@Override
	protected void onStart()
	{
		// onStart runs on the Swing thread, so it must not touch the client.
		state = State.STEAL;
		cooldown = 0;
		stallClickPoint = null;
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

		updateStats();

		if (cooldown > 0)
		{
			--cooldown;
			return;
		}

		if (state == null)
		{
			state = State.STEAL;
		}

		switch (state)
		{
			case STEAL:
				steal(context);
				break;
			case DROP:
				drop(context);
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

		long elapsed = Math.max(1L, System.currentTimeMillis() - statsStartedAt);
		Client client = getContext().getClient();
		long gainedXp = Math.max(0L, client.getSkillExperience(Skill.THIEVING) - startThievingXp);

		List<String> lines = new ArrayList<>();
		lines.add("");
		lines.add("Ardougne baker's stall");
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
			state = State.DROP;
			setStatus("Inventory full, dropping items");
			return;
		}

		if (input.isBusy())
		{
			setStatus("Moving mouse");
			return;
		}

		Optional<TileObject> target = findBakerStall(context);

		// If a right-click menu is already open (for example because the stall's
		// steal option is not its default action), finish the interaction by
		// clicking the steal entry instead of closing the menu and starting over.
		if (client.isMenuOpen())
		{
			if (target.isPresent() && input.interact(target.get(), STEAL_ACTION))
			{
				cooldown = STEAL_COOLDOWN_TICKS;
				setStatus("Stealing from baker's stall");
			}
			else
			{
				input.closeMenu();
				cooldown = 1;
				setStatus("Closing menu");
			}
			return;
		}

		if (target.isEmpty())
		{
			if (findEmptyStall(context).isPresent())
			{
				setStatus("Waiting for baker's stall to respawn");
			}
			else
			{
				setStatus("No baker's stall at 2669, 3310");
			}
			return;
		}

		TileObject stall = target.get();

		// Lock onto the middle of the stall once, then keep clicking that exact
		// point. If the camera or the stall moves, the saved point can end up
		// outside the stall, so it is re-aimed before every click.
		if (stallClickPoint == null || !isPointOnStall(stall, stallClickPoint))
		{
			if (!input.isVisible(stall))
			{
				if (input.isCameraBusy() || input.turnCameraToward(stall.getWorldLocation()))
				{
					setStatus("Turning camera to baker's stall");
					return;
				}
			}

			input.stopCamera();

			Point point = centerOf(stall);
			if (point == null || !isPointOnStall(stall, point))
			{
				stallClickPoint = null;
				cooldown = 1;
				setStatus("Waiting for baker's stall");
				return;
			}

			stallClickPoint = point;
		}

		// Steal-from is the stall's only action, so a normal left click is both
		// the fastest and the least conspicuous way to start the steal. If the
		// action has been rearranged, fall back to the context menu.
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
			cooldown = STEAL_COOLDOWN_TICKS;
			setStatus("Stealing from baker's stall");
		}
		else
		{
			cooldown = 1;
			setStatus("Waiting for baker's stall");
		}
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
		if (itemCount == 0)
		{
			state = State.STEAL;
			cooldown = 1;
			setStatus("Inventory empty, stealing again");
			return;
		}

		if (input.isBusy())
		{
			setStatus("Dropping inventory (" + itemCount + " left)");
			return;
		}

		List<Widget> itemsToDrop = new ArrayList<>();
		Item[] items = inventory.getItems();

		for (int slot = 0; slot < Math.min(items.length, Inventory.SIZE)
			&& itemsToDrop.size() < DROP_BATCH_SIZE; slot++)
		{
			Item item = items[slot];
			if (item == null || item.getId() == -1)
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
		setStatus("Dropping inventory (" + itemCount + " left)");
	}

	private Optional<TileObject> findBakerStall(ScriptContext context)
	{
		return context.getTargets()
			.findTileObject(BAKER_STALL_ID, STALL_DISTANCE)
			.filter(this::isTargetStall);
	}

	private Optional<TileObject> findEmptyStall(ScriptContext context)
	{
		return context.getTargets()
			.findTileObject(EMPTY_STALL_ID, STALL_DISTANCE)
			.filter(this::isTargetStall);
	}

	private boolean isTargetStall(TileObject object)
	{
		WorldPoint location = object.getWorldLocation();
		return location != null
			&& location.getPlane() == STALL_LOCATION.getPlane()
			&& location.distanceTo(STALL_LOCATION) <= STALL_RADIUS;
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

	/**
	 * @return true if the point is still inside the stall, meaning a click
	 * there will actually target the stall
	 */
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
