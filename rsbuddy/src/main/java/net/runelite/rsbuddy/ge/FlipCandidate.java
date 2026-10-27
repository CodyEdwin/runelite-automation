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
 * One item the scanner believes can be bought and resold for a profit, with
 * every number already including the sale tax.
 */
public final class FlipCandidate
{
	private final int itemId;
	private final String name;
	private final int buyPrice;
	private final int sellPrice;
	private final long unitTax;
	private final long unitProfit;
	private final double roiPercent;
	private final long dailyVolume;
	private final long hourlyVolume;
	private final int buyLimit;
	private final int quantity;
	private final long breakEvenPrice;
	private final long sellFloor;

	public FlipCandidate(int itemId, String name, int buyPrice, int sellPrice, long unitTax,
		long unitProfit, double roiPercent, long dailyVolume, long hourlyVolume, int buyLimit,
		int quantity, long breakEvenPrice, long sellFloor)
	{
		this.itemId = itemId;
		this.name = name;
		this.buyPrice = buyPrice;
		this.sellPrice = sellPrice;
		this.unitTax = unitTax;
		this.unitProfit = unitProfit;
		this.roiPercent = roiPercent;
		this.dailyVolume = dailyVolume;
		this.hourlyVolume = hourlyVolume;
		this.buyLimit = buyLimit;
		this.quantity = quantity;
		this.breakEvenPrice = breakEvenPrice;
		this.sellFloor = sellFloor;
	}

	public int getItemId()
	{
		return itemId;
	}

	public String getName()
	{
		return name;
	}

	/**
	 * @return price the script will offer per item when buying
	 */
	public int getBuyPrice()
	{
		return buyPrice;
	}

	/**
	 * @return price the script will list the item at once it owns it
	 */
	public int getSellPrice()
	{
		return sellPrice;
	}

	public long getUnitTax()
	{
		return unitTax;
	}

	public long getUnitProfit()
	{
		return unitProfit;
	}

	public double getRoiPercent()
	{
		return roiPercent;
	}

	public long getDailyVolume()
	{
		return dailyVolume;
	}

	public long getHourlyVolume()
	{
		return hourlyVolume;
	}

	public int getBuyLimit()
	{
		return buyLimit;
	}

	/**
	 * @return how many items this flip buys, already capped by capital, the
	 * per-item budget, the buy limit and traded volume
	 */
	public int getQuantity()
	{
		return quantity;
	}

	/**
	 * @return the lowest listing price that returns the buy price after tax
	 */
	public long getBreakEvenPrice()
	{
		return breakEvenPrice;
	}

	/**
	 * @return the listing price the script refuses to go below; includes the
	 * configured minimum profit
	 */
	public long getSellFloor()
	{
		return sellFloor;
	}

	public long getTotalProfit()
	{
		return unitProfit * quantity;
	}

	public long getTotalCost()
	{
		return (long) buyPrice * quantity;
	}

	@Override
	public String toString()
	{
		return name + " x" + quantity + " @ " + buyPrice + " -> " + sellPrice
			+ " (+" + unitProfit + "/ea, " + String.format("%.2f", roiPercent) + "%)";
	}
}
