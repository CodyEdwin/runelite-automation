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

/**
 * One user setting a routine declares.
 * <p>
 * Wiring: return these from {@code Routine.getRoutineSettings()}. When the
 * routine has no hand-written settings builder in {@code RoutineLibrary},
 * its settings form in the side panel draws one control per setting
 * (checkbox, number spinner or text field) and saves it in the RSBuddy
 * config group under {@link #getKey()}. A {@link TaskRoutine} reads the value
 * with {@code bool/integer/text(key)}; an unset key returns the default.
 * To also show it on RuneLite's wrench config page, add a {@code @ConfigItem}
 * with the same key to RSBuddyConfig (that page is built from the interface,
 * so it cannot be added at runtime).
 * <p>
 * Example: {@code RoutineSetting.bool("oakBank", "Bank logs", "Bank when full", true)}
 * <p>
 * Use a key unique to your routine (prefix it with the routine name).
 */
public final class RoutineSetting
{
	public enum Type
	{
		BOOLEAN, INTEGER, TEXT
	}

	private final String key;
	private final String label;
	private final String tooltip;
	private final Type type;
	private final String defaultValue;

	private RoutineSetting(String key, String label, String tooltip, Type type, String defaultValue)
	{
		this.key = key;
		this.label = label;
		this.tooltip = tooltip;
		this.type = type;
		this.defaultValue = defaultValue;
	}

	/** A checkbox setting. */
	public static RoutineSetting bool(String key, String label, String tooltip, boolean defaultValue)
	{
		return new RoutineSetting(key, label, tooltip, Type.BOOLEAN, Boolean.toString(defaultValue));
	}

	/** A number setting (0 or more). */
	public static RoutineSetting integer(String key, String label, String tooltip, int defaultValue)
	{
		return new RoutineSetting(key, label, tooltip, Type.INTEGER, Integer.toString(defaultValue));
	}

	/** A free-text setting. */
	public static RoutineSetting text(String key, String label, String tooltip, String defaultValue)
	{
		return new RoutineSetting(key, label, tooltip, Type.TEXT, defaultValue == null ? "" : defaultValue);
	}

	public String getKey()
	{
		return key;
	}

	public String getLabel()
	{
		return label;
	}

	public String getTooltip()
	{
		return tooltip;
	}

	public Type getType()
	{
		return type;
	}

	public String getDefaultValue()
	{
		return defaultValue;
	}
}
