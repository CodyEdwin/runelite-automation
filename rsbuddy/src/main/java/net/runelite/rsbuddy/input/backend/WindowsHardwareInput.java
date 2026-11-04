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

import com.sun.jna.Native;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.HashSet;
import java.util.Set;

/**
 * Windows hardware input through the Win32 {@code SendInput} API.
 * <p>
 * SendInput inserts events into the OS input stream (the same queue real
 * mouse and keyboard drivers feed), so the client receives normal
 * WM_MOUSEMOVE / WM_LBUTTONDOWN / WM_KEYDOWN messages. Keys are sent as scan
 * codes ({@code KEYEVENTF_SCANCODE}); the pointer is moved with absolute
 * coordinates over the whole virtual desktop.
 * <p>
 * Coordinates are absolute physical screen pixels. Java 17 on Windows is
 * per-monitor DPI aware, so the device coordinates GameInput computes from the
 * canvas's default transform are physical pixels.
 */
final class WindowsHardwareInput implements HardwareInput
{
	private static final int INPUT_MOUSE = 0;
	private static final int INPUT_KEYBOARD = 1;

	private static final int MOUSEEVENTF_MOVE = 0x0001;
	private static final int MOUSEEVENTF_LEFTDOWN = 0x0002;
	private static final int MOUSEEVENTF_LEFTUP = 0x0004;
	private static final int MOUSEEVENTF_RIGHTDOWN = 0x0008;
	private static final int MOUSEEVENTF_RIGHTUP = 0x0010;
	private static final int MOUSEEVENTF_MIDDLEDOWN = 0x0020;
	private static final int MOUSEEVENTF_MIDDLEUP = 0x0040;
	private static final int MOUSEEVENTF_WHEEL = 0x0800;
	private static final int MOUSEEVENTF_VIRTUALDESK = 0x4000;
	private static final int MOUSEEVENTF_ABSOLUTE = 0x8000;
	private static final int WHEEL_DELTA = 120;

	private static final int KEYEVENTF_EXTENDEDKEY = 0x0001;
	private static final int KEYEVENTF_KEYUP = 0x0002;
	private static final int KEYEVENTF_UNICODE = 0x0004;
	private static final int KEYEVENTF_SCANCODE = 0x0008;
	private static final int MAPVK_VK_TO_VSC = 0;

	private static final int SM_XVIRTUALSCREEN = 76;
	private static final int SM_YVIRTUALSCREEN = 77;
	private static final int SM_CXVIRTUALSCREEN = 78;
	private static final int SM_CYVIRTUALSCREEN = 79;

	private static final int VK_SHIFT = 0x10;

	/** Functions JNA's User32 mapping does not declare. */
	interface User32Extra extends StdCallLibrary
	{
		User32Extra INSTANCE = Native.load("user32", User32Extra.class, W32APIOptions.DEFAULT_OPTIONS);

		int MapVirtualKeyW(int code, int mapType);

		short VkKeyScanW(char character);
	}

	private final User32 user32;
	private final User32Extra extra;
	private final Set<Integer> pressedKeys = new HashSet<>();
	private final Set<Integer> pressedButtons = new HashSet<>();
	private final boolean available;
	private final String problem;

	WindowsHardwareInput()
	{
		User32 loaded = null;
		User32Extra loadedExtra = null;
		String error = null;
		try
		{
			loaded = User32.INSTANCE;
			loadedExtra = User32Extra.INSTANCE;
			// Harmless check: read the cursor position.
			WinDef.POINT point = new WinDef.POINT();
			if (!loaded.GetCursorPos(point))
			{
				error = "GetCursorPos failed (error " + Native.getLastError() + ")";
			}
			else if (loadedExtra.MapVirtualKeyW(0x41, MAPVK_VK_TO_VSC) == 0)
			{
				error = "MapVirtualKeyW returned no scan code";
			}
		}
		catch (Throwable ex)
		{
			error = "user32 not usable: " + ex;
		}
		user32 = loaded;
		extra = loadedExtra;
		problem = error;
		available = error == null;
	}

	boolean isAvailable()
	{
		return available;
	}

	String getProblem()
	{
		return problem;
	}

	@Override
	public String getName()
	{
		return "Windows SendInput";
	}

	@Override
	public synchronized Point getPointerLocation()
	{
		if (!available)
		{
			return null;
		}
		WinDef.POINT point = new WinDef.POINT();
		return user32.GetCursorPos(point) ? new Point(point.x, point.y) : null;
	}

	@Override
	public synchronized void movePointer(int screenX, int screenY)
	{
		if (!available)
		{
			return;
		}
		int left = user32.GetSystemMetrics(SM_XVIRTUALSCREEN);
		int top = user32.GetSystemMetrics(SM_YVIRTUALSCREEN);
		int width = Math.max(2, user32.GetSystemMetrics(SM_CXVIRTUALSCREEN));
		int height = Math.max(2, user32.GetSystemMetrics(SM_CYVIRTUALSCREEN));
		// Normalised 0..65535 over the virtual desktop; round to the pixel centre.
		int dx = (int) Math.round((screenX - left) * 65535.0 / (width - 1));
		int dy = (int) Math.round((screenY - top) * 65535.0 / (height - 1));
		dx = Math.max(0, Math.min(65535, dx));
		dy = Math.max(0, Math.min(65535, dy));
		sendMouse(dx, dy, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK);
	}

	@Override
	public synchronized void mouseButton(int awtButton, boolean pressed)
	{
		if (!available)
		{
			return;
		}
		int flags;
		if (awtButton == MouseEvent.BUTTON3)
		{
			flags = pressed ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP;
		}
		else if (awtButton == MouseEvent.BUTTON2)
		{
			flags = pressed ? MOUSEEVENTF_MIDDLEDOWN : MOUSEEVENTF_MIDDLEUP;
		}
		else
		{
			awtButton = MouseEvent.BUTTON1;
			flags = pressed ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP;
		}
		if (pressed)
		{
			pressedButtons.add(awtButton);
		}
		else
		{
			pressedButtons.remove(awtButton);
		}
		sendMouse(0, 0, 0, flags);
	}

	@Override
	public synchronized void scroll(int notches)
	{
		if (available && notches != 0)
		{
			// Positive notches scroll down, which is a negative wheel delta.
			sendMouse(0, 0, -WHEEL_DELTA * notches, MOUSEEVENTF_WHEEL);
		}
	}

	@Override
	public synchronized void pressKey(int awtKeyCode)
	{
		int vk = toVirtualKey(awtKeyCode);
		if (available && vk != 0)
		{
			pressedKeys.add(vk);
			sendScan(vk, false);
		}
	}

	@Override
	public synchronized void releaseKey(int awtKeyCode)
	{
		int vk = toVirtualKey(awtKeyCode);
		if (available && vk != 0)
		{
			pressedKeys.remove(vk);
			sendScan(vk, true);
		}
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
			sendScan(0x0D, false);
			sendScan(0x0D, true);
			return;
		}
		short scan = extra.VkKeyScanW(character);
		if (scan == -1)
		{
			// No key on this layout: send the character itself, still through SendInput.
			sendKey(0, character, KEYEVENTF_UNICODE);
			sendKey(0, character, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP);
			return;
		}
		int vk = scan & 0xff;
		boolean shift = (scan & 0x100) != 0;
		if (shift)
		{
			sendScan(VK_SHIFT, false);
		}
		sendScan(vk, false);
		sendScan(vk, true);
		if (shift)
		{
			sendScan(VK_SHIFT, true);
		}
	}

	@Override
	public synchronized void releaseAll()
	{
		if (!available)
		{
			return;
		}
		for (Integer vk : new HashSet<>(pressedKeys))
		{
			sendScan(vk, true);
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
		// SendInput delivers immediately.
	}

	@Override
	public synchronized void close()
	{
		releaseAll();
	}

	private void sendScan(int vk, boolean up)
	{
		int scan = extra.MapVirtualKeyW(vk, MAPVK_VK_TO_VSC);
		int flags = KEYEVENTF_SCANCODE | (up ? KEYEVENTF_KEYUP : 0) | (isExtended(vk) ? KEYEVENTF_EXTENDEDKEY : 0);
		if (scan == 0)
		{
			// No scan code for this key: fall back to the virtual key, still SendInput.
			flags &= ~KEYEVENTF_SCANCODE;
		}
		sendKey(scan == 0 ? vk : 0, scan, flags);
	}

	private void sendKey(int vk, int scan, int flags)
	{
		WinUser.INPUT[] inputs = (WinUser.INPUT[]) new WinUser.INPUT().toArray(1);
		WinUser.INPUT input = inputs[0];
		input.type = new WinDef.DWORD(INPUT_KEYBOARD);
		input.input.setType("ki");
		input.input.ki.wVk = new WinDef.WORD(vk);
		input.input.ki.wScan = new WinDef.WORD(scan);
		input.input.ki.dwFlags = new WinDef.DWORD(flags);
		input.input.ki.time = new WinDef.DWORD(0);
		input.input.ki.dwExtraInfo = new BaseTSD.ULONG_PTR(0);
		send(inputs);
	}

	private void sendMouse(int dx, int dy, int data, int flags)
	{
		WinUser.INPUT[] inputs = (WinUser.INPUT[]) new WinUser.INPUT().toArray(1);
		WinUser.INPUT input = inputs[0];
		input.type = new WinDef.DWORD(INPUT_MOUSE);
		input.input.setType("mi");
		input.input.mi.dx = new WinDef.LONG(dx);
		input.input.mi.dy = new WinDef.LONG(dy);
		input.input.mi.mouseData = new WinDef.DWORD(data);
		input.input.mi.dwFlags = new WinDef.DWORD(flags);
		input.input.mi.time = new WinDef.DWORD(0);
		input.input.mi.dwExtraInfo = new BaseTSD.ULONG_PTR(0);
		send(inputs);
	}

	private void send(WinUser.INPUT[] inputs)
	{
		WinDef.DWORD sent = user32.SendInput(new WinDef.DWORD(1), inputs, inputs[0].size());
		if (sent.intValue() != 1)
		{
			// UIPI blocks input to an elevated window, or the desktop is locked.
			throw new IllegalStateException("[Input] SendInput sent " + sent.intValue() + "/1 (error "
				+ Native.getLastError() + "); is the client running as administrator or the screen locked?");
		}
	}

	private static boolean isExtended(int vk)
	{
		switch (vk)
		{
			case 0x21: // page up
			case 0x22: // page down
			case 0x23: // end
			case 0x24: // home
			case 0x25: // left
			case 0x26: // up
			case 0x27: // right
			case 0x28: // down
			case 0x2D: // insert
			case 0x2E: // delete
			case 0x5B: // left windows
			case 0x6F: // numpad divide
			case 0x90: // num lock
				return true;
			default:
				return false;
		}
	}

	/** AWT key code to Win32 virtual key; most codes are already equal. */
	static int toVirtualKey(int awt)
	{
		switch (awt)
		{
			case KeyEvent.VK_ENTER:
				return 0x0D;
			case KeyEvent.VK_DELETE:
				return 0x2E;
			case KeyEvent.VK_INSERT:
				return 0x2D;
			case KeyEvent.VK_META:
			case KeyEvent.VK_WINDOWS:
				return 0x5B;
			case KeyEvent.VK_BACK_QUOTE:
				return 0xC0;
			case KeyEvent.VK_QUOTE:
				return 0xDE;
			case KeyEvent.VK_SEMICOLON:
				return 0xBA;
			case KeyEvent.VK_EQUALS:
				return 0xBB;
			case KeyEvent.VK_COMMA:
				return 0xBC;
			case KeyEvent.VK_MINUS:
				return 0xBD;
			case KeyEvent.VK_PERIOD:
				return 0xBE;
			case KeyEvent.VK_SLASH:
				return 0xBF;
			case KeyEvent.VK_OPEN_BRACKET:
				return 0xDB;
			case KeyEvent.VK_BACK_SLASH:
				return 0xDC;
			case KeyEvent.VK_CLOSE_BRACKET:
				return 0xDD;
			case KeyEvent.VK_PRINTSCREEN:
				return 0x2C;
			case KeyEvent.VK_UNDEFINED:
				return 0;
			default:
				// Letters, digits, F1-F24, arrows, space, escape, tab,
				// backspace, shift/ctrl/alt, page/home/end, numpad match.
				return awt;
		}
	}
}
