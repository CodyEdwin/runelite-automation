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
		return load(className, classLoader, new Object[0]);
	}

	/**
	 * Loads a script and calls the constructor matching the supplied
	 * arguments. This allows reloaded scripts to be created with the same
	 * options as the built-in registrations.
	 */
	public static Script load(String className, ClassLoader classLoader, Object... arguments)
		throws ScriptLoadException
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
			Constructor<?> constructor = findConstructor(type, arguments);
			constructor.setAccessible(true);
			return (Script) constructor.newInstance(arguments);
		}
		catch (NoSuchMethodException ex)
		{
			throw new ScriptLoadException(name + " has no constructor matching "
				+ describeArguments(arguments), ex);
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

	private static Constructor<?> findConstructor(Class<?> type, Object[] arguments)
		throws NoSuchMethodException
	{
		if (arguments.length == 0)
		{
			return type.getDeclaredConstructor();
		}

		for (Constructor<?> constructor : type.getDeclaredConstructors())
		{
			Class<?>[] parameterTypes = constructor.getParameterTypes();
			if (parameterTypes.length != arguments.length)
			{
				continue;
			}

			boolean matches = true;
			for (int i = 0; i < parameterTypes.length; i++)
			{
				if (!accepts(parameterTypes[i], arguments[i]))
				{
					matches = false;
					break;
				}
			}

			if (matches)
			{
				return constructor;
			}
		}

		throw new NoSuchMethodException();
	}

	private static boolean accepts(Class<?> parameterType, Object argument)
	{
		if (argument == null)
		{
			return !parameterType.isPrimitive();
		}

		Class<?> argumentType = argument.getClass();
		if (!parameterType.isPrimitive())
		{
			return parameterType.isAssignableFrom(argumentType);
		}

		if (parameterType == boolean.class)
		{
			return argumentType == Boolean.class;
		}
		if (parameterType == byte.class)
		{
			return argumentType == Byte.class;
		}
		if (parameterType == short.class)
		{
			return argumentType == Short.class || argumentType == Byte.class;
		}
		if (parameterType == int.class)
		{
			return argumentType == Integer.class || argumentType == Short.class
				|| argumentType == Byte.class;
		}
		if (parameterType == long.class)
		{
			return argumentType == Long.class || argumentType == Integer.class
				|| argumentType == Short.class || argumentType == Byte.class;
		}
		if (parameterType == float.class)
		{
			return argumentType == Float.class || argumentType == Long.class
				|| argumentType == Integer.class || argumentType == Short.class
				|| argumentType == Byte.class;
		}
		if (parameterType == double.class)
		{
			return argumentType == Double.class || argumentType == Float.class
				|| argumentType == Long.class || argumentType == Integer.class
				|| argumentType == Short.class || argumentType == Byte.class;
		}
		if (parameterType == char.class)
		{
			return argumentType == Character.class;
		}

		return false;
	}

	private static String describeArguments(Object[] arguments)
	{
		if (arguments.length == 0)
		{
			return "()";
		}

		StringBuilder description = new StringBuilder("(");
		for (int i = 0; i < arguments.length; i++)
		{
			if (i > 0)
			{
				description.append(", ");
			}

			description.append(arguments[i] == null ? "null" : arguments[i].getClass().getSimpleName());
		}

		return description.append(')').toString();
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
