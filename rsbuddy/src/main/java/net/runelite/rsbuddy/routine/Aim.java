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

import java.awt.Point;
import java.awt.Shape;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.runelite.api.coords.WorldPoint;
import net.runelite.rsbuddy.input.GameInput;

/**
 * Mouse/aim module: holds the one target the mouse is aiming at, and
 * enforces the aim rules every routine gets for free.
 * <ul>
 * <li>Dead centre: {@link Interact} makes one curved move to the centre of the
 * clickable hull; this module keeps that target.</li>
 * <li>Drift correction: if the scene shifts after the move (camera settling),
 * the end of the path glides onto the live centre instead of a second hop.</li>
 * <li>No hover while moving: the moment the player moves, the target is
 * dropped and the cursor follower stops.</li>
 * <li>Human reaction: once the cursor is on the target, the click comes
 * 80-200 ms later from the per-frame hook ({@link #onClientTick()}).</li>
 * </ul>
 * Routines rarely call this directly; use {@link #dropAim(String)} when a
 * routine changes plan (for example a hammer arrived) so nothing stays hovered.
 */
public final class Aim
{
	/** Where a target's hull and centre come from; re-read every frame so it follows the scene. */
	interface Aimable
	{
		Shape hull();

		Point centre();

		/** Fallback move when there is no centre point (some point inside the target). */
		boolean hover();

		/** World tile for camera/walk recovery, or null for widgets. */
		WorldPoint location();
	}

	private final RoutineContext context;
	private final ActionLog log;

	// Per-target state, shared with Interact (package-private on purpose).
	String verifyKey;
	boolean verifyHovered;
	Aimable verifyTarget;
	Point aimTarget;
	boolean coverMiss;
	int verifyMisses;
	int verifyRecoveries;
	String lastClickKey;
	long lastClickAt;
	final Set<String> menuDumped = new HashSet<>();
	private long fastReadyAt;

	Aim(RoutineContext context, ActionLog log)
	{
		this.context = context;
		this.log = log;
	}

	/** @return true while a verified hover is in progress (mouse on, or moving to, a target) */
	public boolean isAiming()
	{
		return verifyHovered;
	}

	/** @return true when any aim target is held (hovered, followed, or a pending aim point) */
	public boolean hasTarget()
	{
		return verifyHovered || verifyTarget != null || aimTarget != null;
	}

	/** @return the key of the target being clicked, e.g. "sack", or null */
	public String targetKey()
	{
		return verifyKey;
	}

	/**
	 * Clears every hover/aim target and stops the cursor follower. Logs
	 * {@code [Prefix] <reason>, dropping the <key> target} when something
	 * was held. Safe to call any time.
	 *
	 * @param reason why, shown in the log ("player is moving")
	 */
	public void dropAim(String reason)
	{
		if (hasTarget())
		{
			ActionLog.LOG.info("{} {}, dropping the {} target", log.prefix(), reason,
				verifyKey == null ? "aim" : verifyKey);
		}
		verifyHovered = false;
		verifyTarget = null;
		aimTarget = null;
		context.getInput().follow(null);
	}

	/** Forgets which target is being clicked (miss counters restart on the next click) without moving anything. */
	public void forgetKey()
	{
		verifyKey = null;
	}

	/** Drops the target and forgets it entirely, so the next click starts fresh. */
	public void resetTarget()
	{
		dropAim("target reset");
		verifyKey = null;
	}

	/**
	 * Per-frame (client thread) hook. Drops targets while moving, glides the
	 * end of a move onto a shifted centre, and once the cursor has arrived plus
	 * a human 80-200 ms pause returns true, so the routine runs its step now
	 * (which verifies and clicks) instead of waiting for the next game tick.
	 *
	 * @return true when the routine should run its step immediately
	 */
	public boolean onClientTick()
	{
		GameInput input = context.getInput();
		if (input.isBusy())
		{
			fastReadyAt = 0;
			return false;
		}
		if (hasTarget() && Movement.isMoving(context))
		{
			// Never hover or track a target while the player runs.
			dropAim("player is moving");
			fastReadyAt = 0;
			return false;
		}
		if (verifyHovered && verifyTarget != null)
		{
			Shape area = verifyTarget.hull();
			Point mouse = input.getMouseLocation();
			if (area != null && mouse != null && !area.contains(mouse))
			{
				// The scene shifted after the move (camera settling): glide
				// the end of the path onto the live centre rather than a
				// second hop.
				Point centre = verifyTarget.centre();
				if (centre != null)
				{
					input.follow(centre);
					fastReadyAt = 0;
					return false;
				}
			}
		}
		if (!verifyHovered)
		{
			fastReadyAt = 0;
			return false;
		}
		long now = System.currentTimeMillis();
		if (fastReadyAt == 0)
		{
			// Human reaction after the cursor lands.
			fastReadyAt = now + ThreadLocalRandom.current().nextLong(80, 201);
			return false;
		}
		if (now >= fastReadyAt)
		{
			fastReadyAt = 0;
			return true;
		}
		return false;
	}

	void reset()
	{
		verifyKey = null;
		verifyHovered = false;
		verifyTarget = null;
		aimTarget = null;
		coverMiss = false;
		verifyMisses = 0;
		verifyRecoveries = 0;
		lastClickKey = null;
		lastClickAt = 0;
		fastReadyAt = 0;
		menuDumped.clear();
	}
}
