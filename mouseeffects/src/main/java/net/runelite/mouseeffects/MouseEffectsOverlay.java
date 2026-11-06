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
package net.runelite.mouseeffects;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class MouseEffectsOverlay extends Overlay
{
	private static final long NANOS_PER_MS = 1_000_000L;

	private final Client client;
	private final MouseEffectsConfig config;
	private final List<TrailPoint> trail = new ArrayList<>();
	private final List<ClickEffect> clicks = new CopyOnWriteArrayList<>();

	private int lastX = -1;
	private int lastY = -1;

	@Inject
	MouseEffectsOverlay(Client client, MouseEffectsConfig config)
	{
		this.client = client;
		this.config = config;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPriority(PRIORITY_HIGHEST);
	}

	void click(int x, int y)
	{
		clicks.add(new ClickEffect(x, y, System.nanoTime(), ThreadLocalRandom.current().nextLong()));
	}

	void clearTrail()
	{
		trail.clear();
		lastX = -1;
		lastY = -1;
	}

	void reset()
	{
		clearTrail();
		clicks.clear();
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		long now = System.nanoTime();
		if (config.trailEnabled())
		{
			drawTrail(graphics, now);
		}
		if (config.clickEnabled())
		{
			drawClicks(graphics, now);
		}
		return null;
	}

	private void drawTrail(Graphics2D graphics, long now)
	{
		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		if (mouse == null || mouse.getX() < 0 || mouse.getY() < 0)
		{
			clearTrail();
			return;
		}

		int x = mouse.getX();
		int y = mouse.getY();
		if (lastX >= 0 && (Math.abs(x - lastX) > 180 || Math.abs(y - lastY) > 180))
		{
			trail.clear();
		}
		if (x != lastX || y != lastY)
		{
			trail.add(new TrailPoint(x, y, now));
			lastX = x;
			lastY = y;
		}

		long duration = (long) config.trailDuration() * NANOS_PER_MS;
		trail.removeIf(point -> now - point.time > duration);
		while (trail.size() > Math.max(2, config.trailLength()))
		{
			trail.remove(0);
		}
		if (trail.size() < 2)
		{
			return;
		}

		switch (config.trailStyle())
		{
			case NEON:
				drawNeonTrail(graphics, now, duration);
				break;
			case SPARKLE:
				drawSparkleTrail(graphics, now, duration);
				break;
			case RAINBOW:
				drawRainbowTrail(graphics, now, duration);
				break;
			default:
				drawCometTrail(graphics, now, duration);
				break;
		}
	}

	private void drawCometTrail(Graphics2D graphics, long now, long duration)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		try
		{
			antialias(g);
			Color base = config.trailColor();
			Color accent = config.trailAccent();
			int count = trail.size();
			for (int pass = 0; pass < 2; pass++)
			{
				for (int i = 1; i < count; i++)
				{
					TrailPoint a = trail.get(i - 1);
					TrailPoint b = trail.get(i);
					double t = (double) i / (count - 1);
					double fade = fade(now, b.time, duration);
					double alpha = fade * (0.12 + 0.88 * t) * (pass == 0 ? 0.18 : 1.0);
					float width = (float) (config.trailSize() * (0.3 + 0.7 * t)
						* (pass == 0 ? 2.4 : 1.0));
					g.setColor(withAlpha(blend(accent, base, t), alpha));
					g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND,
						BasicStroke.JOIN_ROUND));
					g.drawLine(a.x, a.y, b.x, b.y);
				}
			}

			TrailPoint head = trail.get(count - 1);
			float radius = Math.max(2f, config.trailSize() * 1.8f);
			float[] fractions = {0f, 0.35f, 1f};
			Color[] colors = {
				withAlpha(Color.WHITE, 0.85),
				withAlpha(base, 0.45),
				withAlpha(base, 0)
			};
			g.setPaint(new RadialGradientPaint(new Point2D.Float(head.x, head.y),
				radius, fractions, colors));
			g.fillOval((int) (head.x - radius), (int) (head.y - radius),
				(int) (radius * 2), (int) (radius * 2));
		}
		finally
		{
			g.dispose();
		}
	}

	private void drawNeonTrail(Graphics2D graphics, long now, long duration)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		try
		{
			antialias(g);
			Color base = config.trailColor();
			Color accent = config.trailAccent();
			Path2D path = new Path2D.Float();
			TrailPoint first = trail.get(0);
			path.moveTo(first.x, first.y);
			for (int i = 1; i < trail.size(); i++)
			{
				TrailPoint point = trail.get(i);
				path.lineTo(point.x, point.y);
			}

			g.setStroke(new BasicStroke(config.trailSize() * 2.8f, BasicStroke.CAP_ROUND,
				BasicStroke.JOIN_ROUND));
			g.setColor(withAlpha(accent, 0.16));
			g.draw(path);
			g.setStroke(new BasicStroke(config.trailSize() * 0.8f, BasicStroke.CAP_ROUND,
				BasicStroke.JOIN_ROUND));
			g.setColor(withAlpha(base, 0.9));
			g.draw(path);

			for (int i = 0; i < trail.size(); i += 3)
			{
				TrailPoint point = trail.get(i);
				double alpha = fade(now, point.time, duration) * 0.8;
				int size = Math.max(1, config.trailSize() / 2);
				g.setColor(withAlpha(blend(base, accent, i / (double) trail.size()), alpha));
				g.fillOval(point.x - size, point.y - size, size * 2, size * 2);
			}
		}
		finally
		{
			g.dispose();
		}
	}

	private void drawSparkleTrail(Graphics2D graphics, long now, long duration)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		try
		{
			antialias(g);
			Color base = config.trailColor();
			Color accent = config.trailAccent();
			int count = trail.size();
			for (int i = 0; i < count; i++)
			{
				TrailPoint point = trail.get(i);
				double t = (double) i / (count - 1);
				double age = now - point.time;
				double twinkle = 0.55 + 0.45 * Math.sin(age / 45_000_000.0 + i * 1.7);
				double alpha = fade(now, point.time, duration) * twinkle;
				int radius = (int) Math.max(1, config.trailSize() * (0.4 + 0.8 * t));
				Color color = blend(base, accent, (i % 2 == 0) ? t : 1.0 - t);
				g.setColor(withAlpha(color, alpha));
				g.setStroke(new BasicStroke(1.5f));
				g.drawLine(point.x - radius, point.y, point.x + radius, point.y);
				g.drawLine(point.x, point.y - radius, point.x, point.y + radius);
				g.drawLine(point.x - radius / 2, point.y - radius / 2,
					point.x + radius / 2, point.y + radius / 2);
				g.drawLine(point.x - radius / 2, point.y + radius / 2,
					point.x + radius / 2, point.y - radius / 2);
			}
		}
		finally
		{
			g.dispose();
		}
	}

	private void drawRainbowTrail(Graphics2D graphics, long now, long duration)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		try
		{
			antialias(g);
			int count = trail.size();
			for (int pass = 0; pass < 2; pass++)
			{
				for (int i = 1; i < count; i++)
				{
					TrailPoint a = trail.get(i - 1);
					TrailPoint b = trail.get(i);
					double t = (double) i / (count - 1);
					float hue = (float) ((i * 0.035 + now / 1_000_000_000.0 * 0.18) % 1.0);
					Color color = Color.getHSBColor(hue, 0.85f, 1f);
					double alpha = fade(now, b.time, duration) * t * (pass == 0 ? 0.2 : 1.0);
					float width = (float) (config.trailSize() * t * (pass == 0 ? 2.5 : 1.0));
					g.setColor(withAlpha(color, alpha));
					g.setStroke(new BasicStroke(Math.max(1f, width), BasicStroke.CAP_ROUND,
						BasicStroke.JOIN_ROUND));
					g.drawLine(a.x, a.y, b.x, b.y);
				}
			}
		}
		finally
		{
			g.dispose();
		}
	}

	private void drawClicks(Graphics2D graphics, long now)
	{
		long duration = (long) config.clickDuration() * NANOS_PER_MS;
		clicks.removeIf(click -> now - click.start > duration);
		if (clicks.isEmpty())
		{
			return;
		}

		Graphics2D g = (Graphics2D) graphics.create();
		try
		{
			antialias(g);
			for (ClickEffect click : clicks)
			{
				drawClick(g, click, now, duration);
			}
		}
		finally
		{
			g.dispose();
		}
	}

	private void drawClick(Graphics2D g, ClickEffect click, long now, long duration)
	{
		double progress = Math.min(1.0, (now - click.start) / (double) duration);
		double ease = 1.0 - Math.pow(1.0 - progress, 3.0);
		double alpha = 1.0 - progress;
		Color base = config.clickColor();
		Color accent = config.clickAccent();
		int size = config.clickSize();

		switch (config.clickStyle())
		{
			case SHOCKWAVE:
				drawShockwave(g, click, progress, base, accent, size);
				break;
			case STARBURST:
				drawStarburst(g, click, progress, ease, alpha, base, accent, size);
				break;
			case CONFETTI:
				drawConfetti(g, click, progress, ease, alpha, base, accent, size);
				break;
			default:
				drawNova(g, click, ease, alpha, base, accent, size);
				break;
		}
	}

	private void drawNova(Graphics2D g, ClickEffect click, double ease, double alpha,
		Color base, Color accent, int size)
	{
		int radius = Math.max(1, (int) (size * ease));
		g.setStroke(new BasicStroke(3f));
		g.setColor(withAlpha(base, alpha));
		g.drawOval(click.x - radius, click.y - radius, radius * 2, radius * 2);

		g.setStroke(new BasicStroke(2f));
		g.setColor(withAlpha(accent, alpha * 0.8));
		for (int i = 0; i < 12; i++)
		{
			double angle = i * Math.PI / 6.0;
			int inner = (int) (radius * 0.65);
			int outer = Math.max(inner + 1, radius);
			g.drawLine(click.x + (int) (Math.cos(angle) * inner),
				click.y + (int) (Math.sin(angle) * inner),
				click.x + (int) (Math.cos(angle) * outer),
				click.y + (int) (Math.sin(angle) * outer));
		}

		int core = Math.max(1, (int) (size * 0.22 * (1.0 - ease)));
		g.setColor(withAlpha(Color.WHITE, alpha * 0.8));
		g.fillOval(click.x - core, click.y - core, core * 2, core * 2);
	}

	private void drawShockwave(Graphics2D g, ClickEffect click, double progress,
		Color base, Color accent, int size)
	{
		for (int i = 0; i < 3; i++)
		{
			double offset = i * 0.16;
			double local = Math.max(0.0, Math.min(1.0, (progress - offset) / (1.0 - offset)));
			double ease = 1.0 - Math.pow(1.0 - local, 3.0);
			double alpha = (1.0 - local) * (1.0 - i * 0.18);
			int radius = Math.max(1, (int) (size * ease));
			g.setStroke(new BasicStroke(4f - i));
			g.setColor(withAlpha(i % 2 == 0 ? base : accent, alpha));
			g.drawOval(click.x - radius, click.y - radius, radius * 2, radius * 2);
		}
	}

	private void drawStarburst(Graphics2D g, ClickEffect click, double progress, double ease,
		double alpha, Color base, Color accent, int size)
	{
		double rotation = progress * Math.PI;
		Path2D star = new Path2D.Double();
		for (int i = 0; i < 16; i++)
		{
			double angle = rotation + i * Math.PI / 8.0;
			double radius = (i % 2 == 0) ? size * ease : size * ease * 0.42;
			double px = click.x + Math.cos(angle) * radius;
			double py = click.y + Math.sin(angle) * radius;
			if (i == 0)
			{
				star.moveTo(px, py);
			}
			else
			{
				star.lineTo(px, py);
			}
		}
		star.closePath();

		g.setColor(withAlpha(base, alpha * 0.2));
		g.fill(star);
		g.setStroke(new BasicStroke(2f));
		g.setColor(withAlpha(accent, alpha));
		g.draw(star);

		int core = Math.max(1, (int) (size * 0.2 * (1.0 - ease)));
		g.setColor(withAlpha(Color.WHITE, alpha * 0.9));
		g.fillOval(click.x - core, click.y - core, core * 2, core * 2);
	}

	private void drawConfetti(Graphics2D g, ClickEffect click, double progress, double ease,
		double alpha, Color base, Color accent, int size)
	{
		Random random = new Random(click.seed);
		Color[] palette = {base, accent, Color.WHITE, new Color(255, 225, 90)};
		for (int i = 0; i < 30; i++)
		{
			double angle = random.nextDouble() * Math.PI * 2.0;
			double speed = 0.35 + random.nextDouble() * 0.9;
			double x = click.x + Math.cos(angle) * size * speed * ease;
			double y = click.y + Math.sin(angle) * size * speed * ease
				+ size * 0.4 * progress * progress;
			double rotation = progress * 8.0 + random.nextDouble() * Math.PI;
			int width = 2 + random.nextInt(4);
			Color color = palette[random.nextInt(palette.length)];

			AffineTransform old = g.getTransform();
			g.translate(x, y);
			g.rotate(rotation);
			g.setColor(withAlpha(color, alpha));
			g.fillRect(-width / 2, -width / 2, width, width);
			g.setTransform(old);
		}
	}

	private static void antialias(Graphics2D graphics)
	{
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
			RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
			RenderingHints.VALUE_STROKE_PURE);
	}

	private static double fade(long now, long startedAt, long duration)
	{
		return Math.max(0.0, Math.min(1.0, 1.0 - (now - startedAt) / (double) duration));
	}

	private static Color withAlpha(Color color, double alpha)
	{
		int value = (int) Math.round(Math.max(0.0, Math.min(1.0, alpha))
			* color.getAlpha());
		return new Color(color.getRed(), color.getGreen(), color.getBlue(), value);
	}

	private static Color blend(Color from, Color to, double amount)
	{
		double t = Math.max(0.0, Math.min(1.0, amount));
		return new Color(
			(int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * t),
			(int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * t),
			(int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * t));
	}

	private static final class TrailPoint
	{
		private final int x;
		private final int y;
		private final long time;

		private TrailPoint(int x, int y, long time)
		{
			this.x = x;
			this.y = y;
			this.time = time;
		}
	}

	private static final class ClickEffect
	{
		private final int x;
		private final int y;
		private final long start;
		private final long seed;

		private ClickEffect(int x, int y, long start, long seed)
		{
			this.x = x;
			this.y = y;
			this.start = start;
			this.seed = seed;
		}
	}
}
