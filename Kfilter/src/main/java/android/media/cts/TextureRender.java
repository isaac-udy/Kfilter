package android.media.cts;
/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.util.Log;

import com.isaacudy.kfilter.Kfilter;
import com.isaacudy.kfilter.utils.KGLUKt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Code for rendering a texture onto a surface using OpenGL ES 2.0.
 */
public class TextureRender {
	private static final String TAG = "TextureRender";
	private static final int FLOAT_SIZE_BYTES = 4;
	private static final int TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES;
	private static final int TRIANGLE_VERTICES_DATA_POS_OFFSET = 0;
	private static final int TRIANGLE_VERTICES_DATA_UV_OFFSET = 3;
	private final float[] mTriangleVerticesData = {
			// X, Y, Z, U, V
			-1.0f, -1.0f, 0, 0.f, 0.f,
			1.0f, -1.0f, 0, 1.f, 0.f,
			-1.0f, 1.0f, 0, 0.f, 1.f,
			1.0f, 1.0f, 0, 1.f, 1.f,
	};
	private FloatBuffer mTriangleVertices;
	private static final String VERTEX_SHADER =
			"uniform mat4 uMVPMatrix;\n" +
					"uniform mat4 uSTMatrix;\n" +
					"attribute vec4 aPosition;\n" +
					"attribute vec4 aTextureCoord;\n" +
					"varying vec2 textureCoord;\n" +
					"void main() {\n" +
					"  gl_Position = uMVPMatrix * aPosition;\n" +
					"  textureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
					"}\n";
	private float[] mMVPMatrix = new float[16];
	private float[] mSTMatrix = new float[16];
	private int mProgram;
	private int muMVPMatrixHandle;
	private int muSTMatrixHandle;
	private int maPositionHandle;
	private int maTextureHandle;

	private Kfilter mKfilter;

	public TextureRender(Kfilter kfilterShader) {
		mKfilter = kfilterShader;

		mTriangleVertices = ByteBuffer.allocateDirect(
				mTriangleVerticesData.length * FLOAT_SIZE_BYTES)
				.order(ByteOrder.nativeOrder()).asFloatBuffer();
		mTriangleVertices.put(mTriangleVerticesData).position(0);
		Matrix.setIdentityM(mSTMatrix, 0);
	}

	public int getTextureId() {
		return mKfilter.getExternalTexture().getId();
	}

	public void drawFrame(SurfaceTexture st) {
		KGLUKt.checkGlError("onDrawFrame start");
		st.getTransformMatrix(mSTMatrix);
		GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
		GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);
		GLES20.glUseProgram(mProgram);
		KGLUKt.checkGlError("ASDASD");
		mKfilter.apply();
		GLES20.glGetError(); // It appears "apply" can sometimes cause erroneous errors??
		mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET);
		GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false,
									 TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
		KGLUKt.checkGlError("glVertexAttribPointer maPosition");
		GLES20.glEnableVertexAttribArray(maPositionHandle);
		KGLUKt.checkGlError("glEnableVertexAttribArray maPositionHandle");
		mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET);
		GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false,
									 TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
		KGLUKt.checkGlError("glVertexAttribPointer maTextureHandle");
		GLES20.glEnableVertexAttribArray(maTextureHandle);
		KGLUKt.checkGlError("glEnableVertexAttribArray maTextureHandle");
		Matrix.setIdentityM(mMVPMatrix, 0);
		GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0);
		GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0);
		GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
		KGLUKt.checkGlError("glDrawArrays");
		GLES20.glFinish();
	}

	/**
	 * Initializes GL state.  Call this after the EGL surface has been created and made current.
	 */
	public void surfaceCreated() {
		mProgram = KGLUKt.createProgram(VERTEX_SHADER, mKfilter.getShader());
		if (mProgram == 0) {
			throw new RuntimeException("failed creating program");
		}
		maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
		KGLUKt.checkGlError("glGetAttribLocation aPosition");
		if (maPositionHandle == -1) {
			throw new RuntimeException("Could not get attrib location for aPosition");
		}
		maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
		KGLUKt.checkGlError("glGetAttribLocation aTextureCoord");
		if (maTextureHandle == -1) {
			throw new RuntimeException("Could not get attrib location for aTextureCoord");
		}
		muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
		KGLUKt.checkGlError("glGetUniformLocation uMVPMatrix");
		if (muMVPMatrixHandle == -1) {
			throw new RuntimeException("Could not get attrib location for uMVPMatrix");
		}
		muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix");
		KGLUKt.checkGlError("glGetUniformLocation uSTMatrix");
		if (muSTMatrixHandle == -1) {
			throw new RuntimeException("Could not get attrib location for uSTMatrix");
		}
		mKfilter.initialise(mProgram);
	}
}