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
import java.io.IOException;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Compiles the script sources in-process with the JDK compiler, so a reload
 * never runs Gradle (which cannot rebuild while the client holds the build
 * output open). Output goes to a fresh temporary directory each time, so the
 * classes the running client already loaded are never overwritten.
 */
public final class ScriptCompiler
{
	/** Source path of the scripts package below a source root. */
	public static final String SCRIPTS_PATH = "net/runelite/rsbuddy/scripts";

	private ScriptCompiler()
	{
	}

	/**
	 * Compiles every .java file in the scripts package of {@code sourceRoot}.
	 *
	 * @param sourceRoot the src/main/java directory
	 * @param classpathHints classes whose jar or directory must be on the
	 *                       compile classpath (plugin, RuneLite API, client)
	 * @return the directory holding the compiled classes
	 */
	public static File compile(File sourceRoot, ClassLoader loader, Class<?>... classpathHints)
	{
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null)
		{
			throw new IllegalStateException("[Reload] no Java compiler: the client is running on a JRE. Start it"
				+ " with a JDK (java.home=" + System.getProperty("java.home") + ")");
		}

		File scriptsDir = new File(sourceRoot, SCRIPTS_PATH);
		List<File> sources;
		try (Stream<Path> walk = Files.walk(scriptsDir.toPath()))
		{
			sources = walk.filter(p -> p.toString().endsWith(".java")).map(Path::toFile)
				.collect(Collectors.toList());
		}
		catch (IOException ex)
		{
			throw new IllegalStateException("[Reload] cannot read script sources in " + scriptsDir + ": "
				+ ex.getMessage(), ex);
		}
		if (sources.isEmpty())
		{
			throw new IllegalStateException("[Reload] no script sources in " + scriptsDir);
		}

		File output;
		try
		{
			output = Files.createTempDirectory("rsbuddy-reload-").toFile();
		}
		catch (IOException ex)
		{
			throw new IllegalStateException("[Reload] cannot create a temp directory: " + ex.getMessage(), ex);
		}
		output.deleteOnExit();

		String classpath = buildClasspath(loader, classpathHints);
		List<String> options = new ArrayList<>(Arrays.asList(
			"-d", output.getAbsolutePath(),
			"-classpath", classpath,
			// Shared helpers come from the running plugin, not from source:
			// only scripts are recompiled and swapped.
			"-implicit:none",
			"-proc:none",
			"-g",
			"-nowarn",
			"-encoding", "UTF-8"));

		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		StringWriter out = new StringWriter();
		try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT,
			StandardCharsets.UTF_8))
		{
			Iterable<? extends JavaFileObject> units = files.getJavaFileObjectsFromFiles(sources);
			Boolean ok = compiler.getTask(out, files, diagnostics, options, null, units).call();
			if (!Boolean.TRUE.equals(ok))
			{
				StringBuilder errors = new StringBuilder();
				int count = 0;
				for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics())
				{
					if (d.getKind() != Diagnostic.Kind.ERROR)
					{
						continue;
					}
					if (count++ < 3)
					{
						String file = d.getSource() == null ? "?" : new File(d.getSource().getName()).getName();
						errors.append(file).append(':').append(d.getLineNumber()).append(' ')
							.append(d.getMessage(Locale.ROOT)).append('\n');
					}
				}
				throw new CompileException("[Reload] compile failed (" + count + " error(s)):\n" + errors
					+ out, count);
			}
		}
		catch (IOException ex)
		{
			throw new IllegalStateException("[Reload] compiler I/O error: " + ex.getMessage(), ex);
		}
		return output;
	}

	private static String buildClasspath(ClassLoader loader, Class<?>... hints)
	{
		Set<String> entries = new LinkedHashSet<>();
		for (Class<?> hint : hints)
		{
			addCodeSource(entries, hint);
		}
		for (ClassLoader l = loader; l != null; l = l.getParent())
		{
			if (l instanceof URLClassLoader)
			{
				for (URL url : ((URLClassLoader) l).getURLs())
				{
					addUrl(entries, url);
				}
			}
		}
		String javaClassPath = System.getProperty("java.class.path", "");
		for (String part : javaClassPath.split(File.pathSeparator))
		{
			if (!part.isEmpty())
			{
				entries.add(part);
			}
		}
		return String.join(File.pathSeparator, entries);
	}

	private static void addCodeSource(Set<String> entries, Class<?> type)
	{
		try
		{
			if (type.getProtectionDomain().getCodeSource() != null)
			{
				addUrl(entries, type.getProtectionDomain().getCodeSource().getLocation());
			}
		}
		catch (SecurityException ignored)
		{
		}
	}

	private static void addUrl(Set<String> entries, URL url)
	{
		if (url == null || !"file".equals(url.getProtocol()))
		{
			return;
		}
		try
		{
			entries.add(new File(url.toURI()).getAbsolutePath());
		}
		catch (URISyntaxException | IllegalArgumentException ignored)
		{
		}
	}

	/** Thrown when the scripts do not compile; the message holds the first errors. */
	public static final class CompileException extends IllegalStateException
	{
		private final int errorCount;

		CompileException(String message, int errorCount)
		{
			super(message);
			this.errorCount = errorCount;
		}

		public int getErrorCount()
		{
			return errorCount;
		}
	}
}
