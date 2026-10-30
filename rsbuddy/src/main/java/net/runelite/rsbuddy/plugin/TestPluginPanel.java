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
import java.awt.Component;
import java.awt.GridLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.rsbuddy.script.Script;
import net.runelite.rsbuddy.script.ScriptLoader;
import net.runelite.rsbuddy.script.ScriptManager;
import net.runelite.rsbuddy.scripts.FruitStallStealerScript;
import net.runelite.rsbuddy.scripts.ObjectInteractScript;

/**
 * Sidebar panel used to pick and control RSBuddy scripts.
 */
public final class TestPluginPanel extends PluginPanel
{
	private static final int REFRESH_INTERVAL_MS = 500;

	private final ScriptManager scriptManager;
	private final TestConfig config;
	private final ConfigManager configManager;
	private final Function<Script, Script> startScript;
	private final Function<Script, Script> reloadScript;
	private final JComboBox<Script> scriptSelector;
	private final JButton startButton;
	private final JButton stopButton;
	private final JButton reloadButton;
	private final JLabel activeLabel;
	private final JLabel statusLabel;
	private final JLabel reloadStatusLabel;
	private final JTextArea description;
	private final JLabel scriptSettingsTitle;
	private final JPanel scriptSettingsPanel;
	private final JLabel scriptSettingsHint;
	private final JTextField customClassField;
	private final JLabel customErrorLabel;
	private final Timer refreshTimer;

	private Script settingsScript;

	public TestPluginPanel(ScriptManager scriptManager, TestConfig config, ConfigManager configManager,
		Function<Script, Script> startScript, Function<Script, Script> reloadScript)
	{
		this.scriptManager = scriptManager;
		this.config = config;
		this.configManager = configManager;
		this.startScript = startScript;
		this.reloadScript = reloadScript;

		setLayout(new DynamicGridLayout(0, 1, 0, 6));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel title = new JLabel("RSBuddy");
		title.setForeground(ColorScheme.BRAND_ORANGE);
		title.setFont(FontManager.getRunescapeBoldFont());
		add(title);

		JLabel subtitle = new JLabel("Script control");
		subtitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		add(subtitle);

		scriptSelector = new JComboBox<>();
		scriptSelector.setRenderer(new ScriptRenderer());
		scriptSelector.setToolTipText("Script to run");
		for (Script script : scriptManager.getScripts())
		{
			scriptSelector.addItem(script);
		}
		add(scriptSelector);

		startButton = new JButton("Start");
		startButton.setFocusPainted(false);
		startButton.addActionListener(event -> startSelectedScript());

		stopButton = new JButton("Stop");
		stopButton.setFocusPainted(false);
		stopButton.addActionListener(event ->
		{
			scriptManager.stop();
			refresh();
		});

		reloadButton = new JButton("Reload");
		reloadButton.setFocusPainted(false);
		reloadButton.setToolTipText("Rebuild the rsbuddy module and reload the selected script without restarting the client");
		reloadButton.addActionListener(event -> reloadSelectedScript());

		JPanel buttonPanel = new JPanel(new GridLayout(1, 3, 4, 0));
		buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		buttonPanel.add(startButton);
		buttonPanel.add(stopButton);
		buttonPanel.add(reloadButton);
		add(buttonPanel);

		reloadStatusLabel = new JLabel("Reload rebuilds rsbuddy first.");
		reloadStatusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		reloadStatusLabel.setFont(FontManager.getRunescapeSmallFont());
		add(reloadStatusLabel);

		activeLabel = new JLabel();
		activeLabel.setForeground(ColorScheme.TEXT_COLOR);
		add(activeLabel);

		statusLabel = new JLabel();
		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		add(statusLabel);

		JLabel descriptionTitle = new JLabel("Description");
		descriptionTitle.setForeground(ColorScheme.TEXT_COLOR);
		descriptionTitle.setFont(FontManager.getRunescapeBoldFont());
		descriptionTitle.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		add(descriptionTitle);

		description = new JTextArea();
		description.setEditable(false);
		description.setFocusable(false);
		description.setLineWrap(true);
		description.setWrapStyleWord(true);
		description.setOpaque(true);
		description.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		description.setForeground(ColorScheme.TEXT_COLOR);
		description.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		description.setRows(4);
		add(description);

		JLabel hint = new JLabel("<html>Script settings are applied when you press Start or Reload.</html>");
		hint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		hint.setFont(FontManager.getRunescapeSmallFont());
		hint.setVerticalAlignment(SwingConstants.TOP);
		add(hint);

		scriptSettingsTitle = new JLabel("Script settings");
		scriptSettingsTitle.setForeground(ColorScheme.TEXT_COLOR);
		scriptSettingsTitle.setFont(FontManager.getRunescapeBoldFont());
		scriptSettingsTitle.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		add(scriptSettingsTitle);

		scriptSettingsPanel = new JPanel(new DynamicGridLayout(0, 1, 0, 4));
		scriptSettingsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(scriptSettingsPanel);

		scriptSettingsHint = new JLabel();
		scriptSettingsHint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		scriptSettingsHint.setFont(FontManager.getRunescapeSmallFont());
		scriptSettingsHint.setVerticalAlignment(SwingConstants.TOP);
		add(scriptSettingsHint);

		JLabel customTitle = new JLabel("Custom script");
		customTitle.setForeground(ColorScheme.TEXT_COLOR);
		customTitle.setFont(FontManager.getRunescapeBoldFont());
		customTitle.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		add(customTitle);

		customClassField = new JTextField(config.customScriptClass());
		customClassField.setToolTipText("Fully-qualified Script class name with a no-arg constructor");
		customClassField.addActionListener(event -> loadCustomScript());

		JButton loadButton = new JButton("Load");
		loadButton.setFocusPainted(false);
		loadButton.addActionListener(event -> loadCustomScript());

		JPanel customLoaderPanel = new JPanel(new BorderLayout(4, 0));
		customLoaderPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		customLoaderPanel.add(customClassField, BorderLayout.CENTER);
		customLoaderPanel.add(loadButton, BorderLayout.EAST);
		add(customLoaderPanel);

		customErrorLabel = new JLabel(" ");
		customErrorLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		customErrorLabel.setFont(FontManager.getRunescapeSmallFont());
		add(customErrorLabel);

		JLabel customHint = new JLabel("<html>Use the class name of a class that extends Script.</html>");
		customHint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		customHint.setFont(FontManager.getRunescapeSmallFont());
		add(customHint);

		refreshTimer = new Timer(REFRESH_INTERVAL_MS, event -> refresh());
		refreshTimer.setCoalesce(true);

		scriptSelector.addActionListener(event ->
		{
			// Rebuild the settings for the newly selected script.
			settingsScript = null;
			refresh();
		});
		refresh();
		refreshTimer.start();
	}

	@Override
	public void onActivate()
	{
		// Pick up any changes made in the plugin config while the panel was
		// hidden.
		settingsScript = null;
		refresh();
		refreshTimer.start();
	}

	@Override
	public void onDeactivate()
	{
		refreshTimer.stop();
	}

	void shutdown()
	{
		refreshTimer.stop();
	}

	void setCustomScriptError(String message)
	{
		if (message == null || message.trim().isEmpty())
		{
			customErrorLabel.setText(" ");
			customErrorLabel.setToolTipText(null);
		}
		else
		{
			customErrorLabel.setText(message);
			customErrorLabel.setToolTipText(message);
		}
	}

	private void loadCustomScript()
	{
		String className = customClassField.getText().trim();
		if (className.isEmpty())
		{
			setCustomScriptError("Enter a script class name");
			return;
		}

		Script script = findLoadedScript(className);
		if (script == null)
		{
			try
			{
				script = ScriptLoader.load(className, TestPluginPanel.class.getClassLoader());
			}
			catch (ScriptLoader.ScriptLoadException ex)
			{
				setCustomScriptError(ex.getMessage());
				return;
			}

			scriptManager.register(script);
			scriptSelector.addItem(script);
		}

		customClassField.setText(className);
		scriptSelector.setSelectedItem(script);
		configManager.setConfiguration(TestConfig.GROUP, "customScriptClass", className);
		setCustomScriptError(null);
	}

	private Script findLoadedScript(String className)
	{
		for (int i = 0; i < scriptSelector.getItemCount(); i++)
		{
			Script candidate = scriptSelector.getItemAt(i);
			if (candidate.getClass().getName().equals(className))
			{
				return candidate;
			}
		}

		return null;
	}

	private void startSelectedScript()
	{
		Script selected = (Script) scriptSelector.getSelectedItem();
		if (selected == null)
		{
			return;
		}

		try
		{
			Script target = startScript.apply(selected);
			if (target != null && target != selected)
			{
				replaceSelectedScript(selected, target);
			}
			setCustomScriptError(null);
		}
		catch (RuntimeException ex)
		{
			setCustomScriptError(ex.getMessage());
		}

		refresh();
	}

	private void reloadSelectedScript()
	{
		final Script selected = (Script) scriptSelector.getSelectedItem();
		if (selected == null)
		{
			return;
		}

		reloadButton.setEnabled(false);
		reloadStatusLabel.setText("Rebuilding and reloading...");
		setCustomScriptError(null);

		new SwingWorker<Script, Void>()
		{
			@Override
			protected Script doInBackground()
			{
				return reloadScript.apply(selected);
			}

			@Override
			protected void done()
			{
				reloadButton.setEnabled(true);

				try
				{
					Script replacement = get();
					if (replacement != null && replacement != selected)
					{
						replaceSelectedScript(selected, replacement);
					}

					reloadStatusLabel.setText("Reloaded " + selected.getName() + ".");
				}
				catch (InterruptedException ex)
				{
					Thread.currentThread().interrupt();
					reloadStatusLabel.setText("Reload interrupted.");
				}
				catch (ExecutionException ex)
				{
					Throwable cause = ex.getCause() == null ? ex : ex.getCause();
					reloadStatusLabel.setText("Reload failed.");
					setCustomScriptError(cause.getMessage());
				}

				refresh();
			}
		}.execute();
	}

	private void replaceSelectedScript(Script oldScript, Script newScript)
	{
		for (int i = 0; i < scriptSelector.getItemCount(); i++)
		{
			if (scriptSelector.getItemAt(i) == oldScript)
			{
				scriptSelector.removeItemAt(i);
				scriptSelector.insertItemAt(newScript, i);
				scriptSelector.setSelectedIndex(i);
				settingsScript = null;
				return;
			}
		}

		scriptSelector.addItem(newScript);
		scriptSelector.setSelectedItem(newScript);
		settingsScript = null;
	}

	private void refresh()
	{
		Script active = scriptManager.getActiveScript();
		if (active == null)
		{
			activeLabel.setText("Running: none");
			statusLabel.setText("Status: idle");
		}
		else
		{
			activeLabel.setText("Running: " + active.getName());
			statusLabel.setText("Status: " + active.getStatus());
		}

		startButton.setEnabled(scriptSelector.getItemCount() > 0);
		stopButton.setEnabled(active != null);

		Script selected = (Script) scriptSelector.getSelectedItem();
		description.setText(selected == null ? "No scripts registered." : selected.getDescription());

		if (selected != settingsScript)
		{
			settingsScript = selected;
			rebuildScriptSettings(selected);
		}
	}

	/**
	 * Builds the settings for the selected script. Scripts with their own
	 * configurable options add their controls here.
	 */
	private void rebuildScriptSettings(Script script)
	{
		scriptSettingsPanel.removeAll();
		boolean hasSettings = false;

		if (script != null && FruitStallStealerScript.class.getName().equals(script.getClass().getName()))
		{
			addCheckSetting("Stay at one stall",
				"Stay at one stall and wait for it to respawn. Uncheck to run between both stalls.",
				config.fruitStallStayAtOneStall(),
				value -> configManager.setConfiguration(
					TestConfig.GROUP, "fruitStallStayAtOneStall", value));

			JLabel keepTitle = new JLabel("Fruit to keep");
			keepTitle.setForeground(ColorScheme.TEXT_COLOR);
			keepTitle.setFont(FontManager.getRunescapeBoldFont());
			scriptSettingsPanel.add(keepTitle);

			addCheckSetting("Papaya fruit",
				"Keep papaya fruit. Unchecked fruit is dropped.",
				config.fruitStallKeepPapaya(),
				value -> configManager.setConfiguration(
					TestConfig.GROUP, "fruitStallKeepPapaya", value));
			addCheckSetting("Golovanova fruit top",
				"Keep Golovanova fruit tops. Unchecked fruit is dropped.",
				config.fruitStallKeepGolovanova(),
				value -> configManager.setConfiguration(
					TestConfig.GROUP, "fruitStallKeepGolovanova", value));
			addCheckSetting("Strange fruit",
				"Keep strange fruit. Unchecked fruit is dropped.",
				config.fruitStallKeepStrangeFruit(),
				value -> configManager.setConfiguration(
					TestConfig.GROUP, "fruitStallKeepStrangeFruit", value));
			addCheckSetting("Jangerberries",
				"Keep jangerberries. Unchecked fruit is dropped.",
				config.fruitStallKeepJangerberries(),
				value -> configManager.setConfiguration(
					TestConfig.GROUP, "fruitStallKeepJangerberries", value));

			scriptSettingsHint.setText(
				"<html>Checked fruit is kept. Unchecked fruit is dropped.</html>");
			hasSettings = true;
		}
		else if (script != null && ObjectInteractScript.class.getName().equals(script.getClass().getName()))
		{
			addTextSetting("Target object", config.targetObjectName(),
				value -> configManager.setConfiguration(TestConfig.GROUP, "targetObjectName", value));
			addTextSetting("Target action", config.targetAction(),
				value -> configManager.setConfiguration(TestConfig.GROUP, "targetAction", value));

			JLabel distanceTitle = new JLabel("Max distance");
			distanceTitle.setForeground(ColorScheme.TEXT_COLOR);
			scriptSettingsPanel.add(distanceTitle);

			JSpinner distance = new JSpinner(new SpinnerNumberModel(config.maxDistance(), 1, 50, 1));
			distance.addChangeListener(event -> configManager.setConfiguration(
				TestConfig.GROUP, "maxDistance", (Integer) distance.getValue()));
			scriptSettingsPanel.add(distance);

			scriptSettingsHint.setText("<html>These settings are used the next time the script starts.</html>");
			hasSettings = true;
		}

		if (!hasSettings)
		{
			scriptSettingsHint.setText("");
		}

		scriptSettingsTitle.setVisible(hasSettings);
		scriptSettingsPanel.setVisible(hasSettings);
		scriptSettingsHint.setVisible(hasSettings);
		scriptSettingsPanel.revalidate();
		scriptSettingsPanel.repaint();
	}

	private void addCheckSetting(String label, String tooltip, boolean selected,
		Consumer<Boolean> save)
	{
		JCheckBox checkBox = new JCheckBox(label);
		checkBox.setOpaque(false);
		checkBox.setForeground(ColorScheme.TEXT_COLOR);
		checkBox.setFocusPainted(false);
		checkBox.setSelected(selected);
		checkBox.setToolTipText(tooltip);
		checkBox.addActionListener(event -> save.accept(checkBox.isSelected()));
		scriptSettingsPanel.add(checkBox);
	}

	private void addTextSetting(String label, String value, Consumer<String> save)
	{
		JLabel title = new JLabel(label);
		title.setForeground(ColorScheme.TEXT_COLOR);
		scriptSettingsPanel.add(title);

		JTextField field = new JTextField(value);
		field.setToolTipText(label);

		Runnable apply = () -> save.accept(field.getText().trim());
		field.addActionListener(event -> apply.run());
		field.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusLost(FocusEvent event)
			{
				apply.run();
			}
		});

		scriptSettingsPanel.add(field);
	}

	private static final class ScriptRenderer extends DefaultListCellRenderer
	{
		@Override
		public Component getListCellRendererComponent(JList<?> list, Object value, int index,
			boolean isSelected, boolean cellHasFocus)
		{
			super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

			if (value instanceof Script)
			{
				setText(((Script) value).getName());
			}

			return this;
		}
	}
}
