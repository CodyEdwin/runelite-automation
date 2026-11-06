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

import com.google.inject.Provides;
import java.awt.event.MouseEvent;
import javax.inject.Inject;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Mouse Effects",
	description = "Animated cursor trails and click bursts for the game canvas.",
	tags = {"mouse", "cursor", "trail", "click", "effect", "cosmetic"}
)
public class MouseEffectsPlugin extends Plugin
{
	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private MouseEffectsOverlay overlay;

	@Inject
	private MouseEffectsConfig config;

	private final MouseAdapter mouseListener = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent mouseEvent)
		{
			if (config.clickEnabled()
				&& (mouseEvent.getButton() == MouseEvent.BUTTON1
				|| mouseEvent.getButton() == MouseEvent.BUTTON3))
			{
				overlay.click(mouseEvent.getX(), mouseEvent.getY());
			}
			return mouseEvent;
		}

		@Override
		public MouseEvent mouseExited(MouseEvent mouseEvent)
		{
			overlay.clearTrail();
			return mouseEvent;
		}
	};

	@Provides
	MouseEffectsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(MouseEffectsConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlay.reset();
		overlayManager.add(overlay);
		mouseManager.registerMouseListener(mouseListener);
	}

	@Override
	protected void shutDown()
	{
		mouseManager.unregisterMouseListener(mouseListener);
		overlayManager.remove(overlay);
		overlay.reset();
	}
}
