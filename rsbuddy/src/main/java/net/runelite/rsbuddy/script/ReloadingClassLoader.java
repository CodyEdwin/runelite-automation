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
import java.util.ArrayList;
import java.util.List;

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

	/**
	 * Loaders that resolve everything outside the scripts package, tried in
	 * order: the plugin's loader first, then the loaders that actually
	 * defined the API/client classes the plugin uses. A sideloaded or
	 * Gradle-run plugin's own loader does not always see every API class.
	 */
	private final List<ClassLoader> delegates = new ArrayList<>();

	public ReloadingClassLoader(File classesRoot, ClassLoader parent) throws MalformedURLException
	{
		this(classesRoot, parent, new ClassLoader[0]);
	}

	public ReloadingClassLoader(File classesRoot, ClassLoader parent, ClassLoader... fallbacks)
		throws MalformedURLException
	{
		super(new URL[]{classesRoot.toURI().toURL()}, parent);
		addDelegate(parent);
		addDelegate(Script.class.getClassLoader());
		for (ClassLoader fallback : fallbacks)
		{
			addDelegate(fallback);
		}
		addDelegate(Thread.currentThread().getContextClassLoader());
	}

	private void addDelegate(ClassLoader loader)
	{
		if (loader != null && loader != this && !delegates.contains(loader))
		{
			delegates.add(loader);
		}
	}

	@Override
	protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException
	{
		if (!name.startsWith(SCRIPT_PACKAGE))
		{
			Class<?> type = loadFromDelegates(name);
			if (resolve)
			{
				resolveClass(type);
			}
			return type;
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
					type = loadFromDelegates(name);
				}
			}

			if (resolve)
			{
				resolveClass(type);
			}

			return type;
		}
	}

	private Class<?> loadFromDelegates(String name) throws ClassNotFoundException
	{
		ClassNotFoundException first = null;
		for (ClassLoader loader : delegates)
		{
			try
			{
				return Class.forName(name, false, loader);
			}
			catch (ClassNotFoundException | NoClassDefFoundError ex)
			{
				if (first == null)
				{
					first = new ClassNotFoundException(name + " not found in " + delegates, ex);
				}
			}
		}
		throw first != null ? first : new ClassNotFoundException(name);
	}
}
