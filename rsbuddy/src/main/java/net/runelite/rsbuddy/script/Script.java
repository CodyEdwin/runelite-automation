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
package net.runelite.rsbuddy.script;

import java.awt.Color;
import java.util.Collections;
import java.util.List;

/**
 * Base class for RSBuddy scripts.
 * <p>
 * Scripts are driven by game ticks via {@link ScriptManager}. They should keep
 * each tick short and leave longer waits to their cooldown/state machine.
 */
public abstract class Script
{
	private final String name;
	private final String description;
	private final int tickDelay;

	private ScriptContext context;
	private String status = "Idle";
	private int ticks;

	protected Script(String name, String description)
	{
		this(name, description, 1);
	}

	protected Script(String name, String description, int tickDelay)
	{
		this.name = name;
		this.description = description;
		this.tickDelay = Math.max(1, tickDelay);
	}

	final void start(ScriptContext context)
	{
		this.context = context;
		this.ticks = 0;
		setStatus("Starting");
		onStart();
	}

	final void stop()
	{
		try
		{
			onStop();
		}
		finally
		{
			setStatus("Stopped");
		}
	}

	final void tick()
	{
		if (++ticks < tickDelay)
		{
			return;
		}

		ticks = 0;
		onGameTick();
	}

	protected abstract void onGameTick();

	protected void onStart()
	{
	}

	protected void onStop()
	{
	}

	protected final ScriptContext getContext()
	{
		return context;
	}

	protected final void setStatus(String status)
	{
		this.status = status;
	}

	public final String getName()
	{
		return name;
	}

	public final String getDescription()
	{
		return description;
	}

	public final String getStatus()
	{
		return status;
	}

	/**
	 * Extra lines rendered by the status overlay while this script is running.
	 * Scripts can override this to expose their own counters and progress.
	 */
	public List<String> getOverlayLines()
	{
		return Collections.emptyList();
	}

	/**
	 * Progress bars rendered by the XP overlay while this script is running.
	 * Each bar represents progress from the current level to the next level.
	 */
	public List<ProgressBar> getProgressBars()
	{
		return Collections.emptyList();
	}

	public static final class ProgressBar
	{
		private final String label;
		private final double progress;
		private final String value;
		private final Color color;

		public ProgressBar(String label, double progress, String value, Color color)
		{
			this.label = label;
			this.progress = Math.max(0.0, Math.min(1.0, progress));
			this.value = value;
			this.color = color == null ? Color.CYAN : color;
		}

		public String getLabel()
		{
			return label;
		}

		public double getProgress()
		{
			return progress;
		}

		public String getValue()
		{
			return value;
		}

		public Color getColor()
		{
			return color;
		}
	}
}
