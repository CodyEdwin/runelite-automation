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

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.platform.unix.X11;
import com.sun.jna.ptr.IntByReference;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.HashSet;
import java.util.Set;

/**
 * X11 (XTest) hardware input for Linux desktops such as XFCE.
 * <p>
 * XTest asks the X server to deliver real pointer and key events, so the
 * client receives them through its normal input pipeline, exactly like input
 * from a physical keyboard and mouse. It does not need root or a custom kernel
 * module, and it works on every X11 session.
 */
final class X11HardwareInput implements HardwareInput
{
	private static final int XK_RETURN = 0xff0d;
	private static final int XK_ESCAPE = 0xff1b;
	private static final int XK_BACK_SPACE = 0xff08;
	private static final int XK_TAB = 0xff09;
	private static final int XK_DELETE = 0xffff;
	private static final int XK_HOME = 0xff50;
	private static final int XK_END = 0xff57;
	private static final int XK_PAGE_UP = 0xff55;
	private static final int XK_PAGE_DOWN = 0xff56;
	private static final int XK_INSERT = 0xff63;
	private static final int XK_LEFT = 0xff51;
	private static final int XK_UP = 0xff52;
	private static final int XK_RIGHT = 0xff53;
	private static final int XK_DOWN = 0xff54;
	private static final int XK_F1 = 0xffbe;
	private static final int XK_SHIFT = 0xffe1;
	private static final int XK_CONTROL = 0xffe3;
	private static final int XK_ALT = 0xffe9;
	private static final int XK_CAPS_LOCK = 0xffe5;
	private static final int XK_NUM_LOCK = 0xff7f;
	private static final int XK_PRINT = 0xff61;
	private static final int XK_SCROLL_LOCK = 0xff14;
	private static final int XK_PAUSE = 0xff13;
	private static final int XK_SUPER_L = 0xffeb;
	private static final int XK_NUMPAD0 = 0xffb0;

	private final X11 x11 = X11.INSTANCE;
	private final X11.Display display;
	private final int screen;
	private final Set<Integer> pressedKeys = new HashSet<>();
	private final Set<Integer> pressedButtons = new HashSet<>();
	private final boolean available;

	X11HardwareInput()
	{
		X11.Display opened = null;
		int defaultScreen = 0;
		try
		{
			opened = x11.XOpenDisplay(null);
			if (opened != null)
			{
				defaultScreen = x11.XDefaultScreen(opened);
			}
		}
		catch (Throwable ex)
		{
			opened = null;
		}

		boolean usable = false;
		if (opened != null)
		{
			IntByReference eventBase = new IntByReference();
			IntByReference errorBase = new IntByReference();
			IntByReference major = new IntByReference();
			IntByReference minor = new IntByReference();
			try
			{
				usable = Xtst.INSTANCE.XTestQueryExtension(opened, eventBase, errorBase, major, minor) != 0;
			}
			catch (Throwable ex)
			{
				usable = false;
			}
		}

		if (!usable && opened != null)
		{
			x11.XCloseDisplay(opened);
			opened = null;
		}

		display = opened;
		screen = defaultScreen;
		available = usable;
	}

	@Override
	public String getName()
	{
		return "X11/XTest";
	}

	boolean isAvailable()
	{
		return available;
	}

	@Override
	public synchronized Point getPointerLocation()
	{
		if (!available)
		{
			return null;
		}

		X11.Window root = x11.XDefaultRootWindow(display);
		X11.WindowByReference rootReturn = new X11.WindowByReference();
		X11.WindowByReference childReturn = new X11.WindowByReference();
		IntByReference rootX = new IntByReference();
		IntByReference rootY = new IntByReference();
		IntByReference winX = new IntByReference();
		IntByReference winY = new IntByReference();
		IntByReference mask = new IntByReference();

		if (!x11.XQueryPointer(display, root, rootReturn, childReturn, rootX, rootY,
			winX, winY, mask))
		{
			return null;
		}

		return new Point(rootX.getValue(), rootY.getValue());
	}

	@Override
	public synchronized void movePointer(int screenX, int screenY)
	{
		if (available)
		{
			Xtst.INSTANCE.XTestFakeMotionEvent(display, screen, screenX, screenY, 0);
		}
	}

	@Override
	public synchronized void mouseButton(int awtButton, boolean pressed)
	{
		if (!available)
		{
			return;
		}

		int button = awtButton == MouseEvent.BUTTON3 ? 3
			: awtButton == MouseEvent.BUTTON2 ? 2
			: 1;

		if (pressed)
		{
			pressedButtons.add(button);
		}
		else
		{
			pressedButtons.remove(button);
		}

		Xtst.INSTANCE.XTestFakeButtonEvent(display, button, pressed ? 1 : 0, 0);
	}

	@Override
	public synchronized void scroll(int notches)
	{
		if (!available)
		{
			return;
		}

		int button = notches >= 0 ? 5 : 4;
		for (int i = 0; i < Math.abs(notches); i++)
		{
			Xtst.INSTANCE.XTestFakeButtonEvent(display, button, 1, 0);
			Xtst.INSTANCE.XTestFakeButtonEvent(display, button, 0, 0);
		}
	}

	@Override
	public synchronized void pressKey(int awtKeyCode)
	{
		pressKeycode(keycodeForKeysym(keysym(awtKeyCode)));
	}

	@Override
	public synchronized void releaseKey(int awtKeyCode)
	{
		releaseKeycode(keycodeForKeysym(keysym(awtKeyCode)));
	}

	@Override
	public synchronized void typeCharacter(char character)
	{
		if (!available)
		{
			return;
		}

		if (character == '\n' || character == '\r')
		{
			int enter = keycodeForKeysym(XK_RETURN);
			pressKeycode(enter);
			releaseKeycode(enter);
			return;
		}

		char base;
		boolean shift;
		if (character >= 'a' && character <= 'z')
		{
			base = character;
			shift = false;
		}
		else if (character >= 'A' && character <= 'Z')
		{
			base = Character.toLowerCase(character);
			shift = true;
		}
		else if (character >= '0' && character <= '9')
		{
			base = character;
			shift = false;
		}
		else
		{
			char[] pair = shiftedBase(character);
			base = pair[0];
			shift = pair[1] == 1;
		}

		int code = base == 0 ? 0 : keycodeForKeysym(base);
		if (code == 0 && character > 0x1f)
		{
			// Unknown printable character: try its Unicode keysym directly.
			code = keycodeForKeysym(0x01000000 | character);
		}

		if (code == 0)
		{
			return;
		}

		int shiftCode = shift ? keycodeForKeysym(XK_SHIFT) : 0;
		if (shiftCode != 0)
		{
			pressKeycode(shiftCode);
		}

		pressKeycode(code);
		releaseKeycode(code);

		if (shiftCode != 0)
		{
			releaseKeycode(shiftCode);
		}
	}

	@Override
	public synchronized void releaseAll()
	{
		if (available)
		{
			for (Integer key : new HashSet<>(pressedKeys))
			{
				Xtst.INSTANCE.XTestFakeKeyEvent(display, key, 0, 0);
			}

			for (Integer button : new HashSet<>(pressedButtons))
			{
				Xtst.INSTANCE.XTestFakeButtonEvent(display, button, 0, 0);
			}

			x11.XFlush(display);
		}

		pressedKeys.clear();
		pressedButtons.clear();
	}

	@Override
	public synchronized void flush()
	{
		if (available)
		{
			x11.XFlush(display);
		}
	}

	@Override
	public synchronized void close()
	{
		if (!available)
		{
			return;
		}

		releaseAll();
		x11.XCloseDisplay(display);
	}

	private void pressKeycode(int keycode)
	{
		if (!available || keycode == 0 || !pressedKeys.add(keycode))
		{
			return;
		}

		Xtst.INSTANCE.XTestFakeKeyEvent(display, keycode, 1, 0);
	}

	private void releaseKeycode(int keycode)
	{
		if (!available || keycode == 0 || !pressedKeys.remove(keycode))
		{
			return;
		}

		Xtst.INSTANCE.XTestFakeKeyEvent(display, keycode, 0, 0);
	}

	/**
	 * Pairs an unshifted key with the character produced when Shift is held.
	 *
	 * @return {base character, 1 if shift is required}
	 */
	private static char[] shiftedBase(char character)
	{
		switch (character)
		{
			case '!': return new char[]{'1', 1};
			case '@': return new char[]{'2', 1};
			case '#': return new char[]{'3', 1};
			case '$': return new char[]{'4', 1};
			case '%': return new char[]{'5', 1};
			case '^': return new char[]{'6', 1};
			case '&': return new char[]{'7', 1};
			case '*': return new char[]{'8', 1};
			case '(': return new char[]{'9', 1};
			case ')': return new char[]{'0', 1};
			case '_': return new char[]{'-', 1};
			case '+': return new char[]{'=', 1};
			case '{': return new char[]{'[', 1};
			case '}': return new char[]{']', 1};
			case '|': return new char[]{'\\', 1};
			case ':': return new char[]{';', 1};
			case '"': return new char[]{'\'', 1};
			case '<': return new char[]{',', 1};
			case '>': return new char[]{'.', 1};
			case '?': return new char[]{'/', 1};
			case '~': return new char[]{'`', 1};
			case ' ': return new char[]{' ', 0};
			case '-': case '=': case '[': case ']': case '\\': case ';': case '\'':
			case ',': case '.': case '/': case '`':
				return new char[]{character, 0};
			default:
				return new char[]{0, 0};
		}
	}

	private int keycodeForKeysym(int keysym)
	{
		if (!available || keysym == 0)
		{
			return 0;
		}

		return x11.XKeysymToKeycode(display, new X11.KeySym(keysym)) & 0xFF;
	}

	private static int keysym(int awtKeyCode)
	{
		if (awtKeyCode >= KeyEvent.VK_A && awtKeyCode <= KeyEvent.VK_Z)
		{
			return 'a' + awtKeyCode - KeyEvent.VK_A;
		}

		if (awtKeyCode >= KeyEvent.VK_0 && awtKeyCode <= KeyEvent.VK_9)
		{
			return '0' + awtKeyCode - KeyEvent.VK_0;
		}

		if (awtKeyCode >= KeyEvent.VK_F1 && awtKeyCode <= KeyEvent.VK_F12)
		{
			return XK_F1 + awtKeyCode - KeyEvent.VK_F1;
		}

		if (awtKeyCode >= KeyEvent.VK_NUMPAD0 && awtKeyCode <= KeyEvent.VK_NUMPAD9)
		{
			return XK_NUMPAD0 + awtKeyCode - KeyEvent.VK_NUMPAD0;
		}

		switch (awtKeyCode)
		{
			case KeyEvent.VK_SPACE: return 0x0020;
			case KeyEvent.VK_ENTER: return XK_RETURN;
			case KeyEvent.VK_ESCAPE: return XK_ESCAPE;
			case KeyEvent.VK_BACK_SPACE: return XK_BACK_SPACE;
			case KeyEvent.VK_TAB: return XK_TAB;
			case KeyEvent.VK_DELETE: return XK_DELETE;
			case KeyEvent.VK_HOME: return XK_HOME;
			case KeyEvent.VK_END: return XK_END;
			case KeyEvent.VK_PAGE_UP: return XK_PAGE_UP;
			case KeyEvent.VK_PAGE_DOWN: return XK_PAGE_DOWN;
			case KeyEvent.VK_INSERT: return XK_INSERT;
			case KeyEvent.VK_LEFT: return XK_LEFT;
			case KeyEvent.VK_UP: return XK_UP;
			case KeyEvent.VK_RIGHT: return XK_RIGHT;
			case KeyEvent.VK_DOWN: return XK_DOWN;
			case KeyEvent.VK_SHIFT: return XK_SHIFT;
			case KeyEvent.VK_CONTROL: return XK_CONTROL;
			case KeyEvent.VK_ALT: return XK_ALT;
			case KeyEvent.VK_CAPS_LOCK: return XK_CAPS_LOCK;
			case KeyEvent.VK_NUM_LOCK: return XK_NUM_LOCK;
			case KeyEvent.VK_PRINTSCREEN: return XK_PRINT;
			case KeyEvent.VK_SCROLL_LOCK: return XK_SCROLL_LOCK;
			case KeyEvent.VK_PAUSE: return XK_PAUSE;
			case KeyEvent.VK_WINDOWS: return XK_SUPER_L;
			case KeyEvent.VK_MINUS: return '-';
			case KeyEvent.VK_EQUALS: return '=';
			case KeyEvent.VK_OPEN_BRACKET: return '[';
			case KeyEvent.VK_CLOSE_BRACKET: return ']';
			case KeyEvent.VK_BACK_SLASH: return '\\';
			case KeyEvent.VK_SEMICOLON: return ';';
			case KeyEvent.VK_QUOTE: return '\'';
			case KeyEvent.VK_COMMA: return ',';
			case KeyEvent.VK_PERIOD: return '.';
			case KeyEvent.VK_SLASH: return '/';
			case KeyEvent.VK_BACK_QUOTE: return '`';
			default: return 0;
		}
	}

	interface Xtst extends Library
	{
		Xtst INSTANCE = Native.load("Xtst", Xtst.class);

		int XTestFakeKeyEvent(X11.Display display, int keycode, int press, long delay);

		int XTestFakeButtonEvent(X11.Display display, int button, int press, long delay);

		int XTestFakeMotionEvent(X11.Display display, int screen, int x, int y, long delay);

		int XTestQueryExtension(X11.Display display, IntByReference eventBase,
			IntByReference errorBase, IntByReference major, IntByReference minor);
	}
}
