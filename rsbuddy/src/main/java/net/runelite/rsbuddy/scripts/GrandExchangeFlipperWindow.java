/*
 * Copyright (c) 2026, reich
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.List;
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
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.rsbuddy.ge.FlipCandidate;
import net.runelite.rsbuddy.ge.FlipSettings;

/**
 * Flip advisor window for {@link GrandExchangeFlipperScript}.
 * <p>
 * The top card is the current pick: exactly what to buy, how many, and what to
 * list them at. Everything below it is context - the filter settings, the full
 * ranked list, and a log. The window never clicks in game; it only tells the
 * player what to type into the Grand Exchange.
 */
final class GrandExchangeFlipperWindow extends JFrame
{
	private static final String[] COLUMNS = {
		"Item", "Buy", "Qty", "Sell", "Profit", "ROI", "Vol 24h", "Limit"
	};

	private static final int MAX_TABLE_ROWS = 200;
	private static final int MAX_LOG_CHARS = 40_000;

	private final GrandExchangeFlipperScript script;

	private final JSpinner capitalSpinner = new JSpinner();
	private final JSpinner minProfitSpinner = new JSpinner();
	private final JSpinner minRoiSpinner = new JSpinner();
	private final JSpinner minVolumeSpinner = new JSpinner();
	private final JSpinner buyOffsetSpinner = new JSpinner();
	private final JSpinner sellOffsetSpinner = new JSpinner();
	private final JSpinner driftSpinner = new JSpinner();
	private final JCheckBox f2pBox = new JCheckBox("Free to play only (hide member items)");
	private final JCheckBox advancedBox = new JCheckBox("Show advanced filters");
	private final JPanel advancedPanel = new JPanel(new GridLayout(0, 2, 8, 4));

	private final JLabel cardTitle = new JLabel("Waiting for prices...");
	private final JLabel cardAction = new JLabel(" ");
	private final JLabel cardMeta = new JLabel(" ");
	private final JButton copyButton = new JButton("Copy name");
	private final JButton nextButton = new JButton("Next pick");

	private DefaultTableModel tableModel;
	private JTable table;
	private JTextArea logArea;
	private JLabel statusLabel;
	private final Timer pollTimer;

	private List<FlipCandidate> shown;
	private int pickIndex;
	private boolean updatingTable;

	GrandExchangeFlipperWindow(GrandExchangeFlipperScript script)
	{
		super("GE Flip Advisor");

		this.script = script;

		FlipSettings defaults = script.getSettings();
		capitalSpinner.setModel(new SpinnerNumberModel(
			defaults.getStartingCapital(), 1L, 2_000_000_000L, 100_000L));
		minProfitSpinner.setModel(new SpinnerNumberModel(
			defaults.getMinProfitPerItem(), 0, 10_000_000, 10));
		minRoiSpinner.setModel(new SpinnerNumberModel(
			defaults.getMinRoiPercent(), 0.0, 100.0, 0.25));
		minVolumeSpinner.setModel(new SpinnerNumberModel(
			defaults.getMinDailyVolume(), 0, 100_000_000, 100));
		buyOffsetSpinner.setModel(new SpinnerNumberModel(
			defaults.getBuyOffset(), -10_000, 10_000, 1));
		sellOffsetSpinner.setModel(new SpinnerNumberModel(
			defaults.getSellOffset(), -10_000, 0, 1));
		driftSpinner.setModel(new SpinnerNumberModel(
			defaults.getMaxPriceDriftPercent(), 1.0, 50.0, 0.5));

		f2pBox.setSelected(defaults.isFreeToPlayOnly());

		for (JSpinner spinner : new JSpinner[]{
			capitalSpinner, minProfitSpinner, minRoiSpinner, minVolumeSpinner,
			buyOffsetSpinner, sellOffsetSpinner, driftSpinner})
		{
			spinner.setPreferredSize(new Dimension(120, 24));
		}

		for (JCheckBox box : new JCheckBox[]{f2pBox, advancedBox})
		{
			box.setOpaque(false);
			box.setForeground(ColorScheme.TEXT_COLOR);
			box.setFont(FontManager.getRunescapeSmallFont());
			box.setFocusPainted(false);
		}

		advancedBox.addActionListener(event ->
		{
			advancedPanel.setVisible(advancedBox.isSelected());
			advancedPanel.revalidate();
		});

		// The free to play toggle is the one setting that changes which items
		// exist at all, so it re-scans instead of waiting for Apply.
		f2pBox.addActionListener(event -> applySettings());

		setDefaultCloseOperation(HIDE_ON_CLOSE);
		setAlwaysOnTop(true);
		setSize(660, 760);
		setMinimumSize(new Dimension(580, 620));
		setLocationRelativeTo(null);

		JPanel content = new JPanel(new BorderLayout(8, 8));
		content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		setContentPane(content);

		content.add(buildHeader(), BorderLayout.NORTH);
		content.add(buildCenter(), BorderLayout.CENTER);
		content.add(buildFooter(), BorderLayout.SOUTH);

		pollTimer = new Timer(500, event -> poll());
		pollTimer.setCoalesce(true);
		pollTimer.start();

		refresh();
	}

	private JPanel buildHeader()
	{
		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setOpaque(false);

		JLabel title = new JLabel("GE Flip Advisor");
		title.setForeground(ColorScheme.BRAND_ORANGE);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setAlignmentX(LEFT_ALIGNMENT);
		header.add(title);

		JLabel subtitle = new JLabel(
			"<html>Wiki prices, ranked so every pick is profitable after tax. "
				+ "You type the offers in game; this window never clicks.</html>");
		subtitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		subtitle.setFont(FontManager.getRunescapeSmallFont());
		subtitle.setAlignmentX(LEFT_ALIGNMENT);
		header.add(subtitle);
		header.add(Box.createVerticalStrut(8));

		JPanel settings = new JPanel(new GridLayout(0, 2, 8, 4));
		settings.setOpaque(false);
		settings.setAlignmentX(LEFT_ALIGNMENT);
		addRow(settings, "Starting capital (gp)", capitalSpinner);
		addRow(settings, "Min profit per item (gp)", minProfitSpinner);

		JPanel f2pRow = new JPanel(new BorderLayout());
		f2pRow.setOpaque(false);
		f2pRow.add(f2pBox, BorderLayout.WEST);
		settings.add(new JLabel());
		settings.add(f2pRow);

		addRow(advancedPanel, "Min ROI (%)", minRoiSpinner);
		addRow(advancedPanel, "Min 24h volume (items)", minVolumeSpinner);
		addRow(advancedPanel, "Buy offset (gp above low)", buyOffsetSpinner);
		addRow(advancedPanel, "Sell offset (gp below high)", sellOffsetSpinner);
		addRow(advancedPanel, "Max 24h price drift (%)", driftSpinner);
		advancedPanel.setOpaque(false);
		advancedPanel.setVisible(false);

		header.add(settings);
		header.add(advancedBox);
		header.add(advancedPanel);
		header.add(Box.createVerticalStrut(8));

		header.add(buildCard());
		return header;
	}

	private JPanel buildCard()
	{
		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.BRAND_ORANGE),
			BorderFactory.createEmptyBorder(8, 10, 8, 10)));
		card.setAlignmentX(LEFT_ALIGNMENT);

		JPanel top = new JPanel(new BorderLayout(6, 0));
		top.setOpaque(false);
		cardTitle.setForeground(ColorScheme.BRAND_ORANGE);
		cardTitle.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
		top.add(cardTitle, BorderLayout.CENTER);

		JPanel cardButtons = new JPanel(new GridLayout(1, 2, 4, 0));
		cardButtons.setOpaque(false);

		copyButton.setFocusPainted(false);
		copyButton.setToolTipText("Copies the exact item name so you can paste it into the GE search");
		copyButton.addActionListener(event -> copyPickName());
		cardButtons.add(copyButton);

		nextButton.setFocusPainted(false);
		nextButton.setToolTipText("Show the next pick in the list");
		nextButton.addActionListener(event -> nextPick());
		cardButtons.add(nextButton);

		top.add(cardButtons, BorderLayout.EAST);
		card.add(top);
		card.add(Box.createVerticalStrut(4));

		cardAction.setForeground(ColorScheme.TEXT_COLOR);
		cardAction.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
		card.add(cardAction);
		card.add(Box.createVerticalStrut(2));

		cardMeta.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		cardMeta.setFont(FontManager.getRunescapeSmallFont());
		card.add(cardMeta);

		return card;
	}

	private JPanel buildCenter()
	{
		JPanel middle = new JPanel(new BorderLayout(0, 6));
		middle.setOpaque(false);

		JLabel listTitle = new JLabel("All picks (best total profit first)");
		listTitle.setForeground(ColorScheme.TEXT_COLOR);
		listTitle.setFont(FontManager.getRunescapeBoldFont());
		middle.add(listTitle, BorderLayout.NORTH);

		tableModel = new DefaultTableModel(COLUMNS, 0)
		{
			@Override
			public boolean isCellEditable(int row, int column)
			{
				return false;
			}

			/**
			 * Keeps the money columns numeric so sorting a header sorts by
			 * value instead of by the formatted text.
			 */
			@Override
			public Class<?> getColumnClass(int column)
			{
				switch (column)
				{
					case 1:
					case 3:
					case 4:
					case 6:
						return Long.class;
					case 2:
					case 7:
						return Integer.class;
					case 5:
						return Double.class;
					default:
						return String.class;
				}
			}
		};

		table = new JTable(tableModel);
		table.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		table.setForeground(ColorScheme.TEXT_COLOR);
		table.setGridColor(ColorScheme.DARK_GRAY_COLOR);
		table.setFont(FontManager.getRunescapeSmallFont());
		table.setRowHeight(18);
		table.setShowGrid(false);
		table.setAutoCreateRowSorter(true);
		table.setFillsViewportHeight(true);
		table.getTableHeader().setFont(FontManager.getRunescapeSmallFont());
		for (int column : new int[]{1, 3, 4, 5, 6})
		{
			table.getColumnModel().getColumn(column).setCellRenderer(new MoneyRenderer(column));
		}
		table.getColumnModel().getColumn(0).setPreferredWidth(150);
		table.getColumnModel().getColumn(1).setPreferredWidth(80);
		table.getColumnModel().getColumn(3).setPreferredWidth(80);
		table.getSelectionModel().addListSelectionListener(event ->
		{
			if (!updatingTable && !event.getValueIsAdjusting())
			{
				selectRow(table.getSelectedRow());
			}
		});

		JScrollPane tableScroll = new JScrollPane(table);
		tableScroll.setBorder(BorderFactory.createLineBorder(ColorScheme.DARKER_GRAY_COLOR.brighter()));
		tableScroll.getVerticalScrollBar().setUnitIncrement(16);
		middle.add(tableScroll, BorderLayout.CENTER);

		return middle;
	}

	private JPanel buildFooter()
	{
		JPanel footer = new JPanel();
		footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
		footer.setOpaque(false);

		logArea = new JTextArea(5, 40);
		logArea.setEditable(false);
		logArea.setLineWrap(true);
		logArea.setWrapStyleWord(true);
		logArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		logArea.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		logArea.setFont(FontManager.getRunescapeSmallFont());
		logArea.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

		JScrollPane logScroll = new JScrollPane(logArea);
		logScroll.setBorder(BorderFactory.createLineBorder(ColorScheme.DARKER_GRAY_COLOR.brighter()));
		logScroll.setPreferredSize(new Dimension(620, 110));
		footer.add(logScroll);
		footer.add(Box.createVerticalStrut(6));

		statusLabel = new JLabel("Status: " + script.getStatus());
		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		footer.add(statusLabel);
		footer.add(Box.createVerticalStrut(6));

		JPanel buttons = new JPanel(new GridLayout(1, 3, 4, 0));
		buttons.setOpaque(false);

		JButton applyButton = new JButton("Apply filters & rescan");
		applyButton.setFocusPainted(false);
		applyButton.addActionListener(event -> applySettings());
		buttons.add(applyButton);

		JButton minimizeButton = new JButton("Minimise");
		minimizeButton.setFocusPainted(false);
		minimizeButton.addActionListener(event -> setState(ICONIFIED));
		buttons.add(minimizeButton);

		JButton closeButton = new JButton("Hide");
		closeButton.setFocusPainted(false);
		closeButton.addActionListener(event -> setVisible(false));
		buttons.add(closeButton);

		footer.add(buttons);
		return footer;
	}

	private void addRow(JPanel grid, String labelText, JSpinner spinner)
	{
		JLabel label = new JLabel(labelText);
		label.setForeground(ColorScheme.TEXT_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());
		grid.add(label);
		grid.add(spinner);
	}

	private void applySettings()
	{
		FlipSettings settings = FlipSettings.builder()
			.startingCapital(longValue(capitalSpinner, 1L))
			.minProfitPerItem(intValue(minProfitSpinner, 0))
			.minRoiPercent(doubleValue(minRoiSpinner, 0.0))
			.minDailyVolume(intValue(minVolumeSpinner, 0))
			.buyOffset(intValue(buyOffsetSpinner, 1))
			.sellOffset(intValue(sellOffsetSpinner, -1))
			.maxPriceDriftPercent(doubleValue(driftSpinner, 8.0))
			.freeToPlayOnly(f2pBox.isSelected())
			.build();

		if (settings.getStartingCapital() < 1)
		{
			statusLabel.setText("Status: enter a starting capital above zero");
			return;
		}

		script.applySettings(settings);
		pickIndex = 0;
		refresh();
	}

	private void copyPickName()
	{
		FlipCandidate candidate = currentPick();
		if (candidate == null)
		{
			return;
		}

		Toolkit.getDefaultToolkit().getSystemClipboard()
			.setContents(new StringSelection(candidate.getName()), null);
		script.note("Copied \"" + candidate.getName() + "\" - paste it into the GE search.");
		statusLabel.setText("Status: copied \"" + candidate.getName() + "\"");
	}

	private void nextPick()
	{
		List<FlipCandidate> list = script.getCandidates();
		if (list.isEmpty())
		{
			return;
		}

		pickIndex = (pickIndex + 1) % list.size();
		showPick(list, pickIndex);
	}

	private void selectRow(int viewRow)
	{
		if (viewRow < 0)
		{
			return;
		}

		int modelRow = table.convertRowIndexToModel(viewRow);
		List<FlipCandidate> list = script.getCandidates();
		if (modelRow >= 0 && modelRow < list.size())
		{
			pickIndex = modelRow;
			showPick(list, modelRow);
		}
	}

	/**
	 * Rebuilds the table and the pick card from the current scan. Must run on
	 * the event thread.
	 */
	void refresh()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::refresh);
			return;
		}

		List<FlipCandidate> list = script.getCandidates();
		if (list == shown)
		{
			return;
		}
		shown = list;

		updatingTable = true;
		try
		{
			tableModel.setRowCount(0);
			int rows = Math.min(list.size(), MAX_TABLE_ROWS);
			for (int i = 0; i < rows; i++)
			{
				FlipCandidate candidate = list.get(i);
				tableModel.addRow(new Object[]{
					candidate.getName(),
					(long) candidate.getBuyPrice(),
					candidate.getQuantity(),
					(long) candidate.getSellPrice(),
					candidate.getTotalProfit(),
					candidate.getRoiPercent(),
					candidate.getDailyVolume(),
					candidate.getBuyLimit()
				});
			}
		}
		finally
		{
			updatingTable = false;
		}

		if (pickIndex >= list.size())
		{
			pickIndex = 0;
		}

		showPick(list, pickIndex);
	}

	private void showPick(List<FlipCandidate> list, int index)
	{
		if (list == null || list.isEmpty())
		{
			cardTitle.setText("No pick right now");
			cardAction.setText("No item clears every filter.");
			cardMeta.setText("Lower min profit or volume, or widen the drift limit, "
				+ "then press Apply filters & rescan.");
			copyButton.setEnabled(false);
			nextButton.setEnabled(false);
			return;
		}

		copyButton.setEnabled(true);
		nextButton.setEnabled(list.size() > 1);

		FlipCandidate candidate = list.get(index);
		cardTitle.setText((index + 1) + " of " + list.size() + "  -  " + candidate.getName());
		cardAction.setText("<html>Buy <b>" + candidate.getQuantity() + "</b> @ <b>"
			+ GrandExchangeFlipperScript.formatExact(candidate.getBuyPrice())
			+ "</b> gp &nbsp;&rarr;&nbsp; list them at <b>"
			+ GrandExchangeFlipperScript.formatExact(candidate.getSellPrice())
			+ "</b> gp</html>");

		cardMeta.setText("<html>+" + GrandExchangeFlipperScript.formatExact(candidate.getTotalProfit())
			+ " gp profit after tax (" + String.format("%.2f", candidate.getRoiPercent())
			+ "% ROI) &middot; costs " + GrandExchangeFlipperScript.formatExact(candidate.getTotalCost())
			+ " gp<br>24h volume " + GrandExchangeFlipperScript.formatGp(candidate.getDailyVolume())
			+ " &middot; buy limit " + candidate.getBuyLimit()
			+ " &middot; break-even " + GrandExchangeFlipperScript.formatExact(candidate.getBreakEvenPrice())
			+ " gp &middot; never list below <b>"
			+ GrandExchangeFlipperScript.formatExact(candidate.getSellFloor()) + " gp</b></html>");
	}

	private FlipCandidate currentPick()
	{
		List<FlipCandidate> list = script.getCandidates();
		return list.isEmpty() ? null : list.get(Math.min(pickIndex, list.size() - 1));
	}

	private void poll()
	{
		long age = System.currentTimeMillis() - script.getLastPublishAt();
		String updated = script.getLastPublishAt() <= 0
			? "waiting for the first fetch"
			: "updated " + (age / 1000L) + "s ago";

		statusLabel.setText("Status: " + script.getStatus() + "  |  " + updated);
		refresh();
		drainLog();
	}

	private void drainLog()
	{
		List<String> lines = script.drainLog();
		if (lines.isEmpty())
		{
			return;
		}

		StringBuilder text = new StringBuilder();
		for (String line : lines)
		{
			text.append(line).append('\n');
		}

		logArea.append(text.toString());
		if (logArea.getDocument().getLength() > MAX_LOG_CHARS)
		{
			logArea.replaceRange("", 0, logArea.getDocument().getLength() - MAX_LOG_CHARS);
		}

		logArea.setCaretPosition(logArea.getDocument().getLength());
	}

	/**
	 * Reads a spinner value through Number instead of a direct cast. The value
	 * class depends on how the editor committed what was typed, so a capital of
	 * 240000 can arrive as Long, Integer or Double; casting to Long crashed.
	 */
	private static long longValue(JSpinner spinner, long fallback)
	{
		Object value = spinner.getValue();
		return value instanceof Number ? ((Number) value).longValue() : fallback;
	}

	private static int intValue(JSpinner spinner, int fallback)
	{
		Object value = spinner.getValue();
		return value instanceof Number ? ((Number) value).intValue() : fallback;
	}

	private static double doubleValue(JSpinner spinner, double fallback)
	{
		Object value = spinner.getValue();
		return value instanceof Number ? ((Number) value).doubleValue() : fallback;
	}

	@Override
	public void dispose()
	{
		pollTimer.stop();
		super.dispose();
	}

	/**
	 * Formats the numeric columns for display while leaving the model values as
	 * numbers, so clicking a header sorts correctly. Buy and sell prices are
	 * exact - they are numbers the player types into the Grand Exchange - while
	 * profit and volume stay abbreviated for width.
	 */
	private static final class MoneyRenderer extends DefaultTableCellRenderer
	{
		private final int column;

		MoneyRenderer(int column)
		{
			this.column = column;
			setOpaque(true);
			setHorizontalAlignment(RIGHT);
			setFont(FontManager.getRunescapeSmallFont());
		}

		@Override
		public Component getTableCellRendererComponent(JTable table, Object value,
			boolean selected, boolean focus, int row, int col)
		{
			super.getTableCellRendererComponent(table, value, selected, focus, row, col);

			Number number = value instanceof Number ? (Number) value : null;
			if (number == null)
			{
				setText("");
				return this;
			}

			switch (column)
			{
				case 1:
				case 3:
					setText(GrandExchangeFlipperScript.formatExact(number.longValue()));
					break;
				case 4:
					setText("+" + GrandExchangeFlipperScript.formatGp(number.longValue()));
					break;
				case 5:
					setText(String.format("%.2f%%", number.doubleValue()));
					break;
				case 6:
					setText(GrandExchangeFlipperScript.formatGp(number.longValue()));
					break;
				default:
					setText(number.toString());
			}

			setForeground(selected
				? table.getSelectionForeground()
				: (column == 4 ? ColorScheme.GRAND_EXCHANGE_PRICE : table.getForeground()));
			return this;
		}
	}
}
