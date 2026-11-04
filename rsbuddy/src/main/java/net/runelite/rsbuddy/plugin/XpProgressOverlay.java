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
package net.runelite.rsbuddy.plugin;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.rsbuddy.routine.Routine;
import net.runelite.rsbuddy.routine.RoutineManager;

/**
 * Draws animated level progress bars for the active script.
 */
public class XpProgressOverlay extends Overlay
{
	private static final int PADDING = 8;
	private static final int WIDTH = 250;
	private static final int BAR_HEIGHT = 12;
	private static final int LABEL_GAP = 4;
	private static final int BAR_GAP = 12;

	private static final Color BACKGROUND = new Color(56, 43, 28, 220);
	private static final Color BORDER = new Color(120, 95, 62, 255);
	private static final Color TITLE = new Color(255, 222, 170);
	private static final Color TEXT = new Color(235, 235, 235);

	private final RSBuddyPlugin plugin;
	private final RSBuddyConfig config;
	private final Map<String, Double> displayedProgress = new HashMap<>();
	private final Map<String, Double> lastTarget = new HashMap<>();
	private final XpBarFx fx = new XpBarFx();
	private ShiftDragHandler dragHandler;

	private long lastFrameAt;

	@Inject
	private XpProgressOverlay(RSBuddyPlugin plugin, RSBuddyConfig config)
	{
		this.plugin = plugin;
		this.config = config;

		setPosition(OverlayPosition.TOP_RIGHT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	/** Lets the Shift-drag handler draw its hover outline on this overlay. */
	void setDragHandler(ShiftDragHandler dragHandler)
	{
		this.dragHandler = dragHandler;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		RoutineManager routineManager = plugin.getRoutineManager();
		Routine active = routineManager == null ? null : routineManager.getActiveRoutine();
		if (active == null)
		{
			resetAnimation();
			return null;
		}

		List<Routine.ProgressBar> bars = active.getProgressBars();
		if (bars == null || bars.isEmpty())
		{
			resetAnimation();
			return null;
		}

		long now = System.currentTimeMillis();
		double deltaSeconds = lastFrameAt == 0 ? 0.2 : Math.min(0.5, (now - lastFrameAt) / 1000.0);
		lastFrameAt = now;
		double ease = 1.0 - Math.exp(-deltaSeconds * 7.0);

		Font titleFont = FontManager.getRunescapeBoldFont();
		Font smallFont = FontManager.getRunescapeSmallFont();
		graphics.setFont(smallFont);
		FontMetrics metrics = graphics.getFontMetrics();
		graphics.setFont(titleFont);
		FontMetrics titleMetrics = graphics.getFontMetrics();

		int width = WIDTH;
		int barWidth = width - PADDING * 2;
		double scale = config.xpBarThickness() / 100.0;
		int barHeight = Math.max(4, (int) Math.round(BAR_HEIGHT * scale));
		int barGap = (int) Math.round(BAR_GAP * (1 + (scale - 1) * 0.5));
		boolean animate = config.xpBarAnimations();
		double intensity = config.xpSparkIntensity() / 100.0;
		long nanos = System.nanoTime();
		int rowHeight = metrics.getHeight() + LABEL_GAP + barHeight + barGap;
		int height = PADDING * 2 + titleMetrics.getHeight() + 4 + bars.size() * rowHeight - barGap;

		graphics.setColor(BACKGROUND);
		graphics.fillRoundRect(0, 0, width, height, 6, 6);
		graphics.setColor(BORDER);
		graphics.drawRoundRect(0, 0, width - 1, height - 1, 6, 6);
		if (dragHandler != null && dragHandler.isHighlighted(this))
		{
			// Shift held over the overlay: show it can be dragged.
			graphics.setColor(new Color(255, 170, 60, 200));
			graphics.drawRoundRect(1, 1, width - 3, height - 3, 6, 6);
		}

		graphics.setFont(titleFont);
		graphics.setColor(TITLE);
		graphics.drawString("XP Progress", PADDING, PADDING + titleMetrics.getAscent());

		int y = PADDING + titleMetrics.getHeight() + 4;
		for (Routine.ProgressBar bar : bars)
		{
			double target = bar.getProgress();
			Double previous = lastTarget.put(bar.getLabel(), target);
			if (previous != null && target > previous + 1e-9)
			{
				fx.pulse(bar.getLabel());
			}
			double current = displayedProgress.getOrDefault(bar.getLabel(), 0.0);
			current += (target - current) * ease;
			if (Math.abs(target - current) < 0.0005)
			{
				current = target;
			}
			displayedProgress.put(bar.getLabel(), current);

			graphics.setFont(smallFont);
			graphics.setColor(TEXT);
			graphics.drawString(bar.getLabel(), PADDING, y + metrics.getAscent());

			String value = bar.getValue();
			if (value != null)
			{
				int valueWidth = metrics.stringWidth(value);
				graphics.setColor(new Color(215, 215, 215));
				graphics.drawString(value, width - PADDING - valueWidth, y + metrics.getAscent());
			}

			y += metrics.getHeight() + LABEL_GAP;

			fx.draw(graphics, bar.getLabel(), PADDING, y, barWidth, barHeight, current, bar.getColor(),
				nanos, animate, intensity);

			graphics.setFont(barHeight >= 20 ? titleFont : smallFont);
			FontMetrics percentMetrics = graphics.getFontMetrics();
			String percent = String.format(Locale.ROOT, "%.1f%%", current * 100.0);
			int percentWidth = percentMetrics.stringWidth(percent);
			int percentX = PADDING + (barWidth - percentWidth) / 2;
			int percentY = y + (barHeight + percentMetrics.getAscent()) / 2 - 1;
			graphics.setColor(new Color(0, 0, 0, 160));
			graphics.drawString(percent, percentX + 1, percentY + 1);
			graphics.setColor(Color.WHITE);
			graphics.drawString(percent, percentX, percentY);

			y += barHeight + barGap;
		}

		return new Dimension(width, height);
	}

	private void resetAnimation()
	{
		displayedProgress.clear();
		lastTarget.clear();
		fx.reset();
		lastFrameAt = 0;
	}
}
