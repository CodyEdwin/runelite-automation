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
package net.runelite.rsbuddy.script;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the currently running script and dispatches game ticks to it.
 * <p>
 * Lifecycle methods are synchronized so the panel (EDT) and game thread can
 * start and stop scripts safely.
 */
public final class ScriptManager
{
	private static final Logger log = LoggerFactory.getLogger(ScriptManager.class);

	private final ScriptContext context;
	private final List<Script> scripts = new ArrayList<>();

	private Script activeScript;

	public ScriptManager(ScriptContext context)
	{
		this.context = context;
	}

	public synchronized void register(Script script)
	{
		if (script != null && !scripts.contains(script))
		{
			scripts.add(script);
		}
	}

	public synchronized List<Script> getScripts()
	{
		return Collections.unmodifiableList(new ArrayList<>(scripts));
	}

	public synchronized Script getActiveScript()
	{
		return activeScript;
	}

	public synchronized boolean isRunning()
	{
		return activeScript != null;
	}

	public synchronized String getStatus()
	{
		return activeScript == null ? "Idle" : activeScript.getStatus();
	}

	public synchronized void start(Script script)
	{
		if (script == null)
		{
			return;
		}

		if (activeScript != null)
		{
			stop();
		}

		activeScript = script;

		try
		{
			script.start(context);
		}
		catch (Throwable ex)
		{
			log.error("Unable to start script {}", script.getName(), ex);
			activeScript = null;
			script.setStatus("Error: " + ex.getMessage());

			try
			{
				script.stop();
			}
			catch (Throwable stopEx)
			{
				log.warn("Unable to stop failed script {}", script.getName(), stopEx);
			}
		}
	}

	public synchronized void stop()
	{
		Script script = activeScript;
		activeScript = null;

		context.getInput().stopCamera();

		if (script == null)
		{
			return;
		}

		try
		{
			script.stop();
		}
		catch (Throwable ex)
		{
			log.warn("Unable to stop script {}", script.getName(), ex);
			script.setStatus("Error: " + ex.getMessage());
		}
	}

	public synchronized void onGameTick()
	{
		Script script = activeScript;
		if (script == null)
		{
			return;
		}

		try
		{
			script.tick();
		}
		catch (Throwable ex)
		{
			log.error("Script {} failed", script.getName(), ex);
			activeScript = null;

			try
			{
				script.stop();
			}
			catch (Throwable stopEx)
			{
				log.warn("Unable to stop failed script {}", script.getName(), stopEx);
			}

			script.setStatus("Error: " + ex.getMessage());
		}
	}
}
