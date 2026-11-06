/*
 * Copyright (c) 2018, Kruithne <kruithne@gmail.com>
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

import com.google.inject.Provides;
import java.awt.Canvas;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.OSType;

@PluginDescriptor(
	name = "Custom Cursor",
	description = "Replaces your mouse cursor image",
	enabledByDefault = false
)
@Slf4j
public class CustomCursorPlugin extends Plugin
{
	private static final File CUSTOM_IMAGE_FILE = new File(RuneLite.RUNELITE_DIR, "cursor.png");
	private static final int MAX_CURSOR_SIZE = 32;

	private static Boolean limitedCursorColors;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientUI clientUI;

	@Inject
	private CustomCursorConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private CustomCursorOverlay cursorOverlay;

	private Cursor transparentCursor;
	private boolean loggedIn;

	private final MouseAdapter mouseListener = new MouseAdapter()
	{
		@Override
		public MouseEvent mouseMoved(MouseEvent mouseEvent)
		{
			cursorOverlay.setMousePosition(mouseEvent.getPoint());
			return mouseEvent;
		}

		@Override
		public MouseEvent mouseDragged(MouseEvent mouseEvent)
		{
			cursorOverlay.setMousePosition(mouseEvent.getPoint());
			return mouseEvent;
		}

		@Override
		public MouseEvent mouseEntered(MouseEvent mouseEvent)
		{
			cursorOverlay.setMousePosition(mouseEvent.getPoint());
			return mouseEvent;
		}

		@Override
		public MouseEvent mouseExited(MouseEvent mouseEvent)
		{
			cursorOverlay.setMouseInside(false);
			return mouseEvent;
		}
	};

	@Provides
	CustomCursorConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CustomCursorConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(cursorOverlay);
		mouseManager.registerMouseListener(mouseListener);
		loggedIn = client.getGameState() == GameState.LOGGED_IN;
		updateCursor();
	}

	@Override
	protected void shutDown()
	{
		mouseManager.unregisterMouseListener(mouseListener);
		overlayManager.remove(cursorOverlay);
		resetCursor();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (event.getGroup().equals("customcursor")
			&& (event.getKey().equals("cursorStyle") || event.getKey().equals("overlayCursor")))
		{
			updateCursor();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		loggedIn = event.getGameState() == GameState.LOGGED_IN;
		updateCursor();
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (config.selectedCursor() == CustomCursor.EQUIPPED_WEAPON && event.getContainerId() == InventoryID.WORN)
		{
			updateCursor();
		}
	}

	private void updateCursor()
	{
		CustomCursor selectedCursor = config.selectedCursor();

		if (selectedCursor == CustomCursor.CUSTOM_IMAGE)
		{
			if (CUSTOM_IMAGE_FILE.exists())
			{
				try
				{
					BufferedImage image;
					synchronized (ImageIO.class)
					{
						image = ImageIO.read(CUSTOM_IMAGE_FILE);
					}
					applyCursor(image, selectedCursor.getName());
				}
				catch (Exception e)
				{
					log.error("error setting custom cursor", e);
					resetCursor();
				}
			}
			else
			{
				resetCursor();
			}
		}
		else if (selectedCursor == CustomCursor.EQUIPPED_WEAPON)
		{
			clientThread.invokeLater(() ->
			{
				final ItemContainer equipment = client.getItemContainer(InventoryID.WORN);

				if (equipment == null)
				{
					resetCursor();
					return;
				}

				Item weapon = equipment.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
				if (weapon == null || weapon.getQuantity() <= 0)
				{
					resetCursor();
					return;
				}

				final BufferedImage image = itemManager.getImage(weapon.getId());
				applyCursor(image, selectedCursor.getName());
			});
		}
		else
		{
			assert selectedCursor.getCursorImage() != null;
			applyCursor(selectedCursor.getCursorImage(), selectedCursor.getName());
		}
	}

	private void applyCursor(@Nullable BufferedImage image, String name)
	{
		if (image == null)
		{
			resetCursor();
			return;
		}

		final Canvas canvas = client.getCanvas();

		if (useOverlayCursor())
		{
			cursorOverlay.setCursorImage(image);
			clientUI.resetCursor();

			if (canvas != null)
			{
				canvas.setCursor(getTransparentCursor());
			}
		}
		else
		{
			cursorOverlay.setCursorImage(null);

			if (canvas != null)
			{
				canvas.setCursor(null);
			}

			clientUI.setCursor(image, name);
		}
	}

	private void resetCursor()
	{
		cursorOverlay.setCursorImage(null);
		cursorOverlay.setMouseInside(false);

		final Canvas canvas = client.getCanvas();
		if (canvas != null)
		{
			canvas.setCursor(null);
		}

		clientUI.resetCursor();
	}

	private boolean useOverlayCursor()
	{
		return config.overlayCursor()
			&& loggedIn
			&& isLimitedCursorColors();
	}

	private static boolean isLimitedCursorColors()
	{
		if (limitedCursorColors == null)
		{
			try
			{
				limitedCursorColors = OSType.getOSType() == OSType.Linux
					&& Toolkit.getDefaultToolkit().getMaximumCursorColors() <= 2;
			}
			catch (Exception ex)
			{
				limitedCursorColors = OSType.getOSType() == OSType.Linux;
			}
		}

		return limitedCursorColors;
	}

	private Cursor getTransparentCursor()
	{
		if (transparentCursor == null)
		{
			final Toolkit toolkit = Toolkit.getDefaultToolkit();
			final Dimension bestSize = toolkit.getBestCursorSize(MAX_CURSOR_SIZE, MAX_CURSOR_SIZE);
			final int width = bestSize.width > 0 ? bestSize.width : MAX_CURSOR_SIZE;
			final int height = bestSize.height > 0 ? bestSize.height : MAX_CURSOR_SIZE;
			final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
			transparentCursor = toolkit.createCustomCursor(image, new Point(0, 0), "invisible");
		}

		return transparentCursor;
	}
}
