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
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Builds tiny routine jars at test time (javac + {@link RoutineJarWriter})
 * and loads them with {@link JarRoutineLoader}.
 */
public class JarRoutineLoaderTest
{
	private static final String CLASS = "test.jarroutine.TinyRoutine";

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private File classes;
	private File folder;
	private final List<String> events = new ArrayList<>();
	private final JarRoutineLoader.Listener listener = new JarRoutineLoader.Listener()
	{
		@Override
		public void removed(JarRoutineLoader.LoadedJar jar)
		{
			events.add("removed " + jar.getFile().getName());
		}

		@Override
		public void added(JarRoutineLoader.LoadedJar jar)
		{
			events.add("added " + jar.getFile().getName());
		}
	};

	@Before
	public void compileTinyRoutine() throws IOException
	{
		File src = temp.newFolder("src");
		File file = new File(src, "test/jarroutine/TinyRoutine.java");
		file.getParentFile().mkdirs();
		Files.write(file.toPath(), ("package test.jarroutine;\n"
			+ "public class TinyRoutine extends net.runelite.rsbuddy.routine.Routine {\n"
			+ "  public TinyRoutine() { super(\"Tiny\", \"A jar routine\"); }\n"
			+ "  @Override protected void onGameTick() { Runnable r = () -> setStatus(\"tick\"); r.run(); }\n"
			+ "}\n").getBytes(StandardCharsets.UTF_8));
		classes = temp.newFolder("classes");
		folder = temp.newFolder("routines");
		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		int result = javac.run(null, null, null, "-cp", System.getProperty("java.class.path"),
			"-d", classes.getAbsolutePath(), file.getAbsolutePath());
		assertEquals("tiny routine compiles", 0, result);
	}

	@Test
	public void loadsExportedJarThroughServiceLoader() throws Exception
	{
		File jar = new File(folder, "Tiny.jar");
		assertEquals(1, RoutineJarWriter.write(classes, CLASS, jar));

		JarRoutineLoader loader = new JarRoutineLoader(getClass().getClassLoader());
		loader.rescan(folder, listener);

		assertEquals(List.of("added Tiny.jar"), events);
		JarRoutineLoader.LoadedJar loaded = loader.getLoaded().get(0);
		assertEquals(CLASS, loaded.getRoutines().get(0).getName());
		assertNotSame("own loader per jar", getClass().getClassLoader(), loaded.getRoutines().get(0).getClassLoader());
		Routine routine = loaded.create(loaded.getRoutines().get(0));
		assertEquals("Tiny", routine.getName());
		assertTrue("RoutineSetting rendering applies to jar routines too", routine.getRoutineSettings().isEmpty());

		// Unchanged: nothing happens. Removed: reported and unloaded.
		loader.rescan(folder, listener);
		assertEquals(1, events.size());
		assertTrue(jar.delete());
		loader.rescan(folder, listener);
		assertEquals("removed Tiny.jar", events.get(1));
		assertTrue(loader.getLoaded().isEmpty());
	}

	@Test
	public void scansClassesWhenThereIsNoServicesFile() throws Exception
	{
		File jar = new File(folder, "NoServices.jar");
		try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar)))
		{
			out.putNextEntry(new JarEntry("test/jarroutine/TinyRoutine.class"));
			Files.copy(new File(classes, "test/jarroutine/TinyRoutine.class").toPath(), out);
			out.closeEntry();
		}

		JarRoutineLoader loader = new JarRoutineLoader(getClass().getClassLoader());
		loader.rescan(folder, listener);
		assertEquals(CLASS, loader.getLoaded().get(0).getRoutines().get(0).getName());
	}

	@Test
	public void skipsBadJarAndKeepsTheOthers() throws Exception
	{
		Files.write(new File(folder, "Broken.jar").toPath(), "not a jar".getBytes(StandardCharsets.UTF_8));
		RoutineJarWriter.write(classes, CLASS, new File(folder, "Tiny.jar"));

		JarRoutineLoader loader = new JarRoutineLoader(getClass().getClassLoader());
		loader.rescan(folder, listener);
		assertEquals(List.of("added Tiny.jar"), events);
		assertNotNull(loader.getLoaded().get(0));
	}

	@Test
	public void changedJarIsUnloadedThenLoadedAgain() throws Exception
	{
		File jar = new File(folder, "Tiny.jar");
		RoutineJarWriter.write(classes, CLASS, jar);
		JarRoutineLoader loader = new JarRoutineLoader(getClass().getClassLoader());
		loader.rescan(folder, listener);
		ClassLoader first = loader.getLoaded().get(0).getLoader();

		RoutineJarWriter.write(classes, CLASS, jar);
		assertTrue(jar.setLastModified(jar.lastModified() + 5000));
		loader.rescan(folder, listener);
		assertEquals(List.of("added Tiny.jar", "removed Tiny.jar", "added Tiny.jar"), events);
		assertNotSame(first, loader.getLoaded().get(0).getLoader());
	}
}
