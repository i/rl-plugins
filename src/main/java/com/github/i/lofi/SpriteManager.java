package com.github.i.lofi;

import com.github.i.lofi.config.SpriteMode;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Renderable;
import net.runelite.api.WorldView;
import net.runelite.client.input.KeyManager;
import net.runelite.client.util.HotkeyListener;

/**
 * Turns players and NPCs into flat sprites, like RuneScape Classic or Paper Mario.
 * <p>
 * Once per frame, on the client thread, {@link #beginFrame} snaps every eligible actor's facing to one of a few
 * views relative to the camera, and builds a {@link SpriteView} describing how to flatten models into a card that
 * faces the camera. {@link ModelUploader} then flattens the actor's vertices as it uploads them.
 * <p>
 * The game still tests clicks against the real 3D model at its real facing, so click areas stay roughly where the
 * sprite is but are not an exact match.
 */
@Singleton
class SpriteManager
{
	private static final float PI = (float) Math.PI;
	private static final float TWO_PI = 2 * PI;
	private static final float HALF_PI = PI / 2;
	private static final float DEG_TO_RAD = PI / 180;
	private static final float JAU_TO_RAD = TWO_PI / 2048;
	private static final float RAD_TO_JAU = 2048 / TWO_PI;

	/** Extra degrees an actor has to turn past a view boundary before switching view, so it doesn't flicker. */
	private static final float SWITCH_HYSTERESIS = 6 * DEG_TO_RAD;
	private static final long FLIP_DURATION_NANOS = 220_000_000L;
	/** How thin a card is compared to the model, keeping just enough depth for its own parts to sort correctly. */
	private static final float DEPTH_SQUASH = 0.06f;
	private static final float SHADOW_RADIUS_PER_TILE = 50;
	static final int MAX_SHADOWS = 32;

	@Inject
	private Client client;

	@Inject
	private LofiConfig config;

	@Inject
	private KeyManager keyManager;

	/** Per-actor facing state, carried from frame to frame. Only touched on the client thread. */
	static final class ActorSprite
	{
		/** Snapped orientation in JAU, used in place of the actor's real orientation. */
		volatile int orientation;
		/** Horizontal scale of the card, below 1 while a 2-direction sprite is flipping over. */
		volatile float width = 1;
		volatile float shadowRadius;

		private int viewIndex = Integer.MIN_VALUE;
		private int fromSide;
		private long flipStartNanos = Long.MIN_VALUE;
	}

	private volatile Map<Actor, ActorSprite> sprites = new IdentityHashMap<>();
	@Nullable
	private volatile SpriteView view;
	private volatile boolean toggledOff;

	private final float[] shadows = new float[MAX_SHADOWS * 4];
	private int shadowCount;

	private String parsedSkipList;
	private final Set<String> skipNames = new HashSet<>();
	private final Set<Integer> skipIds = new HashSet<>();

	private final HotkeyListener toggleListener = new HotkeyListener(() -> config.spriteToggleKey())
	{
		@Override
		public void hotkeyPressed()
		{
			toggledOff = !toggledOff;
		}
	};

	void startUp()
	{
		keyManager.registerKeyListener(toggleListener);
	}

	void shutDown()
	{
		keyManager.unregisterKeyListener(toggleListener);
		sprites = new IdentityHashMap<>();
		view = null;
		clearShadows();
	}

	/**
	 * How to flatten a model into a camera-facing card. Immutable, so render threads can share it.
	 */
	static final class SpriteView
	{
		// Horizontal right vector
		final float rx, rz;
		// Screen up and view direction as seen from the pinned elevation
		final float uEx, uEy, uEz;
		final float dEx, dEy, dEz;
		// Screen up and view direction of the real camera
		final float uPx, uPy, uPz;
		final float dPx, dPy, dPz;

		SpriteView(float[] forward, float elevation, boolean pinElevation)
		{
			float hx = forward[0];
			float hz = forward[2];
			float horizontalLength = (float) Math.sqrt(hx * hx + hz * hz);
			if (horizontalLength < 1e-4f)
			{
				// Looking straight down: any horizontal direction works
				hx = 0;
				hz = 1;
			}
			else
			{
				hx /= horizontalLength;
				hz /= horizontalLength;
			}

			// World up is -y. The camera's pitch is how far forward points below the horizon.
			float sinP = clamp(forward[1], -1, 1);
			float cosP = (float) Math.sqrt(1 - sinP * sinP);
			float sinE = pinElevation ? (float) Math.sin(elevation) : sinP;
			float cosE = pinElevation ? (float) Math.cos(elevation) : cosP;

			rx = hz;
			rz = -hx;

			uEx = hx * sinE;
			uEy = -cosE;
			uEz = hz * sinE;
			dEx = hx * cosE;
			dEy = sinE;
			dEz = hz * cosE;

			uPx = hx * sinP;
			uPy = -cosP;
			uPz = hz * sinP;
			dPx = hx * cosP;
			dPy = sinP;
			dPz = hz * cosP;
		}

		/**
		 * Flattens a vertex offset from the model's base into the camera-facing card.
		 * The card keeps the vertex's sideways and screen-up position as seen from the pinned elevation.
		 */
		void flatten(float x, float y, float z, float width, float[] out)
		{
			float side = (x * rx + z * rz) * width;
			float up = x * uEx + y * uEy + z * uEz;
			float depth = (x * dEx + y * dEy + z * dEz) * DEPTH_SQUASH;
			out[0] = rx * side + uPx * up + dPx * depth;
			out[1] = uPy * up + dPy * depth;
			out[2] = rz * side + uPz * up + dPz * depth;
		}
	}

	/**
	 * The camera's unit look direction in local scene space, where -y is up, from the pitch and yaw core's world
	 * projection is built with. The heading comes from the yaw alone, so it stays correct looking straight down,
	 * where the view direction's horizontal part is mostly noise and reverses past vertical.
	 */
	static float[] lookDirection(float cameraPitch, float cameraYaw)
	{
		// The projection maps view space +z to depth, so the look direction is the rotation's third row
		float[] rotation = Mat4.rotateX(cameraPitch);
		Mat4.mul(rotation, Mat4.rotateY(cameraYaw));
		float down = clamp(rotation[6], -1, 1);

		float[] heading = Mat4.rotateY(cameraYaw);
		float hx = heading[2];
		float hz = heading[10];
		float headingLength = (float) Math.sqrt(hx * hx + hz * hz);
		// Kept just above zero so an exactly vertical camera still carries its heading, see SpriteView
		float horizontal = Math.max((float) Math.sqrt(1 - down * down), 1e-3f) / headingLength;
		return new float[]{hx * horizontal, down, hz * horizontal};
	}

	/**
	 * Updates sprite facings and the flattening view for this frame. Call on the client thread after the scene camera
	 * has been set up, and before models are drawn.
	 */
	void beginFrame(float cameraPitch, float cameraYaw)
	{
		clearShadows();

		SpriteMode mode = config.spriteMode();
		WorldView worldView = client.getTopLevelWorldView();
		if (mode == SpriteMode.OFF || toggledOff || worldView == null)
		{
			view = null;
			if (!sprites.isEmpty())
			{
				sprites = new IdentityHashMap<>();
			}
			return;
		}

		SpriteView frameView = new SpriteView(
			lookDirection(cameraPitch, cameraYaw),
			config.spriteElevation() * DEG_TO_RAD,
			config.spritePinAngle()
		);
		// The camera's horizontal forward is (hx, hz) = (-rz, rx). Angles are measured from +z towards +x.
		float cameraAngle = (float) Math.atan2(-frameView.rz, frameView.rx);

		updateSkipList();

		Map<Actor, ActorSprite> previous = sprites;
		Map<Actor, ActorSprite> next = new IdentityHashMap<>();
		long now = System.nanoTime();
		int maxSize = config.spriteMaxSize();

		for (Player player : worldView.players())
		{
			if (player != null)
			{
				update(next, previous, player, 1, mode, cameraAngle, now);
			}
		}

		for (NPC npc : worldView.npcs())
		{
			if (npc == null)
			{
				continue;
			}
			NPCComposition composition = npc.getTransformedComposition();
			if (composition == null)
			{
				composition = npc.getComposition();
			}
			int size = composition != null ? composition.getSize() : 1;
			if (size > maxSize || isSkipped(npc))
			{
				continue;
			}
			update(next, previous, npc, size, mode, cameraAngle, now);
		}

		sprites = next;
		view = frameView;
	}

	private void update(
		Map<Actor, ActorSprite> next,
		Map<Actor, ActorSprite> previous,
		Actor actor,
		int size,
		SpriteMode mode,
		float cameraAngle,
		long now
	)
	{
		ActorSprite sprite = previous.get(actor);
		if (sprite == null)
		{
			sprite = new ActorSprite();
		}

		// Orientation 0 faces south (-z), and increases towards west (-x)
		float facingAngle = actor.getCurrentOrientation() * JAU_TO_RAD - PI;
		float relative = wrapAngle(facingAngle - cameraAngle);

		float snappedRelative;
		if (mode == SpriteMode.TWO_DIRECTIONS)
		{
			snappedRelative = snapToSide(sprite, relative, now);
		}
		else
		{
			float step = TWO_PI / mode.directions;
			int nearest = Math.round(relative / step);
			if (sprite.viewIndex == Integer.MIN_VALUE ||
				Math.abs(wrapAngle(relative - sprite.viewIndex * step)) > step / 2 + SWITCH_HYSTERESIS)
			{
				sprite.viewIndex = nearest;
			}
			snappedRelative = sprite.viewIndex * step;
			sprite.width = 1;
		}

		float snappedFacing = cameraAngle + snappedRelative;
		sprite.orientation = Math.floorMod(Math.round((snappedFacing + PI) * RAD_TO_JAU), 2048);
		sprite.shadowRadius = size * SHADOW_RADIUS_PER_TILE;
		next.put(actor, sprite);
	}

	/**
	 * Paper Mario style: only the left and right side views, with a flip animation when switching sides.
	 * During the first half of a flip, the old side shrinks to nothing, then the new side grows back.
	 */
	private float snapToSide(ActorSprite sprite, float relative, long now)
	{
		int side = sprite.viewIndex;
		if (side != 1 && side != -1)
		{
			side = relative >= 0 ? 1 : -1;
			sprite.fromSide = side;
		}
		else if (Math.abs(wrapAngle(relative - side * HALF_PI)) > HALF_PI + SWITCH_HYSTERESIS)
		{
			sprite.fromSide = side;
			side = -side;
			sprite.flipStartNanos = now;
		}
		sprite.viewIndex = side;

		float progress = sprite.flipStartNanos == Long.MIN_VALUE ? 1 :
			(float) (now - sprite.flipStartNanos) / FLIP_DURATION_NANOS;
		if (progress >= 1)
		{
			sprite.width = 1;
			return side * HALF_PI;
		}

		sprite.width = Math.max(Math.abs((float) Math.cos(PI * progress)), 0.03f);
		return (progress < 0.5f ? sprite.fromSide : side) * HALF_PI;
	}

	private static float wrapAngle(float angle)
	{
		angle %= TWO_PI;
		if (angle > PI)
		{
			angle -= TWO_PI;
		}
		else if (angle < -PI)
		{
			angle += TWO_PI;
		}
		return angle;
	}

	private static float clamp(float value, float min, float max)
	{
		return Math.max(min, Math.min(max, value));
	}

	private void updateSkipList()
	{
		String list = config.spriteSkipList();
		if (list.equals(parsedSkipList))
		{
			return;
		}

		parsedSkipList = list;
		skipNames.clear();
		skipIds.clear();
		for (String entry : list.split(","))
		{
			String trimmed = entry.trim();
			if (trimmed.isEmpty())
			{
				continue;
			}
			try
			{
				skipIds.add(Integer.parseInt(trimmed));
			}
			catch (NumberFormatException ex)
			{
				skipNames.add(trimmed.toLowerCase(Locale.ROOT));
			}
		}
	}

	private boolean isSkipped(NPC npc)
	{
		if (skipIds.contains(npc.getId()))
		{
			return true;
		}
		String name = npc.getName();
		return name != null && skipNames.contains(name.toLowerCase(Locale.ROOT));
	}

	/**
	 * The flattening for this frame, or null when sprites are off.
	 */
	@Nullable
	SpriteView getView()
	{
		return view;
	}

	/**
	 * The sprite state for a renderable, or null if it isn't drawn as a sprite this frame.
	 */
	@Nullable
	ActorSprite get(Renderable renderable)
	{
		if (!(renderable instanceof Actor))
		{
			return null;
		}
		return sprites.get(renderable);
	}

	boolean isRoundShadowsEnabled()
	{
		return view != null && config.spriteRoundShadows();
	}

	/**
	 * Records a sprite's ground position for its round shadow.
	 */
	synchronized void addShadow(float x, float y, float z, float radius)
	{
		if (shadowCount >= MAX_SHADOWS)
		{
			return;
		}
		int i = shadowCount++ * 4;
		shadows[i] = x;
		shadows[i + 1] = y;
		shadows[i + 2] = z;
		shadows[i + 3] = radius;
	}

	/**
	 * Copies this frame's shadows as (x, y, z, radius) quadruples and returns how many there are.
	 */
	synchronized int copyShadows(float[] out)
	{
		System.arraycopy(shadows, 0, out, 0, shadowCount * 4);
		return shadowCount;
	}

	private synchronized void clearShadows()
	{
		shadowCount = 0;
	}
}
