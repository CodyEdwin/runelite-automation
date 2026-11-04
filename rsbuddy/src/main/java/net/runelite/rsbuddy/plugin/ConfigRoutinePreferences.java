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

import net.runelite.client.config.ConfigManager;

/**
 * Persists routine preferences, including favorites, through RuneLite's {@link ConfigManager}.
 */
public final class ConfigRoutinePreferences implements RoutinePreferences
{
	private final ConfigManager configManager;

	public ConfigRoutinePreferences(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	@Override
	public String getString(String key)
	{
		return configManager.getConfiguration(RSBuddyConfig.GROUP, key);
	}

	@Override
	public void set(String key, String value)
	{
		configManager.setConfiguration(RSBuddyConfig.GROUP, key, value);
	}

	@Override
	public void set(String key, boolean value)
	{
		configManager.setConfiguration(RSBuddyConfig.GROUP, key, value);
	}

	@Override
	public void set(String key, int value)
	{
		configManager.setConfiguration(RSBuddyConfig.GROUP, key, value);
	}
}
