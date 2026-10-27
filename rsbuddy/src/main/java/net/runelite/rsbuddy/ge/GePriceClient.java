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
package net.runelite.rsbuddy.ge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads Grand Exchange prices from the OSRS Wiki real-time price API.
 * <p>
 * Only four endpoints are used: {@code mapping}, {@code latest}, {@code 1h} and
 * {@code 24h}. Responses are cached with short time-to-live values and fetched
 * on a single background thread, so the Swing window and the script never block
 * on the network and the API is not polled harder than it needs to be.
 */
public final class GePriceClient implements AutoCloseable
{
	private static final String BASE_URL = "https://prices.runescape.wiki/api/v2/osrs";
	private static final String USER_AGENT = "rsbuddy-ge-flipper/1.0 (RuneLite plugin)";

	private static final int CONNECT_TIMEOUT_MS = 15_000;
	private static final int READ_TIMEOUT_MS = 30_000;

	private static final long LATEST_TTL_MS = 15_000L;
	private static final long STATS_TTL_MS = 120_000L;
	private static final long MAPPING_TTL_MS = 6L * 60L * 60L * 1000L;

	private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable ->
	{
		Thread thread = new Thread(runnable, "rsbuddy-ge-prices");
		thread.setDaemon(true);
		return thread;
	});

	private final Object lock = new Object();
	private final AtomicBoolean refreshing = new AtomicBoolean();

	private volatile Map<Integer, ItemMapping> mapping = Collections.emptyMap();
	private volatile Map<Integer, ItemPrice> latest = Collections.emptyMap();
	private volatile Map<Integer, ItemStats> hourly = Collections.emptyMap();
	private volatile Map<Integer, ItemStats> daily = Collections.emptyMap();

	private volatile long mappingAt;
	private volatile long latestAt;
	private volatile long hourlyAt;
	private volatile long dailyAt;

	private volatile MarketSnapshot snapshot = MarketSnapshot.EMPTY;
	private volatile String lastError = "";

	/**
	 * @return the newest snapshot, never null
	 */
	public MarketSnapshot getSnapshot()
	{
		return snapshot;
	}

	/**
	 * @return a human readable description of the last failed fetch, or an empty
	 * string when everything has been succeeding
	 */
	public String getLastError()
	{
		return lastError;
	}

	public boolean isRefreshing()
	{
		return refreshing.get();
	}

	/**
	 * Starts a refresh on the background thread and runs {@code callback} when it
	 * finishes. Concurrent calls are coalesced: while a refresh is in flight the
	 * callback is simply ignored.
	 */
	public void refreshAsync(Runnable callback)
	{
		if (!refreshing.compareAndSet(false, true))
		{
			return;
		}

		executor.execute(() ->
		{
			try
			{
				refreshNow();
			}
			catch (Exception ex)
			{
				lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
			}
			finally
			{
				refreshing.set(false);
			}

			if (callback != null)
			{
				callback.run();
			}
		});
	}

	/**
	 * Fetches whichever endpoints have expired. Safe to call from any thread;
	 * all HTTP work happens while holding a private lock, so the public snapshot
	 * is only ever replaced with a complete set of maps.
	 */
	public void refreshNow() throws IOException
	{
		synchronized (lock)
		{
			long now = System.currentTimeMillis();
			StringBuilder errors = new StringBuilder();

			if (mapping.isEmpty() || now - mappingAt > MAPPING_TTL_MS)
			{
				mappingAt = attempt(errors, "mapping", this::fetchMapping, value -> mapping = value, mappingAt);
			}

			if (hourly.isEmpty() || now - hourlyAt > STATS_TTL_MS)
			{
				hourlyAt = attempt(errors, "1h", () -> fetchStats("/1h"), value -> hourly = value, hourlyAt);
			}

			if (daily.isEmpty() || now - dailyAt > STATS_TTL_MS)
			{
				dailyAt = attempt(errors, "24h", () -> fetchStats("/24h"), value -> daily = value, dailyAt);
			}

			if (latest.isEmpty() || now - latestAt > LATEST_TTL_MS)
			{
				latestAt = attempt(errors, "latest", this::fetchLatest, value -> latest = value, latestAt);
			}

			snapshot = new MarketSnapshot(mapping, latest, hourly, daily, System.currentTimeMillis(), latestAt);
			lastError = errors.toString();

			if (!errors.toString().isEmpty() && latest.isEmpty() && mapping.isEmpty())
			{
				throw new IOException(errors.toString());
			}
		}
	}

	private interface Fetcher<T>
	{
		Map<Integer, T> fetch() throws IOException;
	}

	private interface Assigner<T>
	{
		void assign(Map<Integer, T> value);
	}

	/**
	 * Runs one fetch, keeping the previous timestamp on failure so a transient
	 * error does not turn every subsequent tick into another HTTP request.
	 *
	 * @return the timestamp to store for this endpoint
	 */
	private <T> long attempt(StringBuilder errors, String name, Fetcher<T> fetcher,
		Assigner<T> assigner, long previousTimestamp) throws IOException
	{
		try
		{
			assigner.assign(fetcher.fetch());
			return System.currentTimeMillis();
		}
		catch (IOException ex)
		{
			if (errors.length() > 0)
			{
				errors.append("; ");
			}
			errors.append(name).append(": ").append(ex.getMessage());
			return previousTimestamp;
		}
	}

	private Map<Integer, ItemMapping> fetchMapping() throws IOException
	{
		JsonElement root = fetchJson("/mapping");
		if (!root.isJsonArray())
		{
			throw new IOException("unexpected mapping payload");
		}

		Map<Integer, ItemMapping> result = new HashMap<>(8_192);
		for (JsonElement element : root.getAsJsonArray())
		{
			if (!element.isJsonObject())
			{
				continue;
			}

			JsonObject object = element.getAsJsonObject();
			int id = intOr(object, "id", -1);
			if (id <= 0)
			{
				continue;
			}

			result.put(id, new ItemMapping(
				id,
				stringOr(object, "name", null),
				stringOr(object, "examine", null),
				boolOr(object, "members", false),
				intOr(object, "limit", 0),
				intOr(object, "highalch", 0),
				intOr(object, "lowalch", 0),
				intOr(object, "value", 0)));
		}

		if (result.isEmpty())
		{
			throw new IOException("mapping payload was empty");
		}

		return result;
	}

	private Map<Integer, ItemPrice> fetchLatest() throws IOException
	{
		JsonObject data = fetchData("/latest");
		Map<Integer, ItemPrice> result = new HashMap<>(data.size() * 2);

		for (Map.Entry<String, JsonElement> entry : data.entrySet())
		{
			int id = parseId(entry.getKey());
			if (id <= 0 || !entry.getValue().isJsonObject())
			{
				continue;
			}

			JsonObject object = entry.getValue().getAsJsonObject();
			result.put(id, new ItemPrice(
				id,
				intOr(object, "high", 0),
				longOr(object, "highTime", 0L),
				intOr(object, "low", 0),
				longOr(object, "lowTime", 0L)));
		}

		return result;
	}

	private Map<Integer, ItemStats> fetchStats(String path) throws IOException
	{
		JsonObject data = fetchData(path);
		Map<Integer, ItemStats> result = new HashMap<>(data.size() * 2);

		for (Map.Entry<String, JsonElement> entry : data.entrySet())
		{
			int id = parseId(entry.getKey());
			if (id <= 0 || !entry.getValue().isJsonObject())
			{
				continue;
			}

			JsonObject object = entry.getValue().getAsJsonObject();
			result.put(id, new ItemStats(
				id,
				intOr(object, "avgHighPrice", 0),
				intOr(object, "avgLowPrice", 0),
				longOr(object, "highPriceVolume", 0L),
				longOr(object, "lowPriceVolume", 0L)));
		}

		return result;
	}

	private JsonObject fetchData(String path) throws IOException
	{
		JsonElement root = fetchJson(path);
		if (!root.isJsonObject())
		{
			throw new IOException("unexpected payload for " + path);
		}

		JsonElement data = root.getAsJsonObject().get("data");
		if (data == null || !data.isJsonObject())
		{
			throw new IOException("missing data for " + path);
		}

		return data.getAsJsonObject();
	}

	private JsonElement fetchJson(String path) throws IOException
	{
		HttpURLConnection connection = null;
		try
		{
			URL url = new URL(BASE_URL + path);
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
			connection.setReadTimeout(READ_TIMEOUT_MS);
			connection.setRequestProperty("User-Agent", USER_AGENT);
			connection.setRequestProperty("Accept", "application/json");

			int status = connection.getResponseCode();
			if (status != HttpURLConnection.HTTP_OK)
			{
				throw new IOException(path + " returned HTTP " + status);
			}

			StringBuilder body = new StringBuilder(64 * 1024);
			try (InputStream stream = connection.getInputStream();
				BufferedReader reader = new BufferedReader(
					new InputStreamReader(stream, StandardCharsets.UTF_8)))
			{
				char[] buffer = new char[16 * 1024];
				int read;
				while ((read = reader.read(buffer)) != -1)
				{
					body.append(buffer, 0, read);
				}
			}

			return new JsonParser().parse(body.toString());
		}
		finally
		{
			if (connection != null)
			{
				connection.disconnect();
			}
		}
	}

	private static int parseId(String value)
	{
		try
		{
			return Integer.parseInt(value);
		}
		catch (NumberFormatException ex)
		{
			return -1;
		}
	}

	private static int intOr(JsonObject object, String key, int fallback)
	{
		JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsInt();
	}

	private static long longOr(JsonObject object, String key, long fallback)
	{
		JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsLong();
	}

	private static boolean boolOr(JsonObject object, String key, boolean fallback)
	{
		JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsBoolean();
	}

	private static String stringOr(JsonObject object, String key, String fallback)
	{
		JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsString();
	}

	@Override
	public void close()
	{
		executor.shutdownNow();
	}
}
