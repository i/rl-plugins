package com.github.i.lofi;

import org.junit.Test;

import static org.junit.Assert.*;

public class Mat4Test
{
	@Test
	public void inverseUndoesTheSceneProjection()
	{
		// Built the same way as LofiPlugin's world projection
		float[] projection = Mat4.scale(512, 512, 1);
		Mat4.mul(projection, Mat4.projection(765, 503, 50));
		Mat4.mul(projection, Mat4.rotateX(0.6f));
		Mat4.mul(projection, Mat4.rotateY(1.3f));
		Mat4.mul(projection, Mat4.translate(-6400, 800, -5200));

		float[] inverse = Mat4.inverse(projection);
		assertNotNull(inverse);

		float[] product = projection.clone();
		Mat4.mul(product, inverse);
		for (int column = 0; column < 4; column++)
		{
			for (int row = 0; row < 4; row++)
			{
				float expected = column == row ? 1 : 0;
				assertEquals("[" + row + "][" + column + "]", expected, product[column * 4 + row], 1e-3f);
			}
		}
	}

	@Test
	public void singularMatrixHasNoInverse()
	{
		assertNull(Mat4.inverse(new float[16]));
	}
}
