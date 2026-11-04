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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.SwingUtil;
import net.runelite.rsbuddy.routine.Routine;

/**
 * One routine row: favorite star, name, description, start/stop, and configure.
 * Laid out like a RuneLite plugin list row, with the description visible.
 */
final class RoutineListItem extends JPanel
{
	private static final ImageIcon STAR_ON;
	private static final ImageIcon STAR_OFF;
	private static final ImageIcon CONFIG_ICON;
	private static final ImageIcon SWITCH_ON;
	private static final ImageIcon SWITCH_OFF;

	private final RoutineListPanel panel;
	private final RoutineDefinition definition;
	private final RSBuddyConfig config;
	private final RoutinePreferences preferences;
	private final JToggleButton starButton;
	private final JToggleButton toggle;
	private final JLabel nameLabel;
	private final JLabel descriptionLabel;
	private final JLabel statusLabel;
	private final JPanel settingsPanel;
	private final Map<String, Runnable> syncers = new HashMap<>();

	private boolean updating;
	private boolean expanded;

	static
	{
		STAR_ON = icon("star_on.png");
		STAR_OFF = starOff(STAR_ON);
		CONFIG_ICON = icon("config_edit_icon.png");
		SWITCH_ON = icon("switcher_on.png");
		SWITCH_OFF = switchOff(SWITCH_ON);
	}

	RoutineListItem(RoutineListPanel panel, RoutineDefinition definition, RSBuddyConfig config,
		RoutinePreferences preferences)
	{
		this.panel = panel;
		this.definition = definition;
		this.config = config;
		this.preferences = preferences;

		setLayout(new BorderLayout(0, 4));
		setBackground(ColorScheme.DARKER_GRAY_COLOR);
		setBorder(new EmptyBorder(6, 6, 6, 4));
		setOpaque(true);

		starButton = new JToggleButton(STAR_OFF);
		starButton.setSelectedIcon(STAR_ON);
		SwingUtil.removeButtonDecorations(starButton);
		SwingUtil.addModalTooltip(starButton, "Unfavorite routine", "Favorite routine");
		starButton.setPreferredSize(new Dimension(22, 22));
		if (STAR_ON == null)
		{
			starButton.setText("★");
		}
		starButton.addActionListener(event ->
		{
			if (!updating)
			{
				panel.onFavorite(this);
			}
		});

		nameLabel = new JLabel();
		nameLabel.setForeground(Color.WHITE);
		nameLabel.setFont(FontManager.getRunescapeBoldFont());

		descriptionLabel = new JLabel();
		descriptionLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		descriptionLabel.setFont(FontManager.getRunescapeSmallFont());
		descriptionLabel.setVerticalAlignment(JLabel.TOP);

		statusLabel = new JLabel(" ");
		statusLabel.setForeground(ColorScheme.BRAND_ORANGE);
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setVisible(false);

		JPanel text = new JPanel(new BorderLayout(0, 2));
		text.setOpaque(false);
		text.add(nameLabel, BorderLayout.NORTH);
		text.add(descriptionLabel, BorderLayout.CENTER);
		text.add(statusLabel, BorderLayout.SOUTH);

		JButton configButton = new JButton(CONFIG_ICON);
		SwingUtil.removeButtonDecorations(configButton);
		configButton.setPreferredSize(new Dimension(25, 22));
		configButton.setToolTipText("Configure routine");
		if (CONFIG_ICON == null)
		{
			configButton.setText("Cfg");
		}
		configButton.addActionListener(event -> panel.toggleExpanded(this));

		toggle = new JToggleButton(SWITCH_OFF);
		toggle.setSelectedIcon(SWITCH_ON);
		SwingUtil.removeButtonDecorations(toggle);
		toggle.setPreferredSize(new Dimension(25, 22));
		toggle.setToolTipText("Start routine");
		if (SWITCH_ON == null)
		{
			toggle.setText("▶");
		}
		toggle.addActionListener(event ->
		{
			if (!updating)
			{
				panel.onToggle(this, toggle.isSelected());
			}
		});

		JPanel buttons = new JPanel(new GridLayout(1, 2, 0, 0));
		buttons.setOpaque(false);
		buttons.add(configButton);
		buttons.add(toggle);

		JPanel header = new JPanel(new BorderLayout(4, 0));
		header.setOpaque(false);
		header.add(starButton, BorderLayout.LINE_START);
		header.add(text, BorderLayout.CENTER);
		header.add(buttons, BorderLayout.LINE_END);
		add(header, BorderLayout.NORTH);

		settingsPanel = new JPanel(new DynamicGridLayout(0, 1, 0, 4));
		settingsPanel.setOpaque(false);
		settingsPanel.setVisible(false);
		settingsPanel.setBorder(new EmptyBorder(2, 22, 2, 0));
		add(settingsPanel, BorderLayout.CENTER);

		MouseAdapter hover = new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent event)
			{
				setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent event)
			{
				setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		};
		addMouseListener(hover);
		header.addMouseListener(hover);
		text.addMouseListener(hover);

		refreshText();
	}

	RoutineDefinition getDefinition()
	{
		return definition;
	}

	boolean isFavorite()
	{
		return starButton.isSelected();
	}

	void setFavorite(boolean favorite)
	{
		updating = true;
		starButton.setSelected(favorite);
		updating = false;
	}

	boolean isExpanded()
	{
		return expanded;
	}

	void setExpanded(boolean expanded)
	{
		if (this.expanded == expanded)
		{
			return;
		}

		this.expanded = expanded;
		settingsPanel.removeAll();
		syncers.clear();
		settingsPanel.setVisible(expanded);

		if (!expanded)
		{
			revalidate();
			return;
		}

		RoutineSettingsForm form = new RoutineSettingsForm(settingsPanel, config, preferences, syncers);
		form.setHint("<html>Settings are applied the next time the routine starts.</html>");
		if (definition.getSettings() != null)
		{
			definition.getSettings().build(form);
		}
		else if (definition.getInstance() != null && !definition.getInstance().getRoutineSettings().isEmpty())
		{
			// Settings the routine declares itself appear automatically.
			for (net.runelite.rsbuddy.routine.RoutineSetting setting : definition.getInstance().getRoutineSettings())
			{
				form.addDeclared(setting);
			}
		}
		else
		{
			form.setHint("<html>This routine has no settings.</html>");
		}

		JButton reload = new JButton("Reload");
		reload.setFocusPainted(false);
		reload.setToolTipText("Compile routine sources in the client and reload this routine");
		reload.addActionListener(event -> panel.reload(this));
		settingsPanel.add(reload);

		JButton export = new JButton("Export as jar");
		export.setFocusPainted(false);
		export.setToolTipText("Compile this routine's source in the client and write it as a jar to the routines folder");
		export.addActionListener(event -> panel.exportJar(this));
		export.setEnabled(!definition.isJar());
		settingsPanel.add(export);
		if (form.getHint().getText() != null && !form.getHint().getText().isEmpty())
		{
			settingsPanel.add(form.getHint());
		}

		revalidate();
	}

	void sync(String key)
	{
		Runnable syncer = syncers.get(key);
		if (syncer != null)
		{
			syncer.run();
		}
	}

	void refreshState(Routine active)
	{
		Routine instance = definition.getInstance();
		boolean running = instance != null && instance == active;
		updating = true;
		toggle.setSelected(running);
		updating = false;
		toggle.setToolTipText(running ? "Stop routine" : "Start routine");
		if (SWITCH_ON == null)
		{
			toggle.setText(running ? "■" : "▶");
		}

		if (running)
		{
			statusLabel.setText(fitStatus(instance.getStatus()));
			statusLabel.setVisible(true);
			setBorder(new CompoundBorder(
				new MatteBorder(0, 2, 0, 0, ColorScheme.BRAND_ORANGE),
				new EmptyBorder(6, 4, 6, 4)));
		}
		else
		{
			statusLabel.setVisible(false);
			setBorder(new EmptyBorder(6, 6, 6, 4));
		}

		refreshText();
	}

	private void refreshText()
	{
		Routine instance = definition.getInstance();
		String name = definition.getDisplayName();
		String description = instance == null ? "" : instance.getDescription();
		nameLabel.setText(html(name));
		nameLabel.setToolTipText(description == null || description.isEmpty() ? name : description);
		descriptionLabel.setText(html(description == null ? "" : description));
	}

	private static String fitStatus(String status)
	{
		if (status == null || status.isEmpty())
		{
			return "Running";
		}

		String single = status.replace('\n', ' ');
		if (single.length() > 42)
		{
			return single.substring(0, 41) + "…";
		}

		return single;
	}

	private static String html(String text)
	{
		String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		return "<html><body style='width:100px'>" + escaped + "</body></html>";
	}

	private static ImageIcon icon(String name)
	{
		try
		{
			BufferedImage image = ImageUtil.loadImageResource(ImageUtil.class,
				"/net/runelite/client/plugins/config/" + name);
			if (image == null)
			{
				return null;
			}

			return new ImageIcon(image);
		}
		catch (Exception ex)
		{
			return null;
		}
	}

	private static ImageIcon starOff(ImageIcon on)
	{
		if (on == null)
		{
			return null;
		}

		BufferedImage image = ImageUtil.luminanceScale(
			ImageUtil.grayscaleImage((BufferedImage) on.getImage()),
			0.77f);
		return new ImageIcon(image);
	}

	private static ImageIcon switchOff(ImageIcon on)
	{
		if (on == null)
		{
			return null;
		}

		BufferedImage image = ImageUtil.flipImage(
			ImageUtil.luminanceScale(
				ImageUtil.grayscaleImage((BufferedImage) on.getImage()),
				0.61f),
			true,
			false);
		return new ImageIcon(image);
	}
}
