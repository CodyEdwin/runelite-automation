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

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.runelite.api.Player;

/**
 * Waits module: tick-counted waits whose cap is a timeout only, never a
 * delay. A wait finishes the same tick its condition holds.
 * <p>
 * Example:
 * {@code switch (actions.waitUntil("logs", () -> inv.isFull(), 200)) { case TIMED_OUT: ...; }}
 */
public final class Waits
{
	/** Result of {@link #until}. */
	public enum Result
	{
		/** Still waiting; call again next step. */
		WAITING,
		/** The condition held. */
		DONE,
		/** The cap was reached first. */
		TIMED_OUT
	}

	private final RoutineContext context;
	private final ActionLog log;
	private final Map<String, Integer> waits = new HashMap<>();

	Waits(RoutineContext context, ActionLog log)
	{
		this.context = context;
		this.log = log;
	}

	/**
	 * DONE as soon as {@code condition} holds, TIMED_OUT after
	 * {@code maxTicks} calls, otherwise WAITING. Each {@code key} counts
	 * separately and resets once it finishes.
	 *
	 * @param key a name for this wait, e.g. "bank open"
	 * @param maxTicks timeout in calls (game ticks when called once per tick)
	 */
	public Result until(String key, BooleanSupplier condition, int maxTicks)
	{
		if (condition.getAsBoolean())
		{
			waits.remove(key);
			return Result.DONE;
		}
		int n = waits.merge(key, 1, Integer::sum);
		if (n > maxTicks)
		{
			waits.remove(key);
			ActionLog.LOG.info("{} wait '{}' timed out after {} ticks", log.prefix(), key, maxTicks);
			return Result.TIMED_OUT;
		}
		return Result.WAITING;
	}

	/** @return true while the player plays an animation (skilling, combat) */
	public boolean isAnimating()
	{
		Player player = context.getPlayer();
		return player != null && player.getAnimation() != -1;
	}

	void reset()
	{
		waits.clear();
	}
}
