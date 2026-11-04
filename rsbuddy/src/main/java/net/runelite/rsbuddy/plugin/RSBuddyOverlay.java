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
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.rsbuddy.routine.LineRoutineOverlay;
import net.runelite.rsbuddy.routine.Routine;
import net.runelite.rsbuddy.routine.RoutineManager;
import net.runelite.rsbuddy.routine.RoutineOverlay;

/**
 * Draws the running routine's overlay, and nothing else.
 * <p>
 * Routines opt in with {@link Routine#getOverlay()} (text routines get one
 * from {@link Routine#getOverlayLines()}). No routine, no overlay from that
 * routine, or the side-panel toggle off: this draws nothing.
 * <p>
 * The overlay is free-floating ({@link OverlayPosition#DYNAMIC} and movable)
 * so it can be dragged with Shift, and with RuneLite's overlay drag hotkey.
 */
public class RSBuddyOverlay extends Overlay
{
	private static final Color DRAG_OUTLINE = new Color(255, 170, 60, 200);

	private final RSBuddyConfig config;
	private final RSBuddyPlugin plugin;

	private ShiftDragHandler dragHandler;

	@Inject
	RSBuddyOverlay(RSBuddyConfig config, RSBuddyPlugin plugin)
	{
		this.config = config;
		this.plugin = plugin;

		// DYNAMIC clears movable. Turn it back on or RuneLite drops the saved spot.
		setPosition(OverlayPosition.DYNAMIC);
		setMovable(true);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	/** Lets the Shift-drag handler draw its hover outline on this overlay. */
	void setDragHandler(ShiftDragHandler dragHandler)
	{
		this.dragHandler = dragHandler;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showRoutineOverlay())
		{
			return null;
		}

		RoutineManager routineManager = plugin.getRoutineManager();
		Routine active = routineManager == null ? null : routineManager.getActiveRoutine();
		if (active == null)
		{
			return null;
		}

		RoutineOverlay routineOverlay = active.getOverlay();
		if (routineOverlay == null)
		{
			return null;
		}

		if (routineOverlay instanceof LineRoutineOverlay)
		{
			Color textColor = config.textColor();
			((LineRoutineOverlay) routineOverlay).setTextColor(textColor == null ? Color.WHITE : textColor);
		}

		Dimension dimension = routineOverlay.render(graphics);
		if (dimension == null || dimension.width <= 0 || dimension.height <= 0)
		{
			return null;
		}

		if (dragHandler != null && dragHandler.isHighlighted(this)
			&& dimension.width > 4 && dimension.height > 4)
		{
			graphics.setColor(DRAG_OUTLINE);
			graphics.drawRoundRect(1, 1, dimension.width - 3, dimension.height - 3, 6, 6);
		}

		return dimension;
	}
}
