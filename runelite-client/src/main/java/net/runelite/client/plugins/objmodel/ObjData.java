/*
 * Copyright (c) 2025, bec
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.objmodel;

import javax.annotation.Nullable;
import lombok.Getter;

/**
 * Parsed OBJ geometry ready for injection into RuneLite's model system.
 *
 * <p>Coordinates are already converted to OSRS convention (Y-down, Z-negated) and
 * scaled by the configured scale factor.  Face colors are in Jagex HSL format.
 *
 * <h3>Vertex layout</h3>
 * <p>When texture data is present, {@link #verticesX}/{@link #verticesY}/{@link #verticesZ}
 * contain <em>both</em> the geometry vertices (indices 0 … geometryVertexCount−1) and
 * phantom UV-basis vertices appended at the end.  {@link #faceIndices1}/{@code 2}/{@code 3}
 * only reference the geometry section; {@link #texIndices1}/{@code 2}/{@code 3} reference the
 * phantom section.
 *
 * <h3>UV mapping</h3>
 * <p>The OSRS software renderer derives UV coordinates for each textured face by
 * projecting the face's vertex positions onto a UV-basis triangle defined by three
 * phantom vertices (T0, T1, T2).  {@link ObjImporter} solves for T0/T1/T2 from the
 * OBJ UV coordinates so the texture maps correctly.
 *
 * <ul>
 *   <li>{@link #faceTextures} – per-face texture-provider slot ID, {@code -1} = untextured.</li>
 *   <li>{@link #texIndices1}/{@code 2}/{@code 3} – the three phantom vertex indices that
 *       form the UV-basis triangle for each UV-triangle slot (0-indexed).</li>
 *   <li>{@link #textureCoords} – per-face index into the {@code texIndices} arrays
 *       (0-based), or {@code -1} to use the face's own vertices as the UV basis
 *       (whole texture stretched across the face).</li>
 * </ul>
 */
@Getter
class ObjData
{
	/** OSRS X coordinates for each vertex (geometry vertices, then phantom UV vertices). */
	private final float[] verticesX;
	/** OSRS Y coordinates (down-positive) for each vertex. */
	private final float[] verticesY;
	/** OSRS Z coordinates for each vertex. */
	private final float[] verticesZ;

	/** First vertex index per triangle face (0-based, geometry vertices only). */
	private final int[] faceIndices1;
	private final int[] faceIndices2;
	private final int[] faceIndices3;

	/** Per-face color in Jagex HSL format. */
	private final short[] faceColors;

	/**
	 * Per-face texture-provider slot ID, or {@code -1} if the face is untextured.
	 * Length matches {@link #faceIndices1}. Null when no textures are referenced.
	 */
	@Nullable
	private final short[] faceTextures;

	/**
	 * First phantom vertex index of each UV-basis triangle.
	 * Length = number of unique UV triangles (at most 255).
	 */
	@Nullable
	private final short[] texIndices1;

	/** Second phantom vertex index of each UV-basis triangle. */
	@Nullable
	private final short[] texIndices2;

	/** Third phantom vertex index of each UV-basis triangle. */
	@Nullable
	private final short[] texIndices3;

	/**
	 * Per-face index (0-based) into the {@code texIndices} arrays, selecting the
	 * UV-basis triangle for this face.  A value of {@code -1} (byte 0xFF) means
	 * "use the face's own vertices as the UV basis".
	 * Length matches {@link #faceIndices1}.
	 */
	@Nullable
	private final byte[] textureCoords;

	/** Extended UV data for models with more than 255 UV triangles; read by the GPU via ExtendedUV. */
	int[] extTexCoords, extTexIdx1, extTexIdx2, extTexIdx3;

	ObjData(float[] verticesX, float[] verticesY, float[] verticesZ,
		int[] faceIndices1, int[] faceIndices2, int[] faceIndices3,
		short[] faceColors)
	{
		this(verticesX, verticesY, verticesZ,
			faceIndices1, faceIndices2, faceIndices3, faceColors,
			null, null, null, null, null);
	}

	ObjData(float[] verticesX, float[] verticesY, float[] verticesZ,
		int[] faceIndices1, int[] faceIndices2, int[] faceIndices3,
		short[] faceColors,
		@Nullable short[] faceTextures,
		@Nullable short[] texIndices1,
		@Nullable short[] texIndices2,
		@Nullable short[] texIndices3,
		@Nullable byte[] textureCoords)
	{
		this.verticesX = verticesX;
		this.verticesY = verticesY;
		this.verticesZ = verticesZ;
		this.faceIndices1 = faceIndices1;
		this.faceIndices2 = faceIndices2;
		this.faceIndices3 = faceIndices3;
		this.faceColors = faceColors;
		this.faceTextures = faceTextures;
		this.texIndices1 = texIndices1;
		this.texIndices2 = texIndices2;
		this.texIndices3 = texIndices3;
		this.textureCoords = textureCoords;
	}

	boolean hasTextures()
	{
		return faceTextures != null;
	}

	/** Number of geometry faces that have a valid texture slot assigned. */
	int getTexFaceCount()
	{
		if (faceTextures == null)
		{
			return 0;
		}
		int count = 0;
		for (short s : faceTextures)
		{
			if (s != -1)
			{
				count++;
			}
		}
		return count;
	}
}
