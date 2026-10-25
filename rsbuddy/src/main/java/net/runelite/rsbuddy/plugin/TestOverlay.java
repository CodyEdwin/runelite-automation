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
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.Text;
import net.runelite.rsbuddy.script.Script;
import net.runelite.rsbuddy.script.ScriptManager;

/**
 * Displays plugin state and the status of the active script.
 */
public class TestOverlay extends Overlay
{
	private static final int PADDING = 6;
	private static final int LINE_GAP = 2;
	private static final int PANEL_WIDTH = 280;
	private static final Color BACKGROUND = new Color(56, 43, 28, 220);
	private static final Color BORDER = new Color(120, 95, 62, 255);

	private final Client client;
	private final TestConfig config;
	private final TestPlugin plugin;

	@Inject
	private TestOverlay(Client client, TestConfig config, TestPlugin plugin)
	{
		this.client = client;
		this.config = config;
		this.plugin = plugin;

		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		List<String> lines = new ArrayList<>();
		lines.add(config.greeting());

		if (config.showPlayerInfo())
		{
			Player player = client.getLocalPlayer();
			if (player != null)
			{
				lines.add("Player: " + Text.removeTags(player.getName()));
			}

			lines.add("World: " + client.getWorld());
		}

		ScriptManager scriptManager = plugin.getScriptManager();
		Script active = scriptManager == null ? null : scriptManager.getActiveScript();
		lines.add(active == null ? "Script: none" : "Script: " + active.getName());
		lines.add("Status: " + (active == null ? "Idle" : active.getStatus()));

		if (active != null)
		{
			lines.addAll(active.getOverlayLines());
		}

		graphics.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics metrics = graphics.getFontMetrics();

		int width = PANEL_WIDTH;
		int textWidth = width - PADDING * 2;

		int lineHeight = metrics.getHeight() + LINE_GAP;
		int height = lines.size() * lineHeight - LINE_GAP + PADDING * 2;

		graphics.setColor(BACKGROUND);
		graphics.fillRoundRect(0, 0, width, height, 6, 6);
		graphics.setColor(BORDER);
		graphics.drawRoundRect(0, 0, width - 1, height - 1, 6, 6);

		Color textColor = config.textColor() == null ? Color.WHITE : config.textColor();
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
