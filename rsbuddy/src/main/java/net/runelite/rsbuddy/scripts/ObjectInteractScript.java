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
package net.runelite.rsbuddy.scripts;

import java.util.Optional;
import net.runelite.api.GameObject;
import net.runelite.api.Player;
import net.runelite.rsbuddy.script.Script;
import net.runelite.rsbuddy.script.ScriptContext;

/**
 * Example script which finds a nearby object and clicks it.
 * <p>
 * This is intentionally small. Use it as a template for a state machine:
 * find a target, interact, wait for the game to update, then repeat.
 */
public final class ObjectInteractScript extends Script
{
	private static final int CLICK_COOLDOWN_TICKS = 3;

	private final String objectName;
	private final String requiredAction;
	private final int maxDistance;

	private int cooldown;

	public ObjectInteractScript(String objectName, String requiredAction, int maxDistance)
	{
		super("Object Interact", "Finds a nearby object and clicks it", 1);
		this.objectName = objectName;
		this.requiredAction = requiredAction;
		this.maxDistance = maxDistance;
	}

	@Override
	protected void onStart()
	{
		cooldown = 0;
		setStatus("Looking for " + objectName);
	}

	@Override
	protected void onGameTick()
	{
		ScriptContext context = getContext();

		if (!context.isLoggedIn())
		{
			setStatus("Waiting for login");
			return;
		}

		if (cooldown > 0)
		{
			--cooldown;
			setStatus("Cooldown (" + cooldown + ")");
			return;
		}

		Player player = context.getPlayer();
		if (player.getAnimation() != -1)
		{
			setStatus("Waiting for player to become idle");
			return;
		}

		Optional<GameObject> target = context.getTargets()
			.findObject(objectName, requiredAction, maxDistance);
		if (target.isEmpty())
		{
			setStatus("No " + objectName + " within " + maxDistance + " tiles");
			return;
		}

		if (context.getInput().click(target.get()))
		{
			cooldown = CLICK_COOLDOWN_TICKS;
			setStatus("Clicked " + objectName);
		}
		else
		{
			setStatus("Unable to click " + objectName);
		}
	}
}
