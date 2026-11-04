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
package net.runelite.rsbuddy.routine;

import java.util.function.Supplier;
import net.runelite.api.Player;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.rsbuddy.walk.Walker;

/**
 * Movement module: walking by minimap with the mouse idle until arrival.
 * <p>
 * Rules: walking first drops any aim target (no hover while moving);
 * on-screen targets are clicked directly instead of minimap-walking
 * ({@link #goAndClick}); every walk click is logged with its gap.
 * <p>
 * Example: {@code if (!actions.isMoving()) actions.walkTo(BANK_TILE);}
 */
public final class Movement
{
	private final RoutineContext context;
	private final Actions.Host host;
	private final ActionLog log;
	private final Aim aim;
	private final Walker walker;
	private Interact interact;

	Movement(RoutineContext context, Actions.Host host, ActionLog log, Aim aim)
	{
		this.context = context;
		this.host = host;
		this.log = log;
		this.aim = aim;
		this.walker = new Walker(context.getClient(), context.getInput());
	}

	void bind(Interact interact)
	{
		this.interact = interact;
	}

	/** @return the underlying minimap walker (for routines with custom paths) */
	public Walker walker()
	{
		return walker;
	}

	/** @return true while the player is walking or running */
	public boolean isMoving()
	{
		return isMoving(context);
	}

	/** @return true while the routine's player is walking or running */
	public static boolean isMoving(RoutineContext context)
	{
		return isMoving(context.getPlayer());
	}

	/** @return true while the player's pose is not the idle pose (walking or running) */
	public static boolean isMoving(Player player)
	{
		return player != null && player.getPoseAnimation() != player.getIdlePoseAnimation();
	}

	/**
	 * @return true when the object has a clickable point on screen (not off
	 * the viewport or under an interface), so it can be clicked directly
	 * instead of walking to it first. False while the bank covers the view.
	 */
	public boolean clickableOnScreen(TileObject object)
	{
		// With the bank open the game view is covered: walk by minimap
		// instead (the bank closes itself once the walk starts).
		return object != null && context.getInput().objectCenter(object) != null
			&& !context.getTargets().findWidget(InterfaceID.Bankmain.UNIVERSE).isPresent();
	}

	/**
	 * One real minimap (or tile) click toward {@code target}. Drops any aim
	 * target first; the mouse then does nothing until the routine acts again.
	 *
	 * @param minimap true to click the minimap, false to click the tile
	 * @param stopDistance tiles from the target that count as arrived
	 * @return true when a walk click was sent
	 */
	public boolean walkTo(WorldPoint target, boolean minimap, int stopDistance)
	{
		if (target == null)
		{
			return false;
		}
		if (aim.hasTarget())
		{
			aim.dropAim("walking");
		}
		boolean sent = walker.walkTo(target, minimap, stopDistance);
		if (sent)
		{
			log.markAction("walk " + host.stepName() + " -> " + target.getX() + "," + target.getY());
		}
		return sent;
	}

	/** Minimap walk that stops within 2 tiles. */
	public boolean walkTo(WorldPoint target)
	{
		return walkTo(target, true, 2);
	}

	/** @return the same x/y on the player's current plane */
	public WorldPoint onPlayerPlane(WorldPoint point)
	{
		WorldPoint location = context.getPlayerLocation();
		return location == null ? point : new WorldPoint(point.getX(), point.getY(), location.getPlane());
	}

	/**
	 * The standard approach: click the object directly if it is clickable on
	 * screen (or within {@code reach}); otherwise walk to it by minimap with
	 * the mouse idle and click it on arrival. Call every step until true.
	 *
	 * @param find looks the object up (null when not loaded)
	 * @param fallbackArea where to walk when it is not found, or null
	 * @param key short stable name for logs ("oak")
	 * @param optionPrefixes accepted left-click options, lower case ("chop")
	 * @param targetWord text the menu target must contain, lower case ("oak")
	 * @param reach tiles within which a click is tried even when off screen
	 * @return true when the click was sent
	 */
	public boolean goAndClick(Supplier<TileObject> find, WorldPoint fallbackArea, String key,
		String[] optionPrefixes, String targetWord, int reach)
	{
		TileObject object = find.get();
		WorldPoint location = context.getPlayerLocation();
		if (object == null)
		{
			if (fallbackArea != null && !isMoving())
			{
				walkTo(onPlayerPlane(fallbackArea), true, 2);
			}
			host.status("Looking for the " + key);
			return false;
		}
		if (location != null && location.distanceTo2D(object.getWorldLocation()) > reach && !clickableOnScreen(object))
		{
			if (!isMoving())
			{
				walkTo(object.getWorldLocation(), true, 2);
			}
			host.status("Walking to the " + key);
			return false;
		}
		return interact.clickObject(object, key, optionPrefixes, targetWord);
	}

	void reset()
	{
		walker.reset();
	}
}
