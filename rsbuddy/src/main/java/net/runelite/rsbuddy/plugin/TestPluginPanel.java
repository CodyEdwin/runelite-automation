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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import net.runelite.rsbuddy.script.ScriptSetting;
import net.runelite.rsbuddy.input.backend.InputBackendType;
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
import net.runelite.rsbuddy.plugin.RoofCourse;
import net.runelite.rsbuddy.script.Script;
import net.runelite.rsbuddy.script.ScriptLoader;
import net.runelite.rsbuddy.script.ScriptManager;
import net.runelite.rsbuddy.scripts.BowFletcherScript;
import net.runelite.rsbuddy.scripts.FruitStallStealerScript;
import net.runelite.rsbuddy.scripts.GemCutterScript;
import net.runelite.rsbuddy.scripts.MotherlodeMinerScript;
import net.runelite.rsbuddy.scripts.ObjectInteractScript;
import net.runelite.rsbuddy.scripts.RooftopAgilityTrainerScript;

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
	private final JLabel inputStatusLabel;
	private final JComboBox<InputBackendType> inputBackendSelector;
	private volatile Supplier<String> inputStatus = () -> "not checked yet";
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

	/**
	 * Config key -> refresher for the settings controls currently shown, so a
	 * change made in the Test Plugin config (wrench) shows up here too.
	 */
	private final Map<String, Runnable> settingSyncers = new HashMap<>();

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
		reloadButton.setToolTipText("Compile the script sources in the client (no Gradle) and reload the selected script");
		reloadButton.addActionListener(event -> reloadSelectedScript());

		JPanel buttonPanel = new JPanel(new GridLayout(1, 3, 4, 0));
		buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		buttonPanel.add(startButton);
		buttonPanel.add(stopButton);
		buttonPanel.add(reloadButton);
		add(buttonPanel);

		reloadStatusLabel = new JLabel("Reload compiles scripts in the client.");
		reloadStatusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		reloadStatusLabel.setFont(FontManager.getRunescapeSmallFont());
		add(reloadStatusLabel);

		JLabel sourceTitle = new JLabel("Script source directory (empty = auto)");
		sourceTitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sourceTitle.setFont(FontManager.getRunescapeSmallFont());
		add(sourceTitle);
		JTextField sourceField = new JTextField(config.scriptSourceDirectory());
		sourceField.setToolTipText("Path to rsbuddy/src/main/java (or the repo root). Press Enter to save.");
		Runnable saveSource = () -> configManager.setConfiguration(TestConfig.GROUP, "scriptSourceDirectory",
			sourceField.getText().trim());
		sourceField.addActionListener(event -> saveSource.run());
		sourceField.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusLost(FocusEvent event)
			{
				saveSource.run();
			}
		});
		add(sourceField);

		JLabel inputTitle = new JLabel("Input backend (OS hardware only)");
		inputTitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		inputTitle.setFont(FontManager.getRunescapeSmallFont());
		add(inputTitle);
		inputBackendSelector = new JComboBox<>(InputBackendType.values());
		inputBackendSelector.setToolTipText("Auto picks Linux X11 or Windows SendInput. No Java input fallback.");
		inputBackendSelector.setSelectedItem(config.inputBackend());
		inputBackendSelector.addActionListener(event ->
		{
			Object selected = inputBackendSelector.getSelectedItem();
			if (selected != null && selected != config.inputBackend())
			{
				configManager.setConfiguration(TestConfig.GROUP, "inputBackend", ((Enum<?>) selected).name());
			}
		});
		add(inputBackendSelector);
		inputStatusLabel = new JLabel();
		inputStatusLabel.setFont(FontManager.getRunescapeSmallFont());
		add(inputStatusLabel);

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

	/**
	 * Called on the EDT when a Test Plugin config key changes (from the
	 * wrench config or from this panel) to keep the shown controls in sync.
	 */
	void setInputStatusSupplier(Supplier<String> supplier)
	{
		inputStatus = supplier;
		refresh();
	}

	void configChanged(String key)
	{
		if ("inputBackend".equals(key) && inputBackendSelector.getSelectedItem() != config.inputBackend())
		{
			inputBackendSelector.setSelectedItem(config.inputBackend());
		}
		Runnable syncer = settingSyncers.get(key);
		if (syncer != null)
		{
			syncer.run();
		}
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
			// Wrap so multi-line compile errors stay readable in the narrow panel.
			String html = "<html>" + message.replace("&", "&amp;").replace("<", "&lt;")
				.replace(">", "&gt;").replace("\n", "<br>") + "</html>";
			customErrorLabel.setText(html);
			customErrorLabel.setToolTipText(html);
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
		reloadStatusLabel.setText("[Reload] compiling...");
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

					reloadStatusLabel.setText("[Reload] reloaded " + selected.getName() + ".");
				}
				catch (InterruptedException ex)
				{
					Thread.currentThread().interrupt();
					reloadStatusLabel.setText("[Reload] interrupted.");
				}
				catch (ExecutionException ex)
				{
					Throwable cause = ex.getCause() == null ? ex : ex.getCause();
					reloadStatusLabel.setText("[Reload] failed, see message below.");
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
		String inputText = inputStatus.get();
		boolean inputOk = inputText != null && inputText.startsWith("using ");
		inputStatusLabel.setText("<html>[Input] " + (inputText == null ? "" : inputText.replace("<", "&lt;")) + "</html>");
		inputStatusLabel.setForeground(inputOk ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_ERROR_COLOR);

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
		settingSyncers.clear();
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

		else if (script != null && BowFletcherScript.class.getName().equals(script.getClass().getName()))
		{
			addComboSetting("Bow to make", "Which unstrung bow to fletch; the logs follow from it.",
				BowFletcherBowType.values(), "bowFletcherBowType", config::bowFletcherBowType);
			addSpinnerSetting("Amount (0 = unlimited)",
				"How many bows to make, then stop. 0 = until the bank runs out of logs.",
				"bowFletcherAmount", config::bowFletcherAmount);
			addCheckSetting("Bank at closest booth",
				"Open the closest bank: booth, chest or banker NPC. Off: prefer a banker NPC.",
				"bowFletcherClosestBooth", config::bowFletcherClosestBooth);

			scriptSettingsHint.setText("<html>Knife in locked slot 1. Used the next time the script starts."
				+ " Same settings as Test Plugin config.</html>");
			hasSettings = true;
		}
		else if (script != null && GemCutterScript.class.getName().equals(script.getClass().getName()))
		{
			addComboSetting("Gem to cut", "Which uncut gem to withdraw and cut.",
				GemCutterGemType.values(), "gemCutterGemType", config::gemCutterGemType);
			addSpinnerSetting("Amount (0 = unlimited)",
				"How many gems to cut, then stop. 0 = until the bank runs out of uncut gems.",
				"gemCutterAmount", config::gemCutterAmount);
			addCheckSetting("Bank at closest booth",
				"Open the closest bank: booth, chest or banker NPC. Off: prefer a banker NPC.",
				"gemCutterClosestBooth", config::gemCutterClosestBooth);

			scriptSettingsHint.setText("<html>Chisel in locked slot 1. Used the next time the script starts."
				+ " Same settings as Test Plugin config.</html>");
			hasSettings = true;
		}
		else if (script != null && MotherlodeMinerScript.class.getName().equals(script.getClass().getName()))
		{
			addCheckSetting("Upstairs mining",
				"Mine the upstairs veins and use the upstairs hopper, four loads per trip.",
				"motherlodeUpstairs", config::motherlodeUpstairs);
			addCheckSetting("Use dragon pickaxe special",
				"While mining, click the special attack orb every time special energy is 100% (dragon pickaxe wielded).",
				"motherlodeDragonSpec", config::motherlodeDragonSpec);

			scriptSettingsHint.setText("<html>Used the next time the script starts. The script reads this "
				+ "when it begins, so changing it while running has no effect until the next start.</html>");
			hasSettings = true;
		}
		else if (script != null && RooftopAgilityTrainerScript.class.getName().equals(script.getClass().getName()))
		{
			addComboSetting("Course", "Which rooftop agility course to run.",
				RoofCourse.values(), "rooftopCourse", config::rooftopCourse);

			scriptSettingsHint.setText("<html>Choose the course before pressing Start. The script reads it "
				+ "when it begins, so changing it while running has no effect until the next start.</html>");
			hasSettings = true;
		}

		if (!hasSettings && script != null && !script.getScriptSettings().isEmpty())
		{
			// Settings a script declares itself (ScriptSetting).
			for (ScriptSetting setting : script.getScriptSettings())
			{
				String key = setting.getKey();
				Supplier<String> stored = () ->
				{
					String value = configManager.getConfiguration(TestConfig.GROUP, key);
					return value == null ? setting.getDefaultValue() : value;
				};
				switch (setting.getType())
				{
					case BOOLEAN:
						addCheckSetting(setting.getLabel(), setting.getTooltip(), key,
							() -> Boolean.parseBoolean(stored.get()));
						break;
					case INTEGER:
						addSpinnerSetting(setting.getLabel(), setting.getTooltip(), key, () ->
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
						addTextSetting(setting.getLabel(), stored.get(),
							value -> configManager.setConfiguration(TestConfig.GROUP, key, value));
						break;
				}
			}
			scriptSettingsHint.setText("");
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

	/** A checkbox bound to a boolean config key, kept in sync both ways. */
	private void addCheckSetting(String label, String tooltip, String key, Supplier<Boolean> current)
	{
		JCheckBox checkBox = new JCheckBox(label);
		checkBox.setOpaque(false);
		checkBox.setForeground(ColorScheme.TEXT_COLOR);
		checkBox.setFocusPainted(false);
		checkBox.setSelected(current.get());
		checkBox.setToolTipText(tooltip);
		checkBox.addActionListener(event ->
			configManager.setConfiguration(TestConfig.GROUP, key, checkBox.isSelected()));
		scriptSettingsPanel.add(checkBox);
		settingSyncers.put(key, () ->
		{
			boolean value = current.get();
			if (checkBox.isSelected() != value)
			{
				checkBox.setSelected(value);
			}
		});
	}

	/** A dropdown bound to an enum config key, kept in sync both ways. */
	private <E extends Enum<E>> void addComboSetting(String label, String tooltip, E[] values, String key,
		Supplier<E> current)
	{
		JLabel title = new JLabel(label);
		title.setForeground(ColorScheme.TEXT_COLOR);
		scriptSettingsPanel.add(title);

		JComboBox<E> combo = new JComboBox<>(values);
		combo.setToolTipText(tooltip);
		combo.setSelectedItem(current.get());
		combo.addActionListener(event ->
		{
			Object selected = combo.getSelectedItem();
			if (selected != null)
			{
				configManager.setConfiguration(TestConfig.GROUP, key, ((Enum<?>) selected).name());
			}
		});
		scriptSettingsPanel.add(combo);
		settingSyncers.put(key, () ->
		{
			E value = current.get();
			if (combo.getSelectedItem() != value)
			{
				combo.setSelectedItem(value);
			}
		});
	}

	/** A 0..1,000,000 spinner bound to an int config key, kept in sync both ways. */
	private void addSpinnerSetting(String label, String tooltip, String key, Supplier<Integer> current)
	{
		JLabel title = new JLabel(label);
		title.setForeground(ColorScheme.TEXT_COLOR);
		scriptSettingsPanel.add(title);

		JSpinner spinner = new JSpinner(new SpinnerNumberModel((int) current.get(), 0, 1000000, 1));
		spinner.setToolTipText(tooltip);
		spinner.addChangeListener(event ->
			configManager.setConfiguration(TestConfig.GROUP, key, (Integer) spinner.getValue()));
		scriptSettingsPanel.add(spinner);
		settingSyncers.put(key, () ->
		{
			int value = current.get();
			if (!Integer.valueOf(value).equals(spinner.getValue()))
			{
				spinner.setValue(value);
			}
		});
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
