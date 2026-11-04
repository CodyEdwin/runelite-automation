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

	/**
	 * Replaces a registered script, stopping it first if it is currently
	 * running. Used when a script has been reloaded from newly compiled
	 * classes.
	 */
	public synchronized void replace(Script oldScript, Script newScript)
	{
		if (oldScript == null || newScript == null)
		{
			return;
		}

		int index = scripts.indexOf(oldScript);
		if (index >= 0)
		{
			scripts.set(index, newScript);
		}
		else if (!scripts.contains(newScript))
		{
			scripts.add(newScript);
		}

		if (activeScript == oldScript)
		{
			stop();
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

		// OS hardware input only: refuse to start rather than fall back to
		// Java input when no backend works (re-detects if the cached one failed).
		if (!context.getInput().ensureHardwareInput())
		{
			String reason = context.getInput().getBackendStatus();
			log.warn("[Input] not starting {}: {}", script.getName(), reason);
			script.setStatus("Error: [Input] no hardware input - " + reason);
			return;
		}

		activeScript = script;
		context.getInput().setGroundItemPickupFilter(script.getGroundItemPickupFilter());
		context.getInput().setFastMouse(script.useFastMouse());
		// Real visible clicks only for every script. The GE flipper is being
		// rebuilt as an input-free window and is left as it was.
		boolean exempt = "net.runelite.rsbuddy.scripts.GrandExchangeFlipperScript".equals(script.getClass().getName());
		context.getInput().setPhysicalOnly(!exempt);

		try
		{
			script.start(context);
		}
		catch (Throwable ex)
		{
			log.error("Unable to start script {}", script.getName(), ex);
			activeScript = null;
			context.getInput().setGroundItemPickupFilter(null);
			context.getInput().stopPileTracking();
			context.getInput().setFastMouse(false);
			context.getInput().setPhysicalOnly(false);
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
		context.getInput().setGroundItemPickupFilter(null);
		context.getInput().stopPileTracking();
		context.getInput().setFastMouse(false);
		context.getInput().setPhysicalOnly(false);

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

	/** Client thread, every frame: forwards to the active script's onClientTick. */
	public synchronized void onClientTick()
	{
		Script script = activeScript;
		if (script == null)
		{
			return;
		}
		try
		{
			script.clientTick();
		}
		catch (Throwable ex)
		{
			log.warn("Script {} client tick failed: {}", script.getName(), describeFailure(ex), ex);
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
			log.error("Script {} failed: {}", script.getName(), describeFailure(ex), ex);
			activeScript = null;
			context.getInput().setGroundItemPickupFilter(null);
			context.getInput().stopPileTracking();
			context.getInput().setFastMouse(false);
			context.getInput().setPhysicalOnly(false);

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

	/** One line: the exception, its root cause and where it was thrown. */
	static String describeFailure(Throwable ex)
	{
		Throwable root = ex;
		while (root.getCause() != null && root.getCause() != root)
		{
			root = root.getCause();
		}
		StringBuilder line = new StringBuilder(ex.toString());
		if (root != ex)
		{
			line.append(" caused by ").append(root);
		}
		StackTraceElement[] trace = ex.getStackTrace();
		if (trace.length > 0)
		{
			line.append(" at ").append(trace[0]);
		}
		return line.toString().replace('\n', ' ');
	}
}
