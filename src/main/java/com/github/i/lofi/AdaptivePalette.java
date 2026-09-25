package com.github.i.lofi;

import com.github.i.lofi.template.Template;
import lombok.extern.slf4j.Slf4j;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Picks a palette of any size from the frame for the MS Paint style, with k-means clustering on the GPU (see
 * palette_frag.glsl). Each frame, the finished frame is shrunk to a small sample image, and the palette from the
 * last frame is refined by one k-means step, so colors follow the view smoothly. Nothing is read back to the CPU.
 */
@Slf4j
class AdaptivePalette
{
	/** Must match MAX_ADAPTIVE_COLORS in oklab.glsl. */
	static final int MAX_COLORS = 64;

	// Small enough that the k-means pass stays cheap: samples x colors distance checks per color
	private static final int SAMPLES_WIDTH = 64;
	private static final int SAMPLES_HEIGHT = 36;

	// A new palette gets several full k-means steps to settle; after that, colors move part way each frame
	private static final int SEED_ITERATIONS = 8;
	private static final float FRAME_BLEND = 0.35f;

	private static final Shader PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "painterly_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "palette_frag.glsl");

	private int program;
	private int vaoEmpty;
	private int uniSamples;
	private int uniPrevious;
	private int uniPaletteSize;
	private int uniSeeding;
	private int uniBlend;

	private int fboSamples;
	private int texSamples;
	// Ping-pong targets: each pass reads one palette and writes the other
	private final int[] fboPalette = new int[2];
	private final int[] texPalette = new int[2];
	private int current;
	// The palette size the current colors were picked for, 0 when there are none
	private int seededColors;
	private boolean broken;

	void compile(Template template)
	{
		destroy();
		try
		{
			program = PROGRAM.compile(template);
		}
		catch (ShaderException ex)
		{
			log.error("Failed to compile the adaptive palette shader, MS Paint keeps its classic colors", ex);
			program = 0;
			return;
		}
		uniSamples = glGetUniformLocation(program, "samples");
		uniPrevious = glGetUniformLocation(program, "previous");
		uniPaletteSize = glGetUniformLocation(program, "paletteSize");
		uniSeeding = glGetUniformLocation(program, "seeding");
		uniBlend = glGetUniformLocation(program, "blend");
		vaoEmpty = glGenVertexArrays();
	}

	/**
	 * Refines the palette from the frame in {@code sourceFbo} and returns the texture holding it, one OKLab color
	 * per texel of row 0, or 0 if unavailable. Changes the bound framebuffer, viewport, program and texture unit
	 * {@code workUnit}.
	 */
	int update(
		int sourceFbo,
		int sourceWidth,
		int sourceHeight,
		int colors,
		int workUnit
	)
	{
		if (program == 0 || broken || colors <= 0 || !ensureTargets())
		{
			return 0;
		}
		colors = Math.min(colors, MAX_COLORS);

		// Shrink the frame into the sample image. Linear filtering averages a little, the rest is sampling.
		glBindFramebuffer(GL_READ_FRAMEBUFFER, sourceFbo);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fboSamples);
		glBlitFramebuffer(0, 0, sourceWidth, sourceHeight, 0, 0, SAMPLES_WIDTH, SAMPLES_HEIGHT, GL_COLOR_BUFFER_BIT, GL_LINEAR);

		glUseProgram(program);
		glUniform1i(uniSamples, workUnit);
		glUniform1i(uniPrevious, workUnit + 1);
		glUniform1i(uniPaletteSize, colors);
		glActiveTexture(GL_TEXTURE0 + workUnit);
		glBindTexture(GL_TEXTURE_2D, texSamples);
		glBindVertexArray(vaoEmpty);
		glViewport(0, 0, colors, 1);

		if (seededColors != colors)
		{
			pass(true, 1, workUnit);
			for (int i = 0; i < SEED_ITERATIONS; i++)
			{
				pass(false, 1, workUnit);
			}
			seededColors = colors;
		}
		else
		{
			pass(false, FRAME_BLEND, workUnit);
		}

		glBindVertexArray(0);
		glActiveTexture(GL_TEXTURE0);
		return texPalette[current];
	}

	/** One pass from the current palette into the other target, which then becomes current. */
	private void pass(boolean seeding, float blend, int workUnit)
	{
		int next = 1 - current;
		glUniform1i(uniSeeding, seeding ? 1 : 0);
		glUniform1f(uniBlend, blend);
		glActiveTexture(GL_TEXTURE0 + workUnit + 1);
		glBindTexture(GL_TEXTURE_2D, texPalette[current]);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fboPalette[next]);
		glDrawArrays(GL_TRIANGLES, 0, 3);
		// Unbind before the next pass writes to this texture's twin, so reading and writing never overlap
		glBindTexture(GL_TEXTURE_2D, 0);
		current = next;
	}

	private boolean ensureTargets()
	{
		if (fboSamples != 0)
		{
			return true;
		}

		texSamples = createTexture(GL_RGBA8, SAMPLES_WIDTH, SAMPLES_HEIGHT, GL_UNSIGNED_BYTE);
		fboSamples = createFramebuffer(texSamples);
		for (int i = 0; i < 2; i++)
		{
			texPalette[i] = createTexture(GL_RGBA32F, MAX_COLORS, 1, GL_FLOAT);
			fboPalette[i] = createFramebuffer(texPalette[i]);
		}
		seededColors = 0;

		broken = fboSamples == 0 || fboPalette[0] == 0 || fboPalette[1] == 0;
		if (broken)
		{
			log.error("Adaptive palette framebuffers are incomplete, MS Paint keeps its classic colors");
		}
		return !broken;
	}

	private static int createTexture(int internalFormat, int width, int height, int type)
	{
		int texture = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, texture);
		glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, width, height, 0, GL_RGBA, type, 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_2D, 0);
		return texture;
	}

	/** Returns 0 if the framebuffer is incomplete. Leaves no framebuffer bound. */
	private static int createFramebuffer(int texture)
	{
		int fbo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		if (status == GL_FRAMEBUFFER_COMPLETE)
		{
			return fbo;
		}
		glDeleteFramebuffers(fbo);
		return 0;
	}

	void destroyTargets()
	{
		if (fboSamples != 0)
		{
			glDeleteFramebuffers(fboSamples);
		}
		if (texSamples != 0)
		{
			glDeleteTextures(texSamples);
		}
		fboSamples = 0;
		texSamples = 0;
		for (int i = 0; i < 2; i++)
		{
			if (fboPalette[i] != 0)
			{
				glDeleteFramebuffers(fboPalette[i]);
			}
			if (texPalette[i] != 0)
			{
				glDeleteTextures(texPalette[i]);
			}
			fboPalette[i] = 0;
			texPalette[i] = 0;
		}
		seededColors = 0;
		broken = false;
	}

	void destroy()
	{
		destroyTargets();
		if (program != 0)
		{
			glDeleteProgram(program);
		}
		program = 0;
		if (vaoEmpty != 0)
		{
			glDeleteVertexArrays(vaoEmpty);
		}
		vaoEmpty = 0;
	}
}
