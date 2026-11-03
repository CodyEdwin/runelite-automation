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
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.rsbuddy.walk;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.rsbuddy.input.GameInput;

/**
 * Walks the player toward a world position using the collision map.
 * <p>
 * A breadth-first search over the scene's collision flags finds a walkable
 * route, and a point a few tiles along that route is clicked, either on the
 * minimap or on the game tile itself. The route is re-checked and the next
 * waypoint clicked while the player is still running, the way a player keeps
 * clicking ahead of themselves on the minimap.
 */
public final class Walker
{
	private static final int[][] WALK_DIRECTIONS = {
		{0, 1}, {1, 0}, {1, 1}, {1, -1},
		{0, -1}, {-1, 0}, {-1, -1}, {-1, 1}
	};

	// A minimap click is only repeated when the player is standing still and
	// the previous click produced no movement (failed click or blocked path).
	private static final int CLICK_RETRY_TICKS = 2;

	private final Client client;
	private final GameInput input;

	private WorldPoint lastLocation;
	private WorldPoint clickedTarget;
	private WorldPoint requestedTarget;
	private int ticksSinceClick;

	public Walker(Client client, GameInput input)
	{
		this.client = client;
		this.input = input;
	}

	/**
	 * Forgets the last walk click, so the next call can click immediately.
	 */
	public void reset()
	{
		lastLocation = null;
		clickedTarget = null;
		requestedTarget = null;
		ticksSinceClick = CLICK_RETRY_TICKS;
	}

	/**
	 * Walks toward the target, clicking the game tile when it is on screen and
	 * the minimap when it is not.
	 *
	 * @return true if a click was sent
	 */
	public boolean walkTo(WorldPoint target)
	{
		return walkTo(target, false);
	}

	/**
	 * Walks toward the target.
	 *
	 * @param useMinimap always prefer a minimap click, which can never hit a
	 *                   game object such as a door
	 * @return true if a click was sent
	 */
	public boolean walkTo(WorldPoint target, boolean useMinimap)
	{
		return walkTo(target, useMinimap, 2);
	}

	/**
	 * Walks toward the target, stopping when the player is within the given
	 * distance of it.
	 *
	 * @param useMinimap    always prefer a minimap click, which can never hit
	 *                      a game object such as a door
	 * @param stopDistance  how close the player needs to get before walking
	 *                      stops; use 1 to stand next to an object
	 * @return true if a click was sent
	 */
	public boolean walkTo(WorldPoint target, boolean useMinimap, int stopDistance)
	{
		if (target == null || input.isBusy())
		{
			return false;
		}

		Player player = client.getLocalPlayer();
		WorldPoint location = player == null ? null : player.getWorldLocation();
		if (location == null)
		{
			return false;
		}

		if (location.distanceTo(target) <= Math.max(0, stopDistance))
		{
			reset();
			return false;
		}

		// The minimap is drawn around the player, so a click must only be
		// computed while standing still: one accurate click, then wait out
		// the whole walk instead of spamming the minimap every couple ticks.
		boolean moving = isMoving(player)
			|| (lastLocation != null && !lastLocation.equals(location));
		lastLocation = location;
		if (moving)
		{
			ticksSinceClick = 0;
			return false;
		}

		boolean sameTarget = requestedTarget != null
			&& requestedTarget.distanceTo(target) <= 1;
		requestedTarget = target;

		if (sameTarget && clickedTarget != null && ticksSinceClick < CLICK_RETRY_TICKS)
		{
			++ticksSinceClick;
			return false;
		}

		List<WorldPoint> path = findPath(target);
		if (path.size() <= 1)
		{
			return false;
		}

		// Freeze the camera first, otherwise the minimap rotates while the
		// mouse is still travelling and the click lands on the wrong tile.
		input.stopCamera();

		if (useMinimap)
		{
			// Click the farthest point of the route that is actually on the
			// minimap. Normally that is the goal itself, so one click walks
			// the full distance.
			for (int i = path.size() - 1; i >= 1; i--)
			{
				WorldPoint point = path.get(i);
				if (clickMinimap(point))
				{
					clickedTarget = point;
					ticksSinceClick = 0;
					return true;
				}
			}
			return false;
		}

		WorldPoint destination = path.get(path.size() - 1);
		if (input.walkTo(destination))
		{
			clickedTarget = destination;
			ticksSinceClick = 0;
			return true;
		}

		return false;
	}

	private static boolean isMoving(Player player)
	{
		return player != null
			&& player.getPoseAnimation() != player.getIdlePoseAnimation();
	}

	/**
	 * Clicks a point on the minimap. Minimap clicks never land on a game
	 * object, so a route that passes a door cannot toggle it.
	 *
	 * @return true if the click was sent
	 */
	public boolean clickMinimap(WorldPoint point)
	{
		LocalPoint localPoint = LocalPoint.fromWorld(client, point);
		if (localPoint == null)
		{
			return false;
		}

		net.runelite.api.Point minimapPoint = Perspective.localToMinimap(client, localPoint);
		return minimapPoint != null
			&& input.click(new Point(minimapPoint.getX(), minimapPoint.getY()));
	}

	/**
	 * @return true if a walkable route reaches the goal
	 */
	public boolean canReach(WorldPoint goal)
	{
		List<WorldPoint> path = findPath(goal);
		if (path.isEmpty())
		{
			return false;
		}

		WorldPoint end = path.get(path.size() - 1);
		return end.distanceTo(goal) <= 1;
	}

	/**
	 * @return true if the player's plane has an open tile at that point
	 */
	public boolean isWalkable(WorldPoint point)
	{
		if (point == null)
		{
			return false;
		}

		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null)
		{
			return false;
		}

		Scene scene = worldView.getScene();
		CollisionData[] collisionMaps = worldView.getCollisionMaps();
		int plane = worldView.getPlane();
		if (scene == null || point.getPlane() != plane || collisionMaps == null
			|| plane < 0 || plane >= collisionMaps.length || collisionMaps[plane] == null)
		{
			return false;
		}

		int[][] flags = collisionMaps[plane].getFlags();
		if (flags == null || flags.length == 0)
		{
			return false;
		}

		int x = point.getX() - scene.getBaseX();
		int y = point.getY() - scene.getBaseY();
		return inBounds(x, y, flags.length, flags[0].length)
			&& (flags[x][y] & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0;
	}

	/**
	 * Finds a walkable route from the player to the goal. When the goal cannot
	 * be reached, the route ends at the reachable tile closest to it.
	 *
	 * @return the route, including the player's current tile
	 */
	public List<WorldPoint> findPath(WorldPoint goal)
	{
		Player player = client.getLocalPlayer();
		WorldPoint start = player == null ? null : player.getWorldLocation();
		WorldView worldView = client.getTopLevelWorldView();
		if (start == null || goal == null || worldView == null)
		{
			return Collections.emptyList();
		}

		Scene scene = worldView.getScene();
		CollisionData[] collisionMaps = worldView.getCollisionMaps();
		int plane = worldView.getPlane();
		if (scene == null || collisionMaps == null || plane < 0 || plane >= collisionMaps.length
			|| collisionMaps[plane] == null)
		{
			return Collections.emptyList();
		}

		int[][] flags = collisionMaps[plane].getFlags();
		if (flags == null || flags.length == 0)
		{
			return Collections.emptyList();
		}

		int width = flags.length;
		int height = flags[0].length;
		int baseX = scene.getBaseX();
		int baseY = scene.getBaseY();
		int startX = start.getX() - baseX;
		int startY = start.getY() - baseY;
		if (!inBounds(startX, startY, width, height))
		{
			return Collections.emptyList();
		}

		int goalX = goal.getX() - baseX;
		int goalY = goal.getY() - baseY;
		boolean goalInScene = inBounds(goalX, goalY, width, height);

		int total = width * height;
		int[] previous = new int[total];
		Arrays.fill(previous, -1);
		boolean[] visited = new boolean[total];
		int[] queue = new int[total];
		int head = 0;
		int tail = 0;
		int startIndex = startY * width + startX;
		visited[startIndex] = true;
		queue[tail++] = startIndex;

		int bestIndex = startIndex;
		long bestDistance = distanceSquared(baseX + startX, baseY + startY, goal);
		int expansions = 0;

		while (head < tail && expansions++ < total)
		{
			int current = queue[head++];
			int x = current % width;
			int y = current / width;

			if (goalInScene && x == goalX && y == goalY)
			{
				bestIndex = current;
				break;
			}

			long distance = distanceSquared(baseX + x, baseY + y, goal);
			if (distance < bestDistance)
			{
				bestDistance = distance;
				bestIndex = current;
			}

			for (int[] direction : WALK_DIRECTIONS)
			{
				int nextX = x + direction[0];
				int nextY = y + direction[1];
				if (!inBounds(nextX, nextY, width, height))
				{
					continue;
				}

				int nextIndex = nextY * width + nextX;
				if (visited[nextIndex] || !canMove(flags, x, y, direction[0], direction[1]))
				{
					continue;
				}

				visited[nextIndex] = true;
				previous[nextIndex] = current;
				queue[tail++] = nextIndex;
			}
		}

		List<WorldPoint> path = new ArrayList<>();
		for (int index = bestIndex; index != -1; index = previous[index])
		{
			int x = index % width;
			int y = index / width;
			path.add(new WorldPoint(baseX + x, baseY + y, plane));
			if (index == startIndex)
			{
				break;
			}
		}

		Collections.reverse(path);
		return path;
	}

	private static boolean inBounds(int x, int y, int width, int height)
	{
		return x >= 0 && y >= 0 && x < width && y < height;
	}

	private static boolean canMove(int[][] flags, int x, int y, int dx, int dy)
	{
		int source = flags[x][y];
		int destination = flags[x + dx][y + dy];

		// Solid objects, water, and similar tiles block movement into them.
		if ((destination & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0)
		{
			return false;
		}

		int flag;
		int reverseFlag;

		if (dx == 0)
		{
			flag = dy > 0 ? CollisionDataFlag.BLOCK_MOVEMENT_NORTH
				: CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
			reverseFlag = dy > 0 ? CollisionDataFlag.BLOCK_MOVEMENT_SOUTH
				: CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
		}
		else if (dy == 0)
		{
			flag = dx > 0 ? CollisionDataFlag.BLOCK_MOVEMENT_EAST
				: CollisionDataFlag.BLOCK_MOVEMENT_WEST;
			reverseFlag = dx > 0 ? CollisionDataFlag.BLOCK_MOVEMENT_WEST
				: CollisionDataFlag.BLOCK_MOVEMENT_EAST;
		}
		else if (dx > 0)
		{
			flag = dy > 0 ? CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST
				: CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST;
			reverseFlag = dy > 0 ? CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST
				: CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST;
		}
		else
		{
			flag = dy > 0 ? CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST
				: CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST;
			reverseFlag = dy > 0 ? CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST
				: CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST;
		}

		if ((source & flag) != 0 || (destination & reverseFlag) != 0)
		{
			return false;
		}

		// Do not cut diagonally through the corner of a wall.
		if (dx != 0 && dy != 0)
		{
			int horizontal = dx > 0 ? CollisionDataFlag.BLOCK_MOVEMENT_EAST
				: CollisionDataFlag.BLOCK_MOVEMENT_WEST;
			int vertical = dy > 0 ? CollisionDataFlag.BLOCK_MOVEMENT_NORTH
				: CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
			if ((source & horizontal) != 0 || (source & vertical) != 0)
			{
				return false;
			}
		}

		return true;
	}

	private static long distanceSquared(int x, int y, WorldPoint target)
	{
		long dx = (long) x - target.getX();
		long dy = (long) y - target.getY();
		return dx * dx + dy * dy;
	}
}
