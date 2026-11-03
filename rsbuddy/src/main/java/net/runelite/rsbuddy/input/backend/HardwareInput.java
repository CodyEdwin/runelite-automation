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
package net.runelite.rsbuddy.input.backend;

import java.awt.Point;

/**
 * OS level pointer and keyboard device. Implementations send input through the
 * platform instead of posting synthetic AWT events, so the game sees normal
 * pointer and key events from the window system.
 * <p>
 * All coordinates are absolute screen pixels, not game canvas coordinates.
 */
public interface HardwareInput
{
	/**
	 * @return a short name for logging, for example "X11/XTest"
	 */
	String getName();

	/**
	 * @return the current pointer position in screen pixels, or null if it
	 * cannot be read
	 */
	Point getPointerLocation();

	void movePointer(int screenX, int screenY);

	/**
	 * @param awtButton {@link java.awt.event.MouseEvent} button number
	 * @param pressed true to press, false to release
	 */
	void mouseButton(int awtButton, boolean pressed);

	/**
	 * @param notches positive to scroll down, negative to scroll up
	 */
	void scroll(int notches);

	/**
	 * @param awtKeyCode {@link java.awt.event.KeyEvent} virtual key code
	 */
	void pressKey(int awtKeyCode);

	void releaseKey(int awtKeyCode);

	/**
	 * Types one character using the keyboard mapping, including shift when the
	 * character needs it.
	 */
	void typeCharacter(char character);

	/**
	 * Releases every key and button this device pressed, so nothing stays
	 * stuck when a script stops.
	 */
	void releaseAll();

	/**
	 * Pushes queued events to the window system.
	 */
	void flush();

	void close();
}
