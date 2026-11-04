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

import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.Map;
import java.util.function.Supplier;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import net.runelite.rsbuddy.routine.RoutineSetting;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Helpers for the configure section of a routine row. Values are written through
 * {@link RoutinePreferences} and read back from {@link RSBuddyConfig}, which is
 * the same store when the preferences adapter wraps {@code ConfigManager}.
 */
public final class RoutineSettingsForm
{
	private final JPanel panel;
	private final RSBuddyConfig config;
	private final RoutinePreferences preferences;
	private final Map<String, Runnable> syncers;
	private final JLabel hint;

	RoutineSettingsForm(JPanel panel, RSBuddyConfig config, RoutinePreferences preferences,
		Map<String, Runnable> syncers)
	{
		this.panel = panel;
		this.config = config;
		this.preferences = preferences;
		this.syncers = syncers;
		this.hint = new JLabel();
		this.hint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		this.hint.setFont(FontManager.getRunescapeSmallFont());
	}

	public RSBuddyConfig config()
	{
		return config;
	}

	public JLabel getHint()
	{
		return hint;
	}

	public void setHint(String html)
	{
		hint.setText(html == null ? "" : html);
	}

	public void addCheck(String label, String tooltip, String key, Supplier<Boolean> current)
	{
		JCheckBox checkBox = new JCheckBox(label);
		checkBox.setOpaque(false);
		checkBox.setForeground(ColorScheme.TEXT_COLOR);
		checkBox.setFocusPainted(false);
		checkBox.setSelected(current.get());
		checkBox.setToolTipText(tooltip);
		checkBox.addActionListener(event -> preferences.set(key, checkBox.isSelected()));
		panel.add(checkBox);
		syncers.put(key, () ->
		{
			boolean value = current.get();
			if (checkBox.isSelected() != value)
			{
				checkBox.setSelected(value);
			}
		});
	}

	public <E extends Enum<E>> void addCombo(String label, String tooltip, E[] values, String key,
		Supplier<E> current)
	{
		JLabel title = new JLabel(label);
		title.setForeground(ColorScheme.TEXT_COLOR);
		panel.add(title);

		JComboBox<E> combo = new JComboBox<>(values);
		combo.setToolTipText(tooltip);
		combo.setSelectedItem(current.get());
		combo.addActionListener(event ->
		{
			Object selected = combo.getSelectedItem();
			if (selected != null)
			{
				preferences.set(key, ((Enum<?>) selected).name());
			}
		});
		panel.add(combo);
		syncers.put(key, () ->
		{
			E value = current.get();
			if (combo.getSelectedItem() != value)
			{
				combo.setSelectedItem(value);
			}
		});
	}

	public void addSpinner(String label, String tooltip, String key, int min, int max, Supplier<Integer> current)
	{
		JLabel title = new JLabel(label);
		title.setForeground(ColorScheme.TEXT_COLOR);
		panel.add(title);

		JSpinner spinner = new JSpinner(new SpinnerNumberModel((int) current.get(), min, max, 1));
		spinner.setToolTipText(tooltip);
		spinner.addChangeListener(event -> preferences.set(key, (Integer) spinner.getValue()));
		panel.add(spinner);
		syncers.put(key, () ->
		{
			int value = current.get();
			if (!Integer.valueOf(value).equals(spinner.getValue()))
			{
				spinner.setValue(value);
			}
		});
	}

	public void addText(String label, String key, Supplier<String> current)
	{
		JLabel title = new JLabel(label);
		title.setForeground(ColorScheme.TEXT_COLOR);
		panel.add(title);

		JTextField field = new JTextField(current.get());
		field.setToolTipText(label);
		Runnable apply = () -> preferences.set(key, field.getText().trim());
		field.addActionListener(event -> apply.run());
		field.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusLost(FocusEvent event)
			{
				apply.run();
			}
		});
		panel.add(field);
		syncers.put(key, () ->
		{
			String value = current.get() == null ? "" : current.get();
			if (!value.equals(field.getText()))
			{
				field.setText(value);
			}
		});
	}

	/** Adds a control for a setting the routine declares itself ({@link RoutineSetting}). */
	public void addDeclared(RoutineSetting setting)
	{
		String key = setting.getKey();
		Supplier<String> stored = () ->
		{
			String value = preferences.getString(key);
			return value == null ? setting.getDefaultValue() : value;
		};
		switch (setting.getType())
		{
			case BOOLEAN:
				addCheck(setting.getLabel(), setting.getTooltip(), key, () -> Boolean.parseBoolean(stored.get()));
				break;
			case INTEGER:
				addSpinner(setting.getLabel(), setting.getTooltip(), key, 0, 1000000, () ->
				{
					try
					{
						return Integer.parseInt(stored.get().trim());
					}
					catch (NumberFormatException ex)
					{
						return 0;
					}
				});
				break;
			default:
				addText(setting.getLabel(), key, stored);
				break;
		}
	}
}
