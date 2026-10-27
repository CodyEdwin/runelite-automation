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
 * Static item data from the wiki {@code mapping} endpoint.
 */
public final class ItemMapping
{
	private final int id;
	private final String name;
	private final String examine;
	private final boolean members;
	private final int buyLimit;
	private final int highAlch;
	private final int lowAlch;
	private final int value;

	public ItemMapping(int id, String name, String examine, boolean members,
		int buyLimit, int highAlch, int lowAlch, int value)
	{
		this.id = id;
		this.name = name == null ? ("Item " + id) : name;
		this.examine = examine == null ? "" : examine;
		this.members = members;
		this.buyLimit = buyLimit;
		this.highAlch = highAlch;
		this.lowAlch = lowAlch;
		this.value = value;
	}

	public int getId()
	{
		return id;
	}

	public String getName()
	{
		return name;
	}

	public String getExamine()
	{
		return examine;
	}

	public boolean isMembers()
	{
		return members;
	}

	/**
	 * @return the four hour Grand Exchange buy limit, or 0 when unknown
	 */
	public int getBuyLimit()
	{
		return buyLimit;
	}

	public int getHighAlch()
	{
		return highAlch;
	}

	public int getLowAlch()
	{
		return lowAlch;
	}

	public int getValue()
	{
		return value;
	}
}
