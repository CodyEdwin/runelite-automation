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

/**
 * Aggregated trade volume and average prices for one item over a time window
 * (the wiki {@code 5m}, {@code 1h} or {@code 24h} endpoints).
 */
public final class ItemStats
{
	private final int itemId;
	private final int avgHighPrice;
	private final int avgLowPrice;
	private final long highPriceVolume;
	private final long lowPriceVolume;

	public ItemStats(int itemId, int avgHighPrice, int avgLowPrice, long highPriceVolume, long lowPriceVolume)
	{
		this.itemId = itemId;
		this.avgHighPrice = avgHighPrice;
		this.avgLowPrice = avgLowPrice;
		this.highPriceVolume = highPriceVolume;
		this.lowPriceVolume = lowPriceVolume;
	}

	public int getItemId()
	{
		return itemId;
	}

	public int getAvgHighPrice()
	{
		return avgHighPrice;
	}

	public int getAvgLowPrice()
	{
		return avgLowPrice;
	}

	public long getHighPriceVolume()
	{
		return highPriceVolume;
	}

	public long getLowPriceVolume()
	{
		return lowPriceVolume;
	}

	/**
	 * @return the lower of the two sides, i.e. how many items could realistically
	 * be bought and then resold inside the window
	 */
	public long getBalancedVolume()
	{
		return Math.min(highPriceVolume, lowPriceVolume);
	}

	public boolean hasPrices()
	{
		return avgHighPrice > 0 && avgLowPrice > 0;
	}
}
