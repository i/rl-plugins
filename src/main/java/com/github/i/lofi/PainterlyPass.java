package com.github.i.lofi;

import com.github.i.lofi.config.PainterlyStyle;
import com.github.i.lofi.template.Template;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Applies the selected art style to the whole finished frame: the 3D scene, the HUD and RuneLite overlays.
 * <p>
 * Per frame, {@link LofiPlugin#draw} calls {@link #beginFrame} and draws the scene and UI into the framebuffer it
 * returns. When a style is active, that is an offscreen frame whose alpha channel records UI coverage.
 * {@link #endFrame} then paints it to the screen, using the scene's depth for outlines. When the style is off or
 * anything is unsupported, the default framebuffer is returned and nothing changes.
 */
@Slf4j
@Singleton
class PainterlyPass
{
	// Texture units 0 and 1 hold the UI and the game's texture array
	private static final int UNIT_COLOR = 2;
	private static final int UNIT_DEPTH = 3;
	private static final int UNIT_PALETTE = 4;
	// Used while the adaptive palette is refreshed, before the painting pass binds its own textures
	private static final int UNIT_PALETTE_WORK = 5;

	// Keeps the boil seed small enough to stay precise as a float over long sessions
	private static final int BOIL_SEED_PERIOD = 1024;
	private static final float MAX_WOBBLE_PIXELS = 4;

	private static final Shader PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "painterly_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "painterly_frag.glsl");

	@Inject
	private LofiConfig config;

	@Inject
	private SpriteManager spriteManager;

	private final float[] spriteShadows = new float[SpriteManager.MAX_SHADOWS * 4];

	private final long startNanos = System.nanoTime();
	private final AdaptivePalette adaptivePalette = new AdaptivePalette();

	private int program;
	private int vaoEmpty;
	private int uniSceneColor;
	private int uniSceneDepth;
	private int uniResolution;
	private int uniSceneViewport;
	private int uniHasDepth;
	private int uniHudStrength;
	private int uniStyle;
	private int uniDebugView;
	private int uniBoilTime;
	private int uniWobble;
	private int uniLineWidth;
	private int uniPaintRadius;
	private int uniCanvasStrength;
	private int uniHueSteps;
	private int uniSpriteShadowCount;
	private int uniSpriteShadows;
	private int uniInvProjectionMatrix;
	private int uniCameraPos;
	private int uniAdaptivePalette;
	private int uniAdaptiveColors;

	// Full frame: scene + UI, UI coverage in alpha
	private int fboFrame;
	private int texFrame;
	private int frameWidth;
	private int frameHeight;

	// Resolved scene depth, at scene resolution
	private int fboDepth;
	private int texDepth;
	private int depthWidth;
	private int depthHeight;

	private boolean targetsBroken;
	// The style that was active when the pass failed, so picking another style retries
	private PainterlyStyle failedStyle;
	private boolean capturingFrame;
	private boolean frameHasDepth;

	// The scene camera of the frame being drawn, set by LofiPlugin before the scene draws
	private final int[] sceneViewport = new int[4];
	private float[] invProjection;
	private final float[] cameraPos = new float[3];

	void compile(Template template)
	{
		destroyShaders();
		try
		{
			program = PROGRAM.compile(template);
		}
		catch (ShaderException ex)
		{
			// A broken art-style shader should never take the whole renderer down with it
			log.error("Failed to compile the painterly shader, rendering without an art style", ex);
			program = 0;
			return;
		}

		uniSceneColor = glGetUniformLocation(program, "sceneColor");
		uniSceneDepth = glGetUniformLocation(program, "sceneDepth");
		uniResolution = glGetUniformLocation(program, "resolution");
		uniSceneViewport = glGetUniformLocation(program, "sceneViewport");
		uniHasDepth = glGetUniformLocation(program, "hasDepth");
		uniHudStrength = glGetUniformLocation(program, "hudStrength");
		uniStyle = glGetUniformLocation(program, "style");
		uniDebugView = glGetUniformLocation(program, "debugView");
		uniBoilTime = glGetUniformLocation(program, "boilTime");
		uniWobble = glGetUniformLocation(program, "wobble");
		uniLineWidth = glGetUniformLocation(program, "lineWidth");
		uniPaintRadius = glGetUniformLocation(program, "paintRadius");
		uniCanvasStrength = glGetUniformLocation(program, "canvasStrength");
		uniHueSteps = glGetUniformLocation(program, "hueSteps");
		uniSpriteShadowCount = glGetUniformLocation(program, "spriteShadowCount");
		uniSpriteShadows = glGetUniformLocation(program, "spriteShadows");
		uniInvProjectionMatrix = glGetUniformLocation(program, "invProjectionMatrix");
		uniCameraPos = glGetUniformLocation(program, "cameraPos");
		uniAdaptivePalette = glGetUniformLocation(program, "adaptivePalette");
		uniAdaptiveColors = glGetUniformLocation(program, "adaptiveColors");

		adaptivePalette.compile(template);

		// Core profiles need a bound VAO to draw, even though the triangle comes from gl_VertexID
		vaoEmpty = glGenVertexArrays();
	}

	void destroyShaders()
	{
		adaptivePalette.destroy();
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

	/**
	 * Records the scene camera for this frame. The viewport is in framebuffer pixels, as passed to glViewport.
	 */
	void setSceneCamera(
		int[] viewport,
		float[] projectionMatrix,
		float cameraX,
		float cameraY,
		float cameraZ
	)
	{
		System.arraycopy(viewport, 0, sceneViewport, 0, 4);
		invProjection = Mat4.inverse(projectionMatrix);
		cameraPos[0] = cameraX;
		cameraPos[1] = cameraY;
		cameraPos[2] = cameraZ;
	}

	/**
	 * Starts a frame and returns the framebuffer the scene and UI should be drawn into.
	 *
	 * @param defaultFramebuffer the window's framebuffer
	 * @param width              frame width in framebuffer pixels
	 * @param height             frame height in framebuffer pixels
	 * @param fboScene           the scene framebuffer holding this frame's depth, or -1 if there is no scene
	 */
	int beginFrame(
		int defaultFramebuffer,
		int width,
		int height,
		int fboScene
	)
	{
		capturingFrame = false;
		if (failedStyle != null && failedStyle != config.painterlyStyle())
		{
			failedStyle = null;
			targetsBroken = false;
		}
		// Round sprite shadows are drawn by this pass, so it also runs with the art style off
		boolean needed = config.painterlyStyle() != PainterlyStyle.OFF || spriteManager.isRoundShadowsEnabled();
		if (!needed || program == 0 || targetsBroken)
		{
			return defaultFramebuffer;
		}
		if (width <= 0 || height <= 0 || !ensureFrameTarget(width, height, defaultFramebuffer))
		{
			return defaultFramebuffer;
		}

		frameHasDepth = fboScene != -1 && invProjection != null && resolveSceneDepth(fboScene, width, height, defaultFramebuffer);

		glBindFramebuffer(GL_FRAMEBUFFER, fboFrame);
		capturingFrame = true;
		return fboFrame;
	}

	/**
	 * Turns the art style off after an exception, logging it once, until the style setting changes or the targets
	 * are recreated. A style that throws every frame would otherwise disrupt every frame.
	 */
	void fail(RuntimeException ex)
	{
		capturingFrame = false;
		if (!targetsBroken)
		{
			log.error("Art style pass failed, rendering without an art style", ex);
		}
		targetsBroken = true;
		failedStyle = config.painterlyStyle();
	}

	boolean isCapturingFrame()
	{
		return capturingFrame;
	}

	/**
	 * Clears the frame's alpha after the scene is copied in, so it records only UI coverage once the UI is drawn.
	 */
	void clearCoverage()
	{
		if (!capturingFrame)
		{
			return;
		}
		glColorMask(false, false, false, true);
		glClearColor(0, 0, 0, 0);
		glClear(GL_COLOR_BUFFER_BIT);
		glColorMask(true, true, true, true);
	}

	/**
	 * Paints the captured frame to the screen through the art-style shader. Does nothing if no frame was captured.
	 */
	void endFrame(int defaultFramebuffer)
	{
		if (!capturingFrame)
		{
			return;
		}
		capturingFrame = false;

		glDisable(GL_DEPTH_TEST);
		glDisable(GL_BLEND);
		glDisable(GL_CULL_FACE);

		// MS Paint with a color count picks its palette from this frame
		int colors = config.painterlyStyle() == PainterlyStyle.MS_PAINT ? config.painterlyHueSteps() : 0;
		int paletteTexture = colors > 0 ?
			adaptivePalette.update(fboFrame, frameWidth, frameHeight, colors, UNIT_PALETTE_WORK) : 0;

		glBindFramebuffer(GL_FRAMEBUFFER, defaultFramebuffer);
		glViewport(0, 0, frameWidth, frameHeight);
		glDisable(GL_DEPTH_TEST);
		glDisable(GL_BLEND);
		glDisable(GL_CULL_FACE);

		glUseProgram(program);
		glUniform1i(uniSceneColor, UNIT_COLOR);
		glUniform1i(uniSceneDepth, UNIT_DEPTH);
		glUniform2f(uniResolution, frameWidth, frameHeight);
		glUniform4f(uniSceneViewport, sceneViewport[0], sceneViewport[1], sceneViewport[2], sceneViewport[3]);
		glUniform1i(uniHasDepth, frameHasDepth ? 1 : 0);
		glUniform1i(uniStyle, config.painterlyStyle().shaderId);
		glUniform1i(uniDebugView, config.painterlyDebugView().shaderId);
		glUniform1f(uniHudStrength, config.painterlyHudStrength() / 100f);
		glUniform1f(uniBoilTime, boilSeed());
		glUniform1f(uniWobble, config.painterlyWobble() / 100f * MAX_WOBBLE_PIXELS);
		glUniform1f(uniLineWidth, config.painterlyLineWidth());
		glUniform1i(uniPaintRadius, config.painterlyPaintRadius());
		glUniform1f(uniCanvasStrength, config.painterlyCanvasStrength() / 100f);
		glUniform1i(uniHueSteps, config.painterlyHueSteps());
		// Shadows only make sense with the depth they were recorded against
		int shadowCount = frameHasDepth && spriteManager.isRoundShadowsEnabled() ? spriteManager.copyShadows(spriteShadows) : 0;
		glUniform1i(uniSpriteShadowCount, shadowCount);
		if (shadowCount > 0)
		{
			glUniform4fv(uniSpriteShadows, spriteShadows);
		}
		glUniform1i(uniAdaptivePalette, UNIT_PALETTE);
		glUniform1i(uniAdaptiveColors, paletteTexture != 0 ? Math.min(colors, AdaptivePalette.MAX_COLORS) : 0);
		if (invProjection != null)
		{
			glUniformMatrix4fv(uniInvProjectionMatrix, false, invProjection);
		}
		glUniform3f(uniCameraPos, cameraPos[0], cameraPos[1], cameraPos[2]);

		glActiveTexture(GL_TEXTURE0 + UNIT_COLOR);
		glBindTexture(GL_TEXTURE_2D, texFrame);
		glActiveTexture(GL_TEXTURE0 + UNIT_DEPTH);
		glBindTexture(GL_TEXTURE_2D, texDepth);
		glActiveTexture(GL_TEXTURE0 + UNIT_PALETTE);
		glBindTexture(GL_TEXTURE_2D, paletteTexture);
		glActiveTexture(GL_TEXTURE0);

		glBindVertexArray(vaoEmpty);
		glDrawArrays(GL_TRIANGLES, 0, 3);

		glBindVertexArray(0);
		glUseProgram(0);
	}

	/**
	 * A seed that only changes boil rate times per second, so the wobble looks redrawn by hand.
	 */
	private float boilSeed()
	{
		double seconds = (System.nanoTime() - startNanos) / 1e9;
		long frame = (long) Math.floor(seconds * config.painterlyBoilRate());
		return frame % BOIL_SEED_PERIOD;
	}

	/**
	 * Copies the possibly multisampled scene depth into a sampleable texture. The sizes match, so this is legal for
	 * MSAA.
	 */
	private boolean resolveSceneDepth(
		int fboScene,
		int width,
		int height,
		int defaultFramebuffer
	)
	{
		if (!ensureDepthTarget(width, height, defaultFramebuffer))
		{
			return false;
		}

		glBindFramebuffer(GL_READ_FRAMEBUFFER, fboScene);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fboDepth);
		glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_DEPTH_BUFFER_BIT, GL_NEAREST);
		glBindFramebuffer(GL_READ_FRAMEBUFFER, defaultFramebuffer);
		return true;
	}

	private boolean ensureFrameTarget(
		int width,
		int height,
		int defaultFramebuffer
	)
	{
		if (fboFrame != 0 && width == frameWidth && height == frameHeight)
		{
			return true;
		}
		destroyFrameTarget();

		texFrame = createTexture(UNIT_COLOR);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0);
		// Linear, so sub-pixel wobble offsets move smoothly and Kuwahara reads can average 2x2 blocks
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glActiveTexture(GL_TEXTURE0);

		fboFrame = createFramebuffer(GL_COLOR_ATTACHMENT0, texFrame, defaultFramebuffer);
		if (fboFrame == 0)
		{
			return false;
		}
		frameWidth = width;
		frameHeight = height;
		return true;
	}

	private boolean ensureDepthTarget(
		int width,
		int height,
		int defaultFramebuffer
	)
	{
		if (fboDepth != 0 && width == depthWidth && height == depthHeight)
		{
			return true;
		}
		destroyDepthTarget();

		texDepth = createTexture(UNIT_DEPTH);
		// Must match the scene's depth renderbuffer format for the blit to be valid
		glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, width, height, 0, GL_DEPTH_COMPONENT, GL_FLOAT, 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glActiveTexture(GL_TEXTURE0);

		fboDepth = createFramebuffer(GL_DEPTH_ATTACHMENT, texDepth, defaultFramebuffer);
		if (fboDepth == 0)
		{
			return false;
		}
		depthWidth = width;
		depthHeight = height;
		return true;
	}

	/**
	 * Creates a texture and leaves it bound with its unit active, so the caller can set it up. The caller must
	 * switch back to GL_TEXTURE0 afterwards, which the UI pass expects.
	 */
	private static int createTexture(int unit)
	{
		int texture = glGenTextures();
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_2D, texture);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		return texture;
	}

	/**
	 * Creates a framebuffer with a single texture attachment, or returns 0 and disables the pass if unsupported.
	 */
	private int createFramebuffer(
		int attachment,
		int texture,
		int defaultFramebuffer
	)
	{
		int fbo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, attachment, GL_TEXTURE_2D, texture, 0);
		if (attachment == GL_DEPTH_ATTACHMENT)
		{
			glDrawBuffer(GL_NONE);
			glReadBuffer(GL_NONE);
		}
		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		glBindFramebuffer(GL_FRAMEBUFFER, defaultFramebuffer);

		if (status == GL_FRAMEBUFFER_COMPLETE)
		{
			return fbo;
		}
		log.error("Painterly framebuffer is incomplete (status {}), disabling art styles", status);
		glDeleteFramebuffers(fbo);
		targetsBroken = true;
		return 0;
	}

	void destroyTargets()
	{
		destroyFrameTarget();
		destroyDepthTarget();
		adaptivePalette.destroyTargets();
		capturingFrame = false;
		// Recreating the scene FBO (e.g. changing anti-aliasing) gets a fresh attempt
		targetsBroken = false;
	}

	private void destroyFrameTarget()
	{
		if (fboFrame != 0)
		{
			glDeleteFramebuffers(fboFrame);
		}
		fboFrame = 0;
		if (texFrame != 0)
		{
			glDeleteTextures(texFrame);
		}
		texFrame = 0;
		frameWidth = 0;
		frameHeight = 0;
	}

	private void destroyDepthTarget()
	{
		if (fboDepth != 0)
		{
			glDeleteFramebuffers(fboDepth);
		}
		fboDepth = 0;
		if (texDepth != 0)
		{
			glDeleteTextures(texDepth);
		}
		texDepth = 0;
		depthWidth = 0;
		depthHeight = 0;
	}
}
