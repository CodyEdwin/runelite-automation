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

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.Test;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class XpLoggerBarsTest
{
	@Test
	public void overlayFontDefaultsToJetBrainsMonoNerd()
	{
		Font font = XpFonts.font("JetBrainsMonoNerdFont", Font.PLAIN, 16f);
		String compact = font.getFamily().toLowerCase().replace(" ", "");
		assertTrue(compact.contains("jetbrainsmono"));
		// Java reports this face as "JetBrainsMono NF".
		assertTrue(compact.contains("nerd") || compact.contains("nf"));
		assertTrue(XpFonts.resolve("JetBrains Mono Nerd Font").toLowerCase().replace(" ", "").contains("nf"));
	}

	@Test
	public void barFillColorPaintsTheBar()
	{
		Color red = sampleFill(Color.RED);
		Color blue = sampleFill(Color.BLUE);
		assertNotEquals(red.getRGB(), blue.getRGB());
		assertTrue(red.getRed() > red.getBlue());
		assertTrue(blue.getBlue() > blue.getRed());
	}

	@Test
	public void flashColorChangesThePulse()
	{
		int red = flashImage(Color.RED);
		int blue = flashImage(Color.BLUE);
		assertNotEquals(red, blue);
	}

	private static Color sampleFill(Color fill)
	{
		BufferedImage img = new BufferedImage(220, 40, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		XpBarFx fx = new XpBarFx();
		fx.setColors(Color.BLACK, fill, Color.WHITE);
		fx.draw(g, "defence", 0, 8, 200, 24, 1, fill, 0L, false, 0);
		g.dispose();
		return new Color(img.getRGB(40, 28));
	}

	private static int flashImage(Color flash)
	{
		BufferedImage img = new BufferedImage(260, 64, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.BLACK);
		g.fillRect(0, 0, img.getWidth(), img.getHeight());
		XpBarFx fx = new XpBarFx();
		fx.setColors(Color.BLACK, Color.BLACK, flash);
		fx.pulse("defence");
		long start = 1_000_000L;
		fx.draw(g, "defence", 8, 16, 200, 20, 1, Color.BLACK, start, true, 0);
		fx.draw(g, "defence", 8, 16, 200, 20, 1, Color.BLACK, start + 500_000_000L, true, 0);
		g.dispose();
		int hash = 0;
		for (int y = 0; y < img.getHeight(); y++)
		{
			for (int x = 0; x < img.getWidth(); x++)
			{
				hash = hash * 31 + img.getRGB(x, y);
			}
		}
		return hash;
	}
}
