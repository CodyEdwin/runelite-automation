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
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.rsbuddy.routine.RoutineManager;
import net.runelite.rsbuddy.routines.GemCrabKillerScript;
import net.runelite.rsbuddy.routines.MotherlodeMinerScript;
import org.junit.Assert;
import org.junit.Test;

public class RoutineLibraryTest
{
	@Test
	public void libraryListsEveryBuiltinAndFavoritesSortFirst() throws Exception
	{
		RSBuddyConfig config = new RSBuddyConfig()
		{
		};
		RoutineRegistry registry = RoutineLibrary.create(config);
		Assert.assertEquals(15, registry.getDefinitions().size());

		for (RoutineDefinition definition : registry.getDefinitions())
		{
			definition.setInstance(definition.create(RoutineLibraryTest.class.getClassLoader()));
			Assert.assertNotNull(definition.getInstance().getName());
			Assert.assertFalse(definition.getInstance().getDescription().isEmpty());
		}

		Map<String, String> stored = new HashMap<>();
		stored.put(RoutineListPanel.FAVORITES_KEY, GemCrabKillerScript.class.getName()
			+ "," + MotherlodeMinerScript.class.getName());
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
			RoutineListPanel panel = new RoutineListPanel(new RoutineManager(null), registry, config, preferences,
				routine -> routine, routine -> routine, className -> null);
			try
			{
				List<String> names = names(panel);
				Assert.assertEquals("Gem Crab Killer", names.get(0));
				Assert.assertEquals("Motherlode Miner", names.get(1));
				Assert.assertEquals("Animated Suit Killer", names.get(2));
				Assert.assertEquals(15, names.size());

				IconTextField search = findSearch(panel);
				Assert.assertNotNull(search);
				search.setText("fisher");
				List<String> fishers = names(panel);
				Assert.assertEquals(2, fishers.size());
				Assert.assertTrue(fishers.contains("Barbarian Fisher & Cooker"));
				Assert.assertTrue(fishers.contains("Catherby Shark Fisher"));

				search.setText("");
				expandFirst(panel);

				panel.setSize(242, 780);
				panel.doLayout();
				layoutTree(panel);
				javax.swing.RepaintManager.currentManager(panel).setDoubleBufferingEnabled(false);
				BufferedImage image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
				Graphics2D graphics = image.createGraphics();
				panel.paint(graphics);
				graphics.dispose();

				File artifact = new File("/opt/cursor/artifacts/rsbuddy-routine-panel.png");
				File parent = artifact.getParentFile();
				if (parent != null)
				{
					parent.mkdirs();
				}
				try
				{
					ImageIO.write(image, "png", artifact);
				}
				catch (java.io.IOException ex)
				{
					throw new IllegalStateException(ex);
				}
			}
			finally
			{
				panel.shutdown();
			}
		});
	}

	private static List<String> names(Component component)
	{
		List<String> names = new ArrayList<>();
		collect(component, names);
		return names;
	}

	private static void collect(Component component, List<String> names)
	{
		if (component instanceof RoutineListItem)
		{
			names.add(((RoutineListItem) component).getDefinition().getInstance().getName());
		}

		if (component instanceof Container)
		{
			for (Component child : ((Container) component).getComponents())
			{
				collect(child, names);
			}
		}
	}

	private static IconTextField findSearch(Component component)
	{
		if (component instanceof IconTextField)
		{
			return (IconTextField) component;
		}

		if (component instanceof Container)
		{
			for (Component child : ((Container) component).getComponents())
			{
				IconTextField found = findSearch(child);
				if (found != null)
				{
					return found;
				}
			}
		}

		return null;
	}

	private static void layoutTree(Component component)
	{
		component.doLayout();
		if (component instanceof Container)
		{
			for (Component child : ((Container) component).getComponents())
			{
				layoutTree(child);
			}
		}
	}

	private static void expandFirst(Component component)
	{
		if (component instanceof RoutineListItem)
		{
			((RoutineListItem) component).setExpanded(true);
			return;
		}

		if (component instanceof Container)
		{
			for (Component child : ((Container) component).getComponents())
			{
				if (child instanceof RoutineListItem || containsItem(child))
				{
					expandFirst(child);
					return;
				}
			}
		}
	}

	private static boolean containsItem(Component component)
	{
		if (component instanceof RoutineListItem)
		{
			return true;
		}

		if (component instanceof Container)
		{
			for (Component child : ((Container) component).getComponents())
			{
				if (containsItem(child))
				{
					return true;
				}
			}
		}

		return false;
	}
}
