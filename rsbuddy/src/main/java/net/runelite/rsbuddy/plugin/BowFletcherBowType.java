/*
 * Copyright (c) 2026, reich
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
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
package net.runelite.rsbuddy.plugin;

/**
 * Which unstrung bow the Bow Fletcher makes; the log type follows from it.
 * Names match BowFletcherScript.Bow.
 */
public enum BowFletcherBowType
{
	REGULAR_SHORTBOW("Shortbow (u) - regular logs"),
	REGULAR_LONGBOW("Longbow (u) - regular logs"),
	OAK_SHORTBOW("Oak shortbow (u)"),
	OAK_LONGBOW("Oak longbow (u)"),
	WILLOW_SHORTBOW("Willow shortbow (u)"),
	WILLOW_LONGBOW("Willow longbow (u)"),
	MAPLE_SHORTBOW("Maple shortbow (u)"),
	MAPLE_LONGBOW("Maple longbow (u)"),
	YEW_SHORTBOW("Yew shortbow (u)"),
	YEW_LONGBOW("Yew longbow (u)"),
	MAGIC_SHORTBOW("Magic shortbow (u)"),
	MAGIC_LONGBOW("Magic longbow (u)");

	private final String label;

	BowFletcherBowType(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
