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
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.currenthit;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class CurrentHitOverlay extends Overlay
{
	private static final long POPUP_MS = 2200;
	private static final long FADE_MS = 700;
	private static final int PADDING = 4;
	private static final int GAP = 1;

	private static final Color DAMAGE_COLOR = new Color(235, 35, 35);
	private static final Color BLOCK_COLOR = new Color(205, 225, 255);
	private static final Color MAX_COLOR = new Color(255, 155, 0);
	private static final Color MAX_LABEL_COLOR = new Color(255, 215, 40);
	private static final Color BEST_COLOR = new Color(255, 205, 40);
	private static final Color OUTLINE_COLOR = Color.BLACK;

	private int hit = -1;
	private int maxHit = -1;
	private boolean maxSplat;
	private long hitTime;

	@Inject
	CurrentHitOverlay()
	{
		super();
		setPosition(OverlayPosition.BOTTOM_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	void hit(int amount, boolean max)
	{
		hit = amount;
		maxHit = Math.max(maxHit, amount);
		maxSplat = max;
		hitTime = System.nanoTime();
	}

	void reset()
	{
		hit = -1;
		maxHit = -1;
		maxSplat = false;
		hitTime = 0;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (hit < 0)
		{
			return null;
		}

		long elapsed = (System.nanoTime() - hitTime) / 1_000_000L;
		boolean showHit = elapsed < POPUP_MS;
		float alpha = 1f;
		if (elapsed >= POPUP_MS - FADE_MS)
		{
			alpha = Math.max(0f, 1f - (elapsed - (POPUP_MS - FADE_MS)) / (float) FADE_MS);
		}

		Font base = graphics.getFont();
		float baseSize = Math.max(1f, base.getSize2D());
		Font hitFont = base.deriveFont(Font.BOLD, Math.max(26f, baseSize * 1.7f));
		Font maxFont = base.deriveFont(Font.BOLD, Math.max(11f, baseSize * 0.85f));
		Font labelFont = base.deriveFont(Font.BOLD, Math.max(9f, baseSize * 0.7f));

		FontMetrics hitMetrics = graphics.getFontMetrics(hitFont);
		FontMetrics maxMetrics = graphics.getFontMetrics(maxFont);
		FontMetrics labelMetrics = graphics.getFontMetrics(labelFont);

		String hitText = Integer.toString(hit);
		String bestText = "Max " + maxHit;
		String maxLabel = maxSplat && showHit ? "MAX" : "";

		int contentWidth = Math.max(hitMetrics.stringWidth(hitText),
			Math.max(maxMetrics.stringWidth(bestText), labelMetrics.stringWidth(maxLabel)));
		int width = contentWidth + PADDING * 2;
		int height = maxMetrics.getHeight() + PADDING * 2;

		if (showHit)
		{
			height += hitMetrics.getHeight() + GAP;
			if (!maxLabel.isEmpty())
			{
				height += labelMetrics.getHeight();
			}
		}

		if (width <= 0 || height <= 0)
		{
			return null;
		}

		float scale = 1f;
		float rise = 0f;
		if (showHit)
		{
			float pop = Math.min(1f, elapsed / 140f);
			scale = 1f + 0.35f * (1f - pop);
			rise = Math.min(1f, elapsed / (float) POPUP_MS) * 8f;
		}

		Composite oldComposite = graphics.getComposite();
		AffineTransform oldTransform = graphics.getTransform();
		Color oldColor = graphics.getColor();

		int centerX = width / 2;
		int y = PADDING;

		if (showHit && !maxLabel.isEmpty())
		{
			drawText(graphics, labelMetrics, maxLabel, centerX, y + labelMetrics.getAscent(),
				labelFont, MAX_LABEL_COLOR, alpha);
			y += labelMetrics.getHeight();
		}

		if (showHit)
		{
			drawHit(graphics, hitMetrics, hitText, centerX, y + hitMetrics.getHeight() / 2f,
				hitFont, alpha, scale, rise);
			y += hitMetrics.getHeight() + GAP;
		}

		drawText(graphics, maxMetrics, bestText, centerX, y + maxMetrics.getAscent(),
			maxFont, BEST_COLOR, 1f);

		graphics.setComposite(oldComposite);
		graphics.setTransform(oldTransform);
		graphics.setColor(oldColor);

		return new Dimension(width, height);
	}

	private void drawHit(Graphics2D graphics, FontMetrics metrics, String text, int centerX, float centerY,
		Font font, float alpha, float scale, float rise)
	{
		AffineTransform oldTransform = graphics.getTransform();
		Composite oldComposite = graphics.getComposite();

		graphics.translate(centerX, centerY - rise);
		graphics.scale(scale, scale);
		graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		graphics.setFont(font);

		int x = -metrics.stringWidth(text) / 2;
		int y = (metrics.getAscent() - metrics.getDescent()) / 2;
		drawOutlinedString(graphics, text, x, y, hitColor());

		graphics.setComposite(oldComposite);
		graphics.setTransform(oldTransform);
	}

	private void drawText(Graphics2D graphics, FontMetrics metrics, String text, int centerX, int baselineY,
		Font font, Color color, float alpha)
	{
		if (text.isEmpty() || alpha <= 0f)
		{
			return;
		}

		Composite oldComposite = graphics.getComposite();
		graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		graphics.setFont(font);

		int x = centerX - metrics.stringWidth(text) / 2;
		drawOutlinedString(graphics, text, x, baselineY, color);

		graphics.setComposite(oldComposite);
	}

	private void drawOutlinedString(Graphics2D graphics, String text, int x, int y, Color color)
	{
		graphics.setColor(OUTLINE_COLOR);
		graphics.drawString(text, x - 1, y);
		graphics.drawString(text, x + 1, y);
		graphics.drawString(text, x, y - 1);
		graphics.drawString(text, x, y + 1);

		graphics.setColor(color);
		graphics.drawString(text, x, y);
	}

	private Color hitColor()
	{
		if (maxSplat)
		{
			return MAX_COLOR;
		}
		return hit == 0 ? BLOCK_COLOR : DAMAGE_COLOR;
	}
}
