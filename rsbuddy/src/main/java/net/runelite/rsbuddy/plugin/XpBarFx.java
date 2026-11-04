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

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Draws the animated XP bars shared by the XP Logger and XP Progress overlays.
 * <p>
 * Everything is time based ({@link System#nanoTime()}), not tick based, so it
 * stays smooth at any frame rate. Per bar it draws: a gradient fill, moving
 * diagonal energy stripes, a sheen sweeping across, embers spiralling along the
 * fill, a glow on the leading edge, sparks shooting off that edge (gravity and
 * fade) and a white flash plus spark burst when {@link #pulse} is called on an
 * XP gain.
 * <p>
 * Performance: sparks live in a fixed pool of {@value #MAX_SPARKS} per bar
 * (parallel float arrays, reused), embers are computed from time with no
 * state, and the shapes used in the loops are reused, so the hot loops do
 * not allocate. Render-thread only; not thread safe.
 */
public final class XpBarFx
{
	/** Spark pool size per bar. */
	static final int MAX_SPARKS = 150;
	private static final int EMBERS = 14;
	private static final float GRAVITY = 260f;
	private static final long PULSE_NANOS = 650_000_000L;
	private static final long FORGET_NANOS = 30_000_000_000L;

	/** Fiery orange, used when a bar has no colour of its own. */
	public static final Color DEFAULT_COLOR = new Color(255, 122, 24);

	private static final Color TRACK = new Color(20, 15, 10, 230);
	private static final Color TRACK_BORDER = new Color(92, 73, 49, 255);
	private static final Color CLEAR = new Color(255, 255, 255, 0);
	/** Pre-built composites for 0..1 alpha in 1/32 steps so the particle loops never allocate. */
	private static final AlphaComposite[] ALPHA = new AlphaComposite[33];

	static
	{
		for (int i = 0; i < ALPHA.length; i++)
		{
			ALPHA[i] = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, i / 32f);
		}
	}

	private static AlphaComposite alpha(float a)
	{
		return ALPHA[Math.max(0, Math.min(32, Math.round(a * 32)))];
	}

	/** Per-bar animation state, including the pooled sparks. */
	private static final class Bar
	{
		final float[] x = new float[MAX_SPARKS];
		final float[] y = new float[MAX_SPARKS];
		final float[] vx = new float[MAX_SPARKS];
		final float[] vy = new float[MAX_SPARKS];
		final float[] life = new float[MAX_SPARKS];
		final float[] maxLife = new float[MAX_SPARKS];
		int alive;
		long lastNanos;
		long pulseAt;
		float emitDebt;
		int pendingBurst;
	}

	private final Map<String, Bar> bars = new HashMap<>();
	private final RoundRectangle2D.Float round = new RoundRectangle2D.Float();
	private final Line2D.Float line = new Line2D.Float();
	private BasicStroke sparkStroke;
	private final Ellipse2D.Float dot = new Ellipse2D.Float();
	private final int[] polyX = new int[4];
	private final int[] polyY = new int[4];
	private final float[] glowStops = {0f, 0.35f, 1f};
	private final Color[] glowColors = new Color[3];
	private final float[] sheenStops = {0f, 0.5f, 1f};
	private final Color[] sheenColors = {CLEAR, new Color(255, 255, 255, 110), CLEAR};

	/**
	 * Flashes the bar and fires a burst of sparks (call on an XP drop).
	 *
	 * @param key the same key passed to {@link #draw}
	 */
	public void pulse(String key)
	{
		Bar bar = bars.computeIfAbsent(key, k -> new Bar());
		bar.pulseAt = System.nanoTime();
		bar.pendingBurst += 26;
	}

	/** Drops all bar state (overlay hidden or logged out). */
	public void reset()
	{
		bars.clear();
	}

	/**
	 * Draws one bar.
	 *
	 * @param g         graphics of the overlay
	 * @param key       stable id of the bar (skill name or label); state is kept per key
	 * @param x         left edge
	 * @param y         top edge
	 * @param w         full track width
	 * @param h         bar thickness
	 * @param progress  0..1 fill
	 * @param color     bar colour (skill colour), or null for fiery orange
	 * @param now       {@link System#nanoTime()} for this frame
	 * @param animate   false draws a plain gradient bar with no effects
	 * @param intensity spark amount, 0 (none) .. 2 (double); 1 is the default
	 */
	public void draw(Graphics2D g, String key, int x, int y, int w, int h, double progress, Color color,
		long now, boolean animate, double intensity)
	{
		Bar bar = bars.computeIfAbsent(key, k -> new Bar());
		float dt = bar.lastNanos == 0 ? 0f : Math.min(0.05f, (now - bar.lastNanos) / 1e9f);
		bar.lastNanos = now;

		Color base = color == null ? DEFAULT_COLOR : color;
		Color hot = mix(base, Color.WHITE, 0.6f);
		Object oldAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		Composite oldComposite = g.getComposite();
		Paint oldPaint = g.getPaint();
		Shape oldClip = g.getClip();
		Stroke oldStroke = g.getStroke();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		float arc = Math.min(h, 14);
		round.setRoundRect(x, y, w, h, arc, arc);
		g.setColor(TRACK);
		g.fill(round);

		float fill = (float) (w * Math.max(0, Math.min(1, progress)));
		double t = now / 1e9;
		if (fill >= 1f)
		{
			round.setRoundRect(x, y, fill, h, arc, arc);
			g.clip(round);

			// Gradient body: dark at the bottom, hot at the top.
			g.setPaint(new GradientPaint(x, y, hot, x, y + h, base.darker()));
			g.fill(round);

			if (animate)
			{
				drawStripes(g, x, y, fill, h, t, hot);
				drawSheen(g, x, y, w, h, t);
				drawEmbers(g, x, y, fill, h, t, hot);
			}

			long sincePulse = now - bar.pulseAt;
			if (animate && bar.pulseAt != 0 && sincePulse < PULSE_NANOS)
			{
				float a = 1f - sincePulse / (float) PULSE_NANOS;
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f * a * a));
				g.setColor(Color.WHITE);
				g.fill(round);
				g.setComposite(oldComposite);
			}
			g.setClip(oldClip);
		}

		g.setColor(TRACK_BORDER);
		g.setStroke(new BasicStroke(1f));
		round.setRoundRect(x, y, w - 1, h - 1, arc, arc);
		g.draw(round);

		if (animate && fill >= 1f)
		{
			drawEdgeGlow(g, x + fill, y + h / 2f, h, t, base, hot);
			updateSparks(bar, x + fill, y, h, dt, intensity, bar.lastNanos != 0 && progress < 1);
			drawSparks(g, bar, base, hot, Math.max(1f, h / 10f));
		}

		g.setStroke(oldStroke);
		g.setPaint(oldPaint);
		g.setComposite(oldComposite);
		g.setClip(oldClip);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa);
		forgetStale(now);
	}

	private void drawStripes(Graphics2D g, int x, int y, float fill, int h, double t, Color hot)
	{
		int spacing = Math.max(10, h);
		int offset = (int) ((t * 38) % spacing);
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f));
		g.setColor(hot);
		for (int sx = x - h - spacing + offset; sx < x + fill; sx += spacing)
		{
			polyX[0] = sx;
			polyX[1] = sx + spacing / 2;
			polyX[2] = sx + spacing / 2 + h;
			polyX[3] = sx + h;
			polyY[0] = y + h;
			polyY[1] = y + h;
			polyY[2] = y;
			polyY[3] = y;
			g.fillPolygon(polyX, polyY, 4);
		}
		g.setComposite(AlphaComposite.SrcOver);
	}

	private void drawSheen(Graphics2D g, int x, int y, int w, int h, double t)
	{
		double phase = (t % 1.9) / 1.9;
		float width = Math.max(30, h * 2.5f);
		float sx = (float) (x - width + (w + width * 2) * phase);
		g.setPaint(new java.awt.LinearGradientPaint(sx, y, sx + width, y + h * 0.4f, sheenStops, sheenColors,
			MultipleGradientPaint.CycleMethod.NO_CYCLE));
		g.fillRect((int) sx, y, (int) width + 1, h);
	}

	private void drawEmbers(Graphics2D g, int x, int y, float fill, int h, double t, Color hot)
	{
		float cy = y + h / 2f;
		float amp = h * 0.36f;
		for (int i = 0; i < EMBERS; i++)
		{
			// Each ember travels along the fill while twirling around the centre line.
			double speed = 0.18 + (i % 5) * 0.045;
			double along = (i * 0.618 + t * speed) % 1.0;
			double spin = t * (4.5 + (i % 3)) + i * 1.7;
			float ex = (float) (x + along * fill);
			float ey = (float) (cy + Math.sin(spin) * amp);
			float depth = (float) (0.55 + 0.45 * Math.cos(spin));
			float r = Math.max(1.2f, h * 0.09f) * (0.6f + depth);
			float alpha = 0.25f + 0.6f * depth * (float) Math.sin(Math.PI * along);
			g.setComposite(alpha(alpha));
			g.setColor(depth > 0.8f ? Color.WHITE : hot);
			dot.setFrame(ex - r, ey - r, r * 2, r * 2);
			g.fill(dot);
		}
		g.setComposite(AlphaComposite.SrcOver);
	}

	private void drawEdgeGlow(Graphics2D g, float ex, float ey, int h, double t, Color base, Color hot)
	{
		float flicker = (float) (0.85 + 0.15 * Math.sin(t * 23) * Math.sin(t * 7.3));
		float radius = h * 1.5f * flicker;
		glowColors[0] = new Color(255, 255, 255, 230);
		glowColors[1] = withAlpha(hot, 150);
		glowColors[2] = withAlpha(base, 0);
		g.setPaint(new RadialGradientPaint(ex, ey, radius, glowStops, glowColors));
		g.fill(new Ellipse2D.Float(ex - radius, ey - radius, radius * 2, radius * 2));
	}

	private static void updateSparks(Bar bar, float ex, int y, int h, float dt, double intensity, boolean emit)
	{
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int burst = bar.pendingBurst;
		bar.pendingBurst = 0;
		if (emit && intensity > 0)
		{
			bar.emitDebt += (float) (dt * 55 * intensity);
		}
		int spawn = (int) bar.emitDebt + (int) (burst * Math.max(0.3, intensity));
		bar.emitDebt -= (int) bar.emitDebt;

		for (int s = 0; s < spawn && bar.alive < MAX_SPARKS; s++)
		{
			int i = bar.alive++;
			bar.x[i] = ex;
			bar.y[i] = y + h * (0.15f + 0.7f * rnd.nextFloat());
			float speed = 60 + 160 * rnd.nextFloat();
			double angle = -Math.PI / 2 + (rnd.nextDouble() - 0.35) * 2.2;
			bar.vx[i] = (float) (Math.cos(angle) * speed) + 30;
			bar.vy[i] = (float) (Math.sin(angle) * speed);
			bar.maxLife[i] = 0.3f + 0.55f * rnd.nextFloat();
			bar.life[i] = bar.maxLife[i];
		}

		for (int i = 0; i < bar.alive; )
		{
			bar.life[i] -= dt;
			if (bar.life[i] <= 0)
			{
				// Swap-remove: move the last live spark into this slot.
				int last = --bar.alive;
				bar.x[i] = bar.x[last];
				bar.y[i] = bar.y[last];
				bar.vx[i] = bar.vx[last];
				bar.vy[i] = bar.vy[last];
				bar.life[i] = bar.life[last];
				bar.maxLife[i] = bar.maxLife[last];
				continue;
			}
			bar.vy[i] += GRAVITY * dt;
			bar.vx[i] *= 1f - 1.2f * dt;
			bar.x[i] += bar.vx[i] * dt;
			bar.y[i] += bar.vy[i] * dt;
			i++;
		}
	}

	private void drawSparks(Graphics2D g, Bar bar, Color base, Color hot, float width)
	{
		if (sparkStroke == null || sparkStroke.getLineWidth() != width)
		{
			sparkStroke = new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
		}
		g.setStroke(sparkStroke);
		for (int i = 0; i < bar.alive; i++)
		{
			float k = bar.life[i] / bar.maxLife[i];
			g.setComposite(alpha(k * 1.2f));
			g.setColor(k > 0.6f ? Color.WHITE : k > 0.3f ? hot : base);
			line.setLine(bar.x[i], bar.y[i], bar.x[i] - bar.vx[i] * 0.035f, bar.y[i] - bar.vy[i] * 0.035f);
			g.draw(line);
		}
		g.setComposite(AlphaComposite.SrcOver);
	}

	private void forgetStale(long now)
	{
		if (bars.size() > 32)
		{
			bars.values().removeIf(bar -> now - bar.lastNanos > FORGET_NANOS);
		}
	}

	/** @return {@code a} blended toward {@code b} by {@code k} (0..1) */
	static Color mix(Color a, Color b, float k)
	{
		return new Color(
			Math.round(a.getRed() + (b.getRed() - a.getRed()) * k),
			Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * k),
			Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * k));
	}

	private static Color withAlpha(Color c, int alpha)
	{
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
	}

	/** @return live sparks of a bar (tests and preview) */
	int sparkCount(String key)
	{
		Bar bar = bars.get(key);
		return bar == null ? 0 : bar.alive;
	}
}
