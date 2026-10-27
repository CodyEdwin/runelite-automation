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
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Session experience tracker. It watches every skill while the player is
 * logged in and displays XP gained, XP per hour, XP left until the next level
 * and an estimated time to that level.
 */
public class XpLoggerOverlay extends Overlay
{
	private static final int PADDING = 8;
	private static final int WIDTH = 262;
	private static final int HEADER_GAP = 6;
	private static final int TEXT_GAP = 1;
	private static final int BAR_GAP = 4;
	private static final int BAR_HEIGHT = 5;
	private static final int ROW_GAP = 9;
	private static final int MAX_ROWS = 6;
	private static final long MIN_RATE_MILLIS = 10_000L;

	private static final Color BACKGROUND = new Color(56, 43, 28, 220);
	private static final Color BORDER = new Color(120, 95, 62, 255);
	private static final Color TITLE = new Color(255, 222, 170);
	private static final Color TEXT = new Color(235, 235, 235);
	private static final Color MUTED = new Color(178, 178, 178);
	private static final Color GAIN = new Color(139, 195, 74);
	private static final Color RATE = new Color(255, 213, 79);
	private static final Color TRACK = new Color(28, 22, 16, 220);
	private static final Color TRACK_BORDER = new Color(92, 73, 49, 255);

	private final Client client;
	private final TestConfig config;
	private final Map<Skill, SkillSession> sessions = new EnumMap<>(Skill.class);

	private boolean initialized;
	private long sessionStartedAt;

	@Inject
	private XpLoggerOverlay(Client client, TestConfig config)
	{
		this.client = client;
		this.config = config;

		setPosition(OverlayPosition.BOTTOM_RIGHT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showXpLogger())
		{
			reset();
			return null;
		}

		GameState state = client.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.LOGIN_SCREEN_AUTHENTICATOR
			|| state == GameState.HOPPING)
		{
			reset();
			return null;
		}

		if (state != GameState.LOGGED_IN)
		{
			// Loading and connection-lost states are transient; keep the
			// session so a short connection hiccup does not wipe the stats.
			return null;
		}

		long now = System.currentTimeMillis();
		if (!initialized)
		{
			initialize(now);
		}
		update(now);

		List<SkillSession> rows = activeSessions();
		int hiddenRows = Math.max(0, rows.size() - MAX_ROWS);

		graphics.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics small = graphics.getFontMetrics();
		graphics.setFont(FontManager.getRunescapeBoldFont());
		FontMetrics title = graphics.getFontMetrics();

		int rowHeight = small.getHeight() * 2 + TEXT_GAP + BAR_GAP + BAR_HEIGHT;
		int bodyHeight = rows.isEmpty()
			? small.getHeight()
			: Math.min(rows.size(), MAX_ROWS) * (rowHeight + ROW_GAP)
				+ (hiddenRows > 0 ? small.getHeight() + TEXT_GAP : 0) - ROW_GAP;
		int height = PADDING * 2 + title.getHeight() + HEADER_GAP + bodyHeight;

		graphics.setColor(BACKGROUND);
		graphics.fillRoundRect(0, 0, WIDTH, height, 6, 6);
		graphics.setColor(BORDER);
		graphics.drawRoundRect(0, 0, WIDTH - 1, height - 1, 6, 6);

		graphics.setFont(FontManager.getRunescapeBoldFont());
		graphics.setColor(TITLE);
		graphics.drawString("XP Logger", PADDING, PADDING + title.getAscent());

		graphics.setFont(FontManager.getRunescapeSmallFont());
		graphics.setColor(MUTED);
		String session = formatDuration(Math.max(0L, now - sessionStartedAt));
		graphics.drawString(session, WIDTH - PADDING - small.stringWidth(session),
			PADDING + title.getAscent());

		int y = PADDING + title.getHeight() + HEADER_GAP;

		if (rows.isEmpty())
		{
			graphics.setFont(FontManager.getRunescapeSmallFont());
			graphics.setColor(MUTED);
			graphics.drawString("Waiting for XP...", PADDING, y + small.getAscent());
			return new Dimension(WIDTH, height);
		}

		for (int i = 0; i < rows.size() && i < MAX_ROWS; i++)
		{
			SkillSession sessionRow = rows.get(i);
			Skill skill = sessionRow.skill;
			int level = client.getRealSkillLevel(skill);
			long xp = client.getSkillExperience(skill);
			long elapsed = Math.max(0L, now - sessionRow.firstGainAt);
			long xpPerHour = elapsed >= MIN_RATE_MILLIS
				? Math.round(sessionRow.gained * 3_600_000.0 / elapsed)
				: 0L;

			long remaining = level >= Experience.MAX_REAL_LEVEL
				? 0L
				: Math.max(0L, Experience.getXpForLevel(level + 1) - xp);

			graphics.setFont(FontManager.getRunescapeSmallFont());

			int lineY = y + small.getAscent();
			graphics.setColor(TEXT);
			graphics.drawString(skill.getName() + " " + level, PADDING, lineY);

			String rateText = xpPerHour > 0 ? formatRate(xpPerHour) + " xp/hr" : "-- xp/hr";
			graphics.setColor(RATE);
			graphics.drawString(rateText, WIDTH - PADDING - small.stringWidth(rateText), lineY);

			lineY += small.getHeight() + TEXT_GAP;

			String gainedText = "+" + formatNumber(sessionRow.gained) + " xp";
			graphics.setColor(GAIN);
			graphics.drawString(gainedText, PADDING, lineY);

			String nextText;
			if (level >= Experience.MAX_REAL_LEVEL)
			{
				nextText = "MAX";
				graphics.setColor(RATE);
			}
			else
			{
				String eta = xpPerHour > 0
					? formatDuration(Math.round(remaining * 3_600_000.0 / xpPerHour))
					: "--";
				nextText = "next " + formatNumber(remaining) + " (" + eta + ")";
				graphics.setColor(MUTED);
			}
			graphics.drawString(nextText, WIDTH - PADDING - small.stringWidth(nextText), lineY);

			y += small.getHeight() * 2 + TEXT_GAP + BAR_GAP;

			int barWidth = WIDTH - PADDING * 2;
			graphics.setColor(TRACK);
			graphics.fillRoundRect(PADDING, y, barWidth, BAR_HEIGHT, BAR_HEIGHT, BAR_HEIGHT);
			graphics.setColor(TRACK_BORDER);
			graphics.drawRoundRect(PADDING, y, barWidth, BAR_HEIGHT, BAR_HEIGHT, BAR_HEIGHT);

			double progress = levelProgress(skill, level, xp);
			int fillWidth = (int) Math.round(barWidth * progress);
			if (fillWidth > 0)
			{
				Color base = skillColor(skill);
				graphics.setColor(base);
				graphics.fillRoundRect(PADDING, y, fillWidth, BAR_HEIGHT, BAR_HEIGHT, BAR_HEIGHT);
				graphics.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 130));
				graphics.drawRoundRect(PADDING, y, fillWidth, BAR_HEIGHT, BAR_HEIGHT, BAR_HEIGHT);
			}

			y += BAR_HEIGHT + ROW_GAP;
		}

		if (hiddenRows > 0)
		{
			graphics.setFont(FontManager.getRunescapeSmallFont());
			graphics.setColor(MUTED);
			graphics.drawString("+" + hiddenRows + " more skill(s)", PADDING, y + small.getAscent());
		}

		return new Dimension(WIDTH, height);
	}

	private void initialize(long now)
	{
		sessions.clear();
		for (Skill skill : Skill.values())
		{
			sessions.put(skill, new SkillSession(skill, client.getSkillExperience(skill)));
		}

		sessionStartedAt = now;
		initialized = true;
	}

	private void update(long now)
	{
		for (SkillSession session : sessions.values())
		{
			long xp = client.getSkillExperience(session.skill);
			if (xp < session.lastXp)
			{
				// XP should never go down, but a rollback can happen when the
				// client reloads its skill array. Start that skill again.
				session.reset(xp);
				continue;
			}

			if (xp > session.lastXp)
			{
				if (session.firstGainAt == 0L)
				{
					session.firstGainAt = now;
				}

				session.gained += xp - session.lastXp;
				session.lastXp = xp;
				session.lastGainAt = now;
			}
		}
	}

	private List<SkillSession> activeSessions()
	{
		List<SkillSession> active = new ArrayList<>();
		for (SkillSession session : sessions.values())
		{
			if (session.gained > 0)
			{
				active.add(session);
			}
		}

		active.sort(Comparator
			.comparingLong((SkillSession session) -> session.lastGainAt)
			.reversed()
			.thenComparing(session -> session.skill));
		return active;
	}

	private void reset()
	{
		sessions.clear();
		initialized = false;
		sessionStartedAt = 0L;
	}

	private static double levelProgress(Skill skill, int level, long xp)
	{
		if (level >= Experience.MAX_REAL_LEVEL)
		{
			return 1.0;
		}

		long currentLevelXp = Experience.getXpForLevel(level);
		long nextLevelXp = Experience.getXpForLevel(level + 1);
		long span = Math.max(1L, nextLevelXp - currentLevelXp);
		double progress = (double) (xp - currentLevelXp) / span;
		return Math.max(0.0, Math.min(1.0, progress));
	}

	private static Color skillColor(Skill skill)
	{
		// Spread distinct, readable colours across all skills.
		float hue = (skill.ordinal() * 0.61803398875f) % 1.0f;
		return Color.getHSBColor(hue, 0.52f, 0.92f);
	}

	private static String formatRate(long xpPerHour)
	{
		if (xpPerHour >= 1_000_000L)
		{
			return String.format(Locale.ROOT, "%.2fm", xpPerHour / 1_000_000.0);
		}

		if (xpPerHour >= 10_000L)
		{
			return String.format(Locale.ROOT, "%.1fk", xpPerHour / 1_000.0);
		}

		return formatNumber(xpPerHour);
	}

	private static String formatNumber(long value)
	{
		return String.format(Locale.ROOT, "%,d", value);
	}

	private static String formatDuration(long millis)
	{
		long seconds = Math.max(0L, millis / 1000L);
		long days = seconds / 86_400L;
		long hours = (seconds % 86_400L) / 3600L;
		long minutes = (seconds % 3600L) / 60L;
		long remainingSeconds = seconds % 60L;

		if (days > 0)
		{
			return String.format(Locale.ROOT, "%dd %02dh", days, hours);
		}

		if (hours > 0)
		{
			return String.format(Locale.ROOT, "%dh %02dm", hours, minutes);
		}

		if (minutes > 0)
		{
			return String.format(Locale.ROOT, "%dm %02ds", minutes, remainingSeconds);
		}

		return remainingSeconds + "s";
	}

	private static final class SkillSession
	{
		private final Skill skill;
		private long lastXp;
		private long gained;
		private long firstGainAt;
		private long lastGainAt;

		private SkillSession(Skill skill, long xp)
		{
			this.skill = skill;
			this.lastXp = xp;
		}

		private void reset(long xp)
		{
			lastXp = xp;
			gained = 0L;
			firstGainAt = 0L;
			lastGainAt = 0L;
		}
	}
}
