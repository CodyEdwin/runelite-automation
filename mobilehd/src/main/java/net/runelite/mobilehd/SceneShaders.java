package net.runelite.mobilehd;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.Locale;
import net.runelite.client.plugins.gpu.GpuPlugin;

/**
 * Builds patched copies of the GPU plugin's scene shaders. The GPU plugin loads shaders from the
 * classpath, and on Android a writable directory (runelite.android.gpuDir) sits ahead of its jar.
 */
final class SceneShaders
{
	private static final String RES = "net/runelite/client/plugins/gpu/";

	private static final String FOG_LINE = "vec3 mixedColor = mix(c.rgb, fogColor.rgb, fFogAmount);";

	// Hash without sine by Dave Hoskins (MIT), value noise and fbm on top.
	private static final String FRAG_FUNCS = "in vec3 fWorld;\n"
		+ "uniform sampler2DArray mhdGroundTex;\n"
		+ "#if MHD_TEX\n"
		+ "vec3 mhdTex(vec2 p, int layer) {\n"
		+ "  vec3 a = texture(mhdGroundTex, vec3(p, float(layer))).rgb;\n"
		+ "#if MHD_TEX_BLEND\n"
		// second sample at a rotated, larger scale hides the tiling
		+ "  vec3 b = texture(mhdGroundTex, vec3(mat2(0.8, -0.6, 0.6, 0.8) * p * 0.43 + 0.31, float(layer))).rgb;\n"
		+ "  a = 0.5 + ((a + b) * 0.5 - 0.5) * 1.4;\n"
		+ "#endif\n"
		// mostly brightness detail; full texture color clashes with the game's palette
		+ "  return mix(vec3(dot(a, vec3(0.3333))), a, 0.35);\n"
		+ "}\n"
		+ "#endif\n"
		+ "in vec3 fView;\n"
		+ "float mhdHash(vec2 p) {\n"
		+ "  vec3 p3 = fract(vec3(p.xyx) * 0.1031);\n"
		+ "  p3 += dot(p3, p3.yzx + 33.33);\n"
		+ "  return fract((p3.x + p3.y) * p3.z);\n"
		+ "}\n"
		+ "float mhdNoise(vec2 p) {\n"
		+ "  vec2 i = floor(p);\n"
		+ "  vec2 f = fract(p);\n"
		+ "  vec2 u = f * f * (3.0 - 2.0 * f);\n"
		+ "  return mix(mix(mhdHash(i), mhdHash(i + vec2(1.0, 0.0)), u.x),\n"
		+ "             mix(mhdHash(i + vec2(0.0, 1.0)), mhdHash(i + vec2(1.0, 1.0)), u.x), u.y);\n"
		+ "}\n"
		+ "float mhdFbm(vec2 p) {\n"
		+ "  float v = 0.0;\n"
		+ "  float a = 0.5;\n"
		+ "  for (int i = 0; i < MHD_OCTAVES; i++) {\n"
		+ "    v += a * mhdNoise(p);\n"
		+ "    p = p * 2.03 + vec2(17.0, 9.0);\n"
		+ "    a *= 0.5;\n"
		+ "  }\n"
		+ "  return v;\n"
		+ "}\n";

	private static final String FRAG_MAIN = "  vec3 mhdN = normalize(cross(dFdx(fWorld), dFdy(fWorld)));\n"
		+ "  if (dot(mhdN, fView) < 0.0) mhdN = -mhdN;\n"
		+ "  bool mhdGround = fTextureId <= 0 && -mhdN.y > 0.8;\n"
		+ "#if MHD_TEX\n"
		+ "  if (mhdGround) {\n"
		+ "    float tfade = 1.0 - smoothstep(MHD_TEX_DIST * 0.6, MHD_TEX_DIST, length(fView));\n"
		+ "    if (tfade > 0.0) {\n"
		+ "      float lum = dot(c.rgb, vec3(0.299, 0.587, 0.114));\n"
		+ "      float mx = max(c.r, max(c.g, c.b));\n"
		+ "      float sat = (mx - min(c.r, min(c.g, c.b))) / max(mx, 0.001);\n"
		+ "      float grey = 1.0 - smoothstep(0.08, 0.22, sat);\n"
		+ "      float warm = c.r - c.b;\n"
		+ "      float w[6];\n"
		+ "      w[0] = smoothstep(-0.02, 0.06, c.g - max(c.r, c.b));\n"
		+ "      w[5] = smoothstep(0.72, 0.88, lum) * grey;\n"
		+ "      w[2] = smoothstep(0.42, 0.58, lum) * smoothstep(0.04, 0.14, warm) * (1.0 - w[0]) * (1.0 - w[5]);\n"
		+ "      w[1] = smoothstep(0.03, 0.12, warm) * (1.0 - smoothstep(0.42, 0.58, lum)) * (1.0 - w[0]);\n"
		+ "      w[3] = grey * (1.0 - smoothstep(0.28, 0.45, lum));\n"
		+ "      w[4] = max(grey * smoothstep(0.28, 0.45, lum) * (1.0 - w[5]), 0.05);\n"
		+ "      int i1 = 0;\n"
		+ "      int i2 = 1;\n"
		+ "      if (w[1] > w[0]) { i1 = 1; i2 = 0; }\n"
		+ "      for (int i = 2; i < 6; i++) {\n"
		+ "        if (w[i] > w[i1]) { i2 = i1; i1 = i; }\n"
		+ "        else if (w[i] > w[i2]) { i2 = i; }\n"
		+ "      }\n"
		+ "      vec2 tuv = fWorld.xz / 192.0;\n"
		+ "      vec3 t = mhdTex(tuv, i1);\n"
		+ "#if MHD_TEX_BLEND\n"
		+ "      float b = w[i2] / max(w[i1] + w[i2], 0.001);\n"
		+ "      if (b > 0.05) t = mix(t, mhdTex(tuv, i2), b);\n"
		+ "#endif\n"
		+ "      c.rgb *= mix(vec3(1.0), t * 2.0, MHD_TEX_STRENGTH * tfade);\n"
		+ "    }\n"
		+ "  }\n"
		+ "#endif\n"
		+ "#if MHD_GROUND\n"
		+ "  if (mhdGround) {\n"
		+ "    float fade = 1.0 - smoothstep(MHD_DETAIL_DIST * 0.6, MHD_DETAIL_DIST, length(fView));\n"
		+ "    if (fade > 0.0) {\n"
		+ "      vec2 gp = fWorld.xz / 128.0;\n"
		+ "      float n = mhdFbm(gp * 3.0) - 0.5;\n"
		+ "      float fine = mhdNoise(gp * 24.0) - 0.5;\n"
		+ "      float grass = clamp((c.g - max(c.r, c.b)) * 8.0, 0.0, 1.0);\n"
		+ "      c.rgb *= 1.0 + (n * mix(0.22, 0.32, grass) + fine * 0.12) * fade;\n"
		+ "      c.rgb += vec3(0.03, 0.04, -0.02) * n * grass * fade;\n"
		+ "    }\n"
		+ "  }\n"
		+ "#endif\n"
		+ "#if MHD_SUN\n"
		+ "  vec3 mhdSun = normalize(vec3(-0.45, -0.8, -0.4));\n"
		+ "  float ndl = dot(mhdN, mhdSun);\n"
		+ "  float diff = ndl * 0.5 + 0.5;\n"
		+ "  c.rgb *= mix(vec3(1.0), mix(vec3(0.85, 0.87, 0.93), vec3(1.08, 1.06, 1.02), diff), MHD_SUN_STRENGTH);\n"
		+ "#if MHD_SPECULAR\n"
		+ "  if (!mhdGround && ndl > 0.0) {\n"
		+ "    vec3 h = normalize(mhdSun + normalize(fView));\n"
		+ "    c.rgb += pow(max(dot(mhdN, h), 0.0), 24.0) * 0.2 * MHD_SUN_STRENGTH * vec3(1.0, 0.96, 0.88);\n"
		+ "  }\n"
		+ "#endif\n"
		+ "#endif\n"
		+ "  c.rgb = clamp(c.rgb, 0.0, 1.0);\n"
		+ "#if MHD_SMOOTH_FOG\n"
		+ "  float mhdFog = smoothstep(0.0, 1.0, fFogAmount);\n"
		+ "  c.rgb = mix(c.rgb, vec3(dot(c.rgb, vec3(0.299, 0.587, 0.114))), mhdFog * 0.3);\n"
		+ "#else\n"
		+ "  float mhdFog = fFogAmount;\n"
		+ "#endif\n"
		+ "  vec3 mixedColor = mix(c.rgb, fogColor.rgb, mhdFog);";

	private SceneShaders()
	{
	}

	static boolean available()
	{
		return System.getProperty("runelite.android.gpuDir") != null;
	}

	/** Settings that change the shader source; when equal, the files on disk are already current. */
	static String signature(MobileHdConfig c, boolean textures)
	{
		textures &= c.groundTextures();
		if (!c.sunLighting() && !c.groundDetail() && !c.smoothFog() && !textures)
		{
			return "off";
		}
		return c.sunLighting() + "," + c.sunStrength() + "," + c.groundDetail() + "," + c.smoothFog() + "," + c.detailLevel()
			+ "," + textures + "," + c.textureStrength();
	}

	static void writeStock() throws IOException
	{
		write(null, false);
	}

	/** Writes the shaders for these settings (stock when null). Returns false if the GPU plugin's shaders didn't match. */
	static boolean write(MobileHdConfig c, boolean textures) throws IOException
	{
		File dir = new File(System.getProperty("runelite.android.gpuDir"), RES);
		dir.mkdirs();
		// Mali has no noperspective; same strip the app's GPU startup does.
		String frag = pristine("frag.glsl").replace("noperspective ", "");
		String vert = pristine("vert.glsl").replace("noperspective ", "");

		if (c != null && !"off".equals(signature(c, textures)))
		{
			DetailLevel d = c.detailLevel();
			String defines = "#define MHD_SUN " + (c.sunLighting() ? 1 : 0) + "\n"
				+ "#define MHD_SUN_STRENGTH " + String.format(Locale.ROOT, "%.3f", c.sunStrength() / 100f) + "\n"
				+ "#define MHD_SPECULAR " + (d == DetailLevel.LOW ? 0 : 1) + "\n"
				+ "#define MHD_GROUND " + (c.groundDetail() ? 1 : 0) + "\n"
				+ "#define MHD_OCTAVES " + d.octaves + "\n"
				+ "#define MHD_DETAIL_DIST " + d.detailDistance + ".0\n"
				+ "#define MHD_SMOOTH_FOG " + (c.smoothFog() ? 1 : 0) + "\n"
				+ "#define MHD_TEX " + (textures && c.groundTextures() ? 1 : 0) + "\n"
				+ "#define MHD_TEX_STRENGTH " + String.format(Locale.ROOT, "%.3f", c.textureStrength() / 100f) + "\n"
				+ "#define MHD_TEX_BLEND " + (d == DetailLevel.LOW ? 0 : 1) + "\n"
				+ "#define MHD_TEX_DIST " + d.detailDistance * 2 + ".0\n";
			String fragOut = "out vec4 FragColor;";
			String vertOut = "out float fFogAmount;";
			String vertPos = "vec4 worldPos = entityProj * vert;";
			if (!frag.contains(FOG_LINE) || !frag.contains(fragOut) || !vert.contains(vertOut) || !vert.contains(vertPos))
			{
				return false;
			}
			frag = frag.replace(fragOut, fragOut + "\n" + defines + FRAG_FUNCS).replace(FOG_LINE, FRAG_MAIN);
			vert = vert.replace(vertOut, vertOut + "\nout vec3 fWorld;\nout vec3 fView;")
				.replace(vertPos, vertPos + "\n  fWorld = worldPos.xyz;\n  fView = vec3(cameraX, cameraY, cameraZ) - worldPos.xyz;");
		}
		Files.write(new File(dir, "frag.glsl").toPath(), frag.getBytes(StandardCharsets.UTF_8));
		Files.write(new File(dir, "vert.glsl").toPath(), vert.getBytes(StandardCharsets.UTF_8));
		return true;
	}

	/** The shader as shipped in RuneLite's jar, ignoring any patched copy ahead of it on the classpath. */
	private static String pristine(String name) throws IOException
	{
		Enumeration<URL> urls = GpuPlugin.class.getClassLoader().getResources(RES + name);
		URL pick = null;
		while (urls.hasMoreElements())
		{
			URL u = urls.nextElement();
			if ("jar".equals(u.getProtocol()))
			{
				pick = u;
				break;
			}
		}
		if (pick == null)
		{
			throw new IOException(name + " not found in RuneLite jar");
		}
		try (InputStream in = pick.openStream())
		{
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
