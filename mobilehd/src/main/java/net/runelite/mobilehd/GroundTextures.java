package net.runelite.mobilehd;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import javax.imageio.ImageIO;
import net.runelite.client.plugins.gpu.GpuPlugin;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Ground texture array (textures from 117 HD, see textures/LICENSE.txt). Each layer is stored as a
 * detail map whose per-channel average is 0.5, so the shader can multiply it onto the game's tile color.
 */
final class GroundTextures
{
	/** Order must match the layer indices in SceneShaders. */
	private static final String[] NAMES = {"grass", "dirt", "sand", "rock", "gravel", "snow"};
	private static final int SIZE = 256;
	/** The GPU plugin uses units 0 and 1. */
	static final int UNIT = 4;

	private int tex;
	private boolean failed;
	private int programSet;
	private Field glProgram;

	boolean ensure()
	{
		if (tex != 0)
		{
			return true;
		}
		if (failed)
		{
			return false;
		}
		try
		{
			ByteBuffer buf = BufferUtils.createByteBuffer(SIZE * SIZE * 4 * NAMES.length);
			for (String name : NAMES)
			{
				fill(buf, load(name));
			}
			buf.flip();
			int prevActive = glGetInteger(GL_ACTIVE_TEXTURE);
			glActiveTexture(GL_TEXTURE0 + UNIT);
			tex = glGenTextures();
			glBindTexture(GL_TEXTURE_2D_ARRAY, tex);
			glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, SIZE, SIZE, NAMES.length, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
			glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
			glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);
			glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
			glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
			glActiveTexture(prevActive);
			glProgram = GpuPlugin.class.getDeclaredField("glProgram");
			glProgram.setAccessible(true);
			return true;
		}
		catch (Throwable t)
		{
			failed = true;
			free();
			throw new IllegalStateException("ground textures failed to load", t);
		}
	}

	/** Binds the array and points the GPU plugin's scene program at it; call before the scene is drawn. */
	void bind()
	{
		if (tex == 0)
		{
			return;
		}
		int prevActive = glGetInteger(GL_ACTIVE_TEXTURE);
		glActiveTexture(GL_TEXTURE0 + UNIT);
		glBindTexture(GL_TEXTURE_2D_ARRAY, tex);
		glActiveTexture(prevActive);
		try
		{
			int prog = glProgram.getInt(null);
			if (prog != 0 && prog != programSet)
			{
				int loc = glGetUniformLocation(prog, "mhdGroundTex");
				if (loc >= 0)
				{
					int prevProg = glGetInteger(GL_CURRENT_PROGRAM);
					glUseProgram(prog);
					glUniform1i(loc, UNIT);
					glUseProgram(prevProg);
				}
				programSet = prog;
			}
		}
		catch (IllegalAccessException e)
		{
			throw new IllegalStateException(e);
		}
	}

	void free()
	{
		if (tex != 0)
		{
			glDeleteTextures(tex);
			tex = 0;
		}
		programSet = 0;
	}

	private static BufferedImage load(String name) throws IOException
	{
		try (InputStream in = GroundTextures.class.getResourceAsStream("textures/" + name + ".jpg"))
		{
			if (in == null)
			{
				throw new IOException(name + ".jpg missing");
			}
			BufferedImage img = ImageIO.read(in);
			if (img == null || img.getWidth() != SIZE || img.getHeight() != SIZE)
			{
				throw new IOException(name + ".jpg must be " + SIZE + "x" + SIZE);
			}
			return img;
		}
	}

	private static void fill(ByteBuffer buf, BufferedImage img)
	{
		int[] px = img.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
		long r = 0, g = 0, b = 0;
		for (int p : px)
		{
			r += p >> 16 & 0xff;
			g += p >> 8 & 0xff;
			b += p & 0xff;
		}
		float n = px.length;
		float sr = 128f * n / Math.max(r, 1), sg = 128f * n / Math.max(g, 1), sb = 128f * n / Math.max(b, 1);
		for (int p : px)
		{
			buf.put((byte) Math.min(255, Math.round((p >> 16 & 0xff) * sr)));
			buf.put((byte) Math.min(255, Math.round((p >> 8 & 0xff) * sg)));
			buf.put((byte) Math.min(255, Math.round((p & 0xff) * sb)));
			buf.put((byte) 255);
		}
	}
}
