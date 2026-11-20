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

import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Android hardware input for RuneLite Mobile (PojavLauncher / caciocavallo).
 * <p>
 * On Android every touch, mouse and keyboard event reaches the JVM through
 * {@code CTCAndroidInput.receiveData(type, i1, i2, i3, i4)}, called from the
 * launcher's native AWT bridge. This backend calls that same entry point, so
 * the client gets exactly the events a real touch or Bluetooth mouse would
 * produce; nothing is dispatched to the canvas directly.
 * <p>
 * Coordinates are pixels of caciocavallo's virtual screen, which is what
 * {@code Canvas.getLocationOnScreen()} reports there.
 */
final class AndroidHardwareInput implements HardwareInput
{
	private static final String[] BRIDGE_CLASSES = {
		"com.github.caciocavallosilano.cacio.ctc.CTCAndroidInput",
		"net.java.openjdk.cacio.ctc.CTCAndroidInput",
	};

	private static final int EVENT_TYPE_CHAR = 1000;
	private static final int EVENT_TYPE_CURSOR_POS = 1003;
	private static final int EVENT_TYPE_KEY = 1005;
	private static final int EVENT_TYPE_MOUSE_BUTTON = 1006;
	private static final int EVENT_TYPE_SCROLL = 1007;

	private final Method receiveData;
	private final String problem;
	private final Set<Integer> pressedKeys = new HashSet<>();
	private final Set<Integer> pressedButtons = new HashSet<>();
	private Point lastPointer;
	private boolean shiftDown;

	AndroidHardwareInput()
	{
		Method found = null;
		String error = "CTCAndroidInput not found (not running in RuneLite Mobile)";
		for (String name : BRIDGE_CLASSES)
		{
			Class<?> bridge = load(name);
			if (bridge == null)
			{
				continue;
			}
			try
			{
				found = bridge.getMethod("receiveData", int.class, int.class, int.class, int.class, int.class);
				error = null;
				break;
			}
			catch (NoSuchMethodException ex)
			{
				error = name + " has no receiveData(int, int, int, int, int)";
			}
		}
		receiveData = found;
		problem = error;
	}

	/**
	 * @return true when the JVM runs inside RuneLite Mobile's caciocavallo AWT
	 */
	static boolean isAndroidRuntime()
	{
		for (String name : BRIDGE_CLASSES)
		{
			if (load(name) != null)
			{
				return true;
			}
		}
		return false;
	}

	private static Class<?> load(String name)
	{
		ClassLoader[] loaders = {
			ClassLoader.getSystemClassLoader(),
			Thread.currentThread().getContextClassLoader(),
			AndroidHardwareInput.class.getClassLoader(),
		};
		for (ClassLoader loader : loaders)
		{
			try
			{
				return Class.forName(name, false, loader);
			}
			catch (ClassNotFoundException | LinkageError ex)
			{
				// try the next loader
			}
		}
		return null;
	}

	boolean isAvailable()
	{
		return receiveData != null;
	}

	String getProblem()
	{
		return problem;
	}

	@Override
	public String getName()
	{
		return "Android touch bridge";
	}

	@Override
	public synchronized Point getPointerLocation()
	{
		try
		{
			PointerInfo info = MouseInfo.getPointerInfo();
			if (info != null && info.getLocation() != null)
			{
				return info.getLocation();
			}
		}
		catch (RuntimeException ex)
		{
			// fall back to the last position this backend sent
		}
		return lastPointer == null ? null : new Point(lastPointer);
	}

	@Override
	public synchronized void movePointer(int screenX, int screenY)
	{
		lastPointer = new Point(screenX, screenY);
		send(EVENT_TYPE_CURSOR_POS, screenX, screenY, 0);
	}

	@Override
	public synchronized void mouseButton(int awtButton, boolean pressed)
	{
		int button = awtButton == MouseEvent.BUTTON2 || awtButton == MouseEvent.BUTTON3 ? awtButton : MouseEvent.BUTTON1;
		if (pressed)
		{
			pressedButtons.add(button);
		}
		else
		{
			pressedButtons.remove(button);
		}
		send(EVENT_TYPE_MOUSE_BUTTON, InputEvent.getMaskForButton(button), pressed ? 1 : 0, 0);
	}

	@Override
	public synchronized void scroll(int notches)
	{
		// The bridge counts positive notches as wheel up; this interface uses positive for down.
		if (notches != 0)
		{
			send(EVENT_TYPE_SCROLL, -notches, 0, 0);
		}
	}

	@Override
	public synchronized void pressKey(int awtKeyCode)
	{
		if (awtKeyCode == KeyEvent.VK_UNDEFINED)
		{
			return;
		}
		pressedKeys.add(awtKeyCode);
		if (awtKeyCode == KeyEvent.VK_SHIFT)
		{
			shiftDown = true;
		}
		key(awtKeyCode, charFor(awtKeyCode), true);
	}

	@Override
	public synchronized void releaseKey(int awtKeyCode)
	{
		if (awtKeyCode == KeyEvent.VK_UNDEFINED)
		{
			return;
		}
		pressedKeys.remove(awtKeyCode);
		key(awtKeyCode, charFor(awtKeyCode), false);
		if (awtKeyCode == KeyEvent.VK_SHIFT)
		{
			shiftDown = false;
		}
	}

	@Override
	public synchronized void typeCharacter(char character)
	{
		if (character == '\r')
		{
			character = '\n';
		}
		int keyCode = keyCodeFor(character);
		if (keyCode == KeyEvent.VK_UNDEFINED)
		{
			// No key for it: deliver just the typed character, as Android's soft keyboard does.
			send(EVENT_TYPE_CHAR, character, 0, 0);
			return;
		}
		boolean shift = needsShift(character) && !shiftDown;
		if (shift)
		{
			key(KeyEvent.VK_SHIFT, (char) 0, true);
		}
		key(keyCode, character, true);
		key(keyCode, character, false);
		if (shift)
		{
			key(KeyEvent.VK_SHIFT, (char) 0, false);
		}
	}

	@Override
	public synchronized void releaseAll()
	{
		for (Integer keyCode : new HashSet<>(pressedKeys))
		{
			releaseKey(keyCode);
		}
		pressedKeys.clear();
		for (Integer button : new HashSet<>(pressedButtons))
		{
			mouseButton(button, false);
		}
		pressedButtons.clear();
	}

	@Override
	public void flush()
	{
		// receiveData posts straight to caciocavallo's event queue.
	}

	@Override
	public void close()
	{
		releaseAll();
	}

	private void key(int keyCode, char keyChar, boolean down)
	{
		send(EVENT_TYPE_KEY, keyChar, keyCode, down ? 1 : 0);
	}

	private void send(int type, int i1, int i2, int i3)
	{
		if (receiveData == null)
		{
			return;
		}
		try
		{
			receiveData.invoke(null, type, i1, i2, i3, 0);
		}
		catch (ReflectiveOperationException ex)
		{
			Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
			throw new IllegalStateException("Android input bridge failed: " + cause, cause);
		}
	}

	/** The character a key press produces, so letter/digit presses also fire KEY_TYPED like a real keyboard. */
	private char charFor(int keyCode)
	{
		if (keyCode >= KeyEvent.VK_A && keyCode <= KeyEvent.VK_Z)
		{
			char c = (char) ('a' + (keyCode - KeyEvent.VK_A));
			return shiftDown ? Character.toUpperCase(c) : c;
		}
		if (keyCode >= KeyEvent.VK_0 && keyCode <= KeyEvent.VK_9 && !shiftDown)
		{
			return (char) ('0' + (keyCode - KeyEvent.VK_0));
		}
		switch (keyCode)
		{
			case KeyEvent.VK_SPACE:
				return ' ';
			case KeyEvent.VK_ENTER:
				return '\n';
			case KeyEvent.VK_BACK_SPACE:
				return '\b';
			case KeyEvent.VK_TAB:
				return '\t';
			case KeyEvent.VK_ESCAPE:
				return (char) 0x1B;
			default:
				return 0;
		}
	}

	private static int keyCodeFor(char c)
	{
		if (c >= 'a' && c <= 'z')
		{
			return KeyEvent.VK_A + (c - 'a');
		}
		if (c >= 'A' && c <= 'Z')
		{
			return KeyEvent.VK_A + (c - 'A');
		}
		if (c >= '0' && c <= '9')
		{
			return KeyEvent.VK_0 + (c - '0');
		}
		switch (c)
		{
			case ' ':
				return KeyEvent.VK_SPACE;
			case '\n':
				return KeyEvent.VK_ENTER;
			case '\b':
				return KeyEvent.VK_BACK_SPACE;
			case '\t':
				return KeyEvent.VK_TAB;
			case 0x1B:
				return KeyEvent.VK_ESCAPE;
			default:
				return KeyEvent.VK_UNDEFINED;
		}
	}

	private static boolean needsShift(char c)
	{
		return c >= 'A' && c <= 'Z';
	}
}
