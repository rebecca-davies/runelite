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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import sun.misc.Unsafe; //NOPMD

/**
 * Creates {@link ModelData} instances from raw geometry arrays using reflection.
 *
 * <p>The RuneLite public API provides no way to construct a {@code ModelData} from
 * scratch — models can only be loaded from the game cache.  This class works around
 * that limitation by:
 * <ol>
 *   <li>Loading any small model from the cache to obtain a reference to the
 *       obfuscated {@code RSModelDefinition} implementation class.</li>
 *   <li>Walking the class's declared fields and matching them to the known
 *       {@link ModelData} interface methods via reference identity (for arrays)
 *       and value comparison (for integer counts).</li>
 *   <li>Using {@link sun.misc.Unsafe#allocateInstance} to create a blank instance
 *       — bypassing any constructor — then writing the geometry arrays directly
 *       into the identified fields.</li>
 * </ol>
 *
 * <h3>Texture UV</h3>
 * <p>After finding a textured model in the cache, the factory locates the
 * {@code faceTextures}, {@code texIndices1/2/3}, and {@code textureCoords} fields
 * on the ModelData class by reference-identity matching.  These are set in
 * {@link #create} so that {@link ModelData#light()} picks them up and the
 * OSRS software renderer derives correct UV coordinates from the phantom
 * UV-basis triangles computed by {@link ObjImporter}.
 *
 * <p>All work happens on the client thread.  Field discovery is performed once and
 * cached; subsequent {@link #create} calls are cheap.
 */
@Slf4j
class ModelDataFactory
{
	private static boolean initialized = false;

	private static Class<?> modelDataClass;
	private static Unsafe unsafe;

	// Basic geometry fields
	private static Field fVertexCount;
	private static Field fVerticesX;
	private static Field fVerticesY;
	private static Field fVerticesZ;
	private static Field fFaceCount;
	private static Field fFaceIndices1;
	private static Field fFaceIndices2;
	private static Field fFaceIndices3;
	private static Field fFaceColors;

	// Texture fields on ModelData (optional; null if discovery failed)
	private static Field fFaceTextures;      // short[]  – per-face texture-provider slot ID
	private static Field fTexIndices1;       // short[]  – UV-basis triangle vertex 0 indices
	private static Field fTexIndices2;       // short[]  – UV-basis triangle vertex 1 indices
	private static Field fTexIndices3;       // short[]  – UV-basis triangle vertex 2 indices
	private static Field fTextureCoords;     // byte[]   – per-face UV-triangle selector
	private static Field fNumTextureFaces;   // int      – number of UV-basis triangles
	private static Field fTexRenderTypes;    // byte[]   – UV mapping type per UV triangle (0=planar)

	// -------------------------------------------------------------------------
	// Initialisation (client thread only)
	// -------------------------------------------------------------------------

	/**
	 * Performs one-time reflection setup.  Must be called on the client thread.
	 *
	 * @return {@code true} if all required (geometry) fields were identified
	 */
	static boolean init(Client client)
	{
		if (initialized)
		{
			return isReady();
		}

		ModelData dummy = loadDummy(client);
		if (dummy == null)
		{
			log.warn("ObjModel: could not load any model for reflection setup");
			initialized = true;
			return false;
		}

		try
		{
			Field uf = Unsafe.class.getDeclaredField("theUnsafe");
			uf.setAccessible(true);
			unsafe = (Unsafe) uf.get(null);
		}
		catch (Exception e)
		{
			log.warn("ObjModel: could not acquire sun.misc.Unsafe", e);
			initialized = true;
			return false;
		}

		modelDataClass = dummy.getClass();
		discoverFields(dummy);

		// Try to discover texture UV fields using a textured model
		ModelData texturedDummy = loadTexturedDummy(client);
		if (texturedDummy != null)
		{
			Model litTexturedDummy = texturedDummy.light();
			discoverTextureFields(texturedDummy, litTexturedDummy);
		}
		else
		{
			log.debug("ObjModel: no textured model found; texture injection unavailable");
		}

		initialized = true;

		if (!isReady())
		{
			log.warn("ObjModel: could not identify all required ModelData fields – "
				+ "vx={} vy={} vz={} fi1={} fi2={} fi3={} fc={} vc={} faceC={}",
				fVerticesX != null, fVerticesY != null, fVerticesZ != null,
				fFaceIndices1 != null, fFaceIndices2 != null, fFaceIndices3 != null,
				fFaceColors != null, fVertexCount != null, fFaceCount != null);
			return false;
		}

		log.info("ObjModel: ModelDataFactory ready (class={}, ft={} ti1={} ti2={} ti3={} tc={})",
			modelDataClass.getName(), fFaceTextures != null,
			fTexIndices1 != null, fTexIndices2 != null, fTexIndices3 != null,
			fTextureCoords != null);
		return true;
	}

	// -------------------------------------------------------------------------
	// Dummy model loading
	// -------------------------------------------------------------------------

	private static ModelData loadDummy(Client client)
	{
		for (int id : new int[]{5809, 43330, 1, 100, 500, 1000})
		{
			ModelData md = client.loadModelData(id);
			if (md != null)
			{
				return md;
			}
		}
		return null;
	}

	/**
	 * Tries a broad range of model IDs to find one that has texture data
	 * ({@code getFaceTextures() != null} and a non-empty lit-model
	 * {@code getTexIndices1()}).
	 */
	private static ModelData loadTexturedDummy(Client client)
	{
		int[][] ranges = {
			{1, 50},
			{100, 150},
			{500, 550},
			{1000, 1020},
			{3000, 3020},
			{5000, 5020},
			{7000, 7020},
			{10000, 10020},
		};
		for (int[] range : ranges)
		{
			for (int id = range[0]; id <= range[1]; id++)
			{
				ModelData md = client.loadModelData(id);
				if (md == null || md.getFaceTextures() == null)
				{
					continue;
				}
				Model lit = md.light();
				if (lit != null && lit.getTexIndices1() != null && lit.getTexIndices1().length > 0)
				{
					log.debug("ObjModel: found textured dummy model id={}", id);
					return md;
				}
			}
		}
		return null;
	}

	// -------------------------------------------------------------------------
	// Geometry field discovery
	// -------------------------------------------------------------------------

	private static void discoverFields(ModelData dummy)
	{
		float[] vx = dummy.getVerticesX();
		float[] vy = dummy.getVerticesY();
		float[] vz = dummy.getVerticesZ();
		int[] fi1 = dummy.getFaceIndices1();
		int[] fi2 = dummy.getFaceIndices2();
		int[] fi3 = dummy.getFaceIndices3();
		short[] fc = dummy.getFaceColors();
		int vc = dummy.getVerticesCount();
		int faceC = dummy.getFaceCount();

		List<Field> all = getAllFields(modelDataClass);
		List<Field> vcCandidates = new ArrayList<>();
		List<Field> fcCandidates = new ArrayList<>();

		for (Field f : all)
		{
			if (Modifier.isStatic(f.getModifiers()))
			{
				continue;
			}
			f.setAccessible(true);

			Class<?> type = f.getType();
			try
			{
				if (type == float[].class)
				{
					float[] val = (float[]) f.get(dummy);
					if (val == vx)
					{
						fVerticesX = f;
					}
					else if (val == vy)
					{
						fVerticesY = f;
					}
					else if (val == vz)
					{
						fVerticesZ = f;
					}
				}
				else if (type == int[].class)
				{
					int[] val = (int[]) f.get(dummy);
					if (val == fi1)
					{
						fFaceIndices1 = f;
					}
					else if (val == fi2)
					{
						fFaceIndices2 = f;
					}
					else if (val == fi3)
					{
						fFaceIndices3 = f;
					}
				}
				else if (type == short[].class)
				{
					short[] val = (short[]) f.get(dummy);
					if (val == fc)
					{
						fFaceColors = f;
					}
				}
				else if (type == int.class)
				{
					int val = f.getInt(dummy);
					if (val == vc)
					{
						vcCandidates.add(f);
					}
					if (val == faceC)
					{
						fcCandidates.add(f);
					}
				}
			}
			catch (Exception ignored)
			{
			}
		}

		fVertexCount = pickNearest(vcCandidates, fVerticesX, all);
		if (fVertexCount != null)
		{
			fcCandidates.remove(fVertexCount);
		}
		fFaceCount = pickNearest(fcCandidates, fFaceIndices1, all);
	}

	// -------------------------------------------------------------------------
	// Texture field discovery
	// -------------------------------------------------------------------------

	/**
	 * Discovers the texture-related fields on the {@link ModelData} implementation
	 * class.
	 *
	 * <p>{@code fFaceTextures} is found by reference identity with
	 * {@link ModelData#getFaceTextures()}.
	 *
	 * <p>The {@code texIndices} fields are {@code short[]} on ModelData but exposed
	 * only as {@code int[]} on the lit {@link Model}.  We find them by comparing
	 * element values (short → int widening) between unlit ModelData fields and
	 * {@code m.getTexIndices1/2/3()}.
	 *
	 * <p>{@code textureCoords} is the {@code byte[]} of length {@code faceCount}
	 * that is NOT {@code faceTransparencies}.
	 */
	private static void discoverTextureFields(ModelData md, Model m)
	{
		if (md == null || m == null)
		{
			return;
		}

		List<Field> all = getAllFields(modelDataClass);
		int faceCount = md.getFaceCount();

		short[] ftRef = md.getFaceTextures();
		int[] litTi1 = m.getTexIndices1();
		int[] litTi2 = m.getTexIndices2();
		int[] litTi3 = m.getTexIndices3();
		byte[] transRef = md.getFaceTransparencies();

		for (Field f : all)
		{
			if (Modifier.isStatic(f.getModifiers()) || f == fFaceColors)
			{
				continue;
			}
			f.setAccessible(true);
			Class<?> type = f.getType();
			try
			{
				if (type == short[].class)
				{
					short[] val = (short[]) f.get(md);
					if (val == null)
					{
						continue;
					}
					if (ftRef != null && val == ftRef)
					{
						fFaceTextures = f;
					}
					else if (litTi1 != null && matchesShortToInt(val, litTi1))
					{
						fTexIndices1 = f;
					}
					else if (litTi2 != null && matchesShortToInt(val, litTi2))
					{
						fTexIndices2 = f;
					}
					else if (litTi3 != null && matchesShortToInt(val, litTi3))
					{
						fTexIndices3 = f;
					}
				}
				else if (type == byte[].class)
				{
					byte[] val = (byte[]) f.get(md);
					if (val == null)
					{
						continue;
					}
					if (transRef != null && val == transRef)
					{
						continue;
					}
					if (val.length == faceCount)
					{
						fTextureCoords = f;
					}
					else if (litTi1 != null && val.length == litTi1.length)
					{
						// byte[] with length == numTextureFaces → textureRenderTypes
						fTexRenderTypes = f;
					}
				}
			}
			catch (Exception ignored)
			{
			}
		}

		// Discover numTextureFaces: an int field whose value equals the
		// texIndices array length.  Skip fields already identified as
		// vertexCount or faceCount.
		if (litTi1 != null && litTi1.length > 0)
		{
			int numTexFaces = litTi1.length;
			List<Field> candidates = new ArrayList<>();
			for (Field f : all)
			{
				if (Modifier.isStatic(f.getModifiers()) || f.getType() != int.class
					|| f == fVertexCount || f == fFaceCount)
				{
					continue;
				}
				f.setAccessible(true);
				try
				{
					if (f.getInt(md) == numTexFaces)
					{
						candidates.add(f);
					}
				}
				catch (Exception ignored)
				{
				}
			}
			fNumTextureFaces = pickNearest(candidates, fTexIndices1, all);
		}

		log.info("ObjModel: texture field discovery – ft={} ti1={} ti2={} ti3={} tc={} ntf={} trt={}",
			fFaceTextures != null,
			fTexIndices1 != null, fTexIndices2 != null, fTexIndices3 != null,
			fTextureCoords != null, fNumTextureFaces != null, fTexRenderTypes != null);
	}

	/**
	 * Checks whether a {@code short[]} (ModelData storage) matches an
	 * {@code int[]} (Model interface return type) element-by-element.
	 */
	private static boolean matchesShortToInt(short[] s, int[] ints)
	{
		if (s.length != ints.length)
		{
			return false;
		}
		for (int k = 0; k < s.length; k++)
		{
			if (s[k] != ints[k])
			{
				return false;
			}
		}
		return true;
	}

	// -------------------------------------------------------------------------
	// Field-ordering helpers
	// -------------------------------------------------------------------------

	@Nullable
	private static Field pickNearest(List<Field> candidates, @Nullable Field anchor, List<Field> all)
	{
		if (candidates.isEmpty())
		{
			return null;
		}
		if (candidates.size() == 1 || anchor == null)
		{
			return candidates.get(0);
		}
		int anchorIdx = all.indexOf(anchor);
		Field best = candidates.get(0);
		int bestDist = Integer.MAX_VALUE;
		for (Field c : candidates)
		{
			int dist = Math.abs(all.indexOf(c) - anchorIdx);
			if (dist < bestDist)
			{
				bestDist = dist;
				best = c;
			}
		}
		return best;
	}

	private static List<Field> getAllFields(Class<?> cls)
	{
		List<Field> fields = new ArrayList<>();
		for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass())
		{
			for (Field f : c.getDeclaredFields())
			{
				fields.add(f);
			}
		}
		return fields;
	}

	// -------------------------------------------------------------------------
	// Model creation
	// -------------------------------------------------------------------------

	/**
	 * Creates a new {@link ModelData} instance populated with geometry (and
	 * optional texture/UV data) from {@code data}.  The returned instance is
	 * ready for {@code light()}.
	 *
	 * @return a ModelData ready for {@code light()}, or {@code null} if the factory
	 *         is not ready
	 */
	@Nullable
	static ModelData create(ObjData data)
	{
		if (!isReady())
		{
			return null;
		}
		try
		{
			ModelData md = (ModelData) unsafe.allocateInstance(modelDataClass);

			setInt(fVertexCount, md, data.getVerticesX().length);
			setRef(fVerticesX, md, data.getVerticesX());
			setRef(fVerticesY, md, data.getVerticesY());
			setRef(fVerticesZ, md, data.getVerticesZ());

			setInt(fFaceCount, md, data.getFaceIndices1().length);
			setRef(fFaceIndices1, md, data.getFaceIndices1());
			setRef(fFaceIndices2, md, data.getFaceIndices2());
			setRef(fFaceIndices3, md, data.getFaceIndices3());
			setRef(fFaceColors, md, data.getFaceColors());

			if (data.hasTextures() && fFaceTextures != null)
			{
				setRef(fFaceTextures, md, data.getFaceTextures());
				setRef(fTexIndices1, md, data.getTexIndices1());
				setRef(fTexIndices2, md, data.getTexIndices2());
				setRef(fTexIndices3, md, data.getTexIndices3());
				setRef(fTextureCoords, md, data.getTextureCoords());
				if (data.getTexIndices1() != null)
				{
					int numTex = data.getTexIndices1().length;
					setInt(fNumTextureFaces, md, numTex);
					// textureRenderTypes: byte[] filled with 0 = planar UV mapping
					if (fTexRenderTypes != null)
					{
						setRef(fTexRenderTypes, md, new byte[numTex]);
					}
				}
				log.info("ObjModel: create() tex: ntf={} texIndices={}",
					fNumTextureFaces != null,
					data.getTexIndices1() != null ? data.getTexIndices1().length : "null");
			}

			return md;
		}
		catch (Exception e)
		{
			log.error("ObjModel: failed to create ModelData", e);
			return null;
		}
	}

	static boolean isReady()
	{
		return initialized
			&& modelDataClass != null
			&& unsafe != null
			&& fVerticesX != null && fVerticesY != null && fVerticesZ != null
			&& fFaceIndices1 != null && fFaceIndices2 != null && fFaceIndices3 != null
			&& fFaceColors != null;
	}

	// -------------------------------------------------------------------------
	// Reflection helpers
	// -------------------------------------------------------------------------

	private static void setInt(Field f, Object obj, int value)
	{
		if (f == null)
		{
			return;
		}
		try
		{
			f.setInt(obj, value);
		}
		catch (Exception e)
		{
			log.warn("ObjModel: could not set int field {}: {}", f.getName(), e.getMessage());
		}
	}

	private static void setRef(Field f, Object obj, Object value)
	{
		if (f == null)
		{
			return;
		}
		try
		{
			f.set(obj, value);
		}
		catch (Exception e)
		{
			log.warn("ObjModel: could not set field {}: {}", f.getName(), e.getMessage());
		}
	}
}
