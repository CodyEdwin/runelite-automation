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
 * Grand Exchange sale tax.
 * <p>
 * The live game charges 2% of the sale price, rounded down, capped at
 * 5,000,000 gp per item. A handful of items are exempt, but every calculation
 * here assumes the tax always applies: over-estimating cost is what keeps a
 * flip from quietly turning into a loss.
 */
public final class GeTax
{
	public static final int RATE_PERCENT = 2;
	public static final long CAP = 5_000_000L;

	private GeTax()
	{
	}

	/**
	 * @param salePrice price a single item is listed at
	 * @return gp removed from the proceeds of that sale
	 */
	public static long fee(long salePrice)
	{
		if (salePrice <= 0)
		{
			return 0;
		}

		return Math.min(salePrice * RATE_PERCENT / 100, CAP);
	}

	/**
	 * @return gp actually received after tax
	 */
	public static long netProceeds(long salePrice)
	{
		return salePrice - fee(salePrice);
	}

	/**
	 * Smallest listing price whose after-tax proceeds still cover
	 * {@code buyPrice + minProfit}. Used as a hard floor: an offer is never
	 * created below this number.
	 *
	 * @param buyPrice  price paid per item
	 * @param minProfit required profit per item, may be zero
	 * @return minimum safe listing price, or 0 when there is nothing to cover
	 */
	public static long sellFloor(long buyPrice, long minProfit)
	{
		long target = buyPrice + Math.max(0L, minProfit);
		if (target <= 0)
		{
			return 0;
		}

		if (target >= CAP + CAP)
		{
			// Above the tax cap only the fixed fee applies.
			return target + CAP;
		}

		long price = (long) Math.ceil(target / (1.0 - RATE_PERCENT / 100.0));
		while (netProceeds(price) < target)
		{
			price++;
		}

		return price;
	}
}
