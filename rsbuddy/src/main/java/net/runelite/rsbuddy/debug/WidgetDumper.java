/*
 * Copyright (c) 2026, reich
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
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
package net.runelite.rsbuddy.debug;

import java.awt.Rectangle;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;

/**
 * Writes a readable snapshot of every visible widget to a file, so interface
 * ids, child ids, actions and texts can be inspected without hovering each
 * element in the widget inspector.
 * <p>
 * Snapshots are appended to {@code ~/.runelite/rsbuddy-widgets.txt} together
 * with a label, which makes it easy to capture several screens (offer list,
 * setup, search, collect) one after another.
 */
public final class WidgetDumper
{
	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final int MAX_WIDGETS = 20_000;

	private WidgetDumper()
	{
	}

	public static Path getLogFile()
	{
		return Path.of(System.getProperty("user.home"), ".runelite", "rsbuddy-widgets.txt");
	}

	/**
	 * Appends a snapshot of the currently visible interface to the log file.
	 *
	 * @param label short description of the captured screen
	 * @return number of widgets written
	 */
	public static int snapshot(Client client, String label) throws IOException
	{
		String text = describe(client, label);

		Path file = getLogFile();
		Files.createDirectories(file.getParent());
		Files.writeString(file, text, StandardCharsets.UTF_8,
			StandardOpenOption.CREATE, StandardOpenOption.APPEND);

		return text.lines().filter(line -> line.startsWith("  ")).mapToInt(line -> 1).sum();
	}

	public static String describe(Client client, String label)
	{
		StringBuilder out = new StringBuilder(64 * 1024);
		out.append('\n')
			.append("================================================================")
			.append('\n')
			.append("SNAPSHOT: ").append(label == null || label.isEmpty() ? "(no label)" : label)
			.append("  @ ").append(LocalDateTime.now().format(TIMESTAMP))
			.append('\n');

		Widget[] roots;
		try
		{
			roots = client.getWidgetRoots();
		}
		catch (Throwable ex)
		{
			out.append("getWidgetRoots() failed:\n").append(stackTrace(ex));
			return out.toString();
		}

		if (roots == null)
		{
			out.append("no widget roots\n");
			return out.toString();
		}

		out.append("open roots:");
		for (Widget root : roots)
		{
			if (root != null && !root.isHidden())
			{
				out.append(' ').append(root.getId() >>> 16);
			}
		}
		out.append('\n');

		Set<Widget> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		int written = 0;

		for (Widget root : roots)
		{
			if (root == null || root.isHidden())
			{
				continue;
			}

			written += append(root, out, seen, MAX_WIDGETS - written);
			if (written >= MAX_WIDGETS)
			{
				out.append("... truncated at ").append(MAX_WIDGETS).append(" widgets\n");
				break;
			}
		}

		out.append("total widgets: ").append(written).append('\n');
		return out.toString();
	}

	private static int append(Widget root, StringBuilder out, Set<Widget> seen, int limit)
	{
		int written = 0;
		Deque<Widget> pending = new ArrayDeque<>();
		pending.push(root);

		while (!pending.isEmpty() && written < limit)
		{
			Widget widget = pending.pop();
			if (widget == null || !seen.add(widget) || widget.isHidden())
			{
				continue;
			}

			try
			{
				line(widget, out);
			}
			catch (Throwable ex)
			{
				out.append("  [").append(widget.getId() >>> 16).append(':')
					.append(widget.getId() & 0xFFFF).append("] <error>\n")
					.append(stackTrace(ex));
			}
			written++;
			Widget[] children = widget.getChildren();
			if (children != null)
			{
				for (int i = children.length - 1; i >= 0; i--)
				{
					if (children[i] != null)
					{
						pending.push(children[i]);
					}
				}
			}

			Widget[] dynamic = widget.getDynamicChildren();
			if (dynamic != null)
			{
				for (int i = dynamic.length - 1; i >= 0; i--)
				{
					if (dynamic[i] != null)
					{
						pending.push(dynamic[i]);
					}
				}
			}

			Widget[] staticChildren = widget.getStaticChildren();
			if (staticChildren != null)
			{
				for (int i = staticChildren.length - 1; i >= 0; i--)
				{
					if (staticChildren[i] != null)
					{
						pending.push(staticChildren[i]);
					}
				}
			}

			Widget[] nested = widget.getNestedChildren();
			if (nested != null)
			{
				for (int i = nested.length - 1; i >= 0; i--)
				{
					if (nested[i] != null)
					{
						pending.push(nested[i]);
					}
				}
			}
		}

		return written;
	}

	private static void line(Widget widget, StringBuilder out)
	{
		int id = widget.getId();
		int group = id >>> 16;
		int child = id & 0xFFFF;

		out.append("  ")
			.append('[').append(group).append(':').append(child).append(']')
			.append(" idx=").append(widget.getIndex())
			.append(" type=").append(widget.getType())
			.append(" hidden=").append(widget.isHidden());

		Rectangle bounds = widget.getBounds();
		if (bounds != null)
		{
			out.append(" bounds=").append(bounds.x).append(',').append(bounds.y)
				.append(',').append(bounds.width).append('x').append(bounds.height);
		}

		if (widget.getItemId() > 0)
		{
			out.append(" item=").append(widget.getItemId());
		}

		String name = widget.getName();
		if (name != null && !name.isEmpty())
		{
			out.append(" name=\"").append(oneLine(name)).append('"');
		}

		String text = widget.getText();
		if (text != null && !text.isEmpty())
		{
			out.append(" text=\"").append(oneLine(text)).append('"');
		}

		String[] actions = widget.getActions();
		if (actions != null)
		{
			out.append(" actions=[");
			boolean first = true;
			for (int i = 0; i < actions.length; i++)
			{
				String action = actions[i];
				if (action == null || action.isEmpty())
				{
					continue;
				}
				if (!first)
				{
					out.append(", ");
				}
				out.append(i).append(':').append(action);
				first = false;
			}
			out.append(']');
		}

		out.append('\n');
	}

	private static String oneLine(String value)
	{
		return value.replace('\r', ' ').replace('\n', '|');
	}

	private static String stackTrace(Throwable throwable)
	{
		StringWriter writer = new StringWriter();
		throwable.printStackTrace(new PrintWriter(writer));
		return writer.toString();
	}

	/**
	 * Writes a snapshot, wrapping IO errors so callers can surface them as a
	 * single failure instead of an unchecked stack trace.
	 */
	public static int snapshotOrThrow(Client client, String label)
	{
		try
		{
			return snapshot(client, label);
		}
		catch (IOException ex)
		{
			throw new UncheckedIOException(ex);
		}
	}
}
