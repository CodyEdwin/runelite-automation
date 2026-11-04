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

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import net.runelite.client.ui.FontManager;

/**
 * Text overlay built from a routine's {@link Routine#getOverlayLines()}.
 * <p>
 * The panel is translucent so the game shows through. Leading and trailing
 * blank lines are dropped; those used to space text away from the old
 * always-on status header.
 */
public final class LineRoutineOverlay implements RoutineOverlay
{
	private static final int PADDING = 6;
	private static final int LINE_GAP = 2;
	private static final int PANEL_WIDTH = 280;
	private static final int CORNER = 6;

	/** Fill is well below the old opaque test-plugin box (alpha 220). */
	public static final Color BACKGROUND = new Color(56, 43, 28, 112);
	public static final Color BORDER = new Color(120, 95, 62, 160);

	private final List<String> lines;
	private Color textColor;

	private LineRoutineOverlay(List<String> lines)
	{
		this.lines = lines;
		this.textColor = Color.WHITE;
	}

	/**
	 * @return a text overlay, or null when {@code lines} has no visible text
	 */
	@Nullable
	public static LineRoutineOverlay of(List<String> lines)
	{
		List<String> visible = normalize(lines);
		if (visible.isEmpty())
		{
			return null;
		}
		return new LineRoutineOverlay(visible);
	}

	public void setTextColor(Color textColor)
	{
		this.textColor = textColor == null ? Color.WHITE : textColor;
	}

	/**
	 * Fills the shared translucent routine-overlay panel. Custom overlays can
	 * call this before drawing their own contents.
	 */
	public static void drawPanel(Graphics2D graphics, int width, int height)
	{
		graphics.setColor(BACKGROUND);
		graphics.fillRoundRect(0, 0, width, height, CORNER, CORNER);
		graphics.setColor(BORDER);
		graphics.drawRoundRect(0, 0, width - 1, height - 1, CORNER, CORNER);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		graphics.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics metrics = graphics.getFontMetrics();

		int width = PANEL_WIDTH;
		int textWidth = width - PADDING * 2;
		int lineHeight = metrics.getHeight() + LINE_GAP;
		int height = lines.size() * lineHeight - LINE_GAP + PADDING * 2;

		drawPanel(graphics, width, height);

		int y = PADDING + metrics.getAscent();
		for (String line : lines)
		{
			String text = fitText(metrics, line, textWidth);
			graphics.setColor(Color.BLACK);
			graphics.drawString(text, PADDING + 1, y + 1);
			graphics.setColor(textColor);
			graphics.drawString(text, PADDING, y);
			y += lineHeight;
		}

		return new Dimension(width, height);
	}

	private static List<String> normalize(List<String> lines)
	{
		if (lines == null || lines.isEmpty())
		{
			return Collections.emptyList();
		}

		int start = 0;
		int end = lines.size();
		while (start < end && isBlank(lines.get(start)))
		{
			start++;
		}
		while (end > start && isBlank(lines.get(end - 1)))
		{
			end--;
		}
		if (start >= end)
		{
			return Collections.emptyList();
		}

		boolean anyText = false;
		List<String> copy = new ArrayList<>(end - start);
		for (int i = start; i < end; i++)
		{
			String line = lines.get(i);
			String text = line == null ? "" : line;
			if (!isBlank(text))
			{
				anyText = true;
			}
			copy.add(text);
		}
		return anyText ? copy : Collections.emptyList();
	}

	private static boolean isBlank(String text)
	{
		return text == null || text.trim().isEmpty();
	}

	private static String fitText(FontMetrics metrics, String text, int maxWidth)
	{
		if (text == null)
		{
			return "";
		}

		if (metrics.stringWidth(text) <= maxWidth)
		{
			return text;
		}

		String ellipsis = "...";
		int ellipsisWidth = metrics.stringWidth(ellipsis);
		int end = text.length();
		while (end > 0 && metrics.stringWidth(text.substring(0, end)) + ellipsisWidth > maxWidth)
		{
			--end;
		}

		return text.substring(0, end) + ellipsis;
	}
}
