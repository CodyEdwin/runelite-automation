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
import net.runelite.api.NPC;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class CurrentHitNpcOverlay extends Overlay
{
	private static final long POPUP_MS = 2200;
	private static final long FADE_MS = 700;

	private static final Color DAMAGE_COLOR = new Color(235, 35, 35);
	private static final Color BLOCK_COLOR = new Color(205, 225, 255);
	private static final Color MAX_COLOR = new Color(255, 155, 0);
	private static final Color MAX_LABEL_COLOR = new Color(255, 215, 40);

	private final CurrentHitConfig config;

	private NPC npc;
	private int hit = -1;
	private boolean maxSplat;
	private long hitTime;

	@Inject
	CurrentHitNpcOverlay(CurrentHitConfig config)
	{
		super();
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	void hit(NPC npc, int amount, boolean max)
	{
		this.npc = npc;
		hit = amount;
		maxSplat = max;
		hitTime = System.nanoTime();
	}

	void reset()
	{
		npc = null;
		hit = -1;
		maxSplat = false;
		hitTime = 0;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.aboveNpc() || npc == null || hit < 0)
		{
			return null;
		}

		long elapsed = (System.nanoTime() - hitTime) / 1_000_000L;
		if (elapsed >= POPUP_MS)
		{
			return null;
		}

		Font base = graphics.getFont();
		float baseSize = Math.max(1f, base.getSize2D());
		Font hitFont = base.deriveFont(Font.BOLD, Math.max(26f, baseSize * 1.7f));
		Font labelFont = base.deriveFont(Font.BOLD, Math.max(9f, baseSize * 0.7f));

		FontMetrics hitMetrics = graphics.getFontMetrics(hitFont);
		FontMetrics labelMetrics = graphics.getFontMetrics(labelFont);

		String hitText = Integer.toString(hit);
		String maxLabel = maxSplat ? "MAX" : "";

		int width = Math.max(hitMetrics.stringWidth(hitText), labelMetrics.stringWidth(maxLabel));
		Point anchor = npc.getCanvasTextLocation(graphics, "", npc.getLogicalHeight() + 30);
		if (anchor == null)
		{
			return null;
		}

		float alpha = 1f;
		if (elapsed >= POPUP_MS - FADE_MS)
		{
			alpha = Math.max(0f, 1f - (elapsed - (POPUP_MS - FADE_MS)) / (float) FADE_MS);
		}

		float pop = Math.min(1f, elapsed / 140f);
		float scale = 1f + 0.35f * (1f - pop);
		float rise = Math.min(1f, elapsed / (float) POPUP_MS) * 14f;

		Composite oldComposite = graphics.getComposite();
		AffineTransform oldTransform = graphics.getTransform();
		Color oldColor = graphics.getColor();

		graphics.translate(anchor.getX(), anchor.getY() - rise);
		graphics.scale(scale, scale);
		graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		graphics.setFont(hitFont);

		int x = -width / 2;
		int y = 0;

		if (!maxLabel.isEmpty())
		{
			graphics.setFont(labelFont);
			graphics.setColor(MAX_LABEL_COLOR);
			drawOutlinedString(graphics, maxLabel, -labelMetrics.stringWidth(maxLabel) / 2,
				y - hitMetrics.getAscent() - 2);
		}

		graphics.setFont(hitFont);
		graphics.setColor(hitColor());
		drawOutlinedString(graphics, hitText, x, y);

		graphics.setComposite(oldComposite);
		graphics.setTransform(oldTransform);
		graphics.setColor(oldColor);

		return null;
	}

	private void drawOutlinedString(Graphics2D graphics, String text, int x, int y)
	{
		Color color = graphics.getColor();
		graphics.setColor(Color.BLACK);
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
