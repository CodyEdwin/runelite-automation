package net.runelite.mobilehd;

import java.nio.ByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.lwjgl.opengl.GL33C.*;

/** Full-screen pass over the GPU plugin's scene framebuffer: FXAA, sharpen, color grading. */
final class PostProcess
{
	private static final Logger log = LoggerFactory.getLogger(PostProcess.class);

	private static final String VERT = "#version 330\n"
		+ "out vec2 uv;\n"
		+ "void main() {\n"
		+ "  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n"
		+ "  uv = p;\n"
		+ "  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n"
		+ "}\n";

	// FXAA after Timothy Lottes' FXAA 3 (simplified), then a 4-tap unsharp mask and color grade.
	private static final String FRAG = "#version 330\n"
		+ "uniform sampler2D tex;\n"
		+ "uniform vec2 texel;\n"
		+ "uniform int fxaa;\n"
		+ "uniform float sharpen;\n"
		+ "uniform vec3 grade;\n"
		+ "in vec2 uv;\n"
		+ "out vec4 FragColor;\n"
		+ "float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }\n"
		+ "void main() {\n"
		+ "  vec3 m = texture(tex, uv).rgb;\n"
		+ "  vec3 c = m;\n"
		+ "  if (fxaa == 1) {\n"
		+ "    vec3 nw = texture(tex, uv + vec2(-1.0, 1.0) * texel).rgb;\n"
		+ "    vec3 ne = texture(tex, uv + vec2(1.0, 1.0) * texel).rgb;\n"
		+ "    vec3 sw = texture(tex, uv + vec2(-1.0, -1.0) * texel).rgb;\n"
		+ "    vec3 se = texture(tex, uv + vec2(1.0, -1.0) * texel).rgb;\n"
		+ "    float lNW = luma(nw), lNE = luma(ne), lSW = luma(sw), lSE = luma(se), lM = luma(m);\n"
		+ "    float lMin = min(lM, min(min(lNW, lNE), min(lSW, lSE)));\n"
		+ "    float lMax = max(lM, max(max(lNW, lNE), max(lSW, lSE)));\n"
		+ "    if (lMax - lMin > max(0.0312, lMax * 0.125)) {\n"
		+ "      vec2 dir = vec2(-((lNW + lNE) - (lSW + lSE)), (lNW + lSW) - (lNE + lSE));\n"
		+ "      float reduce = max((lNW + lNE + lSW + lSE) * 0.03125, 1.0 / 128.0);\n"
		+ "      float rcp = 1.0 / (min(abs(dir.x), abs(dir.y)) + reduce);\n"
		+ "      dir = clamp(dir * rcp, -8.0, 8.0) * texel;\n"
		+ "      vec3 a = 0.5 * (texture(tex, uv - dir / 6.0).rgb + texture(tex, uv + dir / 6.0).rgb);\n"
		+ "      vec3 b = a * 0.5 + 0.25 * (texture(tex, uv - dir * 0.5).rgb + texture(tex, uv + dir * 0.5).rgb);\n"
		+ "      float lB = luma(b);\n"
		+ "      c = (lB < lMin || lB > lMax) ? a : b;\n"
		+ "    }\n"
		+ "  }\n"
		+ "  if (sharpen > 0.0) {\n"
		+ "    vec3 n = texture(tex, uv + vec2(0.0, texel.y)).rgb;\n"
		+ "    vec3 s = texture(tex, uv - vec2(0.0, texel.y)).rgb;\n"
		+ "    vec3 e = texture(tex, uv + vec2(texel.x, 0.0)).rgb;\n"
		+ "    vec3 w = texture(tex, uv - vec2(texel.x, 0.0)).rgb;\n"
		+ "    c += (4.0 * m - n - s - e - w) * sharpen;\n"
		+ "  }\n"
		+ "  c = mix(vec3(luma(c)), c, grade.x);\n"
		+ "  c = (c - 0.5) * grade.y + 0.5;\n"
		+ "  c *= vec3(1.0 + grade.z, 1.0, 1.0 - grade.z);\n"
		+ "  FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);\n"
		+ "}\n";

	private int program = -1;
	private int vao;
	private int fbo;
	private int tex;
	private int texW;
	private int texH;
	private int uTexel;
	private int uFxaa;
	private int uSharpen;
	private int uGrade;
	private boolean failed;

	boolean failed()
	{
		return failed;
	}

	void run(int sceneFbo, int w, int h, boolean fxaa, float sharpen, float sat, float contrast, float warmth)
	{
		if (failed || w <= 0 || h <= 0)
		{
			return;
		}

		int prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
		int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
		int prevActive = glGetInteger(GL_ACTIVE_TEXTURE);
		glActiveTexture(GL_TEXTURE0);
		int prevTex = glGetInteger(GL_TEXTURE_BINDING_2D);
		int prevSampler = glGetInteger(GL_SAMPLER_BINDING);
		int prevRead = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
		int prevDraw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
		int[] vp = new int[4];
		glGetIntegerv(GL_VIEWPORT, vp);
		boolean blend = glIsEnabled(GL_BLEND);
		boolean depth = glIsEnabled(GL_DEPTH_TEST);
		boolean cull = glIsEnabled(GL_CULL_FACE);
		boolean scissor = glIsEnabled(GL_SCISSOR_TEST);

		try
		{
			if (program == -1 && !init())
			{
				return;
			}
			if (w != texW || h != texH)
			{
				resize(w, h);
			}

			// Copy (and resolve MSAA) the scene into a sampleable texture, then draw it back processed.
			glBindFramebuffer(GL_READ_FRAMEBUFFER, sceneFbo);
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
			glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_NEAREST);
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, sceneFbo);

			glViewport(0, 0, w, h);
			glDisable(GL_BLEND);
			glDisable(GL_DEPTH_TEST);
			glDisable(GL_CULL_FACE);
			glDisable(GL_SCISSOR_TEST);
			glUseProgram(program);
			glBindVertexArray(vao);
			glBindTexture(GL_TEXTURE_2D, tex);
			glBindSampler(0, 0);
			glUniform2f(uTexel, 1f / w, 1f / h);
			glUniform1i(uFxaa, fxaa ? 1 : 0);
			glUniform1f(uSharpen, sharpen);
			glUniform3f(uGrade, sat, contrast, warmth);
			glDrawArrays(GL_TRIANGLES, 0, 3);
		}
		finally
		{
			glBindFramebuffer(GL_READ_FRAMEBUFFER, prevRead);
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, prevDraw);
			glViewport(vp[0], vp[1], vp[2], vp[3]);
			setEnabled(GL_BLEND, blend);
			setEnabled(GL_DEPTH_TEST, depth);
			setEnabled(GL_CULL_FACE, cull);
			setEnabled(GL_SCISSOR_TEST, scissor);
			glBindTexture(GL_TEXTURE_2D, prevTex);
			glBindSampler(0, prevSampler);
			glActiveTexture(prevActive);
			glBindVertexArray(prevVao);
			glUseProgram(prevProgram);
		}
	}

	private static void setEnabled(int cap, boolean on)
	{
		if (on)
		{
			glEnable(cap);
		}
		else
		{
			glDisable(cap);
		}
	}

	private boolean init()
	{
		int vs = compile(GL_VERTEX_SHADER, VERT);
		int fs = compile(GL_FRAGMENT_SHADER, FRAG);
		if (vs == 0 || fs == 0)
		{
			failed = true;
			return false;
		}
		int p = glCreateProgram();
		glAttachShader(p, vs);
		glAttachShader(p, fs);
		glLinkProgram(p);
		glDeleteShader(vs);
		glDeleteShader(fs);
		if (glGetProgrami(p, GL_LINK_STATUS) != GL_TRUE)
		{
			log.warn("Mobile HD: link failed: {}", glGetProgramInfoLog(p));
			glDeleteProgram(p);
			failed = true;
			return false;
		}
		program = p;
		uTexel = glGetUniformLocation(p, "texel");
		uFxaa = glGetUniformLocation(p, "fxaa");
		uSharpen = glGetUniformLocation(p, "sharpen");
		uGrade = glGetUniformLocation(p, "grade");
		glUseProgram(p);
		glUniform1i(glGetUniformLocation(p, "tex"), 0);
		vao = glGenVertexArrays();
		fbo = glGenFramebuffers();
		tex = glGenTextures();
		return true;
	}

	private void resize(int w, int h)
	{
		glBindTexture(GL_TEXTURE_2D, tex);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
		glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
		texW = w;
		texH = h;
	}

	private static int compile(int type, String src)
	{
		int s = glCreateShader(type);
		glShaderSource(s, src);
		glCompileShader(s);
		if (glGetShaderi(s, GL_COMPILE_STATUS) != GL_TRUE)
		{
			log.warn("Mobile HD: shader compile failed: {}", glGetShaderInfoLog(s));
			glDeleteShader(s);
			return 0;
		}
		return s;
	}

	void free()
	{
		if (program != -1)
		{
			glDeleteProgram(program);
			glDeleteVertexArrays(vao);
			glDeleteFramebuffers(fbo);
			glDeleteTextures(tex);
		}
		program = -1;
		texW = texH = 0;
		failed = false;
	}
}
