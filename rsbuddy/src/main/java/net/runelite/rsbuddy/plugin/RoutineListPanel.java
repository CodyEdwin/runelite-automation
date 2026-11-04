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
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.util.Text;
import net.runelite.rsbuddy.input.backend.InputBackendType;
import net.runelite.rsbuddy.routine.Routine;
import net.runelite.rsbuddy.routine.RoutineManager;

/**
 * RSBuddy sidebar: a searchable routine library, modeled on RuneLite's plugin list.
 */
public final class RoutineListPanel extends PluginPanel
{
	static final String FAVORITES_KEY = "favoriteRoutines";

	private static final int REFRESH_INTERVAL_MS = 500;

	private final RoutineManager routineManager;
	private final RoutineRegistry registry;
	private final RSBuddyConfig config;
	private final RoutinePreferences preferences;
	private final Function<Routine, Routine> startRoutine;
	private final Function<Routine, Routine> reloadRoutine;
	private final Function<String, String> loadCustom;
	private final IconTextField searchBar;
	private final JLabel runningLabel;
	private final JLabel noticeLabel;
	private final JComboBox<InputBackendType> inputBackendSelector;
	private final JLabel inputStatusLabel;
	private final JPanel listPanel;
	private final JScrollPane scrollPane;
	private final JTextField sourceField;
	private final JTextField customClassField;
	private final JLabel reloadStatusLabel;
	private final Timer refreshTimer;
	private final Set<String> favorites = new HashSet<>();
	private final List<RoutineListItem> items = new ArrayList<>();
	private volatile Supplier<String> inputStatus = () -> "not checked yet";

	private String expandedId;

	public RoutineListPanel(RoutineManager routineManager, RoutineRegistry registry, RSBuddyConfig config,
		RoutinePreferences preferences, Function<Routine, Routine> startRoutine,
		Function<Routine, Routine> reloadRoutine, Function<String, String> loadCustom)
	{
		super(false);

		this.routineManager = routineManager;
		this.registry = registry;
		this.config = config;
		this.preferences = preferences;
		this.startRoutine = startRoutine;
		this.reloadRoutine = reloadRoutine;
		this.loadCustom = loadCustom;

		loadFavorites();

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel title = new JLabel("RSBuddy");
		title.setForeground(ColorScheme.BRAND_ORANGE);
		title.setFont(FontManager.getRunescapeBoldFont());

		JLabel subtitle = new JLabel("Routine Library");
		subtitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		subtitle.setFont(FontManager.getRunescapeSmallFont());

		searchBar = new IconTextField();
		searchBar.setIcon(IconTextField.Icon.SEARCH);
		searchBar.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 30));
		searchBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchBar.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		searchBar.setToolTipText("Search routines");
		setSearchPrompt(searchBar, "Search routines");
		searchBar.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent event)
			{
				rebuildList();
			}

			@Override
			public void removeUpdate(DocumentEvent event)
			{
				rebuildList();
			}

			@Override
			public void changedUpdate(DocumentEvent event)
			{
				rebuildList();
			}
		});

		runningLabel = new JLabel("No routine running");
		runningLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		runningLabel.setFont(FontManager.getRunescapeSmallFont());

		noticeLabel = new JLabel(" ");
		noticeLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		noticeLabel.setFont(FontManager.getRunescapeSmallFont());

		JLabel inputTitle = new JLabel("Input backend (OS hardware only)");
		inputTitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		inputTitle.setFont(FontManager.getRunescapeSmallFont());
		inputBackendSelector = new JComboBox<>(InputBackendType.values());
		inputBackendSelector.setToolTipText("Auto picks Linux X11 or Windows SendInput. No Java input fallback.");
		inputBackendSelector.setSelectedItem(config.inputBackend());
		inputBackendSelector.addActionListener(event ->
		{
			Object selected = inputBackendSelector.getSelectedItem();
			if (selected != null && selected != config.inputBackend())
			{
				preferences.set("inputBackend", ((Enum<?>) selected).name());
			}
		});
		inputStatusLabel = new JLabel();
		inputStatusLabel.setFont(FontManager.getRunescapeSmallFont());

		JPanel top = new JPanel();
		top.setLayout(new DynamicGridLayout(0, 1, 0, 6));
		top.setBorder(new EmptyBorder(10, 10, 8, 10));
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		top.add(title);
		top.add(subtitle);
		top.add(searchBar);
		top.add(runningLabel);
		top.add(inputTitle);
		top.add(inputBackendSelector);
		top.add(inputStatusLabel);
		top.add(noticeLabel);
		add(top, BorderLayout.NORTH);

		listPanel = new FixedWidthPanel();
		listPanel.setLayout(new DynamicGridLayout(0, 1, 0, 5));
		listPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		listPanel.setBorder(new EmptyBorder(0, 10, 10, 10));

		JPanel north = new FixedWidthPanel();
		north.setLayout(new BorderLayout());
		north.setBackground(ColorScheme.DARK_GRAY_COLOR);
		north.add(listPanel, BorderLayout.NORTH);

		scrollPane = new JScrollPane(north);
		scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.setBorder(new EmptyBorder(0, 0, 0, 0));
		add(scrollPane, BorderLayout.CENTER);

		sourceField = new JTextField(config.routineSourceDirectory());
		sourceField.setToolTipText("Path to rsbuddy/src/main/java (or the repo root). Press Enter to save.");
		Runnable saveSource = () -> preferences.set("routineSourceDirectory", sourceField.getText().trim());
		sourceField.addActionListener(event -> saveSource.run());
		sourceField.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusLost(FocusEvent event)
			{
				saveSource.run();
			}
		});

		customClassField = new JTextField(config.customRoutineClass());
		customClassField.setToolTipText("Fully-qualified Routine class name with a no-arg constructor");
		customClassField.addActionListener(event -> loadCustomRoutine());

		reloadStatusLabel = new JLabel("Reload compiles routines in the client.");
		reloadStatusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		reloadStatusLabel.setFont(FontManager.getRunescapeSmallFont());

		refreshTimer = new Timer(REFRESH_INTERVAL_MS, event -> refresh());
		refreshTimer.setCoalesce(true);

		rebuildList();
		refresh();
		refreshTimer.start();
	}

	@Override
	public void onActivate()
	{
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

	void setNotice(String message)
	{
		if (message == null || message.trim().isEmpty())
		{
			noticeLabel.setText(" ");
			noticeLabel.setToolTipText(null);
			return;
		}

		String html = "<html>" + message.replace("&", "&amp;").replace("<", "&lt;")
			.replace(">", "&gt;").replace("\n", "<br>") + "</html>";
		noticeLabel.setText(html);
		noticeLabel.setToolTipText(html);
	}

	void setInputStatusSupplier(Supplier<String> supplier)
	{
		inputStatus = supplier == null ? () -> "not checked yet" : supplier;
		refresh();
	}

	/**
	 * Called on the EDT when an RSBuddy config key changes.
	 */
	void configChanged(String key)
	{
		if ("inputBackend".equals(key) && inputBackendSelector.getSelectedItem() != config.inputBackend())
		{
			inputBackendSelector.setSelectedItem(config.inputBackend());
		}

		if ("routineSourceDirectory".equals(key) && !sourceField.getText().equals(config.routineSourceDirectory()))
		{
			sourceField.setText(config.routineSourceDirectory());
		}

		if ("customRoutineClass".equals(key) && !customClassField.getText().equals(config.customRoutineClass()))
		{
			customClassField.setText(config.customRoutineClass());
		}

		for (RoutineListItem item : items)
		{
			item.sync(key);
		}
	}

	void onFavorite(RoutineListItem item)
	{
		String id = item.getDefinition().getId();
		if (favorites.contains(id))
		{
			favorites.remove(id);
		}
		else
		{
			favorites.add(id);
		}

		preferences.set(FAVORITES_KEY, Text.toCSV(favorites));
		rebuildList();
	}

	void onToggle(RoutineListItem item, boolean start)
	{
		Routine instance = item.getDefinition().getInstance();
		if (instance == null)
		{
			return;
		}

		if (!start)
		{
			if (routineManager.getActiveRoutine() == instance)
			{
				routineManager.stop();
			}
			refresh();
			return;
		}

		try
		{
			startRoutine.apply(instance);
			setNotice(null);
		}
		catch (RuntimeException ex)
		{
			setNotice(ex.getMessage());
		}

		refresh();
	}

	void toggleExpanded(RoutineListItem item)
	{
		boolean expand = !item.isExpanded();
		expandedId = expand ? item.getDefinition().getId() : null;
		for (RoutineListItem other : items)
		{
			other.setExpanded(expand && other == item);
		}
		revalidate();
		repaint();
	}

	void reload(RoutineListItem item)
	{
		final Routine selected = item.getDefinition().getInstance();
		if (selected == null)
		{
			return;
		}

		reloadStatusLabel.setText("Compiling…");
		setNotice(null);

		new SwingWorker<Routine, Void>()
		{
			@Override
			protected Routine doInBackground()
			{
				return reloadRoutine.apply(selected);
			}

			@Override
			protected void done()
			{
				try
				{
					Routine replacement = get();
					reloadStatusLabel.setText("Reloaded " + selected.getName() + ".");
					if (replacement != null)
					{
						setNotice(null);
					}
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
					setNotice(cause.getMessage());
				}

				refresh();
			}
		}.execute();
	}

	private void loadCustomRoutine()
	{
		String className = customClassField.getText().trim();
		if (className.isEmpty())
		{
			setNotice("Enter a routine class name");
			return;
		}

		String error = loadCustom.apply(className);
		if (error != null)
		{
			setNotice(error);
			return;
		}

		setNotice(null);
		rebuildList();
	}

	private void rebuildList()
	{
		int scroll = scrollPane.getVerticalScrollBar().getValue();
		items.clear();
		listPanel.removeAll();

		String query = searchBar.getText() == null ? "" : searchBar.getText().trim().toLowerCase(Locale.ROOT);
		List<RoutineDefinition> visible = new ArrayList<>();
		for (RoutineDefinition definition : registry.getDefinitions())
		{
			if (matches(definition, query))
			{
				visible.add(definition);
			}
		}

		visible.sort(Comparator
			.comparing((RoutineDefinition definition) -> !favorites.contains(definition.getId()))
			.thenComparing(definition -> displayName(definition), String.CASE_INSENSITIVE_ORDER));

		if (visible.isEmpty())
		{
			JLabel empty = new JLabel(query.isEmpty() ? "No routines registered." : "No routines match your search.");
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			empty.setFont(FontManager.getRunescapeSmallFont());
			listPanel.add(empty);
		}

		for (RoutineDefinition definition : visible)
		{
			RoutineListItem item = new RoutineListItem(this, definition, config, preferences);
			item.setFavorite(favorites.contains(definition.getId()));
			items.add(item);
			listPanel.add(item);
		}

		for (RoutineListItem item : items)
		{
			if (item.getDefinition().getId().equals(expandedId))
			{
				item.setExpanded(true);
			}
		}

		listPanel.add(developerSection());
		refresh();
		listPanel.revalidate();
		listPanel.repaint();
		scrollPane.getVerticalScrollBar().setValue(scroll);
	}

	private JPanel developerSection()
	{
		JPanel section = new JPanel(new DynamicGridLayout(0, 1, 0, 4));
		section.setBackground(ColorScheme.DARK_GRAY_COLOR);
		section.setBorder(new EmptyBorder(8, 0, 0, 0));

		JLabel title = new JLabel("Developer");
		title.setForeground(ColorScheme.TEXT_COLOR);
		title.setFont(FontManager.getRunescapeBoldFont());
		section.add(title);

		JLabel sourceTitle = new JLabel("Routine source directory (empty = auto)");
		sourceTitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sourceTitle.setFont(FontManager.getRunescapeSmallFont());
		section.add(sourceTitle);
		section.add(sourceField);
		section.add(reloadStatusLabel);

		JLabel customTitle = new JLabel("Custom routine");
		customTitle.setForeground(ColorScheme.TEXT_COLOR);
		customTitle.setFont(FontManager.getRunescapeSmallFont());
		section.add(customTitle);

		JButton loadButton = new JButton("Load");
		loadButton.setFocusPainted(false);
		loadButton.addActionListener(event -> loadCustomRoutine());

		JPanel customRow = new JPanel(new BorderLayout(4, 0));
		customRow.setOpaque(false);
		customRow.add(customClassField, BorderLayout.CENTER);
		customRow.add(loadButton, BorderLayout.EAST);
		section.add(customRow);

		JLabel hint = new JLabel("<html>Class name of a class that extends Routine.</html>");
		hint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		hint.setFont(FontManager.getRunescapeSmallFont());
		section.add(hint);
		return section;
	}

	private void refresh()
	{
		String inputText = inputStatus.get();
		boolean inputOk = inputText != null && inputText.startsWith("using ");
		inputStatusLabel.setText("<html>[Input] " + (inputText == null ? "" : inputText.replace("<", "&lt;")) + "</html>");
		inputStatusLabel.setForeground(inputOk ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_ERROR_COLOR);

		Routine active = routineManager.getActiveRoutine();
		if (active == null)
		{
			runningLabel.setText("No routine running");
			runningLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		}
		else
		{
			runningLabel.setText("Running: " + active.getName());
			runningLabel.setToolTipText(active.getStatus());
			runningLabel.setForeground(Color.WHITE);
		}

		for (RoutineListItem item : items)
		{
			item.refreshState(active);
		}
	}

	private void loadFavorites()
	{
		String raw = preferences.getString(FAVORITES_KEY);
		if (raw == null || raw.trim().isEmpty())
		{
			return;
		}

		favorites.addAll(Text.fromCSV(raw));
	}

	private static boolean matches(RoutineDefinition definition, String query)
	{
		if (query.isEmpty())
		{
			return true;
		}

		String name = displayName(definition).toLowerCase(Locale.ROOT);
		Routine instance = definition.getInstance();
		String description = instance == null || instance.getDescription() == null
			? ""
			: instance.getDescription().toLowerCase(Locale.ROOT);
		String simple = definition.getSimpleName().toLowerCase(Locale.ROOT);

		for (String term : query.split("\\s+"))
		{
			if (term.isEmpty())
			{
				continue;
			}

			if (!name.contains(term) && !description.contains(term) && !simple.contains(term))
			{
				return false;
			}
		}

		return true;
	}

	private static String displayName(RoutineDefinition definition)
	{
		Routine instance = definition.getInstance();
		if (instance != null && instance.getName() != null)
		{
			return instance.getName();
		}

		return definition.getSimpleName();
	}

	private static void setSearchPrompt(Component component, String prompt)
	{
		if (component instanceof JTextField)
		{
			((JTextField) component).putClientProperty("JTextField.placeholderText", prompt);
			((JTextField) component).setToolTipText(prompt);
			return;
		}

		if (component instanceof Container)
		{
			for (Component child : ((Container) component).getComponents())
			{
				setSearchPrompt(child, prompt);
			}
		}
	}

	private static final class FixedWidthPanel extends JPanel
	{
		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(PluginPanel.PANEL_WIDTH, super.getPreferredSize().height);
		}
	}
}
