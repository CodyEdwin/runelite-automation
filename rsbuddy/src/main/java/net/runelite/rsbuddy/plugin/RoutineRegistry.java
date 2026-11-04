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
package net.runelite.rsbuddy.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import net.runelite.rsbuddy.routine.Routine;
import net.runelite.rsbuddy.routine.RoutineLoader;

/**
 * Library of routines shown in the RSBuddy panel.
 * <p>
 * Adding a routine means registering it here (see {@link RoutineLibrary}).
 * The panel reads this list instead of wiring each routine by hand.
 */
public final class RoutineRegistry
{
	/**
	 * Creates a routine, possibly from a reloading class loader.
	 */
	@FunctionalInterface
	public interface Factory
	{
		Routine create(ClassLoader loader) throws RoutineLoader.RoutineLoadException;
	}

	/**
	 * Builds the configure section for a routine. Null when the routine has no settings.
	 */
	@FunctionalInterface
	public interface Settings
	{
		void build(RoutineSettingsForm form);
	}

	private final List<RoutineDefinition> definitions = new ArrayList<>();

	public RoutineDefinition register(String id, Factory factory, @Nullable Settings settings)
	{
		RoutineDefinition existing = find(id);
		if (existing != null)
		{
			return existing;
		}

		RoutineDefinition definition = new RoutineDefinition(id, factory, settings);
		definitions.add(definition);
		return definition;
	}

	public List<RoutineDefinition> getDefinitions()
	{
		return Collections.unmodifiableList(definitions);
	}

	public void remove(String id)
	{
		definitions.removeIf(definition -> definition.getId().equals(id));
	}

	@Nullable
	public RoutineDefinition find(String id)
	{
		if (id == null)
		{
			return null;
		}

		for (RoutineDefinition definition : definitions)
		{
			if (definition.getId().equals(id))
			{
				return definition;
			}
		}

		return null;
	}

	@Nullable
	public RoutineDefinition findByInstance(Routine routine)
	{
		if (routine == null)
		{
			return null;
		}

		for (RoutineDefinition definition : definitions)
		{
			if (definition.getInstance() == routine)
			{
				return definition;
			}
		}

		return find(routine.getClass().getName());
	}

	/**
	 * Creates a routine from the library. Unknown class names are loaded with a
	 * no-arg constructor so a custom routine still starts after it is registered.
	 */
	public Routine create(String className, ClassLoader loader) throws RoutineLoader.RoutineLoadException
	{
		RoutineDefinition definition = find(className);
		if (definition == null)
		{
			return RoutineLoader.load(className, loader);
		}

		return definition.create(loader);
	}

	public void replaceInstance(Routine previous, Routine next)
	{
		if (next == null)
		{
			return;
		}

		// Prefer the entry that held the previous instance so a jar routine
		// with the same class name as a built-in updates its own entry.
		RoutineDefinition definition = previous == null ? null : findByInstance(previous);
		if (definition == null)
		{
			definition = find(next.getClass().getName());
		}

		if (definition != null)
		{
			definition.setInstance(next);
		}
	}
}
