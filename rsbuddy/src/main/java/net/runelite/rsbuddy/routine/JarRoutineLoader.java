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

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads routines packaged as jars, the way RuneLite loads sideloaded plugins.
 * <p>
 * Every {@code *.jar} in the routines folder gets its own {@link JarClassLoader}
 * whose parent is the plugin's loader, so a jar sees the whole RSBuddy API
 * ({@link Routine}, {@link TaskRoutine}, {@link Actions}, RuneLite) but jars
 * never see each other. Routines are found with {@link ServiceLoader}
 * ({@code META-INF/services/net.runelite.rsbuddy.routine.Routine}); a jar
 * without that file is scanned for concrete {@link Routine} subclasses with a
 * public no-arg constructor.
 * <p>
 * {@link #rescan} compares the folder with the last scan: new jars are
 * reported as added, changed jars (size or modified time) as removed then
 * added, and deleted jars as removed. Removed loaders are closed after the
 * listener has stopped their routines. A jar that fails to load is logged
 * with {@code [JarRoutines]} and skipped; the others still load.
 * <p>
 * Not thread safe: call it from one thread (the panel uses the Swing thread).
 */
public final class JarRoutineLoader
{
	private static final Logger log = LoggerFactory.getLogger(JarRoutineLoader.class);
	private static final String SERVICE = "META-INF/services/" + Routine.class.getName();

	/** Receives the result of a {@link #rescan}. */
	public interface Listener
	{
		/** A jar went away or changed: stop and unregister its routines. Its loader closes afterwards. */
		void removed(LoadedJar jar);

		/** A jar was found or changed: register its routines. */
		void added(LoadedJar jar);
	}

	/** One loaded jar: the file, its loader and the routine classes it provides. */
	public static final class LoadedJar
	{
		private final File file;
		private final long size;
		private final long modified;
		private final JarClassLoader loader;
		private final List<Class<? extends Routine>> routines;

		LoadedJar(File file, JarClassLoader loader, List<Class<? extends Routine>> routines)
		{
			this.file = file;
			this.size = file.length();
			this.modified = file.lastModified();
			this.loader = loader;
			this.routines = Collections.unmodifiableList(routines);
		}

		/** @return the jar file */
		public File getFile()
		{
			return file;
		}

		/** @return the jar's own class loader */
		public ClassLoader getLoader()
		{
			return loader;
		}

		/** @return the routine classes found in the jar */
		public List<Class<? extends Routine>> getRoutines()
		{
			return routines;
		}

		/** @return a fresh routine of {@code type} from this jar's loader */
		public Routine create(Class<? extends Routine> type) throws RoutineLoader.RoutineLoadException
		{
			return RoutineLoader.load(type.getName(), loader);
		}

		boolean unchanged(File current)
		{
			return current.length() == size && current.lastModified() == modified;
		}
	}

	/**
	 * Child-first for classes inside the jar (so a jar can ship a newer copy
	 * of a built-in routine), parent-first for everything else, and always
	 * parent-first for the RSBuddy framework packages so a jar can never
	 * replace {@link Routine}, {@link Actions} or the input layer.
	 */
	static final class JarClassLoader extends URLClassLoader
	{
		JarClassLoader(File jar, ClassLoader parent) throws IOException
		{
			super(new URL[]{jar.toURI().toURL()}, parent);
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException
		{
			synchronized (getClassLoadingLock(name))
			{
				Class<?> type = findLoadedClass(name);
				if (type == null && !isFramework(name)
					&& findResource(name.replace('.', '/') + ".class") != null)
				{
					type = findClass(name);
				}
				if (type == null)
				{
					return super.loadClass(name, resolve);
				}
				if (resolve)
				{
					resolveClass(type);
				}
				return type;
			}
		}

		private static boolean isFramework(String name)
		{
			return name.startsWith("java.") || name.startsWith("net.runelite.api.")
				|| name.startsWith("net.runelite.client.")
				|| (name.startsWith("net.runelite.rsbuddy.") && !name.startsWith("net.runelite.rsbuddy.routines."));
		}
	}

	private final ClassLoader parent;
	private final Map<String, LoadedJar> loaded = new LinkedHashMap<>();

	/** @param parent the plugin's class loader, parent of every jar loader */
	public JarRoutineLoader(ClassLoader parent)
	{
		this.parent = parent;
	}

	/** @return the jars loaded by the last scan */
	public List<LoadedJar> getLoaded()
	{
		return new ArrayList<>(loaded.values());
	}

	/**
	 * Syncs with the jars in {@code folder}. A missing folder is treated as
	 * empty (it is not created here).
	 */
	public void rescan(File folder, Listener listener)
	{
		Map<String, File> present = new LinkedHashMap<>();
		File[] files = folder == null ? null : folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
		if (files != null)
		{
			Arrays.sort(files);
			for (File file : files)
			{
				present.put(file.getAbsolutePath(), file);
			}
		}

		for (String path : new ArrayList<>(loaded.keySet()))
		{
			LoadedJar jar = loaded.get(path);
			File current = present.get(path);
			if (current != null && jar.unchanged(current))
			{
				continue;
			}
			log.info("[JarRoutines] {} {}, unloading {} routine(s)", jar.getFile().getName(),
				current == null ? "removed" : "changed", jar.getRoutines().size());
			loaded.remove(path);
			try
			{
				listener.removed(jar);
			}
			finally
			{
				close(jar);
			}
		}

		for (File file : present.values())
		{
			if (loaded.containsKey(file.getAbsolutePath()))
			{
				continue;
			}
			LoadedJar jar = load(file);
			if (jar == null)
			{
				continue;
			}
			loaded.put(file.getAbsolutePath(), jar);
			listener.added(jar);
		}
	}

	/** Unloads every jar (plugin shutdown). */
	public void closeAll(Listener listener)
	{
		rescan(null, listener);
	}

	/** Loads one jar, or returns null after logging why it was skipped. */
	LoadedJar load(File file)
	{
		JarClassLoader loader;
		try
		{
			loader = new JarClassLoader(file, parent);
		}
		catch (IOException ex)
		{
			log.warn("[JarRoutines] skipping {}: {}", file.getName(), ex.toString());
			return null;
		}

		try
		{
			List<Class<? extends Routine>> routines = discover(file, loader);
			if (routines.isEmpty())
			{
				log.warn("[JarRoutines] skipping {}: no Routine with a public no-arg constructor", file.getName());
				loader.close();
				return null;
			}
			log.info("[JarRoutines] loaded {}: {}", file.getName(), names(routines));
			return new LoadedJar(file, loader, routines);
		}
		catch (Throwable ex)
		{
			log.warn("[JarRoutines] skipping {}: {}", file.getName(), ex.toString());
			try
			{
				loader.close();
			}
			catch (IOException ignored)
			{
				// already failing
			}
			return null;
		}
	}

	private static List<Class<? extends Routine>> discover(File file, JarClassLoader loader) throws IOException
	{
		Set<Class<? extends Routine>> found = new LinkedHashSet<>();
		boolean hasServices = loader.findResource(SERVICE) != null;
		if (hasServices)
		{
			// ServiceLoader also sees the parent's services; keep only this jar's.
			ServiceLoader.load(Routine.class, loader).stream()
				.map(ServiceLoader.Provider::type)
				.filter(type -> type.getClassLoader() == loader)
				.forEach(found::add);
			log.info("[JarRoutines] {}: {} routine(s) from the services file", file.getName(), found.size());
			return new ArrayList<>(found);
		}

		log.info("[JarRoutines] {}: no services file, scanning classes", file.getName());
		try (JarFile jar = new JarFile(file))
		{
			Enumeration<JarEntry> entries = jar.entries();
			while (entries.hasMoreElements())
			{
				String name = entries.nextElement().getName();
				if (!name.endsWith(".class") || name.contains("$") || name.endsWith("module-info.class"))
				{
					continue;
				}
				String className = name.substring(0, name.length() - 6).replace('/', '.');
				try
				{
					Class<?> type = Class.forName(className, false, loader);
					if (isRoutine(type))
					{
						found.add(type.asSubclass(Routine.class));
					}
				}
				catch (ClassNotFoundException | LinkageError ex)
				{
					log.debug("[JarRoutines] {}: cannot inspect {}: {}", file.getName(), className, ex.toString());
				}
			}
		}
		return new ArrayList<>(found);
	}

	private static boolean isRoutine(Class<?> type)
	{
		if (!Routine.class.isAssignableFrom(type) || Modifier.isAbstract(type.getModifiers())
			|| !Modifier.isPublic(type.getModifiers()))
		{
			return false;
		}
		try
		{
			return Modifier.isPublic(type.getConstructor().getModifiers());
		}
		catch (NoSuchMethodException ex)
		{
			return false;
		}
	}

	private static String names(List<Class<? extends Routine>> routines)
	{
		List<String> names = new ArrayList<>();
		for (Class<?> type : routines)
		{
			names.add(type.getName());
		}
		return String.join(", ", names);
	}

	private static void close(LoadedJar jar)
	{
		try
		{
			jar.loader.close();
		}
		catch (IOException ex)
		{
			log.warn("[JarRoutines] unable to close loader for {}: {}", jar.getFile().getName(), ex.toString());
		}
	}
}
