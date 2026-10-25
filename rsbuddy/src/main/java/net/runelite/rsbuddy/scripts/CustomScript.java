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
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.rsbuddy.script.Script;
import net.runelite.rsbuddy.script.ScriptContext;

/**
 * Starter script for you to edit.
 * <p>
 * Set {@link #TARGET_NPC} to an NPC name to see the example in action. The
 * other examples at the bottom of {@link #onGameTick()} show how to interact
 * with objects and widgets, and how to walk to a tile.
 */
public final class CustomScript extends Script
{
	// TODO: set this to the NPC you want to interact with, for example "Cow".
	private static final String TARGET_NPC = "";
	private static final String TARGET_ACTION = "Attack";
	private static final int MAX_DISTANCE = 10;
	private static final int COOLDOWN_TICKS = 3;

	private int cooldown;

	public CustomScript()
	{
		super("Custom Script", "Edit CustomScript.java to add your own logic.");
	}

	@Override
	protected void onStart()
	{
		cooldown = 0;
		setStatus("Ready");
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

		if (TARGET_NPC.isEmpty())
		{
			setStatus("Set TARGET_NPC in CustomScript.java");
			return;
		}

		Optional<NPC> target = context.getTargets()
			.findNpc(TARGET_NPC, TARGET_ACTION, MAX_DISTANCE);

		if (target.isPresent() && context.getInput().interact(target.get(), TARGET_ACTION))
		{
			cooldown = COOLDOWN_TICKS;
			setStatus("Interacting with " + TARGET_NPC);
			return;
		}

		setStatus("No " + TARGET_NPC + " within " + MAX_DISTANCE + " tiles");

		// Other examples:
		//
		// context.getTargets().findObject("Tree", "Chop down", MAX_DISTANCE)
		//     .ifPresent(object -> context.getInput().click(object));
		//
		// context.getTargets().findWidget(componentId)
		//     .ifPresent(widget -> context.getInput().click(widget));
		//
		// context.getInput().walkTo(new WorldPoint(3200, 3200, context.getClient().getPlane()));
	}
}
