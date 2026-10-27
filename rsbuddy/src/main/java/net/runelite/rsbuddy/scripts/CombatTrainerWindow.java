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
package net.runelite.rsbuddy.scripts;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Configuration window for {@link CombatTrainerScript}. It shows the
 * attackable NPCs around the player, lets the player pick which ones to
 * attack, and sets the HP percentage at which food is eaten. Pressing Start
 * minimises the window while the script runs.
 */
final class CombatTrainerWindow extends JFrame
{
	private final CombatTrainerScript script;
	private final JPanel npcList;
	private final Map<String, JCheckBox> checkBoxes = new LinkedHashMap<>();
	private final Set<String> shownNames = new TreeSet<>();
	private final JSpinner hpSpinner;
	private final JLabel hintLabel;
	private final JLabel statusLabel;
	private final Timer statusTimer;

	CombatTrainerWindow(CombatTrainerScript script)
	{
		super("Combat Trainer");

		this.script = script;

		hintLabel = new JLabel(" ");
		hintLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		hintLabel.setFont(FontManager.getRunescapeSmallFont());

		setDefaultCloseOperation(HIDE_ON_CLOSE);
		setAlwaysOnTop(true);
		setSize(380, 540);
		setMinimumSize(new Dimension(340, 420));
		setLocationRelativeTo(null);

		JPanel content = new JPanel(new BorderLayout(8, 8));
		content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		setContentPane(content);

		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setOpaque(false);

		JLabel title = new JLabel("Combat Trainer");
		title.setForeground(ColorScheme.BRAND_ORANGE);
		title.setFont(FontManager.getRunescapeBoldFont());
		header.add(title);

		JLabel subtitle = new JLabel(
			"<html>Choose the nearby NPCs to attack, set the eat HP, then press Start.</html>");
		subtitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		subtitle.setFont(FontManager.getRunescapeSmallFont());
		header.add(subtitle);

		content.add(header, BorderLayout.NORTH);

		JPanel listContainer = new JPanel(new BorderLayout(0, 4));
		listContainer.setOpaque(false);

		JPanel listHeader = new JPanel(new BorderLayout(6, 0));
		listHeader.setOpaque(false);

		JLabel listTitle = new JLabel("Nearby NPCs");
		listTitle.setForeground(ColorScheme.TEXT_COLOR);
		listTitle.setFont(FontManager.getRunescapeBoldFont());
		listHeader.add(listTitle, BorderLayout.WEST);

		JButton refreshButton = new JButton("Refresh NPCs");
		refreshButton.setFocusPainted(false);
		refreshButton.addActionListener(event ->
		{
			hintLabel.setText("Refreshing nearby NPCs...");
			script.requestNpcRefresh();
		});
		listHeader.add(refreshButton, BorderLayout.EAST);

		npcList = new JPanel();
		npcList.setLayout(new BoxLayout(npcList, BoxLayout.Y_AXIS));
		npcList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		npcList.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

		JScrollPane scroll = new JScrollPane(npcList);
		scroll.setBorder(BorderFactory.createLineBorder(ColorScheme.DARKER_GRAY_COLOR.brighter()));
		scroll.getVerticalScrollBar().setUnitIncrement(16);

		listContainer.add(listHeader, BorderLayout.NORTH);
		listContainer.add(scroll, BorderLayout.CENTER);
		content.add(listContainer, BorderLayout.CENTER);

		JPanel bottom = new JPanel();
		bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
		bottom.setOpaque(false);

		JPanel hpRow = new JPanel(new BorderLayout(6, 0));
		hpRow.setOpaque(false);

		JLabel hpLabel = new JLabel("Eat food below HP %");
		hpLabel.setForeground(ColorScheme.TEXT_COLOR);
		hpRow.add(hpLabel, BorderLayout.WEST);

		hpSpinner = new JSpinner(new SpinnerNumberModel(script.getEatAtHpPercent(), 1, 99, 1));
		hpSpinner.setPreferredSize(new Dimension(64, 24));
		hpRow.add(hpSpinner, BorderLayout.EAST);

		bottom.add(hpRow);
		bottom.add(Box.createVerticalStrut(6));

		statusLabel = new JLabel("Status: " + script.getStatus());
		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		bottom.add(statusLabel);
		bottom.add(Box.createVerticalStrut(4));

		bottom.add(hintLabel);
		bottom.add(Box.createVerticalStrut(6));
		JPanel buttons = new JPanel(new GridLayout(1, 3, 4, 0));
		buttons.setOpaque(false);

		JButton startButton = new JButton("Start");
		startButton.setFocusPainted(false);
		startButton.addActionListener(event -> startTraining());
		buttons.add(startButton);

		JButton stopButton = new JButton("Stop");
		stopButton.setFocusPainted(false);
		stopButton.addActionListener(event -> stopTraining());
		buttons.add(stopButton);

		JButton hideButton = new JButton("Minimise");
		hideButton.setFocusPainted(false);
		hideButton.addActionListener(event -> setState(ICONIFIED));
		buttons.add(hideButton);

		bottom.add(buttons);
		content.add(bottom, BorderLayout.SOUTH);

		statusTimer = new Timer(500, event ->
			statusLabel.setText("Status: " + script.getStatus()));
		statusTimer.setCoalesce(true);
		statusTimer.start();
	}

	/**
	 * Replaces the checkbox list with the NPCs found by the latest scan.
	 * This is safe to call from the game thread.
	 */
	void updateNearbyNpcs(Map<String, Integer> nearby)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			Map<String, Integer> snapshot = new TreeMap<>(nearby);
			SwingUtilities.invokeLater(() -> updateNearbyNpcs(snapshot));
			return;
		}

		Set<String> names = new TreeSet<>(nearby.keySet());
		if (names.equals(shownNames))
		{
			updateCounts(nearby);
			hintLabel.setText(names.isEmpty()
				? "No attackable NPCs nearby."
				: "Found " + names.size() + " NPC type(s) nearby.");
			return;
		}

		Set<String> selected = selectedNames();
		shownNames.clear();
		shownNames.addAll(names);
		checkBoxes.clear();
		npcList.removeAll();

		if (names.isEmpty())
		{
			JLabel empty = new JLabel("No attackable NPCs found. Move closer and press Refresh NPCs.");
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			empty.setFont(FontManager.getRunescapeSmallFont());
			npcList.add(empty);
		}
		else
		{
			for (String name : names)
			{
				JCheckBox box = new JCheckBox(formatName(name, nearby.getOrDefault(name, 0)));
				box.setOpaque(false);
				box.setForeground(ColorScheme.TEXT_COLOR);
				box.setAlignmentX(LEFT_ALIGNMENT);
				box.setSelected(selected.contains(name) || script.isTargetSelected(name));
				checkBoxes.put(name, box);
				npcList.add(box);
			}
		}

		npcList.add(Box.createVerticalGlue());
		npcList.revalidate();
		npcList.repaint();

		hintLabel.setText(names.isEmpty()
			? "No attackable NPCs nearby."
			: "Found " + names.size() + " NPC type(s) nearby.");
	}

	private void updateCounts(Map<String, Integer> nearby)
	{
		for (Map.Entry<String, JCheckBox> entry : checkBoxes.entrySet())
		{
			int count = nearby.getOrDefault(entry.getKey(), 0);
			entry.getValue().setText(formatName(entry.getKey(), count));
		}
	}

	private Set<String> selectedNames()
	{
		Set<String> selected = new TreeSet<>();
		for (Map.Entry<String, JCheckBox> entry : checkBoxes.entrySet())
		{
			if (entry.getValue().isSelected())
			{
				selected.add(entry.getKey());
			}
		}

		return selected;
	}

	private void startTraining()
	{
		Set<String> selected = selectedNames();
		if (selected.isEmpty())
		{
			hintLabel.setText("Select at least one NPC to attack.");
			return;
		}

		script.startTraining(selected, (Integer) hpSpinner.getValue());
		hintLabel.setText("Training started.");
		statusLabel.setText("Status: " + script.getStatus());

		// The window stays available in the taskbar, minimised while the
		// script fights.
		setState(ICONIFIED);
	}

	private void stopTraining()
	{
		script.stopTraining();
		hintLabel.setText("Training stopped. Adjust the settings and press Start.");
		statusLabel.setText("Status: " + script.getStatus());
		setState(NORMAL);
		toFront();
	}

	private static String formatName(String name, int count)
	{
		return count <= 1 ? name : name + " (" + count + " nearby)";
	}

	@Override
	public void dispose()
	{
		statusTimer.stop();
		super.dispose();
	}
}
