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
package net.runelite.rsbuddy.routine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Timing and logging module: every routine action goes through
 * {@link #markAction(String)}, which logs the gap since the previous action,
 * so slow spots ("+900ms") are easy to find in the client log.
 * <p>
 * All lines start with the routine's prefix, e.g. {@code [MotherlodeMiner]}.
 * <p>
 * Example: {@code actions.log().markAction("bank open");}
 */
public final class ActionLog
{
	static final Logger LOG = LoggerFactory.getLogger(Actions.class);

	private final String prefix;
	private long lastActionAt;
	private String lastAction = "start";

	ActionLog(String prefix)
	{
		this.prefix = prefix;
	}

	/** @return the log prefix, for example "[MotherlodeMiner]" */
	public String prefix()
	{
		return prefix;
	}

	/**
	 * Logs an action and the time since the previous one:
	 * {@code [Prefix] +120ms click sack (after walk SACK -> 3748,5659)}.
	 *
	 * @param action a short label, for example "click sack"
	 */
	public void markAction(String action)
	{
		long now = System.currentTimeMillis();
		LOG.info("{} +{}ms {} (after {})", prefix, lastActionAt == 0 ? 0 : now - lastActionAt, action, lastAction);
		lastActionAt = now;
		lastAction = action;
	}

	/** Logs an info line with the routine prefix. Same placeholders as SLF4J. */
	public void info(String format, Object... args)
	{
		Object[] all = new Object[args.length + 1];
		all[0] = prefix;
		System.arraycopy(args, 0, all, 1, args.length);
		LOG.info("{} " + format, all);
	}

	void reset()
	{
		lastActionAt = 0;
		lastAction = "start";
	}
}
