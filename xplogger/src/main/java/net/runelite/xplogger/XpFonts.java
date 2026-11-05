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
package net.runelite.xplogger;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Overlay fonts. Prefers a JetBrainsMono Nerd Font already on disk, then the
 * copy bundled in this plugin, then any family installed on the machine.
 */
final class XpFonts
{
	static final String FAMILY = "JetBrainsMono Nerd Font";

	private static final String REGULAR = "JetBrainsMonoNerdFont-Regular.ttf";
	private static final String BOLD = "JetBrainsMonoNerdFont-Bold.ttf";

	private static boolean ready;
	private static String[] familyNames;

	private XpFonts()
	{
	}

	static synchronized String[] families()
	{
		register();
		if (familyNames == null)
		{
			familyNames = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
		}
		return familyNames;
	}

	/** Family to select: the requested one, else JetBrainsMono Nerd Font, else a plain fallback. */
	static String resolve(String requested)
	{
		String hit = match(requested);
		if (hit != null)
		{
			return hit;
		}
		hit = match(FAMILY);
		if (hit != null)
		{
			return hit;
		}
		hit = match("JetBrainsMono NF");
		if (hit != null)
		{
			return hit;
		}
		hit = match("JetBrains Mono");
		if (hit != null)
		{
			return hit;
		}
		return Font.SANS_SERIF;
	}

	static Font font(String family, int style, float size)
	{
		return new Font(resolve(family), style, 1).deriveFont(style, size);
	}

	private static synchronized void register()
	{
		if (ready)
		{
			return;
		}
		ready = true;
		GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
		load(ge, REGULAR);
		load(ge, BOLD);
	}

	private static void load(GraphicsEnvironment ge, String file)
	{
		try (InputStream in = open(file))
		{
			if (in != null)
			{
				ge.registerFont(Font.createFont(Font.TRUETYPE_FONT, in));
			}
		}
		catch (IOException | FontFormatException ignored)
		{
		}
	}

	private static InputStream open(String file) throws IOException
	{
		Path found = find(file);
		if (found != null)
		{
			return Files.newInputStream(found);
		}
		return XpFonts.class.getResourceAsStream(file);
	}

	private static Path find(String file)
	{
		String home = System.getProperty("user.home", "");
		String local = System.getenv("LOCALAPPDATA");
		String windir = System.getenv("WINDIR");
		String[] roots = {
			home + "/.local/share/fonts",
			home + "/.fonts",
			home + "/.runelite/fonts",
			home + "/Downloads",
			home + "/Library/Fonts",
			"/usr/local/share/fonts",
			"/usr/share/fonts",
			"/Library/Fonts",
			local == null || local.isEmpty() ? "" : local + "/Microsoft/Windows/Fonts",
			(windir == null || windir.isEmpty() ? "C:/Windows" : windir) + "/Fonts"
		};
		for (String root : roots)
		{
			if (root.isEmpty())
			{
				continue;
			}
			Path dir = Path.of(root);
			if (!Files.isDirectory(dir))
			{
				continue;
			}
			try (Stream<Path> walk = Files.walk(dir, 5))
			{
				Path hit = walk.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().equalsIgnoreCase(file))
					.findFirst()
					.orElse(null);
				if (hit != null)
				{
					return hit;
				}
			}
			catch (Exception ignored)
			{
			}
		}
		return null;
	}

	private static String match(String requested)
	{
		if (requested == null || requested.isEmpty())
		{
			return null;
		}
		String want = compact(requested);
		for (String family : families())
		{
			if (family.equalsIgnoreCase(requested) || compact(family).equals(want))
			{
				return family;
			}
		}
		return null;
	}

	private static String compact(String value)
	{
		return value.toLowerCase(Locale.ROOT).replace(" ", "").replace("-", "");
	}
}
