package com.github.i.lofi;

import org.junit.Test;

import static org.junit.Assert.*;

public class SpriteManagerTest
{
	private static final float EPSILON = 1e-3f;

	/** Projects a local point the way LofiPlugin's world projection does, returning normalized device x and y. */
	private static float[] project(float pitch, float yaw, float[] camera, float[] point)
	{
		float[] projection = Mat4.projection(765, 503, 50);
		Mat4.mul(projection, Mat4.rotateX(pitch));
		Mat4.mul(projection, Mat4.rotateY(yaw));
		Mat4.mul(projection, Mat4.translate(-camera[0], -camera[1], -camera[2]));
		float[] clip = new float[4];
		for (int row = 0; row < 4; row++)
		{
			clip[row] = projection[row] * point[0] + projection[4 + row] * point[1] + projection[8 + row] * point[2] + projection[12 + row];
		}
		return new float[]{clip[0] / clip[3], clip[1] / clip[3], clip[3]};
	}

	@Test
	public void lookDirectionPointsAtTheCentreOfTheScreen()
	{
		float[] camera = {6400, -1200, 5200};
		for (float yaw : new float[]{0, 1.2f, 2.5f, 4.4f})
		{
			for (float pitch : new float[]{0.2f, 0.7f, 1.4f})
			{
				float[] look = SpriteManager.lookDirection(pitch, yaw);
				assertEquals(1, look[0] * look[0] + look[1] * look[1] + look[2] * look[2], EPSILON);

				float[] ahead = {camera[0] + look[0] * 1000, camera[1] + look[1] * 1000, camera[2] + look[2] * 1000};
				float[] screen = project(pitch, yaw, camera, ahead);
				String message = "pitch " + pitch + " yaw " + yaw;
				assertEquals(message, 0, screen[0], EPSILON);
				assertEquals(message, 0, screen[1], EPSILON);
				assertTrue(message + " in front", screen[2] > 0);
			}
		}
	}

	@Test
	public void cameraTiltedDownLooksDown()
	{
		// World up is -y, and the game's pitch is positive when looking down at the ground
		assertTrue(SpriteManager.lookDirection(0.7f, 0)[1] > 0.5f);
	}

	@Test
	public void flattenedCardKeepsWidthAndHeightButLosesDepth()
	{
		float[] look = SpriteManager.lookDirection(0.4f, 1.1f);
		SpriteManager.SpriteView view = new SpriteManager.SpriteView(look, 0, false);
		float[] right = {view.rx, 0, view.rz};
		float[] up = {view.uPx, view.uPy, view.uPz};

		float[] out = new float[3];
		// A point 100 units along the look direction flattens to almost no depth
		view.flatten(look[0] * 100, look[1] * 100, look[2] * 100, 1, out);
		assertEquals(6, dot(out, look), 0.01f);

		view.flatten(right[0] * 50, 0, right[2] * 50, 1, out);
		assertEquals(50, dot(out, right), EPSILON);

		view.flatten(up[0] * 80, up[1] * 80, up[2] * 80, 1, out);
		assertEquals(80, dot(out, up), EPSILON);
	}

	private static float dot(float[] a, float[] b)
	{
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}
}
