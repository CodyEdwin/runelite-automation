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
 * Immutable flipping configuration. The window builds one of these when the
 * user presses Start, and the running script keeps that instance for its whole
 * life so settings cannot change underneath it mid-trade.
 */
public final class FlipSettings
{
	public static final long DEFAULT_CAPITAL = 1_000_000L;

	private final long startingCapital;
	private final long maxSpendPerItem;
	private final int minProfitPerItem;
	private final double minRoiPercent;
	private final int minDailyVolume;
	private final int maxOffers;
	private final int buyOffset;
	private final int sellOffset;
	private final double maxPriceDriftPercent;
	private final int maxQuoteAgeSeconds;
	private final int offerTimeoutSeconds;
	private final boolean membersItemsOnly;
	private final boolean freeToPlayOnly;
	private final boolean dryRun;

	private FlipSettings(Builder builder)
	{
		this.startingCapital = Math.max(0L, builder.startingCapital);
		this.maxSpendPerItem = Math.max(0L, builder.maxSpendPerItem);
		this.minProfitPerItem = Math.max(0, builder.minProfitPerItem);
		this.minRoiPercent = Math.max(0.0, builder.minRoiPercent);
		this.minDailyVolume = Math.max(0, builder.minDailyVolume);
		this.maxOffers = Math.max(1, Math.min(8, builder.maxOffers));
		this.buyOffset = builder.buyOffset;
		this.sellOffset = Math.min(0, builder.sellOffset);
		this.maxPriceDriftPercent = Math.max(1.0, builder.maxPriceDriftPercent);
		this.maxQuoteAgeSeconds = Math.max(60, builder.maxQuoteAgeSeconds);
		this.offerTimeoutSeconds = Math.max(10, builder.offerTimeoutSeconds);
		this.membersItemsOnly = builder.membersItemsOnly && !builder.freeToPlayOnly;
		this.freeToPlayOnly = builder.freeToPlayOnly;
		this.dryRun = builder.dryRun;
	}

	public static Builder builder()
	{
		return new Builder();
	}

	public static FlipSettings defaults()
	{
		return builder().build();
	}

	public long getStartingCapital()
	{
		return startingCapital;
	}

	/**
	 * @return most gp the script may tie up in a single item, or 0 for "as much
	 * as the capital allows"
	 */
	public long getMaxSpendPerItem()
	{
		return maxSpendPerItem;
	}

	public int getMinProfitPerItem()
	{
		return minProfitPerItem;
	}

	public double getMinRoiPercent()
	{
		return minRoiPercent;
	}

	public int getMinDailyVolume()
	{
		return minDailyVolume;
	}

	public int getMaxOffers()
	{
		return maxOffers;
	}

	public int getBuyOffset()
	{
		return buyOffset;
	}

	public int getSellOffset()
	{
		return sellOffset;
	}

	public double getMaxPriceDriftPercent()
	{
		return maxPriceDriftPercent;
	}

	public int getMaxQuoteAgeSeconds()
	{
		return maxQuoteAgeSeconds;
	}

	public int getOfferTimeoutSeconds()
	{
		return offerTimeoutSeconds;
	}

	public boolean isMembersItemsOnly()
	{
		return membersItemsOnly;
	}

	/**
	 * @return true when member-only items are filtered out, for free to play
	 * accounts that cannot trade them
	 */
	public boolean isFreeToPlayOnly()
	{
		return freeToPlayOnly;
	}

	/**
	 * @return true when the script should only log what it would do instead of
	 * clicking anything
	 */
	public boolean isDryRun()
	{
		return dryRun;
	}

	public static final class Builder
	{
		private long startingCapital = DEFAULT_CAPITAL;
		private long maxSpendPerItem;
		private int minProfitPerItem = 50;
		private double minRoiPercent = 1.0;
		private int minDailyVolume = 250;
		private int maxOffers = 4;
		private int buyOffset = 1;
		private int sellOffset = -1;
		private double maxPriceDriftPercent = 8.0;
		private int maxQuoteAgeSeconds = 1_800;
		private int offerTimeoutSeconds = 300;
		private boolean membersItemsOnly;
		private boolean freeToPlayOnly = true;
		private boolean dryRun = true;

		public Builder startingCapital(long value)
		{
			this.startingCapital = value;
			return this;
		}

		public Builder maxSpendPerItem(long value)
		{
			this.maxSpendPerItem = value;
			return this;
		}

		public Builder minProfitPerItem(int value)
		{
			this.minProfitPerItem = value;
			return this;
		}

		public Builder minRoiPercent(double value)
		{
			this.minRoiPercent = value;
			return this;
		}

		public Builder minDailyVolume(int value)
		{
			this.minDailyVolume = value;
			return this;
		}

		public Builder maxOffers(int value)
		{
			this.maxOffers = value;
			return this;
		}

		public Builder buyOffset(int value)
		{
			this.buyOffset = value;
			return this;
		}

		public Builder sellOffset(int value)
		{
			this.sellOffset = value;
			return this;
		}

		public Builder maxPriceDriftPercent(double value)
		{
			this.maxPriceDriftPercent = value;
			return this;
		}

		public Builder maxQuoteAgeSeconds(int value)
		{
			this.maxQuoteAgeSeconds = value;
			return this;
		}

		public Builder offerTimeoutSeconds(int value)
		{
			this.offerTimeoutSeconds = value;
			return this;
		}

		public Builder membersItemsOnly(boolean value)
		{
			this.membersItemsOnly = value;
			return this;
		}

		/**
		 * Drops member-only items. On by default because most accounts start
		 * free to play; untick it to see everything.
		 */
		public Builder freeToPlayOnly(boolean value)
		{
			this.freeToPlayOnly = value;
			return this;
		}

		public Builder dryRun(boolean value)
		{
			this.dryRun = value;
			return this;
		}

		public FlipSettings build()
		{
			return new FlipSettings(this);
		}
	}
}
