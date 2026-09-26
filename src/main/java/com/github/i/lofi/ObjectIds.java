package com.github.i.lofi;

/**
 * Ids written per vertex into the scene's object id buffer, so the art style pass can tell objects apart by shape.
 * They are stored in a spare signed short, so they stay within 0-32767.
 */
final class ObjectIds
{
	/** Sky and anything untagged */
	static final int NONE = 0;
	/** Players and NPCs use 1 up to this */
	static final int MAX_CHARACTER = 255;
	/** All ground tiles share one id, so tile seams don't split it */
	static final int GROUND = 256;
	private static final int FIRST_SCENERY = 257;
	private static final int MAX = 32767;

	private ObjectIds()
	{
	}

	static int character(Object actor)
	{
		return 1 + Math.floorMod(System.identityHashCode(actor), MAX_CHARACTER);
	}

	/** Stable for an object across frames and scene reloads: its local position and object id */
	static int scenery(int x, int z, int id)
	{
		int h = x * 0x9E3779B1 ^ z * 0x85EBCA77 ^ id * 0xC2B2AE3D;
		h ^= h >>> 15;
		h *= 0x2C1B3C6D;
		h ^= h >>> 12;
		return FIRST_SCENERY + Math.floorMod(h, MAX - FIRST_SCENERY + 1);
	}
}
