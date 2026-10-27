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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import net.runelite.rsbuddy.ge.FlipCandidate;
import net.runelite.rsbuddy.ge.FlipScanner;
import net.runelite.rsbuddy.ge.FlipSettings;
import net.runelite.api.ItemComposition;
import net.runelite.rsbuddy.ge.GePriceClient;
import net.runelite.rsbuddy.ge.ItemMapping;
import net.runelite.rsbuddy.ge.MarketSnapshot;
import net.runelite.rsbuddy.ge.FlipScanner.Membership;
import net.runelite.rsbuddy.script.Script;
import net.runelite.rsbuddy.script.ScriptContext;

/**
 * Grand Exchange flip advisor.
 * <p>
 * Watches the OSRS Wiki price feed, ranks the flips that clear every safety
 * filter, and shows the exact buy and sell prices in a window. It never clicks
 * in game: the player places the offers, so execution and risk stay manual.
 * <p>
 * {@link net.runelite.rsbuddy.ge.GeTrader} remains available for optional
 * automation but is deliberately not wired to this window.
 */
public final class GrandExchangeFlipperScript extends Script
{
	private static final int REFRESH_TICKS = 5;
	private static final int MAX_LOG_LINES = 200;
	private static final Path LOG_FILE = Paths.get(System.getProperty("user.home"),
		".runelite", "rsbuddy-flipper.log");
	private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

	private final FlipScanner scanner = new FlipScanner();
	private final Deque<String> log = new ArrayDeque<>();
	private final Object logLock = new Object();
	private final Map<Integer, Boolean> memberCache = new HashMap<>();
	private final Membership membership = this::isMembersItem;

	private volatile GrandExchangeFlipperWindow window;
	private volatile GePriceClient priceClient;
	private volatile FlipSettings settings = FlipSettings.defaults();
	private volatile List<FlipCandidate> candidates = Collections.emptyList();

	private long lastScanAt;
	private long lastSnapshotAt;
	private long lastPublishAt;
	private String lastError = "";
	private volatile boolean rescanRequested;
	private int tickCounter;

	public GrandExchangeFlipperScript()
	{
		super("GE Flipper", "Tells you what to buy and sell at the Grand Exchange", 1);
	}

	@Override
	protected void onStart()
	{
		priceClient = new GePriceClient();
		setStatus("Loading prices...");
		logLine("Advisor started. Prices come from the OSRS Wiki; nothing is clicked for you.");

		SwingUtilities.invokeLater(this::showWindow);
	}

	@Override
	protected void onStop()
	{
		GePriceClient client = priceClient;
		priceClient = null;
		if (client != null)
		{
			client.close();
		}

		GrandExchangeFlipperWindow current = window;
		window = null;
		if (current != null)
		{
			SwingUtilities.invokeLater(current::dispose);
		}
	}

	@Override
	protected void onGameTick()
	{
		ScriptContext context = getContext();
		GePriceClient client = priceClient;
		if (context == null || client == null)
		{
			return;
		}

		if (!context.isLoggedIn())
		{
			setStatus("Waiting for login");
			return;
		}

		if (++tickCounter % REFRESH_TICKS == 0)
		{
			client.refreshAsync(this::publish);
		}

		MarketSnapshot snapshot = client.getSnapshot();
		boolean newSnapshot = snapshot.getLatestFetchedAt() != lastSnapshotAt;
		if (newSnapshot || rescanRequested)
		{
			lastSnapshotAt = snapshot.getLatestFetchedAt();
			rescanRequested = false;
			if (newSnapshot)
			{
				lastPublishAt = System.currentTimeMillis();
			}

			// Scanning happens here, on the game thread, because the free to
			// play check asks the client itself which items are members only.
			rescan(snapshot);
		}

		if (candidates.isEmpty())
		{
			setStatus("No flip clears the filters");
		}
		else
		{
			setStatus("Advising - " + candidates.size() + " candidate(s)");
		}
	}

	/**
	 * Applies the filters from the window and rescans straight away so the
	 * table reflects the new settings without waiting for the next refresh.
	 */
	void applySettings(FlipSettings newSettings)
	{
		settings = newSettings;
		lastScanAt = 0L;

		// Called from the window's event thread, so only ask for a rescan. The
		// next game tick rebuilds the list on the thread that can read item
		// definitions safely.
		rescanRequested = true;

		GePriceClient client = priceClient;
		if (client != null)
		{
			client.refreshAsync(this::publish);
		}

		logLine("Filters: capital " + formatGp(newSettings.getStartingCapital())
			+ ", min profit " + formatGp(newSettings.getMinProfitPerItem())
			+ ", min ROI " + String.format("%.2f%%", newSettings.getMinRoiPercent())
			+ ", min volume " + formatGp(newSettings.getMinDailyVolume())
			+ (newSettings.isFreeToPlayOnly() ? ", free to play only" : ", all items"));
	}

	List<FlipCandidate> getCandidates()
	{
		return candidates;
	}

	FlipSettings getSettings()
	{
		return settings;
	}

	long getLastPublishAt()
	{
		return lastPublishAt;
	}

	/**
	 * Records a message from the window in the same log the overlay shows, so
	 * the on-screen history and the log file stay in sync.
	 */
	void note(String message)
	{
		if (message != null && !message.isEmpty())
		{
			logLine(message);
		}
	}

	List<String> drainLog()
	{
		synchronized (logLock)
		{
			List<String> lines = new ArrayList<>(log);
			log.clear();
			return lines;
		}
	}

	private void publish()
	{
		GePriceClient client = priceClient;
		if (client == null)
		{
			return;
		}

		lastPublishAt = System.currentTimeMillis();

		String error = client.getLastError();
		if (error != null && !error.isEmpty() && !error.equals(lastError))
		{
			lastError = error;
			logLine("Price API: " + error);
		}
	}

	private void rescan(MarketSnapshot snapshot)
	{
		if (snapshot == null || snapshot.isEmpty())
		{
			return;
		}

		long now = System.currentTimeMillis();
		if (now - lastScanAt < 2_000L && !candidates.isEmpty())
		{
			return;
		}
		lastScanAt = now;

		candidates = scanner.scan(snapshot, settings, membership);

		GrandExchangeFlipperWindow current = window;
		if (current != null)
		{
			SwingUtilities.invokeLater(() -> current.refresh());
		}
	}

	/**
	 * Asks the client what it thinks of an item, and remembers the answer. The
	 * price API's members flag is only used when the client cannot answer, which
	 * keeps member items out of a free to play list.
	 */
	private boolean isMembersItem(ItemMapping mapping)
	{
		Boolean cached = memberCache.get(mapping.getId());
		if (cached != null)
		{
			return cached;
		}

		boolean members;
		try
		{
			ScriptContext context = getContext();
			ItemComposition composition = context == null
				? null
				: context.getClient().getItemDefinition(mapping.getId());
			members = composition == null ? mapping.isMembers() : composition.isMembers();
		}
		catch (RuntimeException ex)
		{
			members = mapping.isMembers();
		}

		memberCache.put(mapping.getId(), members);
		return members;
	}

	private void showWindow()
	{
		GrandExchangeFlipperWindow current = window;
		if (current == null)
		{
			current = new GrandExchangeFlipperWindow(this);
			window = current;
		}

		current.setState(GrandExchangeFlipperWindow.NORMAL);
		current.setVisible(true);
		current.toFront();
		current.refresh();
	}

	private void logLine(String message)
	{
		synchronized (logLock)
		{
			log.addLast(message);
			while (log.size() > MAX_LOG_LINES)
			{
				log.removeFirst();
			}

			String line = "[" + LOG_TIME.format(LocalDateTime.now()) + "] " + message
				+ System.lineSeparator();
			try
			{
				Files.write(LOG_FILE, line.getBytes(StandardCharsets.UTF_8),
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			}
			catch (IOException ignored)
			{
				// Best effort only.
			}
		}
	}

	@Override
	public List<String> getOverlayLines()
	{
		List<String> lines = new ArrayList<>();
		FlipSettings current = settings;

		lines.add("Capital: " + formatGp(current.getStartingCapital())
			+ (current.isFreeToPlayOnly() ? "  (F2P)" : ""));
		lines.add("Candidates: " + candidates.size());

		if (!candidates.isEmpty())
		{
			FlipCandidate best = candidates.get(0);
			lines.add("Best: " + best.getName() + " x" + best.getQuantity());
			lines.add("  buy " + formatGp(best.getBuyPrice()) + "  sell " + formatGp(best.getSellPrice())
				+ "  +" + formatGp(best.getUnitProfit()) + "/ea");
		}

		return lines;
	}

	/**
	 * Exact gp with thousands separators. The pick card uses this because every
	 * number it shows is a price the player types into the Grand Exchange; an
	 * abbreviated "1.23m" would silently become the wrong offer.
	 */
	static String formatExact(long amount)
	{
		return String.format("%,d", amount);
	}

	static String formatGp(long amount)
	{
		if (amount >= 1_000_000_000L)
		{
			return String.format("%.2fb", amount / 1_000_000_000.0);
		}
		if (amount >= 1_000_000L)
		{
			return String.format("%.2fm", amount / 1_000_000.0);
		}
		if (amount >= 1_000L)
		{
			return String.format("%.1fk", amount / 1_000.0);
		}
		return Long.toString(amount);
	}
}
