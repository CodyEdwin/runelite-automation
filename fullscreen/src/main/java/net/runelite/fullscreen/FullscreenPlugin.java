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
package net.runelite.fullscreen;

import com.google.inject.Provides;
import java.awt.BasicStroke;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

@PluginDescriptor(
	name = "Fullscreen",
	description = "Toggles the RuneLite window into fullscreen",
	tags = {"fullscreen", "display", "window"}
)
public class FullscreenPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private FullscreenConfig config;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	private NavigationButton navButton;
	private GraphicsDevice device;
	private Rectangle previousBounds;
	private int previousState;

	@Provides
	FullscreenConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FullscreenConfig.class);
	}

	@Override
	protected void startUp()
	{
		navButton = NavigationButton.builder()
			.tooltip("Toggle Fullscreen")
			.icon(createIcon())
			.priority(-1)
			.onClick(this::toggle)
			.build();
		clientToolbar.addNavigation(navButton);
		apply();
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		SwingUtilities.invokeLater(() ->
		{
			Frame frame = frame();
			if (frame != null)
			{
				exit(frame);
			}
		});
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (FullscreenConfig.GROUP.equals(event.getGroup()))
		{
			apply();
		}
	}

	private void toggle()
	{
		configManager.setConfiguration(FullscreenConfig.GROUP, "fullscreen", !config.fullscreen());
	}

	private static BufferedImage createIcon()
	{
		BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = icon.createGraphics();
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setColor(Color.WHITE);
		graphics.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		graphics.drawLine(2, 6, 2, 2);
		graphics.drawLine(2, 2, 6, 2);
		graphics.drawLine(10, 2, 14, 2);
		graphics.drawLine(14, 2, 14, 6);
		graphics.drawLine(14, 10, 14, 14);
		graphics.drawLine(14, 14, 10, 14);
		graphics.drawLine(6, 14, 2, 14);
		graphics.drawLine(2, 14, 2, 10);
		graphics.dispose();
		return icon;
	}

	private void apply()
	{
		SwingUtilities.invokeLater(() ->
		{
			Frame frame = frame();
			if (frame == null)
			{
				return;
			}
			if (config.fullscreen())
			{
				enter(frame);
			}
			else
			{
				exit(frame);
			}
		});
	}

	private Frame frame()
	{
		Canvas canvas = client.getCanvas();
		if (canvas == null)
		{
			return null;
		}
		Window window = SwingUtilities.getWindowAncestor(canvas);
		return window instanceof Frame ? (Frame) window : null;
	}

	private void enter(Frame frame)
	{
		GraphicsDevice next = frame.getGraphicsConfiguration().getDevice();
		if (device != null && device.getFullScreenWindow() == frame)
		{
			return;
		}
		previousBounds = frame.getBounds();
		previousState = frame.getExtendedState();
		device = next;
		next.setFullScreenWindow(frame);
	}

	private void exit(Frame frame)
	{
		if (device != null && device.getFullScreenWindow() == frame)
		{
			device.setFullScreenWindow(null);
		}
		if (previousBounds != null)
		{
			frame.setBounds(previousBounds);
			frame.setExtendedState(previousState);
		}
		device = null;
		previousBounds = null;
	}
}
