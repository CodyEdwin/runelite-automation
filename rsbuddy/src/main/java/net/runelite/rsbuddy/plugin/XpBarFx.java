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
import java.awt.Font;
import java.awt.FontMetrics;
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
import java.util.Locale;
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
	/** How long the white sheen takes to run from the left edge to the end of the fill. */
	private static final long SWEEP_NANOS = 380_000_000L;
	private static final long FORGET_NANOS = 30_000_000_000L;


	private static final Color TRACK = new Color(8, 10, 18, 238);
	private static final Color TRACK_BORDER = new Color(70, 82, 115, 210);
	private static final Color TRACK_SHADOW = new Color(0, 0, 0, 170);
	private static final Color TRACK_SHADOW_END = new Color(0, 0, 0, 0);
	private static final Color TRACK_SHINE = new Color(255, 255, 255, 28);
	private static final Color GLASS_TOP = new Color(255, 255, 255, 80);
	private static final Color GLASS_END = new Color(255, 255, 255, 0);
	private static final Color OUTLINE = new Color(6, 8, 16);
	private static final int MAX_LABELS = 16;
	private static final float LABEL_LIFE = 1.05f;
	/** How long the launch arc runs before the label floats up (where it used to fade). */
	private static final float ARC_TIME = 0.58f;

	/** A bar's hand-tuned neon gradient: left end, right end, and the accent used by sparks, embers, glow and text. */
	static final class Palette
	{
		final Color left;
		final Color right;
		final Color accent;
		final Color hot;

		Palette(int l, int r)
		{
			this.left = new Color(l);
			this.right = new Color(r);
			this.accent = mix(mix(left, right, 0.5f), Color.WHITE, 0.25f);
			this.hot = mix(accent, Color.WHITE, 0.55f);
		}
	}

	/** Default when a bar matches no skill: electric cyan to violet. */
	static final Palette DEFAULT_PALETTE = new Palette(0x00E6FF, 0xAA3CFF);
	private static final Map<String, Palette> PALETTES = new HashMap<>();

	static
	{
		PALETTES.put("attack", new Palette(0xDC143C, 0xFF00AA));
		PALETTES.put("strength", new Palette(0x78FF28, 0x00C878));
		PALETTES.put("defence", new Palette(0x2878FF, 0x5A28FF));
		PALETTES.put("ranged", new Palette(0x00C8AA, 0xAAFF3C));
		PALETTES.put("prayer", new Palette(0xC8FAFF, 0x50C8FF));
		PALETTES.put("magic", new Palette(0xA03CFF, 0x288CFF));
		PALETTES.put("runecraft", new Palette(0xFFC828, 0xFF3CB4));
		PALETTES.put("hitpoints", new Palette(0xFF2878, 0xFF143C));
		PALETTES.put("crafting", new Palette(0xBE5AFF, 0xFF6ED2));
		PALETTES.put("mining", new Palette(0x00E6FF, 0x285AFF));
		PALETTES.put("smithing", new Palette(0x5A8CC8, 0x00F0FF));
		PALETTES.put("fishing", new Palette(0x00FFDC, 0x0078FF));
		PALETTES.put("cooking", new Palette(0xFF3CC8, 0x8C28FF));
		PALETTES.put("firemaking", new Palette(0xFF8C00, 0xFF008C));
		PALETTES.put("woodcutting", new Palette(0x96FF32, 0x00C8A0));
		PALETTES.put("agility", new Palette(0x1E5AFF, 0x00E6FF));
		PALETTES.put("herblore", new Palette(0x00DC6E, 0xB4FF3C));
		PALETTES.put("thieving", new Palette(0xC83CFF, 0x5A00C8));
		PALETTES.put("fletching", new Palette(0x00B4A0, 0x3CFFFF));
		PALETTES.put("slayer", new Palette(0xDC003C, 0x8200FF));
		PALETTES.put("farming", new Palette(0xC8FF00, 0x00BE5A));
		PALETTES.put("construction", new Palette(0xFFD278, 0x00D2FF));
		PALETTES.put("hunter", new Palette(0xAADC28, 0x00AAAA));
		PALETTES.put("sailing", new Palette(0x00A0FF, 0x00FFC8));
	}

	/** @return the palette for a bar key: a skill name, or a label starting with one; else {@link #DEFAULT_PALETTE} */
	static Palette paletteFor(String key)
	{
		if (key == null)
		{
			return DEFAULT_PALETTE;
		}
		String lower = key.toLowerCase(Locale.ROOT).trim();
		Palette exact = PALETTES.get(lower);
		if (exact != null)
		{
			return exact;
		}
		for (Map.Entry<String, Palette> entry : PALETTES.entrySet())
		{
			if (lower.startsWith(entry.getKey()))
			{
				return entry.getValue();
			}
		}
		return DEFAULT_PALETTE;
	}
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
		/** Start of the running sheen sweep, 0 when none. */
		long sweepAt;
		/** A drop arrived (or arrived again mid-sweep) and needs a sweep. */
		boolean sweepRequested;
		boolean sweepQueued;
		int pendingBurst;
		/** XP carried by the running sweep and by the one queued behind it. */
		long sweepXp;
		long queuedXp;
		final Palette palette;

		/** Skill icon for this bar's flying XP label, may be null. */
		java.awt.image.BufferedImage icon;

		Bar(String key)
		{
			this.palette = paletteFor(key);
		}
	}

	private final Map<String, Bar> bars = new HashMap<>();
	private final RoundRectangle2D.Float round = new RoundRectangle2D.Float();
	private final Line2D.Float line = new Line2D.Float();
	private BasicStroke sparkStroke;
	private Font labelFont;

	// Flying "+N xp" labels for all bars, pooled (see drawLabels).
	private final float[] lx = new float[MAX_LABELS];
	private final float[] ly = new float[MAX_LABELS];
	private final float[] lvx = new float[MAX_LABELS];
	private final float[] lvy = new float[MAX_LABELS];
	private final float[] lage = new float[MAX_LABELS];
	private final String[] ltext = new String[MAX_LABELS];
	private final Color[] lcolor = new Color[MAX_LABELS];
	private final java.awt.image.BufferedImage[] licon = new java.awt.image.BufferedImage[MAX_LABELS];
	private final boolean[] lclimb = new boolean[MAX_LABELS];
	/** Float duration (0 while still in the launch arc) and the scale the float starts at. */
	private final float[] ldur = new float[MAX_LABELS];
	private final float[] lscale = new float[MAX_LABELS];
	private int labels;
	private long labelsNanos;
	private boolean climb;
	private float climbTop = -1000f;
	private float climbRight = 100000f;
	private int labelSize = 16;
	private final Ellipse2D.Float dot = new Ellipse2D.Float();
	private final int[] polyX = new int[4];
	private final int[] polyY = new int[4];
	private final float[] glowStops = {0f, 0.35f, 1f};
	private final Color[] glowColors = new Color[3];
	private final float[] sheenStops = {0f, 0.5f, 1f};
	private final Color[] sheenColors = {CLEAR, new Color(255, 255, 255, 110), CLEAR};

	/**
	 * Starts an XP-drop sweep: the white sheen runs to the end of the fill, then
	 * the flash, glow flare and spark burst fire there.
	 *
	 * @param key the same key passed to {@link #draw}
	 */
	public void pulse(String key)
	{
		pulse(key, 0);
	}

	/**
	 * Same as {@link #pulse(String)}, and when the sweep arrives a "+N xp"
	 * label flies off the end of the bar. Drops that come in during a sweep
	 * are summed into the next sweep's label.
	 *
	 * @param xp the drop's XP (0 for no label)
	 */
	public void pulse(String key, long xp)
	{
		pulse(key, xp, null);
	}

	/**
	 * Same as {@link #pulse(String, long)} with the skill icon drawn next to
	 * the label in climb mode (so it can replace the vanilla XP drops).
	 */
	public void pulse(String key, long xp, java.awt.image.BufferedImage icon)
	{
		Bar bar = bars.computeIfAbsent(key, Bar::new);
		if (icon != null)
		{
			bar.icon = icon;
		}
		if (bar.sweepAt != 0)
		{
			bar.queuedXp += xp;
		}
		else
		{
			bar.sweepXp += xp;
		}
		// The sheen sweeps to the end of the fill first; the flash, glow
		// flare and spark burst fire when it arrives (see draw). A drop
		// during a sweep queues one more sweep instead of restarting it.
		if (bar.sweepAt != 0)
		{
			bar.sweepQueued = true;
		}
		else
		{
			bar.sweepRequested = true;
		}
	}

	/** Drops all bar state (overlay hidden or logged out). */
	public void reset()
	{
		bars.clear();
		for (int i = 0; i < labels; i++)
		{
			ltext[i] = null;
			licon[i] = null;
		}
		labels = 0;
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
	 * @param color     ignored: the colours come from the bar's palette ({@link #paletteFor}); kept for callers
	 * @param now       {@link System#nanoTime()} for this frame
	 * @param animate   false draws a plain gradient bar with no effects
	 * @param intensity spark amount, 0 (none) .. 2 (double); 1 is the default
	 */
	public void draw(Graphics2D g, String key, int x, int y, int w, int h, double progress, Color color,
		long now, boolean animate, double intensity)
	{
		Bar bar = bars.computeIfAbsent(key, Bar::new);
		float dt = bar.lastNanos == 0 ? 0f : Math.min(0.05f, (now - bar.lastNanos) / 1e9f);
		bar.lastNanos = now;

		// The bar's own neon palette; the caller's colour is no longer used.
		Palette palette = bar.palette;
		Color base = palette.accent;
		Color hot = palette.hot;
		Object oldAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		Composite oldComposite = g.getComposite();
		Paint oldPaint = g.getPaint();
		Shape oldClip = g.getClip();
		Stroke oldStroke = g.getStroke();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		float arc = Math.min(h, 14);
		round.setRoundRect(x, y, w, h, arc, arc);
		// Dark glassy track with an inner shadow along the top and a faint shine at the bottom.
		g.setColor(TRACK);
		g.fill(round);
		Shape trackClip = g.getClip();
		g.clip(round);
		g.setPaint(new GradientPaint(x, y, TRACK_SHADOW, x, y + h * 0.55f, TRACK_SHADOW_END));
		g.fillRect(x, y, w, h);
		g.setColor(TRACK_SHINE);
		g.fillRect(x, y + h - Math.max(1, h / 8), w, Math.max(1, h / 8));
		g.setClip(trackClip);

		float fill = (float) (w * Math.max(0, Math.min(1, progress)));
		double t = now / 1e9;
		if (fill >= 1f)
		{
			round.setRoundRect(x, y, fill, h, arc, arc);
			g.clip(round);

			// Neon gradient body, left to right across the full track, with a glassy top highlight.
			g.setPaint(new GradientPaint(x, y, palette.left, x + w, y, palette.right));
			g.fill(round);
			g.setPaint(new GradientPaint(x, y, GLASS_TOP, x, y + h * 0.5f, GLASS_END));
			g.fillRect(x, y, (int) Math.ceil(fill), (int) Math.ceil(h * 0.5f));

			if (animate)
			{
				drawStripes(g, x, y, fill, h, t, hot);
				if (bar.sweepRequested)
				{
					bar.sweepRequested = false;
					bar.sweepAt = now;
				}
				if (bar.sweepAt != 0)
				{
					float k = (now - bar.sweepAt) / (float) SWEEP_NANOS;
					if (k >= 1f)
					{
						// Arrived at the end: flash, glow flare and spark burst.
						bar.pulseAt = now;
						bar.pendingBurst += 26;
						if (bar.sweepXp > 0)
						{
							spawnLabel(bar, x + fill, y + h / 2f, bar.sweepXp);
						}
						bar.sweepXp = bar.queuedXp;
						bar.queuedXp = 0;
						bar.sweepAt = bar.sweepQueued ? now : 0;
						bar.sweepQueued = false;
					}
					else
					{
						float eased = 1f - (1f - k) * (1f - k) * (1f - k);
						drawSheen(g, x, y, h, x + eased * fill);
					}
				}
				drawEmbers(g, x, y, fill, h, t, hot);
			}

			long sincePulse = now - bar.pulseAt;
			if (animate && bar.pulseAt != 0 && sincePulse < PULSE_NANOS)
			{
				float a = 1f - sincePulse / (float) PULSE_NANOS;
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f * a * a));
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
			updateSparks(bar, x + fill, y, h, dt, intensity);
			// Glow only while the spark burst is flying: full when it fires,
			// fading with the flash and the live sparks, zero otherwise.
			long since = now - bar.pulseAt;
			float flare = bar.pulseAt != 0 && since < PULSE_NANOS ? 1f - since / (float) PULSE_NANOS : 0f;
			float glow = Math.min(1f, Math.max(flare, bar.alive / 26f));
			if (glow > 0.01f)
			{
				drawEdgeGlow(g, x + fill, y + h / 2f, h, t, base, hot, glow);
			}
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

	/** Draws the white sheen with its bright front at {@code head}. */
	private void drawSheen(Graphics2D g, int x, int y, int h, float head)
	{
		float width = Math.max(30, h * 2.5f);
		float sx = head - width / 2f;
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

	private void drawEdgeGlow(Graphics2D g, float ex, float ey, int h, double t, Color base, Color hot, float strength)
	{
		float flicker = (float) (0.85 + 0.15 * Math.sin(t * 23) * Math.sin(t * 7.3));
		float radius = h * (0.9f + 0.8f * strength) * flicker;
		glowColors[0] = new Color(255, 255, 255, Math.round(230 * strength));
		glowColors[1] = withAlpha(hot, Math.round(150 * strength));
		glowColors[2] = withAlpha(base, 0);
		g.setPaint(new RadialGradientPaint(ex, ey, radius, glowStops, glowColors));
		g.fill(new Ellipse2D.Float(ex - radius, ey - radius, radius * 2, radius * 2));
	}

	private static void updateSparks(Bar bar, float ex, int y, int h, float dt, double intensity)
	{
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int burst = bar.pendingBurst;
		bar.pendingBurst = 0;
		// Sparks only fly on an XP drop (the burst queued by pulse); no idle emission.
		int spawn = intensity <= 0 ? 0 : (int) Math.round(burst * intensity);

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

	/**
	 * Launches a flying XP label at the end of a bar. Labels share one pool so
	 * drops from different skills stack instead of overlapping: a new label
	 * starts at least one line below the newest one still near the bar.
	 */
	private void spawnLabel(Bar bar, float ex, float ey, long xp)
	{
		if (labels == MAX_LABELS)
		{
			return;
		}
		float startY = ey;
		float line = labelLine();
		for (int j = 0; j < labels; j++)
		{
			if (lx[j] > ex - 60 && lx[j] < ex + 140 && ly[j] > startY - line)
			{
				startY = ly[j] + line;
			}
		}
		int i = labels++;
		lx[i] = ex;
		ly[i] = startY;
		lvx[i] = 55f + 25f * ThreadLocalRandom.current().nextFloat();
		lvy[i] = -95f;
		lage[i] = 0f;
		ltext[i] = climb
			? "+" + String.format(Locale.ROOT, "%,d", xp) + (bar.icon == null ? " xp" : "")
			: "+" + String.format(Locale.ROOT, "%,d", xp) + " xp";
		lcolor[i] = bar.palette.hot;
		licon[i] = climb ? bar.icon : null;
		lclimb[i] = climb;
		ldur[i] = 0f;
	}

	private float labelLine()
	{
		return labelSize * 1.25f;
	}

	/**
	 * Sets how XP labels fly; call once per frame before {@link #drawLabels}.
	 *
	 * @param climb    true: climb to the top of the canvas like the vanilla XP drops; false: short arc and fade
	 * @param topLocal the canvas top (y = 0) in the overlay's coordinates, i.e. minus the overlay's screen y
	 * @param rightLocal the canvas right edge in the overlay's coordinates; labels stay left of it
	 * @param barH     bar thickness, used for the text size
	 */
	public void setLabelMode(boolean climb, float topLocal, float rightLocal, int barH)
	{
		this.climb = climb;
		this.climbTop = topLocal;
		this.climbRight = rightLocal;
		this.labelSize = Math.max(16, Math.round(barH * 1.35f));
	}

	/**
	 * Draws every flying XP label. Call once per frame after the bars.
	 * RuneLite clips overlays to the whole canvas, not their own bounds, so
	 * labels can leave the overlay and reach the top of the screen.
	 * <p>
	 * Climb mode: the same launch arc off the bar end, then, where the arc
	 * used to fade, a slow eased float up to the canvas top (3-4.2 s) with a
	 * gentle sideways sway and bob, fading only near the top.
	 */
	public void drawLabels(Graphics2D g, long now)
	{
		float dt = labelsNanos == 0 ? 0f : Math.min(0.05f, (now - labelsNanos) / 1e9f);
		labelsNanos = now;
		if (labels == 0)
		{
			return;
		}
		Object oldAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		Font previous = g.getFont();
		if (labelFont == null || labelFont.getSize() != labelSize)
		{
			labelFont = previous.deriveFont(Font.BOLD, (float) labelSize);
		}
		g.setFont(labelFont);
		FontMetrics metrics = g.getFontMetrics();
		float fadeZone = labelLine() * 2.5f;
		for (int i = 0; i < labels; )
		{
			lage[i] += dt;
			float scale;
			float alpha;
			boolean dead;
			if (lclimb[i])
			{
				if (ldur[i] == 0f && lage[i] < ARC_TIME)
				{
					// Same launch arc as before, off the bar end with the sparks.
					float k = lage[i] / LABEL_LIFE;
					lvy[i] += 70f * dt;
					lx[i] += lvx[i] * dt;
					ly[i] += lvy[i] * dt;
					scale = 0.85f + 0.45f * (float) Math.sin(Math.PI * Math.min(1f, k * 1.4f)) - 0.15f * k;
					alpha = 1f;
					dead = false;
				}
				else
				{
					if (ldur[i] == 0f)
					{
						// Where the arc used to fade: switch to a slow float up.
						// lvx/lvy now hold the start x/y of the float.
						lvx[i] = lx[i];
						lvy[i] = ly[i];
						ldur[i] = Math.max(3f, Math.min(4.2f, (ly[i] - climbTop) / 140f));
						lscale[i] = 0.85f + 0.45f * (float) Math.sin(Math.PI * Math.min(1f, ARC_TIME / LABEL_LIFE * 1.4f))
							- 0.15f * ARC_TIME / LABEL_LIFE;
					}
					float f = (lage[i] - ARC_TIME) / ldur[i];
					float p = Math.min(1f, f);
					// Smoothstep: gentle ease in and out, so it drifts rather than shoots.
					float eased = p * p * (3f - 2f * p);
					float floatT = lage[i] - ARC_TIME;
					float sway = (float) Math.sin(floatT * 2.1f + i) * 7f * Math.min(1f, floatT * 2f);
					float bob = (float) Math.sin(floatT * 5.3f) * 1.6f;
					lx[i] = lvx[i] + sway + floatT * 4f;
					ly[i] = lvy[i] + (climbTop + labelSize - lvy[i]) * eased + bob;
					scale = lscale[i] + (1.1f - lscale[i]) * Math.min(1f, floatT * 1.5f);
					float toTop = ly[i] - climbTop - labelSize;
					alpha = Math.max(0f, Math.min(1f, Math.min(toTop / fadeZone, (1f - p) * 4f)));
					dead = f >= 1f;
				}
			}
			else
			{
				float k = lage[i] / LABEL_LIFE;
				lvy[i] += 70f * dt;
				lx[i] += lvx[i] * dt;
				ly[i] += lvy[i] * dt;
				scale = 0.85f + 0.45f * (float) Math.sin(Math.PI * Math.min(1f, k * 1.4f)) - 0.15f * k;
				alpha = k < 0.55f ? 1f : 1f - (k - 0.55f) / 0.45f;
				dead = k >= 1f;
			}
			if (dead)
			{
				int last = --labels;
				lx[i] = lx[last];
				ly[i] = ly[last];
				lvx[i] = lvx[last];
				lvy[i] = lvy[last];
				lage[i] = lage[last];
				ltext[i] = ltext[last];
				lcolor[i] = lcolor[last];
				licon[i] = licon[last];
				lclimb[i] = lclimb[last];
				ldur[i] = ldur[last];
				lscale[i] = lscale[last];
				ltext[last] = null;
				licon[last] = null;
				continue;
			}
			drawLabel(g, metrics, i, scale, alpha);
			i++;
		}
		g.setComposite(AlphaComposite.SrcOver);
		g.setFont(previous);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa);
	}

	private void drawLabel(Graphics2D g, FontMetrics metrics, int i, float scale, float alpha)
	{
		String text = ltext[i];
		java.awt.image.BufferedImage icon = licon[i];
		int iconW = icon == null ? 0 : Math.round(icon.getWidth() * (labelSize / (float) Math.max(1, icon.getHeight()))) + 3;
		float tx = -(metrics.stringWidth(text) + iconW) / 2f;
		float ty = metrics.getAscent() / 2f - 1;
		// Keep the whole label on the canvas when the overlay sits at the right edge.
		float cx = Math.min(lx[i], climbRight + tx * scale - 6f);
		g.translate(cx, ly[i]);
		g.scale(scale, scale);
		g.setComposite(alpha(alpha));
		if (icon != null)
		{
			int ih = labelSize;
			g.drawImage(icon, Math.round(tx), Math.round(ty - metrics.getAscent() + (metrics.getAscent() - ih) / 2f + 1),
				iconW - 3, ih, null);
		}
		float textX = tx + iconW;
		g.setColor(OUTLINE);
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				if (dx != 0 || dy != 0)
				{
					g.drawString(text, textX + dx * 1.5f, ty + dy * 1.5f);
				}
			}
		}
		g.setColor(lcolor[i]);
		g.drawString(text, textX, ty);
		g.scale(1 / scale, 1 / scale);
		g.translate(-cx, -ly[i]);
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
