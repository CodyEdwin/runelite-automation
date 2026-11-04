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

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.MouseListener;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Shift + drag to move the XP overlays.
 * <p>
 * Only listens to the user's real mouse and keyboard through RuneLite's
 * MouseManager/KeyManager; it never injects input. A press is taken (consumed)
 * only while Shift is held and the pointer is over one of the overlays, so
 * Shift-clicks in the game (shift-drop) work everywhere else. On release the
 * new spot is saved with {@link OverlayManager#saveOverlay}, so it survives
 * restarts. RuneLite's Alt-drag still works too: turning it off needs
 * {@code setMovable(false)}, which makes RuneLite discard the saved location.
 */
final class ShiftDragHandler implements MouseListener, KeyListener
{
	private final OverlayManager overlayManager;
	private final List<Overlay> overlays;
	private final Supplier<Dimension> canvasSize;

	private volatile boolean shiftDown;
	private volatile Overlay hovered;
	private Overlay dragging;
	private final Point grab = new Point();
	/** Bounds and preferred location when the drag started (bounds lag a frame behind during a drag). */
	private final Rectangle startBounds = new Rectangle();
	private Point startPreferred;

	ShiftDragHandler(OverlayManager overlayManager, List<Overlay> overlays, Supplier<Dimension> canvasSize)
	{
		this.overlayManager = overlayManager;
		this.overlays = overlays;
		this.canvasSize = canvasSize;
	}

	/** @return true while Shift is held over {@code overlay} or it is being dragged (draw the outline) */
	boolean isHighlighted(Overlay overlay)
	{
		return dragging == overlay || (shiftDown && hovered == overlay);
	}

	private Overlay overlayAt(Point point)
	{
		for (Overlay overlay : overlays)
		{
			Rectangle bounds = overlay.getBounds();
			if (bounds != null && !bounds.isEmpty() && bounds.contains(point))
			{
				return overlay;
			}
		}
		return null;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent event)
	{
		shiftDown = event.isShiftDown();
		Overlay target = shiftDown && SwingUtilities.isLeftMouseButton(event) ? overlayAt(event.getPoint()) : null;
		if (target == null)
		{
			return event;
		}
		dragging = target;
		Rectangle bounds = target.getBounds();
		grab.setLocation(event.getX() - bounds.x, event.getY() - bounds.y);
		startBounds.setBounds(bounds);
		Point preferred = target.getPreferredLocation();
		startPreferred = preferred == null ? null : new Point(preferred);
		event.consume();
		return event;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent event)
	{
		if (dragging == null)
		{
			return event;
		}
		Rectangle bounds = startBounds;
		Dimension canvas = canvasSize.get();
		int x = Math.max(0, Math.min(canvas.width - bounds.width, event.getX() - grab.x));
		int y = Math.max(0, Math.min(canvas.height - bounds.height, event.getY() - grab.y));
		// The overlay's origin settings are internal to RuneLite, so move the
		// preferred location by the same delta as the on-screen move: that is
		// correct for whatever origin (corner) the overlay is anchored to.
		Point next = startPreferred == null
			? new Point(x, y)
			: new Point(startPreferred.x + x - bounds.x, startPreferred.y + y - bounds.y);
		dragging.setPreferredPosition(null);
		dragging.setPreferredLocation(next);
		event.consume();
		return event;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent event)
	{
		if (dragging == null)
		{
			return event;
		}
		overlayManager.saveOverlay(dragging);
		dragging = null;
		event.consume();
		return event;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent event)
	{
		if (event.isShiftDown() && overlayAt(event.getPoint()) != null)
		{
			event.consume();
		}
		return event;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent event)
	{
		shiftDown = event.isShiftDown();
		hovered = overlayAt(event.getPoint());
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
		hovered = null;
		return event;
	}

	@Override
	public void keyTyped(KeyEvent event)
	{
	}

	@Override
	public void keyPressed(KeyEvent event)
	{
		if (event.getKeyCode() == KeyEvent.VK_SHIFT)
		{
			shiftDown = true;
		}
	}

	@Override
	public void keyReleased(KeyEvent event)
	{
		if (event.getKeyCode() == KeyEvent.VK_SHIFT)
		{
			shiftDown = false;
		}
	}
}
