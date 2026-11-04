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

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Light structure for scripts: a list of tasks, each "when this holds, do
 * that". Every game tick (and every frame right after a verified hover
 * arrives) the first task whose condition holds runs. All input goes through
 * {@link #actions}, which applies the standing click/walk rules.
 * <p>
 * Settings are declared with {@link #getRoutineSettings()} and read with
 * {@link #bool}, {@link #integer} and {@link #text}.
 */
public abstract class TaskRoutine extends Routine implements Actions.Host
{
	private static final class Task
	{
		final String name;
		final BooleanSupplier when;
		final Runnable run;

		Task(String name, BooleanSupplier when, Runnable run)
		{
			this.name = name;
			this.when = when;
			this.run = run;
		}
	}

	private final List<Task> tasks = new ArrayList<>();
	private String currentTask = "";
	private int cooldownTicks;
	private boolean halted;

	/** The shared action layer. Available from {@link #setup()} on. */
	protected Actions actions;

	protected TaskRoutine(String name, String description)
	{
		super(name, description);
	}

	/** Register tasks with {@link #task}, in priority order. Called on every Start. */
	protected abstract void setup();

	protected final void task(String name, BooleanSupplier when, Runnable run)
	{
		tasks.add(new Task(name, when, run));
	}

	@Override
	public boolean physicalInputOnly()
	{
		return true;
	}

	@Override
	protected final void onStart()
	{
		tasks.clear();
		currentTask = "";
		cooldownTicks = 0;
		halted = false;
		String simple = getClass().getSimpleName().replace("Script", "").replace("Routine", "");
		actions = new Actions(getContext(), this, "[" + simple + "]");
		setup();
	}

	@Override
	protected final void onGameTick()
	{
		if (halted || !getContext().isLoggedIn())
		{
			return;
		}
		if (cooldownTicks > 0)
		{
			--cooldownTicks;
			return;
		}
		runTasks();
	}

	@Override
	protected final void onClientTick()
	{
		if (!halted && actions != null && getContext().isLoggedIn() && actions.onClientTick())
		{
			cooldownTicks = 0;
			runTasks();
		}
	}

	private void runTasks()
	{
		for (Task task : tasks)
		{
			if (task.when.getAsBoolean())
			{
				currentTask = task.name;
				task.run.run();
				return;
			}
		}
		currentTask = "";
	}

	// Actions.Host

	@Override
	public void status(String status)
	{
		setStatus(status);
	}

	@Override
	public void halt(String status)
	{
		halted = true;
		setStatus(status);
	}

	@Override
	public void cooldown(int ticks)
	{
		cooldownTicks = Math.max(0, ticks);
	}

	@Override
	public String stepName()
	{
		return currentTask;
	}

	// Settings

	protected final boolean bool(String key)
	{
		return Boolean.parseBoolean(setting(key));
	}

	protected final int integer(String key)
	{
		try
		{
			return Integer.parseInt(setting(key).trim());
		}
		catch (NumberFormatException ex)
		{
			return 0;
		}
	}

	protected final String text(String key)
	{
		return setting(key);
	}

	private String setting(String key)
	{
		for (RoutineSetting s : getRoutineSettings())
		{
			if (s.getKey().equals(key))
			{
				String value = getContext().getSetting(key);
				return value == null ? s.getDefaultValue() : value;
			}
		}
		throw new IllegalArgumentException("Undeclared setting " + key);
	}
}
