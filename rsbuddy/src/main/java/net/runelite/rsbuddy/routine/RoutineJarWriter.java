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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Packages one compiled routine into a jar that {@link JarRoutineLoader}
 * can load: the routine's class, its nested/anonymous classes
 * ({@code Name$*.class}) and the {@code META-INF/services} file naming it.
 * No Gradle needed; used by the panel's "Export as jar" button.
 */
public final class RoutineJarWriter
{
	private RoutineJarWriter()
	{
	}

	/**
	 * Writes {@code jar} from the classes under {@code classesRoot}.
	 *
	 * @param classesRoot output folder of {@link RoutineCompiler#compile} (or build/classes/java/main)
	 * @param className   fully qualified routine class
	 * @param jar         target jar; written to a temp file first, then moved into place
	 * @return the number of class files packaged
	 */
	public static int write(File classesRoot, String className, File jar) throws IOException
	{
		String path = className.replace('.', '/');
		Path root = classesRoot.toPath();
		Path main = root.resolve(path + ".class");
		if (!Files.isRegularFile(main))
		{
			throw new IOException("No compiled class for " + className + " in " + classesRoot);
		}

		String prefix = main.getFileName().toString().replace(".class", "$");
		List<Path> classes = new ArrayList<>();
		classes.add(main);
		try (Stream<Path> siblings = Files.list(main.getParent()))
		{
			classes.addAll(siblings
				.filter(p -> p.getFileName().toString().startsWith(prefix) && p.toString().endsWith(".class"))
				.sorted().collect(Collectors.toList()));
		}

		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		manifest.getMainAttributes().putValue("RSBuddy-Routine", className);

		File parent = jar.getAbsoluteFile().getParentFile();
		Files.createDirectories(parent.toPath());
		Path temp = Files.createTempFile(parent.toPath(), ".export-", ".tmp");
		try (OutputStream out = Files.newOutputStream(temp); JarOutputStream jarOut = new JarOutputStream(out, manifest))
		{
			jarOut.putNextEntry(new JarEntry("META-INF/services/"));
			jarOut.closeEntry();
			jarOut.putNextEntry(new JarEntry("META-INF/services/" + Routine.class.getName()));
			jarOut.write((className + "\n").getBytes(StandardCharsets.UTF_8));
			jarOut.closeEntry();
			for (Path file : classes)
			{
				jarOut.putNextEntry(new JarEntry(root.relativize(file).toString().replace(File.separatorChar, '/')));
				Files.copy(file, jarOut);
				jarOut.closeEntry();
			}
		}
		catch (IOException ex)
		{
			Files.deleteIfExists(temp);
			throw ex;
		}
		Files.move(temp, jar.toPath(), StandardCopyOption.REPLACE_EXISTING);
		return classes.size();
	}
}
