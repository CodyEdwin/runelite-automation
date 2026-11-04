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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Sidebar controls for the overlay. Values are stored on {@link XpLoggerConfig}.
 */
public class XpLoggerPanel extends PluginPanel
{
	private static final OverlayPosition[] POSITIONS = {
		OverlayPosition.TOP_LEFT, OverlayPosition.TOP_CENTER, OverlayPosition.TOP_RIGHT,
		OverlayPosition.BOTTOM_LEFT, OverlayPosition.BOTTOM_RIGHT,
		OverlayPosition.ABOVE_CHATBOX_RIGHT, OverlayPosition.CANVAS_TOP_RIGHT
	};

	private final XpLoggerConfig config;
	private final ConfigManager configManager;
	private final List<JCheckBox> skillBoxes = new ArrayList<>();

	XpLoggerPanel(XpLoggerConfig config, ConfigManager configManager)
	{
		this.config = config;
		this.configManager = configManager;

		heading("Overlay");
		bool("show", "Show overlay", config.show());
		bool("movable", "Movable (Shift-drag or Alt-drag)", config.movable());
		add(label("Hold Shift and drag the overlay. The spot is saved."));
		position();
		text("title", "Title", config.title());
		bool("showTitle", "Show title", config.showTitle());
		bool("showSession", "Session time", config.showSession());

		heading("Colors");
		color("background", "Background", config.background());
		color("border", "Border", config.border());
		color("text", "Text", config.text());
		color("muted", "Muted text", config.muted());
		color("gain", "XP gained", config.gain());
		color("rate", "XP / hour", config.rate());
		color("titleColor", "Title", config.titleColor());

		heading("Layout");
		number("fontSize", "Font size", config.fontSize(), 10, 28);
		number("padding", "Padding", config.padding(), 0, 32);
		number("rowGap", "Row spacing", config.rowGap(), 0, 40);
		number("iconSize", "Icon size (0 hides)", config.iconSize(), 0, 32);
		number("width", "Width", config.width(), 160, 480);
		number("maxRows", "Max rows", config.maxRows(), 1, 23);

		heading("Bars");
		number("barThickness", "Thickness %", config.barThickness(), 100, 400);
		bool("animate", "Animations", config.animate());
		number("spark", "Spark intensity %", config.spark(), 0, 200);
		bool("climb", "XP drops climb to the top", config.climb());
		bool("showDrops", "XP drop labels", config.showDrops());
		bool("showPercent", "Percent on the bar", config.showPercent());
		bool("skillColors", "Per-skill bar colors", config.skillColors());
		color("barTrack", "Bar background", config.barTrack());
		color("barFill", "Bar fill", config.barFill());
		color("barFlash", "Flash / glow", config.barFlash());

		heading("Rows");
		bool("showXpHr", "XP per hour", config.showXpHr());
		bool("showGained", "XP gained", config.showGained());
		bool("showLeft", "XP left and time to level", config.showLeft());
		bool("showActions", "Actions to level", config.showActions());
		bool("showAvg", "Average XP per action", config.showAvg());
		bool("showLevels", "Levels gained", config.showLevels());
		bool("onlyGained", "Only skills gained this session", config.onlyGained());
		add(label("Uncheck a skill to hide it. All checked shows every skill."));
		skills();
	}

	private void heading(String text)
	{
		JLabel label = label(text);
		label.setForeground(ColorScheme.BRAND_ORANGE);
		add(label);
	}

	private JLabel label(String text)
	{
		JLabel label = new JLabel("<html>" + text + "</html>");
		label.setForeground(Color.WHITE);
		return label;
	}

	private void bool(String key, String text, boolean value)
	{
		JCheckBox box = new JCheckBox(text, value);
		box.setOpaque(false);
		box.setForeground(Color.WHITE);
		box.addActionListener(e -> configManager.setConfiguration(XpLoggerConfig.GROUP, key, box.isSelected()));
		add(box);
	}

	private void number(String key, String text, int value, int min, int max)
	{
		JPanel row = row();
		row.add(label(text), BorderLayout.CENTER);
		JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, min, max, 1));
		spinner.addChangeListener(e -> configManager.setConfiguration(XpLoggerConfig.GROUP, key, (Integer) spinner.getValue()));
		row.add(spinner, BorderLayout.EAST);
		add(row);
	}

	private void text(String key, String text, String value)
	{
		add(label(text));
		JTextField field = new JTextField(value);
		field.addActionListener(e -> configManager.setConfiguration(XpLoggerConfig.GROUP, key, field.getText()));
		field.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusLost(FocusEvent e)
			{
				configManager.setConfiguration(XpLoggerConfig.GROUP, key, field.getText());
			}
		});
		add(field);
	}

	private void color(String key, String text, Color value)
	{
		JPanel row = row();
		JButton button = new JButton(text);
		JSpinner alpha = new JSpinner(new SpinnerNumberModel(value.getAlpha(), 0, 255, 5));
		Color[] current = {value};
		button.setBackground(value);
		button.setOpaque(true);
		button.addActionListener(e ->
		{
			Color picked = JColorChooser.showDialog(this, text, current[0]);
			if (picked == null)
			{
				return;
			}
			saveColor(key, button, current, picked.getRed(), picked.getGreen(), picked.getBlue(), current[0].getAlpha());
		});
		alpha.addChangeListener(e -> saveColor(key, button, current,
			current[0].getRed(), current[0].getGreen(), current[0].getBlue(), (Integer) alpha.getValue()));
		row.add(button, BorderLayout.CENTER);
		row.add(alpha, BorderLayout.EAST);
		add(row);
	}

	private void saveColor(String key, JButton button, Color[] current, int r, int g, int b, int a)
	{
		current[0] = new Color(r, g, b, a);
		button.setBackground(current[0]);
		configManager.setConfiguration(XpLoggerConfig.GROUP, key, current[0]);
	}

	private void position()
	{
		JPanel row = row();
		row.add(label("Corner"), BorderLayout.WEST);
		JComboBox<OverlayPosition> box = new JComboBox<>(POSITIONS);
		box.setSelectedItem(config.position());
		box.addActionListener(e -> configManager.setConfiguration(XpLoggerConfig.GROUP, "position", box.getSelectedItem()));
		row.add(box, BorderLayout.CENTER);
		add(row);
	}

	private void skills()
	{
		Set<String> picked = new HashSet<>();
		String raw = config.skills();
		if (raw != null && !raw.isEmpty())
		{
			for (String part : raw.split(","))
			{
				picked.add(part.trim().toLowerCase(Locale.ROOT));
			}
		}
		JPanel grid = new JPanel(new GridLayout(0, 2));
		grid.setOpaque(false);
		for (Skill skill : Skill.values())
		{
			boolean on = picked.isEmpty() || picked.contains(skill.getName().toLowerCase(Locale.ROOT));
			JCheckBox box = new JCheckBox(skill.getName(), on);
			box.setOpaque(false);
			box.setForeground(Color.WHITE);
			box.addActionListener(e -> saveSkills());
			skillBoxes.add(box);
			grid.add(box);
		}
		add(grid);
	}

	private void saveSkills()
	{
		StringBuilder sb = new StringBuilder();
		int selected = 0;
		for (JCheckBox box : skillBoxes)
		{
			if (!box.isSelected())
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(box.getText());
			selected++;
		}
		String value = selected == skillBoxes.size() ? "" : sb.toString();
		configManager.setConfiguration(XpLoggerConfig.GROUP, "skills", value);
	}

	private JPanel row()
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setOpaque(false);
		return row;
	}
}
