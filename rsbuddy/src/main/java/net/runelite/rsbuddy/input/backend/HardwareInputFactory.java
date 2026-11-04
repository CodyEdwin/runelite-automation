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

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chooses the OS level input backend. There is no Java fallback: when no
 * hardware backend works, {@link #create} returns null and scripts refuse to
 * start. Java's own input (AWT Robot, synthetic AWT events) is never used.
 */
public final class HardwareInputFactory
{
	private static final Logger log = LoggerFactory.getLogger(HardwareInputFactory.class);

	private static volatile String lastProblem = "not checked yet";

	private HardwareInputFactory()
	{
	}

	/** @return why the last {@link #create} call found no backend */
	public static String getLastProblem()
	{
		return lastProblem;
	}

	/** Records why the current backend stopped working. */
	public static void setProblem(String problem)
	{
		lastProblem = problem;
	}

	/**
	 * @return a ready hardware backend, or null (see {@link #getLastProblem})
	 */
	public static HardwareInput create(InputBackendType preference)
	{
		InputBackendType type = preference == null ? InputBackendType.AUTO : preference;
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (type == InputBackendType.AUTO)
		{
			if (os.contains("win"))
			{
				type = InputBackendType.WINDOWS_SENDINPUT;
			}
			else if (os.contains("linux"))
			{
				type = InputBackendType.LINUX_X11;
			}
			else
			{
				return fail("no hardware input backend for OS '" + System.getProperty("os.name") + "'");
			}
		}

		try
		{
			if (type == InputBackendType.WINDOWS_SENDINPUT)
			{
				if (!os.contains("win"))
				{
					return fail("Windows SendInput selected but this is " + System.getProperty("os.name"));
				}
				WindowsHardwareInput input = new WindowsHardwareInput();
				if (!input.isAvailable())
				{
					return fail("Windows SendInput unavailable: " + input.getProblem());
				}
				return ok(input);
			}

			if (!os.contains("linux"))
			{
				return fail("Linux X11 selected but this is " + System.getProperty("os.name"));
			}
			if (System.getenv("DISPLAY") == null)
			{
				return fail("Linux X11: no DISPLAY (Wayland-only sessions are not supported; use an X11/XFCE session)");
			}
			X11HardwareInput input = new X11HardwareInput();
			if (!input.isAvailable())
			{
				input.close();
				return fail("Linux X11: cannot open DISPLAY " + System.getenv("DISPLAY")
					+ " or the XTEST extension / libXtst is missing (install libxtst6)");
			}
			return ok(input);
		}
		catch (Throwable ex)
		{
			return fail(type + " failed to start: " + ex);
		}
	}

	private static HardwareInput ok(HardwareInput input)
	{
		lastProblem = null;
		log.info("[Input] using {}", input.getName());
		return input;
	}

	private static HardwareInput fail(String problem)
	{
		lastProblem = problem;
		log.warn("[Input] no hardware input: {}. Scripts will not start (no Java input fallback).", problem);
		return null;
	}
}
