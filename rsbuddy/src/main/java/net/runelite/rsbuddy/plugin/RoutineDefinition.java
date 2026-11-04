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

import javax.annotation.Nullable;
import net.runelite.rsbuddy.routine.Routine;

/**
 * One entry in the routine library: a stable id, a factory, and optional settings.
 */
public final class RoutineDefinition
{
	private final String id;
	private final RoutineRegistry.Factory factory;
	@Nullable
	private final RoutineRegistry.Settings settings;
	private Routine instance;

	RoutineDefinition(String id, RoutineRegistry.Factory factory, @Nullable RoutineRegistry.Settings settings)
	{
		this.id = id;
		this.factory = factory;
		this.settings = settings;
	}

	public String getId()
	{
		return id;
	}

	public String getSimpleName()
	{
		int dot = id.lastIndexOf('.');
		return dot < 0 ? id : id.substring(dot + 1);
	}

	@Nullable
	public RoutineRegistry.Settings getSettings()
	{
		return settings;
	}

	public Routine getInstance()
	{
		return instance;
	}

	public void setInstance(Routine instance)
	{
		this.instance = instance;
	}

	public Routine create(ClassLoader loader) throws net.runelite.rsbuddy.routine.RoutineLoader.RoutineLoadException
	{
		return factory.create(loader);
	}
}
