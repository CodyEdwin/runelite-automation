/*
 * Copyright (c) 2026, reich
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
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
package net.runelite.rsbuddy.debug;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;

/**
 * Records widget snapshots for a limited time without needing the player to
 * type anything.
 * <p>
 * This exists because interfaces that take keyboard focus (the Grand Exchange
 * item search, quantity and price prompts) swallow chat commands, so there is
 * no way to run {@code ::rsbuddy widgets} while they are open. Instead the
 * player starts a watch, then clicks through the screens normally; a snapshot
 * is written every time the visible interface actually changes.
 */
public final class WidgetWatcher
{
	private static final long MIN_SNAPSHOT_INTERVAL_MS = 1_200L;
	private static final long FORCE_SNAPSHOT_INTERVAL_MS = 15_000L;

	private final long endAt;
	private final int tickInterval;

	private long lastSnapshotAt;
	private String lastSignature;
	private int snapshots;
	private int ticks;
	private String lastError = "";

	public WidgetWatcher(int seconds)
	{
		this.endAt = System.currentTimeMillis() + Math.max(5, seconds) * 1000L;
		// Comparing every other tick is plenty; interfaces do not change faster
		// than that and the walk is not free.
		this.tickInterval = 2;
	}

	public boolean isExpired()
	{
		return System.currentTimeMillis() >= endAt || snapshots >= 60;
	}

	public int getSnapshots()
	{
		return snapshots;
	}

	public String getLastError()
	{
		return lastError;
	}

	public long getRemainingSeconds()
	{
		return Math.max(0L, (endAt - System.currentTimeMillis()) / 1000L);
	}

	/**
	 * Called from the game tick. Takes a snapshot when the interface signature
	 * changed and enough time has passed since the previous one.
	 *
	 * @return true when a snapshot was written
	 */
	public boolean tick(Client client)
	{
		if (++ticks % tickInterval != 0 || isExpired())
		{
			return false;
		}

		long now = System.currentTimeMillis();
		if (now - lastSnapshotAt < MIN_SNAPSHOT_INTERVAL_MS)
		{
			return false;
		}

		String signature;
		try
		{
			signature = signature(client);
		}
		catch (Throwable ex)
		{
			lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
			return false;
		}

		if (signature.equals(lastSignature) && now - lastSnapshotAt < FORCE_SNAPSHOT_INTERVAL_MS)
		{
			return false;
		}

		lastSignature = signature;
		lastSnapshotAt = now;
		snapshots++;

		try
		{
			WidgetDumper.snapshot(client, String.format("watch-%02d (%ds left)", snapshots,
				getRemainingSeconds()));
			return true;
		}
		catch (Throwable ex)
		{
			lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
			return false;
		}
	}

	/**
	 * Cheap 64 bit fingerprint of everything visible: widget ids, item ids,
	 * texts and actions. Two different screens almost never collide.
	 */
	private String signature(Client client)
	{
		Widget[] roots = client.getWidgetRoots();
		if (roots == null)
		{
			return "none";
		}

		long hash = 0xcbf29ce484222325L;
		Deque<Widget> pending = new ArrayDeque<>();
		Set<Widget> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Widget root : roots)
		{
			if (root != null)
			{
				pending.push(root);
			}
		}

		int visited = 0;
		while (!pending.isEmpty() && visited < 20_000)
		{
			Widget widget = pending.pop();
			if (widget == null || widget.isHidden() || !seen.add(widget))
			{
				continue;
			}

			visited++;
			hash = mix(hash, widget.getId());
			hash = mix(hash, widget.getItemId());
			hash = mix(hash, widget.getText() == null ? 0 : widget.getText().hashCode());
			hash = mix(hash, widget.getName() == null ? 0 : widget.getName().hashCode());

			String[] actions = widget.getActions();
			if (actions != null)
			{
				for (String action : actions)
				{
					hash = mix(hash, action == null ? 0 : action.hashCode());
				}
			}

			Widget[] children = widget.getChildren();
			if (children != null)
			{
				for (Widget child : children)
				{
					if (child != null)
					{
						pending.push(child);
					}
				}
			}

			Widget[] dynamic = widget.getDynamicChildren();
			if (dynamic != null)
			{
				for (Widget child : dynamic)
				{
					if (child != null)
					{
						pending.push(child);
					}
				}
			}

			Widget[] staticChildren = widget.getStaticChildren();
			if (staticChildren != null)
			{
				for (Widget child : staticChildren)
				{
					if (child != null)
					{
						pending.push(child);
					}
				}
			}

			Widget[] nested = widget.getNestedChildren();
			if (nested != null)
			{
				for (Widget child : nested)
				{
					if (child != null)
					{
						pending.push(child);
					}
				}
			}
		}

		return Long.toHexString(hash) + ":" + visited;
	}

	private static long mix(long hash, long value)
	{
		hash ^= value;
		hash *= 0x100000001b3L;
		return hash;
	}
}
