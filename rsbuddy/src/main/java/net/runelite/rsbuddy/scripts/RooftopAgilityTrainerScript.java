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
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.Item;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.rsbuddy.input.GameInput;
import net.runelite.rsbuddy.plugin.RoofCourse;
import net.runelite.rsbuddy.script.Script;
import net.runelite.rsbuddy.script.ScriptContext;
import net.runelite.rsbuddy.walk.Walker;

/**
 * Runs a rooftop agility course. The course and its obstacles are chosen by
 * the {@link RoofCourse} setting in Test Plugin config (Varrock by default;
 * add more courses as {@link Course} instances to support other locations).
 * <p>
 * The course is a fixed sequence of obstacles. After an obstacle is clicked the
 * script waits until its animation has finished and the player has stopped
 * moving before it looks for the next one, so the next click is sent as soon as
 * the current part can be reached. If the player falls, or the next obstacle is
 * not where it should be, the script walks back to the first obstacle and
 * starts the lap over.
 */
public final class RooftopAgilityTrainerScript extends Script
{
	private static final int SEARCH_RADIUS = 6;

	// Tiles around the first wall that the player can stand on to climb it.
	private static final int[][] STAND_OFFSETS = {
		{0, 1}, {0, -1}, {-1, 0}, {1, 0}, {-1, 1}, {1, 1}, {-1, -1}, {1, -1}
	};
	private static final int START_WALK_DISTANCE = 5;
	private static final int START_OBJECT_CLICK_DISTANCE = 1;
	private static final int MAX_DIRECT_CLICK_DISTANCE = 16;
	private static final int MAX_CLICK_ATTEMPTS = 4;
	private static final int MAX_MISSING_TICKS = 3;
	private static final int CLICK_FAILURE_TICKS = 5;
	private static final int OBSTACLE_TIMEOUT_TICKS = 40;
	private static final int MAX_STUCK_TICKS = 12;
	private static final int MOVE_ONLY_MIN_DISTANCE = 2;
	private static final int MOVE_ONLY_MIN_CROSS_DISTANCE = 4;
	private static final int STATIC_TICKS_BEFORE_CLICK = 1;
	private static final int ANIMATION_SETTLE_TICKS = 2;
	private static final int AIM_TOLERANCE_PX = 3;
	private static final int AIM_MENU_ATTEMPTS = 3;
	private static final int CAMERA_SETTLE_UNITS = 64;
	private static final int CAMERA_WAIT_LIMIT_TICKS = 10;

	private static final int MARK_PICKUP_DISTANCE = 12;
	private static final int MARK_NEAR_DISTANCE = 3;
	// Human pacing: spot the mark, pause as if noticing it, then walk and
	// reach for it. Fixed tick values make the pickup look scripted, so every
	// delay is a small random range.
	private static final int MARK_REACTION_MIN_TICKS = 2;
	private static final int MARK_REACTION_MAX_TICKS = 5;
	private static final int MARK_DWELL_MIN_TICKS = 1;
	private static final int MARK_DWELL_MAX_TICKS = 3;
	private static final int MARK_RETRY_MIN_TICKS = 4;
	private static final int MARK_RETRY_MAX_TICKS = 7;
	private static final int MARK_ATTEMPT_LIMIT = 3;
	private static final int MARK_IGNORE_TICKS = 100;
	private static final int MARK_TARGET_TIMEOUT_TICKS = 30;
	private static final String MARK_NAME = "Mark of grace";

	private static final class Obstacle
	{
		private final int id;
		private final String name;
		/**
		 * Every menu option that starts this obstacle, most likely first. The
		 * client's action names differ between courses (Jump vs Leap, Vault vs
		 * Cross), so storing aliases keeps the click working without guessing
		 * the exact wording for each new course.
		 */
		private final String[] actions;
		private final WorldPoint location;
		/**
		 * True when the obstacle finishes by movement alone and plays no
		 * dependable player animation (tightropes, pole vaults, forced leaps).
		 * The script treats it as complete once the player has moved far enough
		 * past it, instead of waiting for an animation that never comes.
		 */
		private final boolean movementOnly;

		private Obstacle(int id, String name, String action, WorldPoint location)
		{
			this(id, name, action, location, false);
		}

		private Obstacle(int id, String name, String action, WorldPoint location, boolean movementOnly)
		{
			this(id, name, action, location, movementOnly, new String[0]);
		}

		private Obstacle(int id, String name, String action, WorldPoint location, boolean movementOnly,
			String... aliases)
		{
			this.id = id;
			this.name = name;
			this.actions = new String[aliases.length + 1];
			this.actions[0] = action;
			System.arraycopy(aliases, 0, this.actions, 1, aliases.length);
			this.location = location;
			this.movementOnly = movementOnly;
		}
	}

	private static final class Course
	{
		private final String name;
		private final Obstacle[] obstacles;
		/**
		 * True when a new lap starts right next to the first obstacle, so the
		 * script can click it like any other obstacle instead of walking to a
		 * standing tile first. Canifis finishes at the foot of the start tree;
		 * Varrock finishes across the square from the wall, which must be
		 * walked to or the click lands beside the building.
		 */
		private final boolean restartClicksStart;

		private Course(String name, Obstacle[] obstacles)
		{
			this(name, obstacles, false);
		}

		private Course(String name, Obstacle[] obstacles, boolean restartClicksStart)
		{
			this.name = name;
			this.obstacles = obstacles;
			this.restartClicksStart = restartClicksStart;
		}
	}

	private static final class GroundLoot
	{
		private final WorldPoint location;

		private GroundLoot(WorldPoint location)
		{
			this.location = location;
		}
	}

	private static final Course VARROCK = new Course("Varrock", new Obstacle[]
	{
		new Obstacle(ObjectID.ROOFTOPS_VARROCK_WALLCLIMB, "Rough wall", "Climb", new WorldPoint(3221, 3414, 0)),
		new Obstacle(ObjectID.ROOFTOPS_VARROCK_CLOTHESLINE, "Clothes line", "Cross", new WorldPoint(3213, 3414, 3)),
		new Obstacle(ObjectID.ROOFTOPS_VARROCK_LEAPTORUINS, "Gap", "Leap", new WorldPoint(3200, 3416, 3)),
		new Obstacle(ObjectID.ROOFTOPS_VARROCK_WALLSWING, "Wall", "Balance", new WorldPoint(3191, 3415, 1)),
		new Obstacle(ObjectID.ROOFTOPS_VARROCK_WALLSCRAMBLE, "Gap", "Leap", new WorldPoint(3193, 3401, 3)),
		new Obstacle(ObjectID.ROOFTOPS_VARROCK_LEAPTOBALCONY, "Gap", "Leap", new WorldPoint(3209, 3397, 3)),
		new Obstacle(ObjectID.ROOFTOPS_VARROCK_LEAPDOWN, "Gap", "Leap", new WorldPoint(3233, 3402, 3)),
		new Obstacle(ObjectID.ROOFTOPS_VARROCK_STEPUPROOF, "Ledge", "Hurdle", new WorldPoint(3236, 3409, 3)),
		new Obstacle(ObjectID.ROOFTOPS_VARROCK_FINISH, "Edge", "Jump-off", new WorldPoint(3236, 3416, 3))
	});

	private static final Course CANIFIS = new Course("Canifis", new Obstacle[]
	{
		// Order follows the course, not the object id numbering: tall tree,
		// gap, gap, gap (JUMP_5), gap (JUMP_3), pole-vault, gap (JUMP_4),
		// final leap down. This is the same order RuneLite's own agility
		// plugin tracks, and the order the player actually meets them.
		new Obstacle(ObjectID.ROOFTOPS_CANIFIS_START_TREE, "Tall tree", "Climb",
			new WorldPoint(3505, 3489, 0)),
		new Obstacle(ObjectID.ROOFTOPS_CANIFIS_JUMP, "Gap", "Jump",
			new WorldPoint(3505, 3498, 3), false, "Leap"),
		new Obstacle(ObjectID.ROOFTOPS_CANIFIS_JUMP_2, "Gap", "Jump",
			new WorldPoint(3496, 3504, 3), false, "Leap"),
		new Obstacle(ObjectID.ROOFTOPS_CANIFIS_JUMP_5, "Gap", "Jump",
			new WorldPoint(3485, 3499, 3), false, "Leap"),
		new Obstacle(ObjectID.ROOFTOPS_CANIFIS_JUMP_3, "Gap", "Jump",
			new WorldPoint(3478, 3491, 3), false, "Leap"),
		new Obstacle(ObjectID.ROOFTOPS_CANIFIS_POLEVAULT, "Pole-vault", "Vault",
			new WorldPoint(3480, 3483, 3), true, "Cross", "Jump", "Leap"),
		new Obstacle(ObjectID.ROOFTOPS_CANIFIS_JUMP_4, "Gap", "Jump",
			new WorldPoint(3503, 3476, 3), false, "Leap"),
		new Obstacle(ObjectID.ROOFTOPS_CANIFIS_LEAPDOWN, "Gap", "Jump",
			new WorldPoint(3510, 3483, 3), true, "Leap", "Jump-off")
	}, true);

	/**
	 * The course currently in use. Set from the constructor argument (the
	 * selected course from Test Plugin config), so the script can run any
	 * course that has a {@link Course} defined for it.
	 */
	private Course course;

	private Walker walker;
	private int index;
	private int laps;
	private int obstaclesCompleted;
	private int missingTicks;
	private int failedClicks;
	private int aimTicks;
	private int cameraWaitTicks;
	private int stuckTicks;
	private WorldPoint lastWalkLocation;
	private boolean aligned;

	private boolean waiting;
	private int waitTicks;
	private boolean sawAnimation;
	private int animationSettleTicks;
	private boolean sawMovement;
	private WorldPoint waitStartLocation;
	private WorldPoint lastLocation;
	private int stableTicks;

	private WorldPoint lastTickPlayerLocation;
	private int playerStillTicks;

	private GroundLoot markTarget;
	private int markTicks;
	private int markAttemptTicks;
	private int markAttempts;
	private int markReactionTicks;
	private int markReactionGoal;
	private int markDwellTicks;
	private int markDwellGoal;
	private final Map<WorldPoint, Integer> ignoredMarks = new HashMap<>();

	private boolean statsInitialized;
	private long statsStartedAt;
	private long startAgilityXp;
	private int lastMarkCount;
	private int marksCollected;

	public RooftopAgilityTrainerScript()
	{
		this(RoofCourse.VARROCK.name());
	}

	/**
	 * @param courseName the name of a {@link RoofCourse} constant (e.g. "VARROCK"
	 *                   or "CANIFIS"). Passed by name so reloaded scripts, which
	 *                   have their own copy of every class, can still receive it.
	 */
	public RooftopAgilityTrainerScript(String courseName)
	{
		super("Rooftop Agility Trainer", "Runs a rooftop agility course and recovers from falls.");

		RoofCourse selected = RoofCourse.VARROCK;
		try
		{
			selected = RoofCourse.valueOf(courseName);
		}
		catch (IllegalArgumentException ignored)
		{
			// Unknown course: fall back to Varrock.
		}

		course = selected == RoofCourse.CANIFIS ? CANIFIS : VARROCK;
	}

	@Override
	protected void onStart()
	{
		walker = null;
		index = 0;
		laps = 0;
		obstaclesCompleted = 0;
		missingTicks = 0;
		failedClicks = 0;
		aimTicks = 0;
		cameraWaitTicks = 0;
		stuckTicks = 0;
		lastWalkLocation = null;
		aligned = false;
		waiting = false;
		waitTicks = 0;
		sawAnimation = false;
		animationSettleTicks = 0;
		sawMovement = false;
		waitStartLocation = null;
		lastLocation = null;
		stableTicks = 0;
		lastTickPlayerLocation = null;
		playerStillTicks = 0;
		markTarget = null;
		markTicks = 0;
		markAttemptTicks = 0;
		markAttempts = 0;
		ignoredMarks.clear();
		statsInitialized = false;
		statsStartedAt = 0;
		startAgilityXp = 0;
		lastMarkCount = 0;
		marksCollected = 0;
		setStatus("Ready for " + course.name);
	}

	@Override
	protected void onGameTick()
	{
		ScriptContext context = getContext();

		if (!context.isLoggedIn())
		{
			waiting = false;
			setStatus("Waiting for login");
			return;
		}

		tickIgnoredMarks();

		if (walker == null)
		{
			walker = new Walker(context.getClient(), context.getInput());
		}

		updateStats();

		Player player = context.getPlayer();
		if (player == null)
		{
			setStatus("Waiting for player");
			return;
		}

		// Track how long the player has been standing still. Obstacle clicks
		// are only sent once the character is stationary, so the camera and
		// the object stay put while the mouse travels to the click point.
		WorldPoint tickLocation = player.getWorldLocation();
		if (tickLocation != null && tickLocation.equals(lastTickPlayerLocation))
		{
			++playerStillTicks;
		}
		else
		{
			playerStillTicks = 0;
		}
		lastTickPlayerLocation = tickLocation;

		if (!aligned)
		{
			alignToCourse(player);
			aligned = true;
		}

		if (waiting)
		{
			updateWaiting(player);

			// If the obstacle just finished or was given up on, carry on with
			// this same tick and click the next one immediately instead of
			// idling for another tick.
			if (waiting)
			{
				return;
			}
		}

		if (context.getClient().isMenuOpen())
		{
			context.getInput().closeMenu();
			setStatus("Closing menu");
			return;
		}

		if (context.getInput().isBusy())
		{
			setStatus("Moving mouse");
			return;
		}

		if (isAnimating(player))
		{
			setStatus("Waiting for animation");
			return;
		}

		Obstacle obstacle = course.obstacles[index];
		WorldPoint playerLocation = player.getWorldLocation();

		// A new lap starts on the ground. If the lap counter reset while the
		// jump-off was still finishing, the player can still be on a rooftop,
		// and walking to the ground-level wall from up there just wanders
		// across the roof towards the bank. Pick up the course where it is.
		if (index == 0 && playerLocation != null && playerLocation.getPlane() != 0)
		{
			alignToCourse(player);
			setStatus("Landing on the course");
			return;
		}

		// A fall drops the player to the ground (plane 0). The course itself
		// legitimately moves between rooftop planes 1 and 3, so a mismatch
		// there is not a fall and must not trigger recovery.
		if (playerLocation != null
			&& playerLocation.getPlane() == 0
			&& obstacle.location.getPlane() != 0)
		{
			if (++missingTicks >= MAX_MISSING_TICKS)
			{
				recover("off the course");
			}
			else
			{
				setStatus("Waiting for " + obstacle.name);
			}
			return;
		}

		// Marks of grace are only picked up when the player is on the course
		// section the mark is on and a walkable route actually reaches it.
		if (updateMarkPickup(context, player))
		{
			return;
		}

		TileObject target = findObstacle(context, obstacle);
		if (target == null)
		{
			// The obstacle is not loaded yet, so the player is too far away
			// (a fall can drop them well off the course). Walk back toward it
			// instead of waiting for it to appear.
			if (playerLocation != null
				&& playerLocation.distanceTo2D(obstacle.location) > SEARCH_RADIUS)
			{
				if (index == 0 && !course.restartClicksStart)
				{
					walkToFirstWall(obstacle.location, playerLocation, null, obstacle);
				}
				else
				{
					walkToward(obstacle.location, 4, obstacle.name);
				}
				return;
			}

			if (++missingTicks >= MAX_MISSING_TICKS)
			{
				recover("lost " + obstacle.name);
			}
			else
			{
				setStatus("Looking for " + obstacle.name);
			}
			return;
		}

		missingTicks = 0;

		WorldPoint targetLocation = target.getWorldLocation();
		int distance = playerLocation == null || targetLocation == null
			? Integer.MAX_VALUE
			: playerLocation.distanceTo2D(targetLocation);

		// On the rooftop, click the next obstacle from where the player
		// landed, even if it still looks a little far: walking closer just
		// clicks pathway tiles and can send the player off the course. If the
		// click does nothing, the failure path walks closer and retries.
		//
		// A course whose lap ends at the foot of the first obstacle (Canifis)
		// can click it exactly like any other obstacle. Courses that end away
		// from the first obstacle (Varrock) walk to it instead, because
		// clicking the wall from across the square lands beside the building.
		boolean clickStartDirectly = index == 0 && course.restartClicksStart;
		int walkDistance;
		if (index == 0 && !clickStartDirectly)
		{
			walkDistance = START_OBJECT_CLICK_DISTANCE;
		}
		else if (failedClicks > 0)
		{
			walkDistance = START_WALK_DISTANCE;
		}
		else
		{
			walkDistance = MAX_DIRECT_CLICK_DISTANCE;
		}
		boolean clickFromHere = distance <= walkDistance
			|| ((index > 0 || clickStartDirectly) && failedClicks == 0
				&& context.getInput().isVisible(target));

		if (!clickFromHere)
		{
			// Walk to a tile the player can actually stand on right next to the
			// first wall, not to the wall tile itself. The wall tile is not
			// walkable, and pathing to "the closest reachable tile" to it can
			// pick a tile inside whatever open building is geometrically
			// nearest (the bank), which is what walked the character into the
			// bank and then left the wall off screen.
			if (index == 0 && !clickStartDirectly && targetLocation != null)
		{
				walkToFirstWall(targetLocation, playerLocation, target, obstacle);
				return;
			}

			walkToward(obstacle.location, 4, obstacle.name);
			return;
		}

		// The object is close enough to use, but if the camera is facing away
		// there is nothing to click on screen. Face it instead of walking.
		if (!context.getInput().isVisible(target))
		{
			if (context.getInput().isCameraBusy()
				|| context.getInput().turnCameraToward(targetLocation))
			{
				setStatus("Turning camera to " + obstacle.name);
				return;
			}
		}

		context.getInput().stopCamera();

		// Wait until the character is standing still. The camera follows the
		// player while they move, so a click sent mid-step drags the object
		// out from under the cursor and lands on the pathway instead.
		if (playerStillTicks < STATIC_TICKS_BEFORE_CLICK)
		{
			setStatus("Waiting to click " + obstacle.name);
			return;
		}

		// The camera keeps easing after the arrow key is released. Clicking
		// before it stops slides the object out from under the cursor. The
		// wait is bounded so a camera that never quite settles cannot stall
		// the script; the hover-menu check below still blocks a blind click.
		if (!isCameraSettled(context))
		{
			if (++cameraWaitTicks < CAMERA_WAIT_LIMIT_TICKS)
			{
				setStatus("Waiting for camera");
				return;
			}
		}
		else
		{
			cameraWaitTicks = 0;
		}

		// Aim at the centre of the object's hull and click from there. A
		// random point on a tall clickbox can sit beside the model, where the
		// click walks instead of using the obstacle.
		java.awt.Point aim = centerOf(target);
		boolean sent;
		if (aim == null || failedClicks >= MAX_CLICK_ATTEMPTS - 1)
		{
			sent = context.getInput().invokeObjectAction(target, obstacle.actions);
		}
		else
		{
			if (context.getInput().isBusy())
			{
				setStatus("Moving mouse");
				return;
			}

			java.awt.Point mouse = context.getInput().getMouseLocation();
			boolean onTarget = mouse != null
				&& mouse.distance(aim) <= AIM_TOLERANCE_PX
				&& context.getInput().isMouseOver(target);

			if (!onTarget)
			{
				aimTicks = 0;
				if (context.getInput().moveTo(aim))
				{
					setStatus("Hovering " + obstacle.name);
					return;
				}

				// No part of the object is on screen, so clicking at the cursor
				// would hit something else. Use the exact option instead.
				sent = context.getInput().invokeObjectAction(target, obstacle.actions);
			}
			else if (!isDefaultObjectAction(context, target, obstacle.actions))
			{
				// The camera is still settling or another object slid under
				// the cursor. Do not click blind; wait for the client to agree
				// about what a left click would do.
				if (++aimTicks >= AIM_MENU_ATTEMPTS)
				{
					aimTicks = 0;
					sent = context.getInput().invokeObjectAction(target, obstacle.actions);
				}
				else
				{
					setStatus("Lining up " + obstacle.name);
					return;
				}
			}
			else
			{
				aimTicks = 0;
				sent = context.getInput().click(mouse);
			}
		}

		if (sent)
		{
			beginWaiting(playerLocation);
			setStatus("Clicked " + obstacle.name);
		}
		else if (++failedClicks >= MAX_CLICK_ATTEMPTS)
		{
			recover("could not click " + obstacle.name);
		}
		else
		{
			setStatus("Retrying " + obstacle.name);
		}
	}

	private void beginWaiting(WorldPoint playerLocation)
	{
		waiting = true;
		waitTicks = 0;
		sawAnimation = false;
		animationSettleTicks = 0;
		sawMovement = false;
		waitStartLocation = playerLocation;
		lastLocation = playerLocation;
		stableTicks = 0;
		lastWalkLocation = null;
		stuckTicks = 0;
	}

	/**
	 * Walks toward an obstacle and recovers if the player has not moved for
	 * too long. The walker intentionally skips some ticks between clicks, so
	 * only actual player movement resets the stall counter.
	 */
	private void walkToward(WorldPoint target, int stopDistance, String name)
	{
		walkToward(target, stopDistance, name, false);
	}

	/**
	 * @param useMinimap click the minimap instead of the game view, so the walk
	 *                   cannot hit an object such as a bank door or booth
	 */
	private void walkToward(WorldPoint target, int stopDistance, String name, boolean useMinimap)
	{
		Player player = getContext().getPlayer();
		WorldPoint location = player == null ? null : player.getWorldLocation();

		if (location != null && location.equals(lastWalkLocation))
		{
			++stuckTicks;
		}
		else
		{
			lastWalkLocation = location;
			stuckTicks = 0;
		}

		if (stuckTicks >= MAX_STUCK_TICKS)
		{
			recover("cannot reach " + name);
			return;
		}

		walker.walkTo(target, useMinimap, stopDistance);
		setStatus("Walking to " + name);
	}

	/**
	 * Walks to the first wall of the course.
	 * <p>
	 * The wall's own tile cannot be walked on, so the destination is a walkable,
	 * reachable tile right next to it on the player's side. Walking there on the
	 * minimap keeps stray clicks off nearby objects. If the player is boxed in
	 * and no such tile exists, the wall's own option is run instead of walking
	 * toward a tile the pathfinder can only approximate.
	 */
	private void walkToFirstWall(WorldPoint wall, WorldPoint playerLocation, TileObject target, Obstacle obstacle)
	{
		WorldPoint standTile = firstWallStandTile(wall, playerLocation);
		if (standTile == null)
		{
			// No tile next to the wall can be reached. If the wall is already
			// in range, run its option: the client walks the player over and
			// climbs when it gets there. Otherwise get next to the wall with a
			// plain walk and let the next tick click it.
			boolean inRange = playerLocation != null
				&& playerLocation.distanceTo2D(wall) <= 3;
			if (inRange && target != null
				&& getContext().getInput().invokeObjectAction(target, obstacle.actions))
			{
				beginWaiting(playerLocation);
				setStatus("Clicked " + obstacle.name);
				return;
			}

			walkToward(wall, 1, obstacle.name, true);
			return;
		}

		// Walk the whole way and only then climb, so the click never comes from
		// a tile the game decides to walk to on its own.
		walkToward(standTile, 0, obstacle.name, true);
	}

	/**
	 * @return the walkable tile next to the wall that is fewest steps from the
	 * player, so the route stays on the player's side of the building, or null
	 * when there is no such tile
	 */
	private WorldPoint firstWallStandTile(WorldPoint wall, WorldPoint player)
	{
		if (wall == null || player == null || walker == null)
		{
			return null;
		}

		WorldPoint best = null;
		int bestSteps = Integer.MAX_VALUE;
		int bestSide = Integer.MAX_VALUE;

		for (int[] offset : STAND_OFFSETS)
		{
			WorldPoint candidate = new WorldPoint(wall.getX() + offset[0], wall.getY() + offset[1],
				player.getPlane());
			if (!walker.isWalkable(candidate))
			{
				continue;
			}

			List<WorldPoint> path = walker.findPath(candidate);
			if (path.isEmpty() || !candidate.equals(path.get(path.size() - 1)))
			{
				continue;
			}

			// Prefer the tile closest to where the player already is when two
			// sides of the wall are both reachable.
			int side = candidate.distanceTo2D(player);
			int steps = path.size();
			if (steps < bestSteps || (steps == bestSteps && side < bestSide))
			{
				best = candidate;
				bestSteps = steps;
				bestSide = side;
			}
		}

		return best;
	}

	private void updateWaiting(Player player)
	{
		++waitTicks;

		WorldPoint location = player.getWorldLocation();
		if (isAnimating(player))
		{
			sawAnimation = true;
			animationSettleTicks = 0;
		}
		else if (sawAnimation)
		{
			++animationSettleTicks;
		}

		if (lastLocation != null && location != null && !lastLocation.equals(location))
		{
			sawMovement = true;
			stableTicks = 0;
		}
		else
		{
			++stableTicks;
		}

		lastLocation = location;

		Obstacle obstacle = course.obstacles[index];
		// Give the game a couple of ticks after the first climb's animation
		// ends. That climb teleports the player up to the rooftop, and the
		// plane change can arrive after the animation has already stopped;
		// checking too early used to see plane 0 and treat a successful
		// climb as a fall. The other obstacles stay on the rooftop, so they
		// complete the moment the animation ends to keep the lap fast.
		boolean needsSettle = index == 0;
		boolean animationDone = !isAnimating(player)
			&& (!needsSettle || animationSettleTicks >= ANIMATION_SETTLE_TICKS);
		boolean movementDone = stableTicks >= 1;

		if (sawAnimation && animationDone && movementDone)
		{
			if (fellOff(location))
			{
				recover("fell on " + obstacle.name);
				return;
			}

			completeObstacle();
			return;
		}

		// The clothes line is crossed by walking, and some later leaps move
		// the player without a normal animation. Once the player has actually
		// crossed to the far side, advance instead of clicking it again (which
		// would send them straight back over it).
		if (!sawAnimation && movementDone
			&& (isMovementOnlyComplete(obstacle, location) || crossedObstacle(obstacle, location)))
		{
			if (fellOff(location))
			{
				recover("fell on " + obstacle.name);
				return;
			}

			completeObstacle();
			return;
		}

		// Moving without the obstacle's animation means the click was treated
		// as a walk (or hit something else) instead of starting the obstacle.
		// Let the player stop and give the action a few ticks to begin before
		// clicking the obstacle again.
		if (!sawAnimation && sawMovement && stableTicks >= 2 && waitTicks >= 10)
		{
			waiting = false;

			if (++failedClicks >= MAX_CLICK_ATTEMPTS)
			{
				recover("could not start " + obstacle.name);
			}
			else
			{
				setStatus("Retrying " + obstacle.name);
			}
			return;
		}

		if (waitTicks >= OBSTACLE_TIMEOUT_TICKS)
		{
			waiting = false;
			recover("timed out on " + obstacle.name);
			return;
		}

		if (!sawAnimation && !sawMovement && waitTicks >= CLICK_FAILURE_TICKS)
		{
			waiting = false;

			if (++failedClicks >= MAX_CLICK_ATTEMPTS)
			{
				recover("click did nothing on " + obstacle.name);
			}
			else
			{
				setStatus("Retrying " + obstacle.name);
			}
			return;
		}

		setStatus("Doing " + obstacle.name);
	}

	private void completeObstacle()
	{
		waiting = false;
		failedClicks = 0;
		missingTicks = 0;
		stuckTicks = 0;
		lastWalkLocation = null;

		++obstaclesCompleted;

		if (index + 1 < course.obstacles.length)
		{
			++index;
			setStatus("Next: " + course.obstacles[index].name);
		}
		else
		{
			++laps;
			index = 0;
			setStatus("Lap complete (" + laps + ")");
		}

		if (walker != null)
		{
			walker.reset();
		}
	}

	/**
	 * The clothes line is crossed by walking and the final leap down is a
	 * forced movement; neither plays a dependable player animation. Both are
	 * complete once the player has moved to the far side of the obstacle.
	 */
	private boolean isMovementOnlyComplete(Obstacle obstacle, WorldPoint location)
	{
		if (waitStartLocation == null || location == null)
		{
			return false;
		}

		int moved = waitStartLocation.distanceTo2D(location);

		// Any course can mark an obstacle as completed by movement alone (no
		// player animation). Once the player has moved far enough, it is done.
		if (obstacle.movementOnly)
		{
			return moved >= MOVE_ONLY_MIN_DISTANCE;
		}

		if (obstacle.id == ObjectID.ROOFTOPS_VARROCK_CLOTHESLINE)
		{
			return moved >= MOVE_ONLY_MIN_DISTANCE
				&& location.getX() <= obstacle.location.getX();
		}

		// The final leap down is a forced movement that can finish without a
		// player animation. If the player has moved several tiles, it landed.
		if (obstacle.id == ObjectID.ROOFTOPS_VARROCK_LEAPDOWN)
		{
			return moved >= MOVE_ONLY_MIN_CROSS_DISTANCE;
		}

		return false;
	}

	/**
	 * Some obstacles, such as the leap down at the end of the Varrock course,
	 * move the player without playing a normal animation. Treat the obstacle
	 * as finished when the player has moved several tiles and is now on the
	 * far side of the obstacle relative to where the click was made. A short
	 * walk from a misclick cannot pass the obstacle centre, so it still gets
	 * retried.
	 */
	private boolean crossedObstacle(Obstacle obstacle, WorldPoint location)
	{
		if (waitStartLocation == null || location == null)
		{
			return false;
		}

		if (waitStartLocation.distanceTo2D(location) < MOVE_ONLY_MIN_CROSS_DISTANCE)
		{
			return false;
		}

		int towardX = obstacle.location.getX() - waitStartLocation.getX();
		int towardY = obstacle.location.getY() - waitStartLocation.getY();
		int movedX = location.getX() - waitStartLocation.getX();
		int movedY = location.getY() - waitStartLocation.getY();

		int lengthSquared = towardX * towardX + towardY * towardY;
		return lengthSquared == 0 || movedX * towardX + movedY * towardY > lengthSquared;
	}

	/**
	 * @return true if the player fell off the course. Rooftop obstacles
	 * legitimately move between planes 1 and 3, so only plane 0 is a fall.
	 * The last obstacle is the jump-off, which is supposed to end on plane 0.
	 */
	private boolean fellOff(WorldPoint location)
	{
		if (location == null)
		{
			return false;
		}

		if (index + 1 >= course.obstacles.length)
		{
			return location.getPlane() != 0;
		}

		return location.getPlane() == 0;
	}

	private void recover(String reason)
	{
		waiting = false;
		missingTicks = 0;
		failedClicks = 0;
		aimTicks = 0;
		cameraWaitTicks = 0;
		stuckTicks = 0;
		lastWalkLocation = null;
		clearMarkTarget();

		if (walker != null)
		{
			walker.reset();
		}

		Player player = getContext().getPlayer();
		WorldPoint location = player == null ? null : player.getWorldLocation();
		if (location != null && location.getPlane() != 0)
		{
			// Still on a rooftop, so the course is right here. Start again
			// from the nearest rooftop obstacle; resetting to the ground-level
			// wall would leave the script looking for an object that is not
			// loaded on this plane and stand there forever.
			alignToCourse(player);
		}
		else
		{
			index = 0;
		}

		setStatus("Recovering: " + reason);
	}

	/**
	 * If the script is started while already on the rooftop, begin at the
	 * nearest obstacle on the player's current plane instead of trying to walk
	 * to the ground-level first obstacle.
	 */
	private void alignToCourse(Player player)
	{
		WorldPoint location = player.getWorldLocation();
		if (location == null || location.getPlane() == 0)
		{
			return;
		}

		int bestIndex = -1;
		int bestDistance = Integer.MAX_VALUE;

		for (int i = 0; i < course.obstacles.length; i++)
		{
			Obstacle obstacle = course.obstacles[i];

			// Only the ground-level start is off the rooftop. The remaining
			// obstacles use planes 1 and 3 interchangeably, so compare them
			// by their 2D position.
			if (obstacle.location.getPlane() == 0)
			{
				continue;
			}

			int distance = location.distanceTo2D(obstacle.location);
			if (distance < bestDistance)
			{
				bestDistance = distance;
				bestIndex = i;
			}
		}

		if (bestIndex >= 0)
		{
			index = bestIndex;
			setStatus("Starting at " + course.obstacles[index].name);
		}
	}

	private TileObject findObstacle(ScriptContext context, Obstacle obstacle)
	{
		TileObject best = null;
		int bestDistance = Integer.MAX_VALUE;

		// Match by id and 2D position. The course's obstacle records use
		// several planes (the wall, for example, is stored on plane 1 while
		// the player is on the rooftop), so a plane comparison would hide the
		// very object the player is standing next to.
		for (TileObject object : context.getTargets().getTileObjectsNear(obstacle.location, SEARCH_RADIUS))
		{
			if (object.getId() != obstacle.id)
			{
				continue;
			}

			WorldPoint location = object.getWorldLocation();
			if (location == null)
			{
				continue;
			}

			int distance = location.distanceTo2D(obstacle.location);
			if (distance < bestDistance)
			{
				bestDistance = distance;
				best = object;
			}
		}

		return best;
	}

	/**
	 * Handles a Mark of Grace that is on the ground at the player's current
	 * course section. The mark's plane must match the player's plane, and a
	 * mark farther than a few tiles must have a walkable route to it, which
	 * stops the script from walking off the course for a mark on another
	 * rooftop section.
	 *
	 * @return true if a mark action was taken this tick
	 */
	private boolean updateMarkPickup(ScriptContext context, Player player)
	{
		if (markTarget != null && !markStillPresent(context, markTarget))
		{
			// The mark was picked up or despawned.
			clearMarkTarget();
			setStatus("Picked up Mark of Grace");
			return true;
		}

		WorldPoint playerLocation = player.getWorldLocation();
		if (playerLocation == null || playerLocation.getPlane() <= 0)
		{
			clearMarkTarget();
			return false;
		}

		if (markTarget == null)
		{
			markTarget = findMark(context, player);
			if (markTarget == null)
			{
				return false;
			}

			markTicks = 0;
			markAttemptTicks = 0;
			markAttempts = 0;
			markReactionTicks = 0;
			markDwellTicks = 0;
			// Pause for a moment before reacting, the way a player notices a
			// mark instead of snapping onto it the instant it appears. The
			// delay and the final reach are randomised so repeated pickups do
			// not all take exactly the same number of ticks.
			markReactionGoal = randomTicks(MARK_REACTION_MIN_TICKS, MARK_REACTION_MAX_TICKS);
			markDwellGoal = randomTicks(MARK_DWELL_MIN_TICKS, MARK_DWELL_MAX_TICKS);
		}

		++markTicks;
		++markAttemptTicks;

		WorldPoint markLocation = markTarget.location;

		// Never chase a mark after a fall, from another course section, or
		// for longer than a short detour.
		if (playerLocation.getPlane() != markLocation.getPlane())
		{
			clearMarkTarget();
			return false;
		}

		if (markTicks >= MARK_TARGET_TIMEOUT_TICKS)
		{
			ignoreMark(markLocation);
			clearMarkTarget();
			return false;
		}

		// Brief, randomised reaction delay after first spotting the mark.
		if (markReactionTicks < markReactionGoal)
		{
			++markReactionTicks;
			setStatus("Spotting Mark of Grace");
			return true;
		}

		int distance = playerLocation.distanceTo(markLocation);
		if (distance > 1)
		{
			walker.walkTo(markLocation, false, 1);
			setStatus("Walking to Mark of Grace");
			return true;
		}

		if (markAttempts >= MARK_ATTEMPT_LIMIT)
		{
			ignoreMark(markLocation);
			clearMarkTarget();
			return false;
		}

		// Let the character finish arriving for a tick or two before reaching
		// for the mark, so the click is not snapped the moment it is in range.
		if (markDwellTicks < markDwellGoal)
		{
			++markDwellTicks;
			setStatus("Reaching for Mark of Grace");
			return true;
		}

		// Give the previous Take click a randomised few ticks to walk over and
		// pick the mark up before clicking it again.
		if (markAttempts > 0
			&& markAttemptTicks < randomTicks(MARK_RETRY_MIN_TICKS, MARK_RETRY_MAX_TICKS))
		{
			setStatus("Taking Mark of Grace");
			return true;
		}

		if (context.getInput().isBusy())
		{
			setStatus("Moving mouse");
			return true;
		}

		// Recompute the scene coordinates from the mark's world location: the
		// scene base can shift while the player walks across the course.
		LocalPoint local = LocalPoint.fromWorld(context.getClient(), markLocation);
		if (local == null)
		{
			clearMarkTarget();
			return false;
		}

		int sceneX = local.getSceneX();
		int sceneY = local.getSceneY();

		// Keep the cursor on the mark and read the game's own hover menu.
		// When the item's Take is the left-click option, clicking the cursor
		// is a real click on the item instead of a guessed menu action.
		java.awt.Point mouse = context.getInput().getMouseLocation();
		int hover = context.getInput().groundItemHoverState(ItemID.GRACE, sceneX, sceneY);
		if (hover == 2 && mouse != null)
		{
			context.getInput().click(mouse);
			markTaken();
			return true;
		}

		if (hover == 1)
		{
			// Something else is on top of the mark in the menu. Run the
			// item's own Take row so the click cannot pick the wrong thing.
			MenuEntry take = context.getInput().findMenuEntry("Take", ItemID.GRACE);
			if (take != null && context.getInput().invokeMenu(take))
			{
				markTaken();
				return true;
			}
		}

		// Not the left-click option yet: aim at the middle of the mark's tile
		// and wait for the game to offer Take before clicking.
		java.awt.Point point = context.getInput().groundItemPoint(sceneX, sceneY);
		if (point != null && context.getInput().moveTo(point))
		{
			setStatus("Hovering Mark of Grace");
			return true;
		}

		// The mark is not on screen. Turn the camera toward it for a tick
		// instead of firing a menu action out of view, which looks scripted.
		if (context.getInput().isCameraBusy()
			|| context.getInput().turnCameraToward(markLocation))
		{
			setStatus("Turning to Mark of Grace");
			return true;
		}

		// Could not put the mark on screen after trying. Only then run its
		// exact Take so the mark is not lost, and only after a few attempts.
		if (markAttempts >= 1 || markAttemptTicks >= 5)
		{
			context.getInput().invokeGroundItemTake(sceneX, sceneY, ItemID.GRACE, MARK_NAME);
			markTaken();
		}
		else
		{
			setStatus("Looking at Mark of Grace");
		}
		return true;
	}

	/**
	 * Records that a Take was sent for the current mark and schedules the
	 * next attempt after a short, randomised pause.
	 */
	private void markTaken()
	{
		++markAttempts;
		markAttemptTicks = 0;
		markDwellTicks = 0;
		markDwellGoal = randomTicks(MARK_DWELL_MIN_TICKS, MARK_DWELL_MAX_TICKS);
		setStatus("Taking Mark of Grace");
	}

	private static int randomTicks(int min, int max)
	{
		return min + ThreadLocalRandom.current().nextInt(max - min + 1);
	}

	private GroundLoot findMark(ScriptContext context, Player player)
	{
		WorldPoint playerLocation = player.getWorldLocation();
		if (playerLocation == null || playerLocation.getPlane() <= 0)
		{
			return null;
		}

		WorldView worldView = context.getClient().getTopLevelWorldView();
		if (worldView == null)
		{
			return null;
		}

		Scene scene = worldView.getScene();
		if (scene == null)
		{
			return null;
		}

		Tile[][][] tiles = scene.getTiles();
		int plane = playerLocation.getPlane();
		if (tiles == null || plane < 0 || plane >= tiles.length)
		{
			return null;
		}

		Tile[][] planeTiles = tiles[plane];
		if (planeTiles == null)
		{
			return null;
		}

		GroundLoot best = null;
		int bestDistance = Integer.MAX_VALUE;

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
				if (itemLocation == null || itemLocation.getPlane() != plane)
				{
					continue;
				}

				int distance = itemLocation.distanceTo(playerLocation);
				if (distance > MARK_PICKUP_DISTANCE || distance >= bestDistance
					|| isIgnoredMark(itemLocation))
				{
					continue;
				}

				boolean hasMark = false;
				for (TileItem groundItem : groundItems)
				{
					if (groundItem != null && groundItem.getId() == ItemID.GRACE)
					{
						hasMark = true;
						break;
					}
				}

				if (!hasMark)
				{
					continue;
				}

				// A mark across a gap or on another rooftop is skipped, but a
				// mark within a few tiles is taken even when the collision map
				// does not path to its exact tile.
				if (distance > MARK_NEAR_DISTANCE && !walker.canReach(itemLocation))
				{
					continue;
				}

				bestDistance = distance;
				best = new GroundLoot(itemLocation);
			}
		}

		return best;
	}

	private boolean markStillPresent(ScriptContext context, GroundLoot mark)
	{
		WorldView worldView = context.getClient().getTopLevelWorldView();
		if (worldView == null)
		{
			return false;
		}

		Scene scene = worldView.getScene();
		if (scene == null)
		{
			return false;
		}

		Tile[][][] tiles = scene.getTiles();
		int plane = mark.location.getPlane();
		if (tiles == null || plane < 0 || plane >= tiles.length)
		{
			return false;
		}

		Tile[][] planeTiles = tiles[plane];
		if (planeTiles == null)
		{
			return false;
		}

		int sceneX = mark.location.getX() - scene.getBaseX();
		int sceneY = mark.location.getY() - scene.getBaseY();
		if (sceneX < 0 || sceneX >= planeTiles.length)
		{
			return false;
		}

		Tile[] row = planeTiles[sceneX];
		if (row == null || sceneY < 0 || sceneY >= row.length)
		{
			return false;
		}

		Tile tile = row[sceneY];
		if (tile == null)
		{
			return false;
		}

		List<TileItem> groundItems = tile.getGroundItems();
		if (groundItems == null)
		{
			return false;
		}

		for (TileItem groundItem : groundItems)
		{
			if (groundItem != null && groundItem.getId() == ItemID.GRACE)
			{
				return true;
			}
		}

		return false;
	}

	private void clearMarkTarget()
	{
		markTarget = null;
		markTicks = 0;
		markAttemptTicks = 0;
		markAttempts = 0;
		markReactionTicks = 0;
		markReactionGoal = 0;
		markDwellTicks = 0;
		markDwellGoal = 0;
	}

	private boolean isIgnoredMark(WorldPoint location)
	{
		return ignoredMarks.containsKey(location);
	}

	private void ignoreMark(WorldPoint location)
	{
		ignoredMarks.put(location, MARK_IGNORE_TICKS);
	}

	private void tickIgnoredMarks()
	{
		if (ignoredMarks.isEmpty())
		{
			return;
		}

		Iterator<Map.Entry<WorldPoint, Integer>> iterator = ignoredMarks.entrySet().iterator();
		while (iterator.hasNext())
		{
			Map.Entry<WorldPoint, Integer> entry = iterator.next();
			int ticks = entry.getValue() - 1;
			if (ticks <= 0)
			{
				iterator.remove();
			}
			else
			{
				entry.setValue(ticks);
			}
		}
	}

	private void updateStats()
	{
		Client client = getContext().getClient();
		if (!statsInitialized)
		{
			statsInitialized = true;
			statsStartedAt = System.currentTimeMillis();
			startAgilityXp = client.getSkillExperience(Skill.AGILITY);
			lastMarkCount = countMarks();
			return;
		}

		int markCount = countMarks();
		if (markCount > lastMarkCount)
		{
			marksCollected += markCount - lastMarkCount;
		}
		lastMarkCount = markCount;
	}

	/**
	 * Marks of grace stack, so the inventory holds them in one slot whose
	 * quantity grows. Counting slots would stay at one forever.
	 *
	 * @return the number of marks currently carried
	 */
	private int countMarks()
	{
		int count = 0;
		for (Item item : getContext().getInventory().getItems())
		{
			if (item != null && item.getId() == ItemID.GRACE)
			{
				count += item.getQuantity();
			}
		}

		return count;
	}

	/**
	 * Rooftop obstacles are clicked back to back, so the relaxed mouse profile
	 * cannot keep up with the course. Use the fast, attentive profile.
	 */
	@Override
	public boolean useFastMouse()
	{
		return true;
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
		long gainedXp = Math.max(0L, client.getSkillExperience(Skill.AGILITY) - startAgilityXp);

		List<String> lines = new ArrayList<>();
		lines.add("");
		lines.add(course.name + " rooftop course");
		lines.add("Laps: " + formatNumber(laps) + " (" + formatPerHour(laps, elapsed) + "/hr)");
		lines.add("Obstacles: " + formatNumber(obstaclesCompleted));
		lines.add("Marks of Grace: " + formatNumber(marksCollected));
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
		int level = client.getRealSkillLevel(Skill.AGILITY);
		if (level >= Experience.MAX_REAL_LEVEL)
		{
			return Collections.singletonList(new ProgressBar("Agility", 1.0, "Max level", Color.GREEN));
		}

		long xp = client.getSkillExperience(Skill.AGILITY);
		long currentLevelXp = Experience.getXpForLevel(level);
		long nextLevelXp = Experience.getXpForLevel(level + 1);
		long progressXp = Math.max(0L, xp - currentLevelXp);
		long totalXp = Math.max(1L, nextLevelXp - currentLevelXp);

		return Collections.singletonList(new ProgressBar("Agility",
			(double) progressXp / totalXp,
			formatNumber(xp) + " / " + formatNumber(nextLevelXp),
			Color.GREEN));
	}

	/**
	 * Tile next to an obstacle on the side the player is already standing on.
	 * Walking there can never route around a building to reach the far side of
	 * the obstacle, which is what made the character wander off before a
	 * climb.
	 */
	private static WorldPoint approachTile(WorldPoint obstacle, WorldPoint player)
	{
		if (obstacle == null || player == null)
		{
			return obstacle;
		}

		int dx = Integer.compare(player.getX(), obstacle.getX());
		int dy = Integer.compare(player.getY(), obstacle.getY());
		if (dx == 0 && dy == 0)
		{
			return obstacle;
		}

		return new WorldPoint(obstacle.getX() + dx, obstacle.getY() + dy, player.getPlane());
	}

	/**
	 * @return true once the camera has finished easing toward its target, so
	 * an object's screen position will not move while it is being clicked
	 */
	private static boolean isCameraSettled(ScriptContext context)
	{
		if (context.getInput().isCameraBusy() || context.getInput().isZooming())
		{
			return false;
		}

		Client client = context.getClient();
		int yaw = client.getCameraYaw() & 0x3FFF;
		int target = client.getCameraYawTarget() & 0x3FFF;
		int delta = target - yaw;
		if (delta > 0x2000)
		{
			delta -= 0x4000;
		}
		else if (delta < -0x2000)
		{
			delta += 0x4000;
		}

		return Math.abs(delta) <= CAMERA_SETTLE_UNITS;
	}

	/**
	 * @return true if a left click right now would run the given option on
	 * this exact object, according to the client's own hover menu
	 */
	private static boolean isDefaultObjectAction(ScriptContext context, TileObject target, String... actions)
	{
		MenuEntry top = context.getInput().getLeftClickEntry();
		if (top == null || top.getType() == null || top.getOption() == null)
		{
			return false;
		}

		MenuAction type = top.getType();
		boolean objectOption = type == MenuAction.GAME_OBJECT_FIRST_OPTION
			|| type == MenuAction.GAME_OBJECT_SECOND_OPTION
			|| type == MenuAction.GAME_OBJECT_THIRD_OPTION
			|| type == MenuAction.GAME_OBJECT_FOURTH_OPTION
			|| type == MenuAction.GAME_OBJECT_FIFTH_OPTION;
		if (!objectOption || !matchesAnyAction(top.getOption(), actions))
		{
			return false;
		}

		if ((top.getIdentifier() & 0xFFFF) == (target.getId() & 0xFFFF))
		{
			return true;
		}

		// Some wall objects use a packed identifier, so also accept the entry
		// whose target is this object's name.
		ObjectComposition composition = context.getClient().getObjectDefinition(target.getId());
		String name = composition == null ? null : composition.getName();
		return name != null && top.getTarget() != null
			&& GameInput.plainText(top.getTarget()).equalsIgnoreCase(GameInput.plainText(name));
	}

	/**
	 * @return true if the menu option matches any of the obstacle's action
	 * aliases, so a course that labels a gap "Leap" instead of "Jump" (or a
	 * pole vault "Cross" instead of "Vault") still clicks.
	 */
	private static boolean matchesAnyAction(String option, String... actions)
	{
		if (option == null || actions == null)
		{
			return false;
		}

		String plainOption = GameInput.plainText(option).trim();
		for (String action : actions)
		{
			if (action != null && plainOption.equalsIgnoreCase(action.trim()))
			{
				return true;
			}
		}

		return false;
	}

	/**
	 * Centre of the object's hull. The vertex average of a convex hull stays
	 * well inside the model, unlike the middle of its bounding box, which can
	 * sit beside a tall or slanted object.
	 */
	private static java.awt.Point centerOf(TileObject object)
	{
		Shape shape = object.getClickbox();
		if (shape == null)
		{
			// The object is not drawn, so there is no model to put the mouse
			// on; the caller uses the exact option instead.
			return null;
		}

		if (shape instanceof Polygon)
		{
			Polygon polygon = (Polygon) shape;
			if (polygon.npoints == 0)
			{
				return null;
			}

			long x = 0;
			long y = 0;
			for (int i = 0; i < polygon.npoints; i++)
			{
				x += polygon.xpoints[i];
				y += polygon.ypoints[i];
			}

			return new java.awt.Point((int) (x / polygon.npoints), (int) (y / polygon.npoints));
		}

		Rectangle bounds = shape.getBounds();
		int centerX = bounds.x + bounds.width / 2;
		int centerY = bounds.y + bounds.height / 2;
		return shape.contains(centerX, centerY)
			? new java.awt.Point(centerX, centerY)
			: null;
	}

	private static boolean isAnimating(Player player)
	{
		return player != null && player.getAnimation() != -1;
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
