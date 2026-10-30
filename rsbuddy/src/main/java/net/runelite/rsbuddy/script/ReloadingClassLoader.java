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

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Loads freshly compiled script classes from disk while sharing the script
 * framework classes with the running plugin.
 * <p>
 * Only classes in the scripts package are loaded child-first. Everything else
 * (Script, ScriptContext, GameInput, the RuneLite API, ...) comes from the
 * plugin's class loader, so reloaded scripts remain compatible with objects
 * that the plugin passes to them.
 */
public final class ReloadingClassLoader extends URLClassLoader
{
	private static final String SCRIPT_PACKAGE = "net.runelite.rsbuddy.scripts.";

	public ReloadingClassLoader(File classesRoot, ClassLoader parent) throws MalformedURLException
	{
		super(new URL[]{classesRoot.toURI().toURL()}, parent);
	}

	@Override
	protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException
	{
		if (!name.startsWith(SCRIPT_PACKAGE))
		{
			return super.loadClass(name, resolve);
		}

		synchronized (getClassLoadingLock(name))
		{
			Class<?> type = findLoadedClass(name);
			if (type == null)
			{
				try
				{
					type = findClass(name);
				}
				catch (ClassNotFoundException ex)
				{
					// The class might only exist in the installed plugin jar.
					type = super.loadClass(name, false);
				}
			}

			if (resolve)
			{
				resolveClass(type);
			}

			return type;
		}
	}
}
