/*
 * Copyright (c) 2025, RuneLite
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
package net.runelite.client.plugins.customcursor;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import javax.annotation.Nullable;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ImageUtil;

/**
 * Draws the selected cursor image at the mouse position. This is used on Linux
 * where the native X11 cursor only supports a couple of colors.
 */
class CustomCursorOverlay extends Overlay
{
	private static final int MAX_CURSOR_SIZE = 32;

	@Nullable
	private volatile BufferedImage cursorImage;
	@Nullable
	private volatile Point mousePosition;
	private volatile boolean mouseInside;

	CustomCursorOverlay()
	{
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setMovable(false);
		setSnappable(false);
		setResettable(false);
	}

	void setCursorImage(@Nullable BufferedImage cursorImage)
	{
		if (cursorImage == null)
		{
			this.cursorImage = null;
			return;
		}

		final int width = cursorImage.getWidth();
		final int height = cursorImage.getHeight();

		this.cursorImage = width <= MAX_CURSOR_SIZE && height <= MAX_CURSOR_SIZE
			? cursorImage
			: ImageUtil.resizeImage(cursorImage, MAX_CURSOR_SIZE, MAX_CURSOR_SIZE, true);
	}

	void setMousePosition(Point mousePosition)
	{
		this.mousePosition = mousePosition;
		this.mouseInside = true;
	}

	void setMouseInside(boolean mouseInside)
	{
		this.mouseInside = mouseInside;
	}

	@Nullable
	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!mouseInside || cursorImage == null || mousePosition == null)
		{
			return null;
		}

		graphics.drawImage(cursorImage, mousePosition.x, mousePosition.y, null);
		return null;
	}
}
