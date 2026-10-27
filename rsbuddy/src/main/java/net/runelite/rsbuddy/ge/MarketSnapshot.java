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

import java.util.Collections;
import java.util.Map;

/**
 * Immutable view of the market at one point in time. Instances are swapped
 * wholesale by {@link GePriceClient}, so a scanner can read a consistent set of
 * prices without locking.
 */
public final class MarketSnapshot
{
	public static final MarketSnapshot EMPTY = new MarketSnapshot(
		Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
		Collections.emptyMap(), 0L, 0L);

	private final Map<Integer, ItemMapping> mapping;
	private final Map<Integer, ItemPrice> latest;
	private final Map<Integer, ItemStats> hourly;
	private final Map<Integer, ItemStats> daily;
	private final long fetchedAt;
	private final long latestFetchedAt;

	public MarketSnapshot(Map<Integer, ItemMapping> mapping,
		Map<Integer, ItemPrice> latest,
		Map<Integer, ItemStats> hourly,
		Map<Integer, ItemStats> daily,
		long fetchedAt,
		long latestFetchedAt)
	{
		this.mapping = mapping;
		this.latest = latest;
		this.hourly = hourly;
		this.daily = daily;
		this.fetchedAt = fetchedAt;
		this.latestFetchedAt = latestFetchedAt;
	}

	public Map<Integer, ItemMapping> getMapping()
	{
		return mapping;
	}

	public Map<Integer, ItemPrice> getLatest()
	{
		return latest;
	}

	public Map<Integer, ItemStats> getHourly()
	{
		return hourly;
	}

	public Map<Integer, ItemStats> getDaily()
	{
		return daily;
	}

	public ItemMapping getMapping(int itemId)
	{
		return mapping.get(itemId);
	}

	public ItemPrice getPrice(int itemId)
	{
		return latest.get(itemId);
	}

	public ItemStats getHourly(int itemId)
	{
		return hourly.get(itemId);
	}

	public ItemStats getDaily(int itemId)
	{
		return daily.get(itemId);
	}

	public boolean isEmpty()
	{
		return latest.isEmpty();
	}

	/**
	 * @return epoch millis of the newest successful fetch of any endpoint
	 */
	public long getFetchedAt()
	{
		return fetchedAt;
	}

	/**
	 * @return epoch millis of the newest {@code latest} fetch, which is the only
	 * endpoint that needs to be reasonably fresh
	 */
	public long getLatestFetchedAt()
	{
		return latestFetchedAt;
	}

	public long getAgeMillis()
	{
		return fetchedAt <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() - fetchedAt;
	}
}
