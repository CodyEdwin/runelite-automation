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
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
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
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Session experience tracker. Shows skills gained this session, or the skills
 * picked in the panel, with the stats the panel leaves enabled.
 */
public class XpLoggerOverlay extends Overlay
{
	private static final int TEXT_GAP = 1;
	private static final long MIN_RATE_MILLIS = 10_000L;

	private final Client client;
	private final XpLoggerConfig config;
	private final SkillIconManager skillIcons;
	private final Map<Skill, SkillSession> sessions = new EnumMap<>(Skill.class);
	private final XpBarFx fx = new XpBarFx();

	private ShiftDragHandler dragHandler;
	private boolean initialized;
	private long sessionStartedAt;

	@Inject
	private XpLoggerOverlay(Client client, XpLoggerConfig config, SkillIconManager skillIcons)
	{
		this.client = client;
		this.config = config;
		this.skillIcons = skillIcons;
		setPosition(OverlayPosition.BOTTOM_RIGHT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	void setDragHandler(ShiftDragHandler dragHandler)
	{
		this.dragHandler = dragHandler;
	}

	void applyLayout(OverlayPosition pos, boolean movable, boolean resetSpot)
	{
		if (pos == null)
		{
			pos = OverlayPosition.BOTTOM_RIGHT;
		}
		if (resetSpot && getPosition() != pos)
		{
			setPreferredLocation(null);
		}
		setPosition(pos);
		setMovable(movable);
	}

	void onStatChanged(Skill skill, int xp)
	{
		SkillSession session = sessions.get(skill);
		if (!initialized || session == null)
		{
			return;
		}
		if (session.statXp >= 0 && xp > session.statXp)
		{
			session.actions++;
			session.actionXp += xp - session.statXp;
			long drop = config.showDrops() ? xp - session.statXp : 0;
			fx.pulse(skill.getName(), drop, skillIcons.getSkillImage(skill, true));
		}
		session.statXp = xp;
	}

	/** @return actions left from the session's mean XP per drop, or -1 before the first drop */
	static long actionsToLevel(long remaining, long actionXp, int actions)
	{
		if (actions <= 0 || actionXp <= 0)
		{
			return -1;
		}
		return (long) Math.ceil(remaining / (actionXp / (double) actions));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.show())
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
			return null;
		}

		long now = System.currentTimeMillis();
		if (!initialized)
		{
			initialize(now);
		}
		update(now);

		int pad = config.padding();
		int width = config.width();
		int rowGap = config.rowGap();
		int barGap = rowGap / 2;
		int icon = Math.max(0, config.iconSize());
		int textX = pad + (icon > 0 ? icon + 3 : 0);
		int barHeight = Math.max(3, (int) Math.round(5 * config.barThickness() / 100.0));
		int maxRows = Math.max(1, config.maxRows());
		boolean line2 = config.showGained() || config.showLeft();
		boolean line3 = config.showActions() || config.showAvg();
		int lines = 1 + (line2 ? 1 : 0) + (line3 ? 1 : 0);

		float fontSize = config.fontSize();
		Font textFont = XpFonts.font(config.overlayFont(), Font.PLAIN, fontSize);
		Font titleFont = XpFonts.font(config.overlayFont(), Font.BOLD, fontSize);
		graphics.setFont(textFont);
		FontMetrics small = graphics.getFontMetrics();
		graphics.setFont(titleFont);
		FontMetrics titleFm = graphics.getFontMetrics();

		List<SkillSession> rows = activeSessions();
		int shown = Math.min(rows.size(), maxRows);
		int hidden = rows.size() - shown;
		int textBlock = lines * small.getHeight() + (lines - 1) * TEXT_GAP;
		int rowHeight = textBlock + barGap + barHeight;
		int body = rows.isEmpty()
			? small.getHeight()
			: shown * (rowHeight + rowGap) + (hidden > 0 ? small.getHeight() + TEXT_GAP : 0) - rowGap;
		boolean header = config.showTitle() || config.showSession();
		int height = pad * 2 + (header ? titleFm.getHeight() + 6 : 0) + body;

		graphics.setColor(config.background());
		graphics.fillRoundRect(0, 0, width, height, 6, 6);
		graphics.setColor(config.border());
		graphics.drawRoundRect(0, 0, width - 1, height - 1, 6, 6);
		if (config.movable() && dragHandler != null && dragHandler.isHighlighted(this))
		{
			graphics.setColor(new Color(255, 170, 60, 200));
			graphics.drawRoundRect(1, 1, width - 3, height - 3, 6, 6);
		}

		int y = pad;
		if (header)
		{
			if (config.showTitle())
			{
				graphics.setFont(titleFont);
				graphics.setColor(config.titleColor());
				graphics.drawString(config.title(), pad, pad + titleFm.getAscent());
			}
			if (config.showSession())
			{
				graphics.setFont(textFont);
				graphics.setColor(config.muted());
				String session = formatDuration(Math.max(0L, now - sessionStartedAt));
				graphics.drawString(session, width - pad - small.stringWidth(session), pad + titleFm.getAscent());
			}
			y += titleFm.getHeight() + 6;
		}

		graphics.setFont(textFont);
		if (rows.isEmpty())
		{
			graphics.setColor(config.muted());
			graphics.drawString("Waiting for XP...", textX, y + small.getAscent());
			return new Dimension(width, height);
		}

		fx.setColors(config.barTrack(), config.skillColors() ? null : config.barFill(), config.barFlash());
		fx.setStrands(config.lightStrands(), config.strandColor(), config.strandIntensity() / 100.0);
		long nanos = System.nanoTime();
		double intensity = config.spark() / 100.0;
		for (int i = 0; i < shown; i++)
		{
			y = drawRow(graphics, small, rows.get(i), y, width, pad, textX, icon, barHeight, barGap, line2, line3, nanos, intensity);
			y += rowGap;
		}
		if (config.showDrops())
		{
			graphics.setFont(titleFont);
			fx.setLabelMode(config.climb(), -getBounds().y, client.getCanvasWidth() - getBounds().x, barHeight);
			fx.drawLabels(graphics, nanos);
		}
		if (hidden > 0)
		{
			graphics.setFont(textFont);
			graphics.setColor(config.muted());
			graphics.drawString("+" + hidden + " more skill(s)", pad, y + small.getAscent());
		}
		return new Dimension(width, height);
	}

	private int drawRow(Graphics2D graphics, FontMetrics small, SkillSession session, int y, int width, int pad,
		int textX, int icon, int barHeight, int barGap, boolean line2, boolean line3, long nanos, double intensity)
	{
		Skill skill = session.skill;
		int level = client.getRealSkillLevel(skill);
		long xp = client.getSkillExperience(skill);
		long elapsed = Math.max(0L, System.currentTimeMillis() - session.firstGainAt);
		long xpPerHour = elapsed >= MIN_RATE_MILLIS ? Math.round(session.gained * 3_600_000.0 / elapsed) : 0L;
		long remaining = level >= Experience.MAX_REAL_LEVEL ? 0L : Math.max(0L, Experience.getXpForLevel(level + 1) - xp);
		int lineY = y + small.getAscent();

		if (icon > 0)
		{
			BufferedImage image = skillIcons.getSkillImage(skill, true);
			if (image != null)
			{
				graphics.drawImage(image, pad, y, icon, icon, null);
			}
		}

		String name = skill.getName() + " " + level;
		if (config.showLevels())
		{
			name += " (+" + (level - session.startLevel) + ")";
		}
		graphics.setColor(config.text());
		graphics.drawString(name, textX, lineY);
		if (config.showXpHr())
		{
			String rateText = (xpPerHour > 0 ? formatRate(xpPerHour) : "--") + " xp/hr";
			graphics.setColor(config.rate());
			graphics.drawString(rateText, width - pad - small.stringWidth(rateText), lineY);
		}

		if (line2)
		{
			lineY += small.getHeight() + TEXT_GAP;
			if (config.showGained())
			{
				graphics.setColor(config.gain());
				graphics.drawString("+" + formatNumber(session.gained) + " xp", textX, lineY);
			}
			if (config.showLeft())
			{
				String next;
				if (level >= Experience.MAX_REAL_LEVEL)
				{
					next = "MAX";
					graphics.setColor(config.rate());
				}
				else
				{
					String eta = xpPerHour > 0 ? formatDuration(Math.round(remaining * 3_600_000.0 / xpPerHour)) : "--";
					next = "next " + formatNumber(remaining) + " (" + eta + ")";
					graphics.setColor(config.muted());
				}
				graphics.drawString(next, width - pad - small.stringWidth(next), lineY);
			}
		}

		if (line3)
		{
			lineY += small.getHeight() + TEXT_GAP;
			if (config.showActions())
			{
				long left = actionsToLevel(remaining, session.actionXp, session.actions);
				String actions = level >= Experience.MAX_REAL_LEVEL
					? "Actions to level: MAX"
					: "Actions to level: " + (left < 0 ? "--" : formatNumber(left));
				graphics.setColor(config.text());
				graphics.drawString(actions, textX, lineY);
			}
			if (config.showAvg() && session.actions > 0)
			{
				String avg = "avg " + formatNumber(Math.round(session.actionXp / (double) session.actions)) + " xp";
				graphics.setColor(config.muted());
				graphics.drawString(avg, width - pad - small.stringWidth(avg), lineY);
			}
		}

		y += (1 + (line2 ? 1 : 0) + (line3 ? 1 : 0)) * small.getHeight() + ((line2 ? 1 : 0) + (line3 ? 1 : 0)) * TEXT_GAP + barGap;
		int barWidth = width - pad * 2;
		double progress = levelProgress(level, xp);
		fx.draw(graphics, skill.getName(), pad, y, barWidth, barHeight, progress, config.barFill(),
			nanos, config.animate(), intensity);
		if (config.showPercent() && barHeight >= 11)
		{
			String percent = String.format(Locale.ROOT, "%.1f%%", progress * 100.0);
			int px = pad + (barWidth - small.stringWidth(percent)) / 2;
			int py = y + (barHeight + small.getAscent()) / 2 - 1;
			graphics.setColor(new Color(0, 0, 0, 170));
			graphics.drawString(percent, px + 1, py + 1);
			graphics.setColor(Color.WHITE);
			graphics.drawString(percent, px, py);
		}
		return y + barHeight;
	}

	private void initialize(long now)
	{
		sessions.clear();
		for (Skill skill : Skill.values())
		{
			SkillSession created = new SkillSession(skill, client.getSkillExperience(skill), client.getRealSkillLevel(skill));
			created.statXp = created.lastXp;
			sessions.put(skill, created);
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
		String filter = config.skills();
		List<SkillSession> active = new ArrayList<>();
		for (SkillSession session : sessions.values())
		{
			if (!shown(session.skill, filter) || (config.onlyGained() && session.gained <= 0))
			{
				continue;
			}
			active.add(session);
		}
		active.sort(Comparator.comparingLong((SkillSession session) -> session.lastGainAt).reversed()
			.thenComparing(session -> session.skill));
		return active;
	}

	private static boolean shown(Skill skill, String filter)
	{
		if (filter == null || filter.isEmpty())
		{
			return true;
		}
		for (String part : filter.split(","))
		{
			if (skill.getName().equalsIgnoreCase(part.trim()))
			{
				return true;
			}
		}
		return false;
	}

	private void reset()
	{
		sessions.clear();
		fx.reset();
		initialized = false;
		sessionStartedAt = 0L;
	}

	private static double levelProgress(int level, long xp)
	{
		if (level >= Experience.MAX_REAL_LEVEL)
		{
			return 1.0;
		}
		long span = Math.max(1L, Experience.getXpForLevel(level + 1) - Experience.getXpForLevel(level));
		return Math.max(0.0, Math.min(1.0, (double) (xp - Experience.getXpForLevel(level)) / span));
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
		long secs = seconds % 60L;
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
			return String.format(Locale.ROOT, "%dm %02ds", minutes, secs);
		}
		return secs + "s";
	}

	private static final class SkillSession
	{
		private final Skill skill;
		private final int startLevel;
		private long lastXp;
		private long gained;
		private long firstGainAt;
		private long lastGainAt;
		private long statXp = -1;
		private int actions;
		private long actionXp;

		private SkillSession(Skill skill, long xp, int level)
		{
			this.skill = skill;
			this.lastXp = xp;
			this.startLevel = level;
		}

		private void reset(long xp)
		{
			lastXp = xp;
			gained = 0L;
			statXp = xp;
			actions = 0;
			actionXp = 0L;
			firstGainAt = 0L;
			lastGainAt = 0L;
		}
	}
}
