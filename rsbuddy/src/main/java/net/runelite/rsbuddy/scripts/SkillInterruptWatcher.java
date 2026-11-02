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

import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/**
 * Shared "did the make-all stop?" check for production scripts (Bow
 * Fletcher, Gem Cutter). Call {@link #update} once per game tick (client
 * thread) while the batch runs.
 *
 * <p>A level-up stops the batch at once, so it is caught on the tick it
 * happens: the real skill level goes up, or the level-up dialog appears.
 * Otherwise the batch counts as stopped after {@link #IDLE_TICKS} ticks
 * with no animation and no item used: a single tick without animation is
 * normal between two items, so it never re-clicks on that.
 */
final class SkillInterruptWatcher
{
	/** Ticks with no animation and no item used before the batch counts as stopped. */
	static final int IDLE_TICKS = 2;
	/** Before the first item is used the animation can take a tick longer to start. */
	static final int FIRST_ITEM_IDLE_TICKS = 3;

	private final Skill skill;
	private int lastLevel = -1;
	private boolean dialogShown;
	private int noAnimationTicks;
	private int ticksSinceProgress;
	private boolean progressed;

	SkillInterruptWatcher(Skill skill)
	{
		this.skill = skill;
	}

	/** Start of a batch (make menu just closed). */
	void reset(Client client)
	{
		lastLevel = client.getRealSkillLevel(skill);
		dialogShown = levelUpDialogOpen(client);
		noAnimationTicks = 0;
		ticksSinceProgress = 0;
		progressed = false;
	}

	/**
	 * @param animating the player is animating this tick
	 * @param usedItem an input item was used up since the last tick
	 * @return why the batch stopped, or null while it is still running
	 */
	String update(Client client, boolean animating, boolean usedItem)
	{
		int level = client.getRealSkillLevel(skill);
		boolean levelUp = lastLevel > 0 && level > lastLevel;
		lastLevel = level;

		boolean dialog = levelUpDialogOpen(client);
		boolean dialogAppeared = dialog && !dialogShown;
		dialogShown = dialog;

		if (usedItem)
		{
			progressed = true;
			ticksSinceProgress = 0;
		}
		else
		{
			++ticksSinceProgress;
		}
		noAnimationTicks = animating ? 0 : noAnimationTicks + 1;

		if (levelUp)
		{
			return "level up (" + skill.getName() + " " + level + ")";
		}
		if (dialogAppeared)
		{
			return "level-up dialog opened";
		}
		int idleLimit = progressed ? IDLE_TICKS : FIRST_ITEM_IDLE_TICKS;
		if (noAnimationTicks >= idleLimit && ticksSinceProgress >= idleLimit)
		{
			return "idle " + noAnimationTicks + " ticks with no animation";
		}
		return null;
	}

	private static boolean levelUpDialogOpen(Client client)
	{
		Widget widget = client.getWidget(InterfaceID.LevelupDisplay.UNIVERSE);
		return widget != null && !widget.isHidden();
	}
}
