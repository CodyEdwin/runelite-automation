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
package net.runelite.rsbuddy.routines;

import java.util.Arrays;
import java.util.List;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.rsbuddy.routine.RoutineSetting;
import net.runelite.rsbuddy.routine.TaskRoutine;

/**
 * TEMPLATE - copy this file to start a new routine. It is not registered in
 * the RoutineLibrary, so it never shows in the panel.
 * <p>
 * Chops oak trees and banks the logs. Every click goes through
 * {@code actions}, so the standing rules (real visible clicks, dead centre,
 * verified option, no hover while moving, human timing) apply automatically.
 */
public class ExampleRoutine extends TaskRoutine
{
	// 1. Ids and tiles the routine needs.
	private static final int OAK_TREE = 10820;
	private static final int OAK_LOGS = 1521;
	private static final WorldPoint TREES = new WorldPoint(3164, 3418, 0);

	// 2. A public no-arg constructor: name and description shown in the panel.
	public ExampleRoutine()
	{
		super("Example: Oak Chopper", "Template routine: chops oaks and banks the logs.");
	}

	// 3. Settings: drawn automatically in the panel, read with bool/integer/text.
	@Override
	public List<RoutineSetting> getRoutineSettings()
	{
		return Arrays.asList(
			RoutineSetting.bool("exampleBank", "Bank logs", "Bank when full instead of stopping", true),
			RoutineSetting.integer("exampleRadius", "Search radius", "Tiles to look for trees", 15));
	}

	// 4. Tasks, highest priority first. The first whose condition holds runs.
	@Override
	protected void setup()
	{
		// Bank open with logs: one click on Deposit inventory.
		task("deposit", () -> actions.bankOpen() && getContext().getInventory().countItem(OAK_LOGS) > 0,
			() -> actions.depositAll());

		// Full: open the nearest bank (click if on screen, else walk + click).
		task("bank", () -> getContext().getInventory().isFull() && bool("exampleBank"),
			() -> actions.openNearestBank(15, true));

		// Full and not banking: stop with a reason.
		task("stop", () -> getContext().getInventory().isFull(), () -> halt("Inventory full"));

		// Idle: click the nearest oak (walks there first when it is off screen).
		task("chop", () -> !actions.isAnimating() && !actions.isMoving(), () ->
		{
			TileObject tree = getContext().getTargets().findTileObject(OAK_TREE, integer("exampleRadius"))
				.orElse(null);
			actions.goAndClick(() -> tree, TREES, "oak", new String[]{"chop"}, "oak", 8);
		});
	}
}
