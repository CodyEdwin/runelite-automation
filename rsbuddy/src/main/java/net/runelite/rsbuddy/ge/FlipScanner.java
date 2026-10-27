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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns a {@link MarketSnapshot} into a ranked list of safe flips.
 * <p>
 * The scanner is deliberately picky. It only keeps items with a live spread, a
 * minimum amount of two-sided volume, prices that agree with the 24 hour
 * averages, fresh quotes, and a sell price that clears the buy price plus tax
 * plus the configured profit. Anything that fails a rule is dropped instead of
 * being shown with a warning, because a candidate list is a list of things the
 * script is allowed to act on.
 */
public final class FlipScanner
{
	private static final Comparator<FlipCandidate> BY_TOTAL_PROFIT =
		Comparator.comparingLong(FlipCandidate::getTotalProfit).reversed()
			.thenComparing(Comparator.comparingDouble(FlipCandidate::getRoiPercent).reversed());

	/**
	 * Membership check that can consult the game client instead of the price
	 * API. The API flag is a good hint but not a promise, and showing a member
	 * item to a free to play player wastes a trip to the Grand Exchange.
	 */
	public interface Membership
	{
		boolean isMembers(ItemMapping mapping);
	}

	public List<FlipCandidate> scan(MarketSnapshot snapshot, FlipSettings settings)
	{
		return scan(snapshot, settings, null);
	}

	/**
	 * @param membership authoritative membership lookup, or null to trust the
	 *                   price API mapping
	 */
	public List<FlipCandidate> scan(MarketSnapshot snapshot, FlipSettings settings,
		Membership membership)
	{
		List<FlipCandidate> candidates = new ArrayList<>();

		if (snapshot == null || snapshot.isEmpty())
		{
			return candidates;
		}

		long nowSeconds = System.currentTimeMillis() / 1000L;

		for (ItemPrice price : snapshot.getLatest().values())
		{
			FlipCandidate candidate = evaluate(snapshot, settings, membership, price, nowSeconds);
			if (candidate != null)
			{
				candidates.add(candidate);
			}
		}

		candidates.sort(BY_TOTAL_PROFIT);
		return candidates;
	}

	private FlipCandidate evaluate(MarketSnapshot snapshot, FlipSettings settings,
		Membership membership, ItemPrice price, long nowSeconds)
	{
		if (price == null || !price.isComplete() || price.getHigh() <= price.getLow())
		{
			return null;
		}

		ItemMapping mapping = snapshot.getMapping(price.getItemId());
		ItemStats daily = snapshot.getDaily(price.getItemId());
		ItemStats hourly = snapshot.getHourly(price.getItemId());

		if (mapping == null || daily == null || hourly == null
			|| !daily.hasPrices() || !hourly.hasPrices())
		{
			return null;
		}

		if (mapping.getBuyLimit() <= 0)
		{
			return null;
		}

		if (settings.isMembersItemsOnly() && !mapping.isMembers())
		{
			return null;
		}

		// Quotes must be recent: a "spread" between two stale trades is not a
		// spread you can actually trade into.
		if (nowSeconds - price.getLowTime() > settings.getMaxQuoteAgeSeconds()
			|| nowSeconds - price.getHighTime() > settings.getMaxQuoteAgeSeconds())
		{
			return null;
		}

		if (daily.getBalancedVolume() < settings.getMinDailyVolume())
		{
			return null;
		}

		double driftLimit = settings.getMaxPriceDriftPercent() / 100.0;

		// Both sides must sit close to their 24 hour averages, otherwise the
		// item is mid-spike or mid-crash and the "profit" may be an artefact.
		if (drift(daily.getAvgLowPrice(), price.getLow()) > driftLimit
			|| drift(daily.getAvgHighPrice(), price.getHigh()) > driftLimit)
		{
			return null;
		}

		// A recent collapse on the buy side means sellers are dumping; do not
		// catch that knife.
		if (hourly.getAvgLowPrice() < daily.getAvgLowPrice() * (1.0 - driftLimit))
		{
			return null;
		}

		long buyPrice = (long) price.getLow() + settings.getBuyOffset();
		long sellPrice = (long) price.getHigh() + settings.getSellOffset();

		buyPrice = Math.max(1L, Math.min(buyPrice, price.getHigh()));
		sellPrice = Math.max(1L, Math.min(sellPrice, price.getHigh()));

		if (sellPrice <= buyPrice)
		{
			return null;
		}

		long sellFloor = GeTax.sellFloor(buyPrice, settings.getMinProfitPerItem());
		if (sellPrice < sellFloor)
		{
			return null;
		}

		long unitProfit = GeTax.netProceeds(sellPrice) - buyPrice;
		long requiredProfit = Math.max(settings.getMinProfitPerItem(),
			(long) Math.ceil(buyPrice * settings.getMinRoiPercent() / 100.0));

		if (unitProfit < requiredProfit)
		{
			return null;
		}

		long spendCap = settings.getMaxSpendPerItem() > 0
			? settings.getMaxSpendPerItem()
			: settings.getStartingCapital();

		if (settings.getStartingCapital() > 0 && buyPrice > settings.getStartingCapital())
		{
			return null;
		}

		long affordable = spendCap / buyPrice;
		long volumeShare = Math.max(1L, daily.getBalancedVolume() / 10L);
		long quantity = Math.min(mapping.getBuyLimit(), Math.min(affordable, volumeShare));

		if (quantity < 1)
		{
			return null;
		}

		// Checked last on purpose: a client lookup per item would be wasteful,
		// and only a handful of items survive the numeric filters.
		if (settings.isFreeToPlayOnly() && isMember(mapping, membership))
		{
			return null;
		}

		double roi = 100.0 * unitProfit / buyPrice;

		return new FlipCandidate(
			price.getItemId(),
			mapping.getName(),
			(int) buyPrice,
			(int) sellPrice,
			GeTax.fee(sellPrice),
			unitProfit,
			roi,
			daily.getBalancedVolume(),
			hourly.getBalancedVolume(),
			mapping.getBuyLimit(),
			(int) quantity,
			GeTax.sellFloor(buyPrice, 0),
			sellFloor);
	}

	private static boolean isMember(ItemMapping mapping, Membership membership)
	{
		if (membership == null)
		{
			return mapping.isMembers();
		}

		try
		{
			return membership.isMembers(mapping);
		}
		catch (RuntimeException ex)
		{
			return mapping.isMembers();
		}
	}

	private static double drift(int average, int latest)
	{
		if (average <= 0)
		{
			return Double.MAX_VALUE;
		}

		return Math.abs(latest - average) / (double) average;
	}
}
