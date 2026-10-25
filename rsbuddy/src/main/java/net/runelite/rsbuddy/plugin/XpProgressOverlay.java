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
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.rsbuddy.script.Script;
import net.runelite.rsbuddy.script.ScriptManager;

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
	private static final Color TRACK = new Color(28, 22, 16, 220);
	private static final Color TRACK_BORDER = new Color(92, 73, 49, 255);

	private final TestPlugin plugin;
	private final Map<String, Double> displayedProgress = new HashMap<>();

	private long lastFrameAt;

	@Inject
	private XpProgressOverlay(TestPlugin plugin)
	{
		this.plugin = plugin;

		setPosition(OverlayPosition.TOP_RIGHT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		ScriptManager scriptManager = plugin.getScriptManager();
		Script active = scriptManager == null ? null : scriptManager.getActiveScript();
		if (active == null)
		{
			resetAnimation();
			return null;
		}

		List<Script.ProgressBar> bars = active.getProgressBars();
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
		int rowHeight = metrics.getHeight() + LABEL_GAP + BAR_HEIGHT + BAR_GAP;
		int height = PADDING * 2 + titleMetrics.getHeight() + 4 + bars.size() * rowHeight - BAR_GAP;

		graphics.setColor(BACKGROUND);
		graphics.fillRoundRect(0, 0, width, height, 6, 6);
		graphics.setColor(BORDER);
		graphics.drawRoundRect(0, 0, width - 1, height - 1, 6, 6);

		graphics.setFont(titleFont);
		graphics.setColor(TITLE);
		graphics.drawString("XP Progress", PADDING, PADDING + titleMetrics.getAscent());

		int y = PADDING + titleMetrics.getHeight() + 4;
		for (Script.ProgressBar bar : bars)
		{
			double target = bar.getProgress();
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

			graphics.setColor(TRACK);
			graphics.fillRoundRect(PADDING, y, barWidth, BAR_HEIGHT, BAR_HEIGHT, BAR_HEIGHT);
			graphics.setColor(TRACK_BORDER);
			graphics.drawRoundRect(PADDING, y, barWidth, BAR_HEIGHT, BAR_HEIGHT, BAR_HEIGHT);

			int fillWidth = (int) Math.round(barWidth * current);
			if (fillWidth > 0)
			{
				Shape oldClip = graphics.getClip();
				Shape fill = new RoundRectangle2D.Double(
					PADDING, y, fillWidth, BAR_HEIGHT, BAR_HEIGHT, BAR_HEIGHT);
				graphics.setClip(fill);

				Color base = bar.getColor();
				graphics.setPaint(new GradientPaint(
					PADDING, y, base.darker(),
					PADDING, y + BAR_HEIGHT, base.brighter()));
				graphics.fillRect(PADDING, y, fillWidth, BAR_HEIGHT);

				// A soft highlight sweeps across the filled section.
				double phase = (now % 2200L) / 2200.0;
				int shimmerX = PADDING - 24 + (int) Math.round((barWidth + 48) * phase);
				graphics.setPaint(new GradientPaint(
					shimmerX, 0, new Color(255, 255, 255, 0),
					shimmerX + 16, 0, new Color(255, 255, 255, 90)));
				graphics.fillRect(shimmerX, y, 16, BAR_HEIGHT);
				graphics.setPaint(new GradientPaint(
					shimmerX + 16, 0, new Color(255, 255, 255, 90),
					shimmerX + 32, 0, new Color(255, 255, 255, 0)));
				graphics.fillRect(shimmerX + 16, y, 16, BAR_HEIGHT);

				graphics.setClip(oldClip);
				graphics.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 130));
				graphics.drawRoundRect(PADDING, y, fillWidth, BAR_HEIGHT, BAR_HEIGHT, BAR_HEIGHT);
			}

			graphics.setFont(smallFont);
			String percent = String.format(Locale.ROOT, "%.1f%%", current * 100.0);
			int percentWidth = metrics.stringWidth(percent);
			int percentX = PADDING + (barWidth - percentWidth) / 2;
			int percentY = y + (BAR_HEIGHT + metrics.getAscent()) / 2 - 1;
			graphics.setColor(new Color(0, 0, 0, 160));
			graphics.drawString(percent, percentX + 1, percentY + 1);
			graphics.setColor(Color.WHITE);
			graphics.drawString(percent, percentX, percentY);

			y += BAR_HEIGHT + BAR_GAP;
		}

		return new Dimension(width, height);
	}

	private void resetAnimation()
	{
		displayedProgress.clear();
		lastFrameAt = 0;
	}
}
