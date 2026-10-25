/*
 * Copyright (c) 2026, reich
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Stroke;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.client.input.MouseListener;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.rsbuddy.input.GameInput;
import net.runelite.rsbuddy.script.Script;
import net.runelite.rsbuddy.script.ScriptManager;

/**
 * Draws the current mouse position and recent click animations. Real mouse
 * input is always shown, and the simulated mouse is drawn separately while a
 * script is running.
 */
public class MouseOverlay extends Overlay implements MouseListener
{
	private static final long CLICK_DURATION_MS = 360L;
	private static final int CURSOR_RADIUS = 7;
	private static final Color ACTUAL_CURSOR_COLOR = new Color(235, 245, 255, 235);
	private static final Color SCRIPT_CURSOR_COLOR = new Color(255, 230, 0, 235);
	private static final Color CURSOR_OUTLINE = new Color(0, 0, 0, 180);
	private static final Color CLICK_LEFT = new Color(80, 200, 255);
	private static final Color CLICK_RIGHT = new Color(255, 90, 90);
	private static final long CLICK_MERGE_TIME_MS = 100L;
	private static final int CLICK_MERGE_DISTANCE = 8;

	private final Client client;
	private final TestPlugin plugin;
	private final List<ClickFx> clicks = new CopyOnWriteArrayList<>();

	@Inject
	private MouseOverlay(Client client, TestPlugin plugin)
	{
		this.client = client;
		this.plugin = plugin;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_HIGH);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		GameInput input = plugin.getInput();
		ScriptManager scriptManager = plugin.getScriptManager();
		Script active = scriptManager == null ? null : scriptManager.getActiveScript();

		Point actual = getActualMousePoint();
		if (actual != null)
		{
			drawCursor(graphics, actual, ACTUAL_CURSOR_COLOR);
		}

		if (active != null && input != null)
		{
			Point simulated = input.getMouseLocation();
			if (simulated != null)
			{
				drawCursor(graphics, simulated, SCRIPT_CURSOR_COLOR);
			}
		}

		drawClicks(graphics, input);
		return null;
	}

	private Point getActualMousePoint()
	{
		net.runelite.api.Point position = client.getMouseCanvasPosition();
		return position == null ? null : new Point(position.getX(), position.getY());
	}

	private void drawCursor(Graphics2D graphics, Point point, Color color)
	{
		Stroke oldStroke = graphics.getStroke();
		Color ring = new Color(color.getRed(), color.getGreen(), color.getBlue(), 90);

		graphics.setColor(ring);
		graphics.setStroke(new BasicStroke(1.0f));
		graphics.drawOval(point.x - CURSOR_RADIUS - 3, point.y - CURSOR_RADIUS - 3,
			(CURSOR_RADIUS + 3) * 2, (CURSOR_RADIUS + 3) * 2);

		graphics.setColor(CURSOR_OUTLINE);
		graphics.setStroke(new BasicStroke(3.0f));
		graphics.drawLine(point.x - CURSOR_RADIUS, point.y - CURSOR_RADIUS,
			point.x + CURSOR_RADIUS, point.y + CURSOR_RADIUS);
		graphics.drawLine(point.x - CURSOR_RADIUS, point.y + CURSOR_RADIUS,
			point.x + CURSOR_RADIUS, point.y - CURSOR_RADIUS);

		graphics.setColor(color);
		graphics.setStroke(new BasicStroke(1.5f));
		graphics.drawLine(point.x - CURSOR_RADIUS, point.y - CURSOR_RADIUS,
			point.x + CURSOR_RADIUS, point.y + CURSOR_RADIUS);
		graphics.drawLine(point.x - CURSOR_RADIUS, point.y + CURSOR_RADIUS,
			point.x + CURSOR_RADIUS, point.y - CURSOR_RADIUS);
		graphics.fillOval(point.x - 1, point.y - 1, 3, 3);

		graphics.setStroke(oldStroke);
	}

	private void drawClicks(Graphics2D graphics, GameInput input)
	{
		long now = System.currentTimeMillis();
		clicks.removeIf(click -> now - click.startedAt >= CLICK_DURATION_MS);

		List<ClickFx> recent = new ArrayList<>(clicks);

		if (input != null)
		{
			for (GameInput.ClickEffect effect : input.getRecentClicks())
			{
				if (now - effect.getStartedAt() >= CLICK_DURATION_MS)
				{
					continue;
				}

				// Real clicks and simulated clicks can both reach the overlay.
				// Only show one ring when they describe the same click.
				if (!isDuplicate(recent, effect))
				{
					recent.add(new ClickFx(effect.getLocation(), effect.getButton(),
						effect.getStartedAt()));
				}
			}
		}

		for (ClickFx click : recent)
		{
			drawClick(graphics, click);
		}
	}

	private static boolean isDuplicate(List<ClickFx> clicks, GameInput.ClickEffect effect)
	{
		for (ClickFx click : clicks)
		{
			if (click.button != effect.getButton())
			{
				continue;
			}

			if (Math.abs(click.startedAt - effect.getStartedAt()) > CLICK_MERGE_TIME_MS)
			{
				continue;
			}

			if (click.point.distance(effect.getLocation()) <= CLICK_MERGE_DISTANCE)
			{
				return true;
			}
		}

		return false;
	}

	private void drawClick(Graphics2D graphics, ClickFx click)
	{
		long elapsed = System.currentTimeMillis() - click.startedAt;
		if (elapsed < 0 || elapsed >= CLICK_DURATION_MS)
		{
			return;
		}

		Point point = click.point;
		double progress = (double) elapsed / CLICK_DURATION_MS;
		int radius = 3 + (int) Math.round(progress * 16.0);
		int alpha = (int) Math.round(230.0 * (1.0 - progress));
		Color base = click.button == MouseEvent.BUTTON3 ? CLICK_RIGHT : CLICK_LEFT;

		Stroke oldStroke = graphics.getStroke();
		graphics.setStroke(new BasicStroke(2.0f));
		graphics.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha));
		graphics.drawOval(point.x - radius, point.y - radius, radius * 2, radius * 2);

		if (progress < 0.45)
		{
			double burst = (0.45 - progress) / 0.45;
			int size = Math.max(1, (int) Math.round(burst * 5.0));
			int burstAlpha = (int) Math.round(alpha * burst);
			graphics.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), burstAlpha));
			graphics.drawLine(point.x - size, point.y, point.x + size, point.y);
			graphics.drawLine(point.x, point.y - size, point.x, point.y + size);
		}

		graphics.setStroke(oldStroke);
	}

	@Override
	public MouseEvent mousePressed(MouseEvent event)
	{
		if (event.getButton() != MouseEvent.NOBUTTON)
		{
			long now = System.currentTimeMillis();
			clicks.add(new ClickFx(new Point(event.getX(), event.getY()), event.getButton(), now));
			clicks.removeIf(click -> now - click.startedAt >= CLICK_DURATION_MS);
		}

		return event;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent event)
	{
		return event;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent event)
	{
		return event;
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent event)
	{
		return event;
	}

	@Override
	public MouseEvent mouseExited(MouseEvent event)
	{
		return event;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent event)
	{
		return event;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent event)
	{
		return event;
	}

	private static final class ClickFx
	{
		private final Point point;
		private final int button;
		private final long startedAt;

		private ClickFx(Point point, int button, long startedAt)
		{
			this.point = point;
			this.button = button;
			this.startedAt = startedAt;
		}
	}
}
