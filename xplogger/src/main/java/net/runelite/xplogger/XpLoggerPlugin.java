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
package net.runelite.xplogger;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.events.StatChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "XP Logger",
	description = "Session XP overlay with a customization panel",
	tags = {"xp", "skills", "overlay"}
)
public class XpLoggerPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private XpLoggerConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private XpLoggerOverlay overlay;

	private ShiftDragHandler drag;
	private NavigationButton navButton;

	@Provides
	XpLoggerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(XpLoggerConfig.class);
	}

	@Override
	protected void startUp()
	{
		applyLayout(false);
		overlay.setDragHandler(drag = new ShiftDragHandler(overlayManager, List.of(overlay), client::getRealDimensions));
		overlayManager.add(overlay);
		mouseManager.registerMouseListener(0, drag);
		keyManager.registerKeyListener(drag);

		BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = icon.createGraphics();
		g.setColor(new Color(255, 213, 79));
		g.fillRoundRect(1, 5, 14, 6, 3, 3);
		g.dispose();
		navButton = NavigationButton.builder()
			.tooltip("XP Logger")
			.icon(icon)
			.priority(2)
			.panel(new XpLoggerPanel(config, configManager))
			.build();
		clientToolbar.addNavigation(navButton);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		if (drag != null)
		{
			mouseManager.unregisterMouseListener(drag);
			keyManager.unregisterKeyListener(drag);
			drag = null;
		}
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		overlay.onStatChanged(event.getSkill(), event.getXp());
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!XpLoggerConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		if ("position".equals(event.getKey()) || "movable".equals(event.getKey()))
		{
			applyLayout("position".equals(event.getKey()));
		}
	}

	private void applyLayout(boolean resetSpot)
	{
		overlay.applyLayout(config.position(), config.movable(), resetSpot);
	}
}
