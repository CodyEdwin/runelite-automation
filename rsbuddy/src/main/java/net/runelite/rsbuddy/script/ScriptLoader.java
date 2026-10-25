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

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;

/**
 * Creates scripts from a class name.
 * <p>
 * Custom script classes must extend {@link Script} and have a no-argument
 * constructor. The class is loaded from the plugin's class loader.
 */
public final class ScriptLoader
{
	private ScriptLoader()
	{
	}

	public static Script load(String className, ClassLoader classLoader) throws ScriptLoadException
	{
		if (className == null || className.trim().isEmpty())
		{
			throw new ScriptLoadException("Enter a script class name");
		}

		String name = className.trim();
		Class<?> type;

		try
		{
			type = Class.forName(name, true, classLoader);
		}
		catch (ClassNotFoundException ex)
		{
			throw new ScriptLoadException("Class not found: " + name, ex);
		}
		catch (LinkageError ex)
		{
			throw new ScriptLoadException("Unable to load " + name + ": " + ex.getMessage(), ex);
		}

		if (!Script.class.isAssignableFrom(type))
		{
			throw new ScriptLoadException(name + " does not extend " + Script.class.getName());
		}

		if (Modifier.isAbstract(type.getModifiers()))
		{
			throw new ScriptLoadException(name + " is abstract");
		}

		try
		{
			Constructor<?> constructor = type.getDeclaredConstructor();
			constructor.setAccessible(true);
			return (Script) constructor.newInstance();
		}
		catch (NoSuchMethodException ex)
		{
			throw new ScriptLoadException(name + " needs a no-arg constructor", ex);
		}
		catch (InvocationTargetException ex)
		{
			Throwable cause = ex.getCause() == null ? ex : ex.getCause();
			throw new ScriptLoadException("Constructor failed: " + cause, cause);
		}
		catch (ReflectiveOperationException | SecurityException ex)
		{
			throw new ScriptLoadException("Unable to create " + name + ": " + ex, ex);
		}
	}

	/**
	 * Thrown when a custom script class cannot be loaded.
	 */
	public static final class ScriptLoadException extends Exception
	{
		public ScriptLoadException(String message)
		{
			super(message);
		}

		public ScriptLoadException(String message, Throwable cause)
		{
			super(message, cause);
		}
	}
}
