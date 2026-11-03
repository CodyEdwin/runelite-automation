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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chooses the OS level input backend.
 * <p>
 * On Linux with an X display this is XTest. Anywhere else (Windows, macOS,
 * Wayland without an X display, headless CI) this returns null and
 * {@link net.runelite.rsbuddy.input.GameInput} keeps using in-process AWT
 * events. Set {@code -Drsbuddy.input.backend=awt} to force the old behavior.
 */
public final class HardwareInputFactory
{
	private static final Logger log = LoggerFactory.getLogger(HardwareInputFactory.class);
	private static final String BACKEND_PROPERTY = "rsbuddy.input.backend";

	private HardwareInputFactory()
	{
	}

	/**
	 * @return a ready hardware backend, or null if none is available
	 */
	public static HardwareInput create()
	{
		if ("awt".equalsIgnoreCase(System.getProperty(BACKEND_PROPERTY, "")))
		{
			log.info("Hardware input disabled by -D{}", BACKEND_PROPERTY);
			return null;
		}

		String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
		if (!os.contains("linux"))
		{
			return null;
		}

		if (System.getenv("DISPLAY") == null && System.getenv("WAYLAND_DISPLAY") == null)
		{
			return null;
		}

		try
		{
			X11HardwareInput input = new X11HardwareInput();
			if (!input.isAvailable())
			{
				log.info("XTest is not available, falling back to AWT input");
				input.close();
				return null;
			}

			return input;
		}
		catch (Throwable ex)
		{
			log.warn("Unable to start X11 hardware input, falling back to AWT input", ex);
			return null;
		}
	}
}
