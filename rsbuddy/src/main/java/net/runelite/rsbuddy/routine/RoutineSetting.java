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
 * One user setting a script declares. It appears automatically in the
 * RSBuddy side panel under routine settings and is stored in the RSBuddy
 * config group under {@link #getKey()}. To also show it in the wrench config,
 * add a matching {@code @ConfigItem} with the same key to RSBuddyConfig (RuneLite
 * builds that page from the interface, so it cannot be added at runtime).
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

	public static RoutineSetting bool(String key, String label, String tooltip, boolean defaultValue)
	{
		return new RoutineSetting(key, label, tooltip, Type.BOOLEAN, Boolean.toString(defaultValue));
	}

	public static RoutineSetting integer(String key, String label, String tooltip, int defaultValue)
	{
		return new RoutineSetting(key, label, tooltip, Type.INTEGER, Integer.toString(defaultValue));
	}

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
