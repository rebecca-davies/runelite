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

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.JagexColor;

/**
 * Parses Wavefront .obj files (and optional .mtl sidecar) into {@link ObjData}.
 *
 * <h3>Colour sources (in priority order per face)</h3>
 * <ol>
 *   <li><b>Vertex colours</b> – non-standard {@code v x y z r g b} extension.</li>
 *   <li><b>MTL diffuse (Kd)</b> – per-material colour from {@code usemtl} / {@code Kd}.</li>
 *   <li><b>Default</b> – medium grey if no colour information is present.</li>
 * </ol>
 *
 * <h3>Texture support</h3>
 * <p>When the caller passes a {@code texSlots} map (material name → TextureProvider slot ID),
 * UV coordinates from {@code vt} lines are used to compute per-face UV-basis triangles
 * (T0, T1, T2) that are stored as phantom vertices in the returned {@link ObjData}.
 * The OSRS UV system then projects each face's actual vertex positions onto this
 * triangle to recover the original UV values.
 *
 * <p>OBJ has V=0 at the bottom (OpenGL convention); OSRS stores pixels top-to-bottom
 * (V=0 → top row).  V coordinates are flipped ({@code v_osrs = 1 − v_obj}) during
 * basis-triangle computation so the texture appears the right way up.
 *
 * <h3>Gouraud shading</h3>
 * <p>Coincident vertices are merged so that {@code ModelData.light()} can average
 * normals and produce smooth shading.
 *
 * <h3>Coordinate conversion</h3>
 * <p>OBJ is Y-up; OSRS is Y-down with Z flipped:
 * {@code osrsX = objX}, {@code osrsY = -objY}, {@code osrsZ = -objZ}.
 */
@Slf4j
class ObjImporter
{
	/** Fallback per-face colour – medium grey, unsaturated. */
	private static final short DEFAULT_COLOR = JagexColor.packHSL(0, 0, 50);

	// -------------------------------------------------------------------------
	// Entry points
	// -------------------------------------------------------------------------

	/**
	 * Convenience overload – no texture images (geometry + MTL colours only).
	 */
	ObjData load(File file, int scale) throws IOException
	{
		return load(file, scale, Collections.emptyMap());
	}

	/**
	 * Loads the OBJ with proper UV-mapped textures via TextureProvider slots.
	 * Computes phantom UV-basis vertices for the OSRS software renderer.
	 */
	ObjData loadWithTextures(File file, int scale, Map<String, Short> texSlots,
		Map<String, BufferedImage> texImages) throws IOException
	{
		Map<String, Short> materialColors = new HashMap<>();
		Map<String, String> materialTexFiles = new HashMap<>();
		loadMtl(file, materialColors, materialTexFiles);

		List<float[]> rawVerts = new ArrayList<>();
		List<Integer> rawVertRgb = new ArrayList<>();
		boolean hasVertexColors = false;
		List<float[]> rawUVs = new ArrayList<>();
		List<int[]> rawFaces = new ArrayList<>();
		List<int[]> rawFaceUvs = new ArrayList<>();
		List<Short> faceMaterialColors = new ArrayList<>();
		List<Short> faceMaterialTexSlots = new ArrayList<>();
		List<BufferedImage> faceMaterialImages = new ArrayList<>();

		short currentColor = DEFAULT_COLOR;
		short currentTexSlot = -1;
		BufferedImage currentImage = null;

		try (BufferedReader br = new BufferedReader(new FileReader(file)))
		{
			String line;
			while ((line = br.readLine()) != null)
			{
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#"))
				{
					continue;
				}
				if (line.startsWith("v "))
				{
					int rgb = parseVertex(line, rawVerts);
					rawVertRgb.add(rgb);
					if (rgb != -1) hasVertexColors = true;
				}
				else if (line.startsWith("vt "))
				{
					parseUV(line, rawUVs);
				}
				else if (line.startsWith("usemtl "))
				{
					String name = line.substring(7).trim();
					currentColor = materialColors.getOrDefault(name, DEFAULT_COLOR);
					currentTexSlot = texSlots.getOrDefault(name, (short) -1);
					currentImage = texImages.get(name);
				}
				else if (line.startsWith("f "))
				{
					parseFaceWithSlots(line, rawVerts.size(), rawUVs.size(),
						rawFaces, faceMaterialColors, currentColor,
						rawFaceUvs, faceMaterialTexSlots, currentTexSlot);
					// Add image for each triangle produced by fan-triangulation
					int tokens = line.substring(2).trim().split("\\s+").length;
					for (int t = 0; t < Math.max(1, tokens - 2); t++)
					{
						faceMaterialImages.add(currentImage);
					}
				}
			}
		}

		if (rawVerts.isEmpty()) throw new IOException("OBJ file contains no vertices: " + file);
		if (rawFaces.isEmpty()) throw new IOException("OBJ file contains no faces: " + file);

		short[] faceColors = computeFaceColorsSimple(rawFaces, rawVertRgb,
			faceMaterialColors, hasVertexColors);
		int[] remap = buildVertexRemap(rawVerts);

		return assembleFinalWithUV(rawVerts, remap, rawFaces, faceColors, scale,
			rawUVs, rawFaceUvs, toShortArray(faceMaterialTexSlots), faceMaterialImages);
	}

	private void parseFaceWithSlots(String line, int vertCount, int uvCount,
		List<int[]> faces, List<Short> colors, short color,
		List<int[]> faceUvs, List<Short> texSlots, short texSlot)
	{
		String[] tokens = line.substring(2).trim().split("\\s+");
		int[] vIndices = new int[tokens.length];
		int[] uvIndices = new int[tokens.length];
		for (int i = 0; i < tokens.length; i++)
		{
			String[] parts = tokens[i].split("/");
			try { vIndices[i] = Integer.parseInt(parts[0]); vIndices[i] = vIndices[i] > 0 ? vIndices[i] - 1 : vertCount + vIndices[i]; }
			catch (NumberFormatException e) { return; }
			uvIndices[i] = -1;
			if (parts.length > 1 && !parts[1].isEmpty())
			{
				try { int idx = Integer.parseInt(parts[1]); uvIndices[i] = idx > 0 ? idx - 1 : uvCount + idx; if (uvIndices[i] < 0 || uvIndices[i] >= uvCount) uvIndices[i] = -1; }
				catch (NumberFormatException ignored) {}
			}
		}
		for (int i = 1; i < vIndices.length - 1; i++)
		{
			faces.add(new int[]{vIndices[0], vIndices[i], vIndices[i + 1]});
			faceUvs.add(new int[]{uvIndices[0], uvIndices[i], uvIndices[i + 1]});
			colors.add(color);
			texSlots.add(texSlot);
		}
	}

	private short[] computeFaceColorsSimple(List<int[]> faces, List<Integer> vertRgb,
		List<Short> materialColors, boolean hasVertexColors)
	{
		short[] out = new short[faces.size()];
		for (int i = 0; i < faces.size(); i++)
		{
			if (hasVertexColors)
			{
				int[] f = faces.get(i);
				int rgb0 = vertRgb.get(f[0]), rgb1 = vertRgb.get(f[1]), rgb2 = vertRgb.get(f[2]);
				if (rgb0 != -1 && rgb1 != -1 && rgb2 != -1)
				{
					int avgR = (((rgb0 >> 16) & 0xFF) + ((rgb1 >> 16) & 0xFF) + ((rgb2 >> 16) & 0xFF)) / 3;
					int avgG = (((rgb0 >> 8) & 0xFF) + ((rgb1 >> 8) & 0xFF) + ((rgb2 >> 8) & 0xFF)) / 3;
					int avgB = ((rgb0 & 0xFF) + (rgb1 & 0xFF) + (rgb2 & 0xFF)) / 3;
					out[i] = JagexColor.rgbToHSL((avgR << 16) | (avgG << 8) | avgB, 1.0);
					continue;
				}
			}
			out[i] = materialColors.get(i);
		}
		return out;
	}

	private ObjData assembleFinalWithUV(List<float[]> rawVerts, int[] remap,
		List<int[]> rawFaces, short[] faceColors, int scale,
		List<float[]> rawUVs, List<int[]> rawFaceUvs, short[] faceTex,
		List<BufferedImage> faceImages)
	{
		int mergedCount = 0;
		for (int r : remap) if (r >= mergedCount) mergedCount = r + 1;
		float scaleFactor = scale / 128f;
		float[] vx = new float[mergedCount], vy = new float[mergedCount], vz = new float[mergedCount];
		for (int i = 0; i < rawVerts.size(); i++)
		{
			float[] v = rawVerts.get(i);
			int ri = remap[i];
			vx[ri] = v[0] * scaleFactor; vy[ri] = -v[1] * scaleFactor; vz[ri] = -v[2] * scaleFactor;
		}
		groundModel(vy, mergedCount);
		int fCount = rawFaces.size();
		int[] fi1 = new int[fCount], fi2 = new int[fCount], fi3 = new int[fCount];
		for (int i = 0; i < fCount; i++)
		{
			int[] f = rawFaces.get(i);
			fi1[i] = remap[f[0]]; fi2[i] = remap[f[1]]; fi3[i] = remap[f[2]];
		}
		if (rawUVs.isEmpty() || faceTex == null)
		{
			return new ObjData(vx, vy, vz, fi1, fi2, fi3, faceColors);
		}

		// ---------------------------------------------------------------
		// UV allocation with solid-colour optimisation
		// ---------------------------------------------------------------
		// Faces whose UV region is a single solid colour don't need a UV
		// slot — they can use faceColors instead.  This frees UV slots for
		// faces that actually need per-pixel texture detail.
		// ---------------------------------------------------------------

		short[] faceTextures = new short[fCount];
		Arrays.fill(faceTextures, (short) -1);
		byte[] textureCoords = new byte[fCount];
		Arrays.fill(textureCoords, (byte) -1);
		int[] extTexCoords = new int[fCount];
		Arrays.fill(extTexCoords, -1);
		boolean anyTextured = false;

		Map<String, Integer> uvTriangleMap = new HashMap<>();
		List<float[]> phantomVerts = new ArrayList<>();
		int numSlots = 0;
		int solidCount = 0;
		int overflowCount = 0;

		for (int i = 0; i < fCount; i++)
		{
			if (faceTex[i] == -1) continue;

			BufferedImage img = (i < faceImages.size()) ? faceImages.get(i) : null;

			int[] uvIdxs = rawFaceUvs.get(i);
			if (uvIdxs == null || uvIdxs[0] < 0 || uvIdxs[1] < 0 || uvIdxs[2] < 0)
			{
				// No UV data — colour-bake if image available, otherwise texture
				if (img != null)
				{
					faceColors[i] = JagexColor.rgbToHSL(sampleTexture(img, 0.5f, 0.5f), 1.0);
					solidCount++;
				}
				else
				{
					faceTextures[i] = faceTex[i];
					anyTextured = true;
				}
				continue;
			}

			float u0 = rawUVs.get(uvIdxs[0])[0], v0 = rawUVs.get(uvIdxs[0])[1];
			float u1 = rawUVs.get(uvIdxs[1])[0], v1 = rawUVs.get(uvIdxs[1])[1];
			float u2 = rawUVs.get(uvIdxs[2])[0], v2 = rawUVs.get(uvIdxs[2])[1];

			// Check if this face's UV region is a solid colour — if so, bake the
			// colour into faceColors and skip the UV slot entirely.
			if (img != null && isSolidColourRegion(img, u0, v0, u1, v1, u2, v2))
			{
				float cu = (u0 + u1 + u2) / 3f, cv = (v0 + v1 + v2) / 3f;
				faceColors[i] = JagexColor.rgbToHSL(sampleTexture(img, cu, cv), 1.0);
				// leave faceTextures[i] = -1 (no texture, use face colour)
				solidCount++;
				continue;
			}

			float det = u0 * (v1 - v2) + v0 * (u2 - u1) + (u1 * v2 - u2 * v1);
			if (Math.abs(det) < 1e-6f)
			{
				// Degenerate UV triangle — colour-bake from centroid
				if (img != null)
				{
					float cu = (u0 + u1 + u2) / 3f, cv = (v0 + v1 + v2) / 3f;
					faceColors[i] = JagexColor.rgbToHSL(sampleTexture(img, cu, cv), 1.0);
					solidCount++;
				}
				else
				{
					faceTextures[i] = faceTex[i];
					anyTextured = true;
				}
				continue;
			}
			float inv = 1f / det;

			float p0x = vx[fi1[i]], p0y = vy[fi1[i]], p0z = vz[fi1[i]];
			float p1x = vx[fi2[i]], p1y = vy[fi2[i]], p1z = vz[fi2[i]];
			float p2x = vx[fi3[i]], p2y = vy[fi3[i]], p2z = vz[fi3[i]];

			float ax = ((v1 - v2) * p0x + (v2 - v0) * p1x + (v0 - v1) * p2x) * inv;
			float ay = ((v1 - v2) * p0y + (v2 - v0) * p1y + (v0 - v1) * p2y) * inv;
			float az = ((v1 - v2) * p0z + (v2 - v0) * p1z + (v0 - v1) * p2z) * inv;
			float bx = ((u2 - u1) * p0x + (u0 - u2) * p1x + (u1 - u0) * p2x) * inv;
			float by = ((u2 - u1) * p0y + (u0 - u2) * p1y + (u1 - u0) * p2y) * inv;
			float bz = ((u2 - u1) * p0z + (u0 - u2) * p1z + (u1 - u0) * p2z) * inv;
			float cx = ((u1 * v2 - u2 * v1) * p0x + (u2 * v0 - u0 * v2) * p1x + (u0 * v1 - u1 * v0) * p2x) * inv;
			float cy = ((u1 * v2 - u2 * v1) * p0y + (u2 * v0 - u0 * v2) * p1y + (u0 * v1 - u1 * v0) * p2y) * inv;
			float cz = ((u1 * v2 - u2 * v1) * p0z + (u2 * v0 - u0 * v2) * p1z + (u0 * v1 - u1 * v0) * p2z) * inv;

			float t0x = cx, t0y = cy, t0z = cz;
			float t1x = cx + ax, t1y = cy + ay, t1z = cz + az;
			float t2x = cx + bx, t2y = cy + by, t2z = cz + bz;

			String key = Float.toHexString(t0x) + ',' + Float.toHexString(t0y) + ',' + Float.toHexString(t0z) + ';'
				+ Float.toHexString(t1x) + ',' + Float.toHexString(t1y) + ',' + Float.toHexString(t1z) + ';'
				+ Float.toHexString(t2x) + ',' + Float.toHexString(t2y) + ',' + Float.toHexString(t2z);
			Integer existing = uvTriangleMap.get(key);

			int uvIdx;
			if (existing != null) { uvIdx = existing; }
			else
			{
				uvIdx = numSlots++;
				uvTriangleMap.put(key, uvIdx);
				phantomVerts.add(new float[]{t0x, t0y, t0z});
				phantomVerts.add(new float[]{t1x, t1y, t1z});
				phantomVerts.add(new float[]{t2x, t2y, t2z});
			}

			faceTextures[i] = faceTex[i];
			extTexCoords[i] = uvIdx;
			if (uvIdx < 255) textureCoords[i] = (byte) uvIdx;
			anyTextured = true;
		}

		log.info("ObjModel: UV slots={}, solid-colour faces={}", numSlots, solidCount);

		int totalVerts = mergedCount + phantomVerts.size();
		float[] fullVx = Arrays.copyOf(vx, totalVerts);
		float[] fullVy = Arrays.copyOf(vy, totalVerts);
		float[] fullVz = Arrays.copyOf(vz, totalVerts);
		for (int j = 0; j < phantomVerts.size(); j++)
		{
			float[] pv = phantomVerts.get(j);
			fullVx[mergedCount + j] = pv[0]; fullVy[mergedCount + j] = pv[1]; fullVz[mergedCount + j] = pv[2];
		}

		int capped = Math.min(numSlots, 255);
		short[] ti1 = new short[capped], ti2 = new short[capped], ti3 = new short[capped];
		for (int k = 0; k < capped; k++)
		{
			ti1[k] = (short) (mergedCount + k * 3);
			ti2[k] = (short) (mergedCount + k * 3 + 1);
			ti3[k] = (short) (mergedCount + k * 3 + 2);
		}

		int[] eti1 = new int[numSlots], eti2 = new int[numSlots], eti3 = new int[numSlots];
		for (int k = 0; k < numSlots; k++)
		{
			eti1[k] = mergedCount + k * 3;
			eti2[k] = mergedCount + k * 3 + 1;
			eti3[k] = mergedCount + k * 3 + 2;
		}

		ObjData r = new ObjData(fullVx, fullVy, fullVz, fi1, fi2, fi3, faceColors,
			faceTextures, ti1, ti2, ti3, textureCoords);
		r.extTexCoords = extTexCoords;
		r.extTexIdx1 = eti1;
		r.extTexIdx2 = eti2;
		r.extTexIdx3 = eti3;
		return r;
	}



	/**
	 * Loads and parses the given .obj file.
	 *
	 * <p>When {@code texImages} is non-empty, each face whose material maps to
	 * an image has its colour <em>sampled</em> from the PNG at the UV centroid,
	 * giving a flat-shaded approximation of the full texture.  This avoids
	 * injecting into the game's texture system entirely.
	 *
	 * @param file      path to the .obj file
	 * @param scale     scale factor – final OSRS unit = {@code objUnit * scale / 128f}
	 * @param texImages map from material name to loaded texture image; may be empty
	 * @return parsed geometry ready for {@link ModelDataFactory}
	 * @throws IOException if the file cannot be read or contains no geometry
	 */
	ObjData load(File file, int scale, Map<String, BufferedImage> texImages) throws IOException
	{
		// Parse MTL for both colours and texture-file mappings
		Map<String, Short> materialColors = new HashMap<>();
		Map<String, String> materialTexFiles = new HashMap<>();
		loadMtl(file, materialColors, materialTexFiles);

		// Raw, pre-merge geometry
		List<float[]> rawVerts = new ArrayList<>();
		List<Integer> rawVertRgb = new ArrayList<>();
		boolean hasVertexColors = false;

		// UV coordinates (v flipped for OSRS convention)
		List<float[]> rawUVs = new ArrayList<>();

		// Per-triangle data (after fan-triangulation)
		List<int[]> rawFaces = new ArrayList<>();
		List<int[]> rawFaceUvs = new ArrayList<>();
		List<Short> faceMaterialColors = new ArrayList<>();
		List<BufferedImage> faceMaterialImages = new ArrayList<>();

		short currentColor = DEFAULT_COLOR;
		BufferedImage currentImage = null;

		try (BufferedReader br = new BufferedReader(new FileReader(file)))
		{
			String line;
			while ((line = br.readLine()) != null)
			{
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#"))
				{
					continue;
				}

				if (line.startsWith("v "))
				{
					int rgb = parseVertex(line, rawVerts);
					rawVertRgb.add(rgb);
					if (rgb != -1)
					{
						hasVertexColors = true;
					}
				}
				else if (line.startsWith("vt "))
				{
					parseUV(line, rawUVs);
				}
				else if (line.startsWith("usemtl "))
				{
					String name = line.substring(7).trim();
					currentColor = materialColors.getOrDefault(name, DEFAULT_COLOR);
					currentImage = texImages.get(name);
				}
				else if (line.startsWith("f "))
				{
					parseFace(line, rawVerts.size(), rawUVs.size(),
						rawFaces, faceMaterialColors, currentColor,
						rawFaceUvs, faceMaterialImages, currentImage);
				}
			}
		}

		if (rawVerts.isEmpty())
		{
			throw new IOException("OBJ file contains no vertices: " + file);
		}
		if (rawFaces.isEmpty())
		{
			throw new IOException("OBJ file contains no faces: " + file);
		}

		// Subdivide textured faces for higher colour resolution.
		// Each level splits every face into 4 sub-faces (midpoint subdivision),
		// giving 4^levels times the colour samples from the texture.
		if (!texImages.isEmpty())
		{
			int levels = 2; // 4^2 = 16 sub-faces per original face
			for (int level = 0; level < levels; level++)
			{
				subdivide(rawVerts, rawVertRgb, rawFaces, rawUVs,
					rawFaceUvs, faceMaterialColors, faceMaterialImages);
			}
			log.info("ObjModel: subdivided to {} faces ({} vertices) for texture colour sampling",
				rawFaces.size(), rawVerts.size());
		}

		short[] faceColors = computeFaceColors(rawFaces, rawVertRgb,
			faceMaterialColors, hasVertexColors,
			faceMaterialImages, rawUVs, rawFaceUvs);

		// When using texture-sampled colours: do NOT merge coincident vertices.
		// Unique vertices per face prevent light() from averaging normals across
		// face boundaries (Gouraud smoothing), giving clean flat-shaded faces
		// instead of the blurry "playdoh" look.
		int[] remap;
		if (!texImages.isEmpty())
		{
			remap = new int[rawVerts.size()];
			for (int i = 0; i < remap.length; i++)
			{
				remap[i] = i;
			}
		}
		else
		{
			remap = buildVertexRemap(rawVerts);
		}

		return assembleFinal(rawVerts, remap, rawFaces, faceColors, scale);
	}

	// -------------------------------------------------------------------------
	// Face subdivision (midpoint)
	// -------------------------------------------------------------------------

	/**
	 * One level of midpoint subdivision: each triangle becomes 4 sub-triangles.
	 * Operates in-place on all the raw data lists.
	 */
	private static void subdivide(
		List<float[]> verts, List<Integer> vertRgb,
		List<int[]> faces, List<float[]> uvs,
		List<int[]> faceUvs, List<Short> faceColors,
		List<BufferedImage> faceImages)
	{
		Map<Long, Integer> edgeMidVerts = new HashMap<>();
		Map<Long, Integer> edgeMidUVs = new HashMap<>();

		int origFaceCount = faces.size();
		List<int[]> newFaces = new ArrayList<>(origFaceCount * 4);
		List<int[]> newFaceUvs = new ArrayList<>(origFaceCount * 4);
		List<Short> newFaceColors = new ArrayList<>(origFaceCount * 4);
		List<BufferedImage> newFaceImages = new ArrayList<>(origFaceCount * 4);

		for (int i = 0; i < origFaceCount; i++)
		{
			int[] f = faces.get(i);
			int[] uv = faceUvs.get(i);
			Short color = faceColors.get(i);
			BufferedImage img = faceImages.get(i);

			int m01 = midVert(f[0], f[1], verts, vertRgb, edgeMidVerts);
			int m12 = midVert(f[1], f[2], verts, vertRgb, edgeMidVerts);
			int m20 = midVert(f[2], f[0], verts, vertRgb, edgeMidVerts);

			int uvm01 = midUV(uv[0], uv[1], uvs, edgeMidUVs);
			int uvm12 = midUV(uv[1], uv[2], uvs, edgeMidUVs);
			int uvm20 = midUV(uv[2], uv[0], uvs, edgeMidUVs);

			newFaces.add(new int[]{f[0], m01, m20});
			newFaces.add(new int[]{m01, f[1], m12});
			newFaces.add(new int[]{m20, m12, f[2]});
			newFaces.add(new int[]{m01, m12, m20});

			newFaceUvs.add(new int[]{uv[0], uvm01, uvm20});
			newFaceUvs.add(new int[]{uvm01, uv[1], uvm12});
			newFaceUvs.add(new int[]{uvm20, uvm12, uv[2]});
			newFaceUvs.add(new int[]{uvm01, uvm12, uvm20});

			for (int j = 0; j < 4; j++)
			{
				newFaceColors.add(color);
				newFaceImages.add(img);
			}
		}

		faces.clear();
		faces.addAll(newFaces);
		faceUvs.clear();
		faceUvs.addAll(newFaceUvs);
		faceColors.clear();
		faceColors.addAll(newFaceColors);
		faceImages.clear();
		faceImages.addAll(newFaceImages);
	}

	private static int midVert(int a, int b, List<float[]> verts,
		List<Integer> vertRgb, Map<Long, Integer> cache)
	{
		long key = a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
		Integer existing = cache.get(key);
		if (existing != null)
		{
			return existing;
		}
		float[] pa = verts.get(a);
		float[] pb = verts.get(b);
		int idx = verts.size();
		verts.add(new float[]{
			(pa[0] + pb[0]) / 2f, (pa[1] + pb[1]) / 2f, (pa[2] + pb[2]) / 2f
		});
		vertRgb.add(-1); // midpoint vertex has no inherent colour
		cache.put(key, idx);
		return idx;
	}

	private static int midUV(int a, int b, List<float[]> uvs, Map<Long, Integer> cache)
	{
		if (a < 0 || b < 0)
		{
			return -1;
		}
		long key = a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
		Integer existing = cache.get(key);
		if (existing != null)
		{
			return existing;
		}
		float[] ua = uvs.get(a);
		float[] ub = uvs.get(b);
		int idx = uvs.size();
		uvs.add(new float[]{(ua[0] + ub[0]) / 2f, (ua[1] + ub[1]) / 2f});
		cache.put(key, idx);
		return idx;
	}

	/**
	 * Scans the OBJ (and any referenced MTL) to extract the mapping
	 * from material name to texture image filename (from {@code map_Kd}).
	 *
	 * @return map of material name → texture file (relative to {@code objFile}'s parent),
	 *         empty if no textures are referenced
	 */
	Map<String, File> scanTextureFiles(File objFile) throws IOException
	{
		Map<String, String> materialTexFiles = new HashMap<>();
		loadMtl(objFile, new HashMap<>(), materialTexFiles);
		Map<String, File> result = new HashMap<>();
		for (Map.Entry<String, String> e : materialTexFiles.entrySet())
		{
			result.put(e.getKey(), resolveTexFile(objFile, e.getValue()));
		}
		return result;
	}

	// -------------------------------------------------------------------------
	// Vertex parsing
	// -------------------------------------------------------------------------

	private int parseVertex(String line, List<float[]> out)
	{
		String[] p = line.substring(2).trim().split("\\s+");
		if (p.length < 3)
		{
			return -1;
		}
		try
		{
			float x = Float.parseFloat(p[0]);
			float y = Float.parseFloat(p[1]);
			float z = Float.parseFloat(p[2]);
			out.add(new float[]{x, y, z});

			if (p.length >= 6)
			{
				int colorOffset = p.length >= 7 ? 4 : 3;
				float r = Float.parseFloat(p[colorOffset]);
				float g = Float.parseFloat(p[colorOffset + 1]);
				float b = Float.parseFloat(p[colorOffset + 2]);
				int ri = Math.min(255, Math.round(r * 255));
				int gi = Math.min(255, Math.round(g * 255));
				int bi = Math.min(255, Math.round(b * 255));
				return (ri << 16) | (gi << 8) | bi;
			}
		}
		catch (NumberFormatException e)
		{
			log.warn("ObjImporter: skipping malformed vertex line: {}", line);
		}
		return -1;
	}

	// -------------------------------------------------------------------------
	// UV parsing
	// -------------------------------------------------------------------------

	/**
	 * Parses a {@code vt u v} line and appends the UV (with V flipped for OSRS
	 * convention) to {@code out}.
	 */
	private void parseUV(String line, List<float[]> out)
	{
		String[] p = line.substring(3).trim().split("\\s+");
		if (p.length < 2)
		{
			return;
		}
		try
		{
			float u = Float.parseFloat(p[0]);
			float v = Float.parseFloat(p[1]);
			// OBJ V=0 is bottom; OSRS pixels are stored top-to-bottom → flip V
			out.add(new float[]{u, 1.0f - v});
		}
		catch (NumberFormatException e)
		{
			log.warn("ObjImporter: skipping malformed vt line: {}", line);
		}
	}

	// -------------------------------------------------------------------------
	// Face parsing
	// -------------------------------------------------------------------------

	private void parseFace(String line, int vertCount, int uvCount,
		List<int[]> faces, List<Short> colors, short color,
		List<int[]> faceUvs, List<BufferedImage> faceImages, BufferedImage image)
	{
		String[] tokens = line.substring(2).trim().split("\\s+");
		int[] vIndices = new int[tokens.length];
		int[] uvIndices = new int[tokens.length];

		for (int i = 0; i < tokens.length; i++)
		{
			String[] parts = tokens[i].split("/");
			try
			{
				int vIdx = Integer.parseInt(parts[0]);
				vIndices[i] = vIdx > 0 ? vIdx - 1 : vertCount + vIdx;
			}
			catch (NumberFormatException e)
			{
				log.warn("ObjImporter: skipping malformed face token: {}", tokens[i]);
				return;
			}

			uvIndices[i] = -1;
			if (parts.length > 1 && !parts[1].isEmpty())
			{
				try
				{
					int uvIdx = Integer.parseInt(parts[1]);
					uvIndices[i] = uvIdx > 0 ? uvIdx - 1 : uvCount + uvIdx;
					// Bounds check
					if (uvIndices[i] < 0 || uvIndices[i] >= uvCount)
					{
						uvIndices[i] = -1;
					}
				}
				catch (NumberFormatException e)
				{
					log.warn("ObjImporter: skipping malformed UV index in face: {}", tokens[i]);
				}
			}
		}

		// Fan-triangulate n-gons: (0,1,2), (0,2,3), …
		for (int i = 1; i < vIndices.length - 1; i++)
		{
			faces.add(new int[]{vIndices[0], vIndices[i], vIndices[i + 1]});
			faceUvs.add(new int[]{uvIndices[0], uvIndices[i], uvIndices[i + 1]});
			colors.add(color);
			faceImages.add(image);
		}
	}

	// -------------------------------------------------------------------------
	// Per-face colour computation
	// -------------------------------------------------------------------------

	private short[] computeFaceColors(List<int[]> faces, List<Integer> vertRgb,
		List<Short> materialColors, boolean hasVertexColors,
		List<BufferedImage> faceImages, List<float[]> rawUVs, List<int[]> rawFaceUvs)
	{
		short[] out = new short[faces.size()];
		int texSampled = 0;
		for (int i = 0; i < faces.size(); i++)
		{
			// Priority 1: sample texture image at UV centroid
			BufferedImage img = faceImages.get(i);
			int[] uvIdxs = rawFaceUvs.get(i);
			if (img != null && uvIdxs != null
				&& uvIdxs[0] >= 0 && uvIdxs[1] >= 0 && uvIdxs[2] >= 0)
			{
				float[] uv0 = rawUVs.get(uvIdxs[0]);
				float[] uv1 = rawUVs.get(uvIdxs[1]);
				float[] uv2 = rawUVs.get(uvIdxs[2]);
				float u = (uv0[0] + uv1[0] + uv2[0]) / 3f;
				float v = (uv0[1] + uv1[1] + uv2[1]) / 3f;
				int rgb = sampleTexture(img, u, v);
				out[i] = JagexColor.rgbToHSL(rgb, 1.0);
				texSampled++;
				continue;
			}

			// Priority 2: vertex colours
			if (hasVertexColors)
			{
				int[] f = faces.get(i);
				int rgb0 = vertRgb.get(f[0]);
				int rgb1 = vertRgb.get(f[1]);
				int rgb2 = vertRgb.get(f[2]);
				if (rgb0 != -1 && rgb1 != -1 && rgb2 != -1)
				{
					int avgR = (((rgb0 >> 16) & 0xFF) + ((rgb1 >> 16) & 0xFF) + ((rgb2 >> 16) & 0xFF)) / 3;
					int avgG = (((rgb0 >> 8) & 0xFF) + ((rgb1 >> 8) & 0xFF) + ((rgb2 >> 8) & 0xFF)) / 3;
					int avgB = ((rgb0 & 0xFF) + (rgb1 & 0xFF) + (rgb2 & 0xFF)) / 3;
					out[i] = JagexColor.rgbToHSL((avgR << 16) | (avgG << 8) | avgB, 1.0);
					continue;
				}
			}

			// Priority 3: MTL diffuse colour
			out[i] = materialColors.get(i);
		}
		if (texSampled > 0)
		{
			log.info("ObjModel: sampled texture colours for {} faces", texSampled);
		}
		return out;
	}

	private static int sampleTexture(BufferedImage img, float u, float v)
	{
		u = u - (float) Math.floor(u);
		v = v - (float) Math.floor(v);
		int x = Math.min((int) (u * img.getWidth()), img.getWidth() - 1);
		int y = Math.min((int) (v * img.getHeight()), img.getHeight() - 1);
		return img.getRGB(x, y) & 0x00FFFFFF;
	}

	/**
	 * Returns {@code true} if the texture region covered by the given UV
	 * triangle is approximately a single solid colour (all sample points
	 * within a small tolerance).
	 */
	private static boolean isSolidColourRegion(BufferedImage img,
		float u0, float v0, float u1, float v1, float u2, float v2)
	{
		// Sample at the 3 corners, centroid, and 3 edge midpoints (7 samples)
		int c0 = sampleTexture(img, u0, v0);
		int c1 = sampleTexture(img, u1, v1);
		int c2 = sampleTexture(img, u2, v2);
		int cc = sampleTexture(img, (u0 + u1 + u2) / 3f, (v0 + v1 + v2) / 3f);
		int m01 = sampleTexture(img, (u0 + u1) / 2f, (v0 + v1) / 2f);
		int m12 = sampleTexture(img, (u1 + u2) / 2f, (v1 + v2) / 2f);
		int m20 = sampleTexture(img, (u2 + u0) / 2f, (v2 + v0) / 2f);

		int threshold = 16; // per-channel tolerance
		return colourClose(c0, c1, threshold)
			&& colourClose(c0, c2, threshold)
			&& colourClose(c0, cc, threshold)
			&& colourClose(c0, m01, threshold)
			&& colourClose(c0, m12, threshold)
			&& colourClose(c0, m20, threshold);
	}

	private static boolean colourClose(int a, int b, int threshold)
	{
		int dr = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF));
		int dg = Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF));
		int db = Math.abs((a & 0xFF) - (b & 0xFF));
		return dr <= threshold && dg <= threshold && db <= threshold;
	}

	// -------------------------------------------------------------------------
	// Vertex merging (for Gouraud shading)
	// -------------------------------------------------------------------------

	private int[] buildVertexRemap(List<float[]> verts)
	{
		Map<String, Integer> posMap = new HashMap<>(verts.size() * 2);
		int[] remap = new int[verts.size()];
		int nextIdx = 0;

		for (int i = 0; i < verts.size(); i++)
		{
			float[] v = verts.get(i);
			String key = Float.toHexString(v[0]) + "," + Float.toHexString(v[1]) + "," + Float.toHexString(v[2]);
			Integer existing = posMap.get(key);
			if (existing == null)
			{
				posMap.put(key, nextIdx);
				remap[i] = nextIdx++;
			}
			else
			{
				remap[i] = existing;
			}
		}
		return remap;
	}

	// -------------------------------------------------------------------------
	// Final array assembly (geometry + UV basis triangles)
	// -------------------------------------------------------------------------

	private ObjData assembleFinal(List<float[]> rawVerts, int[] remap,
		List<int[]> rawFaces, short[] faceColors, int scale)
	{
		// Count merged (deduplicated) geometry vertices
		int mergedCount = 0;
		for (int r : remap)
		{
			if (r >= mergedCount)
			{
				mergedCount = r + 1;
			}
		}

		float scaleFactor = scale / 128f;
		float[] vx = new float[mergedCount];
		float[] vy = new float[mergedCount];
		float[] vz = new float[mergedCount];

		for (int i = 0; i < rawVerts.size(); i++)
		{
			float[] v = rawVerts.get(i);
			int ri = remap[i];
			vx[ri] = v[0] * scaleFactor;
			vy[ri] = -v[1] * scaleFactor;
			vz[ri] = -v[2] * scaleFactor;
		}

		// Shift model so its lowest point sits at Y=0 (ground level).
		// In OSRS, positive Y = down, so max(vy) = lowest point.
		groundModel(vy, mergedCount);

		int fCount = rawFaces.size();
		int[] fi1 = new int[fCount];
		int[] fi2 = new int[fCount];
		int[] fi3 = new int[fCount];

		for (int i = 0; i < fCount; i++)
		{
			int[] f = rawFaces.get(i);
			fi1[i] = remap[f[0]];
			fi2[i] = remap[f[1]];
			fi3[i] = remap[f[2]];
		}

		return new ObjData(vx, vy, vz, fi1, fi2, fi3, faceColors);
	}

	/** Shifts all Y values so the maximum (lowest point in OSRS) sits at 0. */
	private static void groundModel(float[] vy, int count)
	{
		float maxY = Float.NEGATIVE_INFINITY;
		for (int i = 0; i < count; i++)
		{
			if (vy[i] > maxY) maxY = vy[i];
		}
		if (maxY != 0 && maxY != Float.NEGATIVE_INFINITY)
		{
			for (int i = 0; i < count; i++)
			{
				vy[i] -= maxY;
			}
		}
	}

	// -------------------------------------------------------------------------
	// MTL loading
	// -------------------------------------------------------------------------

	/**
	 * Parses the MTL file referenced by {@code objFile} (if any), populating
	 * {@code colorsOut} with per-material Jagex HSL colours and {@code texOut}
	 * with per-material texture filenames (from {@code map_Kd}).
	 */
	private void loadMtl(File objFile, Map<String, Short> colorsOut,
		Map<String, String> texOut) throws IOException
	{
		String mtlName = null;
		try (BufferedReader br = new BufferedReader(new FileReader(objFile)))
		{
			String line;
			while ((line = br.readLine()) != null)
			{
				line = line.trim();
				if (line.startsWith("mtllib "))
				{
					mtlName = line.substring(7).trim();
					break;
				}
			}
		}

		if (mtlName == null)
		{
			return;
		}

		File mtlFile = new File(objFile.getParent(), mtlName);
		if (!mtlFile.exists())
		{
			log.warn("ObjImporter: mtllib '{}' not found alongside {}", mtlName, objFile.getName());
			return;
		}

		parseMtl(mtlFile, colorsOut, texOut);
	}

	private void parseMtl(File mtlFile, Map<String, Short> colorsOut,
		Map<String, String> texOut) throws IOException
	{
		String currentName = null;

		try (BufferedReader br = new BufferedReader(new FileReader(mtlFile)))
		{
			String line;
			while ((line = br.readLine()) != null)
			{
				line = line.trim();
				if (line.startsWith("newmtl "))
				{
					currentName = line.substring(7).trim();
				}
				else if (line.startsWith("Kd ") && currentName != null)
				{
					String[] p = line.substring(3).trim().split("\\s+");
					if (p.length >= 3)
					{
						try
						{
							float r = Float.parseFloat(p[0]);
							float g = Float.parseFloat(p[1]);
							float b = Float.parseFloat(p[2]);
							int rgb = new Color(Math.min(1f, r), Math.min(1f, g), Math.min(1f, b)).getRGB();
							colorsOut.put(currentName, JagexColor.rgbToHSL(rgb, 1.0));
						}
						catch (NumberFormatException e)
						{
							log.warn("ObjImporter: skipping malformed Kd in {}: {}", mtlFile.getName(), line);
						}
					}
				}
				else if (line.startsWith("map_Kd ") && currentName != null)
				{
					String texFile = line.substring(7).trim();
					if (!texFile.isEmpty())
					{
						texOut.put(currentName, texFile);
					}
				}
			}
		}
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	/**
	 * Resolves a texture filename relative to the OBJ file's parent directory,
	 * also checking a {@code textures/} sub-folder.
	 */
	private static File resolveTexFile(File objFile, String filename)
	{
		// Absolute or relative path with separator
		if (filename.contains("/") || filename.contains(File.separator))
		{
			return new File(filename);
		}
		// Same folder as the OBJ
		File candidate = new File(objFile.getParent(), filename);
		if (candidate.exists())
		{
			return candidate;
		}
		// textures/ sub-folder
		return new File(new File(objFile.getParent(), "textures"), filename);
	}

	private static short[] toShortArray(List<Short> list)
	{
		short[] arr = new short[list.size()];
		for (int i = 0; i < list.size(); i++)
		{
			arr[i] = list.get(i);
		}
		return arr;
	}
}
