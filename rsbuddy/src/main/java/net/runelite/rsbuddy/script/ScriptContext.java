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

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.rsbuddy.input.GameInput;
import net.runelite.rsbuddy.inventory.Inventory;
import net.runelite.rsbuddy.target.TargetLocator;

/**
 * Services exposed to a running script.
 */
public final class ScriptContext
{
	private final Client client;
	private final GameInput input;
	private final TargetLocator targets;
	private final Inventory inventory;

	public ScriptContext(Client client, GameInput input, TargetLocator targets, Inventory inventory)
	{
		this.client = client;
		this.input = input;
		this.targets = targets;
		this.inventory = inventory;
	}

	public Client getClient()
	{
		return client;
	}

	public GameInput getInput()
	{
		return input;
	}

	public TargetLocator getTargets()
	{
		return targets;
	}

	public Inventory getInventory()
	{
		return inventory;
	}

	public Player getPlayer()
	{
		return client.getLocalPlayer();
	}

	public WorldPoint getPlayerLocation()
	{
		Player player = getPlayer();
		return player == null ? null : player.getWorldLocation();
	}

	public boolean isLoggedIn()
	{
		return client.getGameState() == GameState.LOGGED_IN && getPlayer() != null;
	}
}
