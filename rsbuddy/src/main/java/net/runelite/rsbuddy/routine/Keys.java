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

import java.awt.event.KeyEvent;

/**
 * Keys module: real key presses through the OS hardware backend, logged with
 * their gap. Used for make/production menus and dialogs.
 * <p>
 * Example: {@code if (makeMenuOpen) actions.makeAll();}
 */
public final class Keys
{
	private final RoutineContext context;
	private final ActionLog log;

	Keys(RoutineContext context, ActionLog log)
	{
		this.context = context;
		this.log = log;
	}

	/**
	 * Presses and releases one key.
	 *
	 * @param keyCode a {@link KeyEvent} VK_ code
	 * @return true when it was sent (false when there is no hardware backend)
	 */
	public boolean press(int keyCode)
	{
		boolean sent = context.getInput().pressKey(keyCode);
		if (sent)
		{
			log.markAction("key " + KeyEvent.getKeyText(keyCode));
		}
		return sent;
	}

	/** Make-all in a make/production menu: Space (the highlighted option). */
	public boolean makeAll()
	{
		return press(KeyEvent.VK_SPACE);
	}

	/**
	 * Picks option {@code n} in a make/production menu with its number key.
	 *
	 * @param n option number, clamped to 1-9
	 */
	public boolean makeOption(int n)
	{
		return press(KeyEvent.VK_0 + Math.max(1, Math.min(9, n)));
	}
}
