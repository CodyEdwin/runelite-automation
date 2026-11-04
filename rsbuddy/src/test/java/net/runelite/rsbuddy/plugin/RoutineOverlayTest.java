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

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JCheckBox;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.rsbuddy.routine.LineRoutineOverlay;
import net.runelite.rsbuddy.routine.Routine;
import net.runelite.rsbuddy.routine.RoutineManager;
import net.runelite.rsbuddy.routine.RoutineOverlay;
import org.junit.Assert;
import org.junit.Test;

public class RoutineOverlayTest
{
	@Test
	public void overlayShowsOnlyContentFromTheRunningRoutine() throws Exception
	{
		RoutineManager manager = new RoutineManager(null);
		boolean[] show = {true};
		RSBuddyConfig config = new RSBuddyConfig()
		{
			@Override
			public boolean showRoutineOverlay()
			{
				return show[0];
			}
		};
		RSBuddyOverlay overlay = new RSBuddyOverlay(config, new ActiveRoutinePlugin(manager));
		Assert.assertEquals(OverlayPosition.DYNAMIC, overlay.getPosition());
		Assert.assertTrue(overlay.isMovable());
		Assert.assertTrue(LineRoutineOverlay.BACKGROUND.getAlpha() < 160);

		BufferedImage image = new BufferedImage(320, 240, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			Assert.assertNull(overlay.render(graphics));

			Routine silent = routine("Hello from the test plugin!", Collections.emptyList(), null);
			setActive(manager, silent);
			Assert.assertNull(silent.getOverlay());
			Assert.assertNull(overlay.render(graphics));

			Routine blank = routine("Hi from the test plugin", Arrays.asList("", "   "), null);
			setActive(manager, blank);
			Assert.assertNull(blank.getOverlay());
			Assert.assertNull(overlay.render(graphics));

			Routine lines = routine("Fisher", Arrays.asList("", "Trout: 4", " "), null);
			setActive(manager, lines);
			Assert.assertTrue(lines.getOverlay() instanceof LineRoutineOverlay);
			Dimension size = overlay.render(graphics);
			Assert.assertNotNull(size);
			Assert.assertTrue(size.width > 0);
			Assert.assertTrue(size.height > 0);
			int argb = image.getRGB(size.width / 2, 3);
			int alpha = (argb >>> 24) & 0xff;
			Assert.assertEquals(LineRoutineOverlay.BACKGROUND.getAlpha(), alpha);

			show[0] = false;
			Assert.assertNull(overlay.render(graphics));

			show[0] = true;
			boolean[] drawn = {false};
			Routine custom = routine("Custom", Collections.singletonList("unused line"), g ->
			{
				drawn[0] = true;
				return new Dimension(12, 8);
			});
			setActive(manager, custom);
			Dimension customSize = overlay.render(graphics);
			Assert.assertTrue(drawn[0]);
			Assert.assertNotNull(customSize);
			Assert.assertEquals(new Dimension(12, 8), customSize);

			Routine hidden = routine("Hidden", Collections.singletonList("nope"), g -> null);
			setActive(manager, hidden);
			Assert.assertNull(overlay.render(graphics));
		}
		finally
		{
			graphics.dispose();
		}
	}

	@Test
	public void panelTogglePersistsOverlayVisibility() throws Exception
	{
		Map<String, String> stored = new HashMap<>();
		RoutinePreferences preferences = new RoutinePreferences()
		{
			@Override
			public String getString(String key)
			{
				return stored.get(key);
			}

			@Override
			public void set(String key, String value)
			{
				stored.put(key, value);
			}

			@Override
			public void set(String key, boolean value)
			{
				stored.put(key, Boolean.toString(value));
			}

			@Override
			public void set(String key, int value)
			{
				stored.put(key, Integer.toString(value));
			}
		};
		RSBuddyConfig config = new RSBuddyConfig()
		{
			@Override
			public boolean showRoutineOverlay()
			{
				String value = stored.get(RSBuddyConfig.SHOW_ROUTINE_OVERLAY);
				return value == null || Boolean.parseBoolean(value);
			}
		};

		try
		{
			Class.forName("com.formdev.flatlaf.FlatDarkLaf").getMethod("setup").invoke(null);
		}
		catch (ReflectiveOperationException ex)
		{
			throw new IllegalStateException("FlatLaf is required to render the routine panel", ex);
		}

		SwingUtilities.invokeAndWait(() ->
		{
			RoutineListPanel panel = new RoutineListPanel(new RoutineManager(null), new RoutineRegistry(),
				config, preferences, routine -> routine, routine -> routine, className -> null);
			try
			{
				JCheckBox toggle = findToggle(panel);
				Assert.assertNotNull(toggle);
				Assert.assertTrue(toggle.isSelected());

				toggle.doClick();
				Assert.assertEquals("false", stored.get(RSBuddyConfig.SHOW_ROUTINE_OVERLAY));
				Assert.assertFalse(toggle.isSelected());

				stored.put(RSBuddyConfig.SHOW_ROUTINE_OVERLAY, "true");
				panel.configChanged(RSBuddyConfig.SHOW_ROUTINE_OVERLAY);
				Assert.assertTrue(toggle.isSelected());
			}
			finally
			{
				panel.shutdown();
			}
		});
	}

	private static Routine routine(String name, List<String> lines, RoutineOverlay custom)
	{
		return new Routine(name, "test")
		{
			@Override
			protected void onGameTick()
			{
			}

			@Override
			public List<String> getOverlayLines()
			{
				return lines;
			}

			@Override
			public RoutineOverlay getOverlay()
			{
				return custom != null ? custom : super.getOverlay();
			}
		};
	}

	private static void setActive(RoutineManager manager, Routine routine) throws Exception
	{
		Field field = RoutineManager.class.getDeclaredField("activeScript");
		field.setAccessible(true);
		field.set(manager, routine);
	}

	private static JCheckBox findToggle(Component component)
	{
		if (component instanceof JCheckBox
			&& "Show routine overlay".equals(((JCheckBox) component).getText()))
		{
			return (JCheckBox) component;
		}

		if (component instanceof Container)
		{
			for (Component child : ((Container) component).getComponents())
			{
				JCheckBox found = findToggle(child);
				if (found != null)
				{
					return found;
				}
			}
		}

		return null;
	}

	private static final class ActiveRoutinePlugin extends RSBuddyPlugin
	{
		private final RoutineManager routines;

		private ActiveRoutinePlugin(RoutineManager routines)
		{
			this.routines = routines;
		}

		@Override
		RoutineManager getRoutineManager()
		{
			return routines;
		}
	}
}
