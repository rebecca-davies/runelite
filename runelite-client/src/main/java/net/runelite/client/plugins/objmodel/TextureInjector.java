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

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import sun.misc.Unsafe; //NOPMD
import static org.lwjgl.opengl.GL33C.*;

/**
 * Injects PNG textures by expanding the TextureProvider's Texture array into
 * unused slots and overwriting those GPU layers with PNG pixels via
 * glTexSubImage3D. Existing game textures are left untouched.
 */
@Slf4j
class TextureInjector
{
	private static final int TEX_DIM = 128;   // matches TextureManager.TEXTURE_SIZE
	private static final int MIP_LEVELS = 8;  // matches glTexStorage3D level count (128..1)

	private static boolean ready = false;
	private static Unsafe unsafe;
	private static Field fPixels;         // int[] pixel field on Texture
	private static Field fProviderArray;  // Texture[] field on TextureProvider
	private static int originalArraySize;
	private static List<Field> allTexFields = new ArrayList<>();

	private final List<Integer> injectedSlots = new ArrayList<>();
	private final Map<Integer, int[]> pngPixelsMap = new HashMap<>();

	/**
	 * Discovers the private Texture pixel field and TextureProvider array field
	 * via reflection, then pre-expands the array to 256 slots. Reflection is
	 * required because these are internal deob fields with no public accessors.
	 */
	static boolean init(TextureProvider provider)
	{
		if (ready)
		{
			return true;
		}

		try
		{
			Field uf = Unsafe.class.getDeclaredField("theUnsafe");
			uf.setAccessible(true);
			unsafe = (Unsafe) uf.get(null);
		}
		catch (Exception e)
		{
			log.warn("ObjModel/TextureInjector: could not get Unsafe", e);
			return false;
		}

		Texture[] textures = provider.getTextures();
		originalArraySize = textures.length;

		// Load a real texture to use as a reference for field discovery.
		Texture sample = null;
		for (int i = 0; i < textures.length; i++)
		{
			if (textures[i] != null)
			{
				provider.load(i);
				if (textures[i].getPixels() != null)
				{
					sample = textures[i];
					break;
				}
			}
		}
		if (sample == null)
		{
			log.warn("ObjModel/TextureInjector: no texture available");
			return false;
		}

		// Match the field holding the pixel array by reference identity.
		int[] pixRef = sample.getPixels();
		Class<?> texClass = sample.getClass();
		for (Class<?> c = texClass; c != null && c != Object.class; c = c.getSuperclass())
		{
			for (Field f : c.getDeclaredFields())
			{
				if (Modifier.isStatic(f.getModifiers()))
				{
					continue;
				}
				f.setAccessible(true);
				allTexFields.add(f);
				try
				{
					if (f.get(sample) == pixRef)
					{
						fPixels = f;
					}
				}
				catch (Exception ignored)
				{
				}
			}
		}

		// Match the provider's Texture[] field by reference identity.
		Object texRef = provider.getTextures();
		for (Class<?> c = provider.getClass(); c != null && c != Object.class; c = c.getSuperclass())
		{
			for (Field f : c.getDeclaredFields())
			{
				if (Modifier.isStatic(f.getModifiers()))
				{
					continue;
				}
				f.setAccessible(true);
				try
				{
					if (f.get(provider) == texRef)
					{
						fProviderArray = f;
					}
				}
				catch (Exception ignored)
				{
				}
			}
		}

		if (fPixels == null || fProviderArray == null)
		{
			log.warn("ObjModel/TextureInjector: field discovery failed");
			return false;
		}

		// Expand to 256 slots, filling new slots with an existing real Texture.
		// Reusing a real texture (rather than a clone) keeps provider.load()
		// working — a clone can leave stale cache refs that NPE after
		// setBrightness. The extra GPU layers are overwritten per frame.
		if (textures.length < 256)
		{
			try
			{
				Texture[] expanded = Arrays.copyOf(textures, 256);
				for (int i = textures.length; i < 256; i++)
				{
					expanded[i] = sample;
				}
				fProviderArray.set(provider, expanded);
				log.info("ObjModel/TextureInjector: expanded texture array {} → 256", textures.length);
			}
			catch (Exception e)
			{
				log.warn("ObjModel/TextureInjector: array expansion failed", e);
			}
		}

		ready = true;
		log.info("ObjModel/TextureInjector: ready (origSize={}, pixelField={})",
			originalArraySize, fPixels.getName());
		return true;
	}

	static boolean isReady()
	{
		return ready;
	}

	/** Next free slot in the expanded array. */
	private static int nextSlot = -1;

	/**
	 * Loads and scales a PNG, then points the next free slot's pixel field at it.
	 * Returns the slot index, or -1 on failure.
	 */
	int inject(File pngFile, TextureProvider provider)
	{
		if (!isReady())
		{
			return -1;
		}

		int[] pngPixels = loadAndScalePng(pngFile, 128);
		if (pngPixels == null)
		{
			return -1;
		}

		if (nextSlot < 0)
		{
			nextSlot = originalArraySize;
		}

		Texture[] textures = provider.getTextures();
		if (nextSlot >= textures.length)
		{
			log.warn("ObjModel/TextureInjector: no free slots (max 256)");
			return -1;
		}

		int slot = nextSlot++;
		try
		{
			fPixels.set(textures[slot], pngPixels);
		}
		catch (Exception e)
		{
			log.error("ObjModel/TextureInjector: inject failed for slot {}", slot, e);
			return -1;
		}

		injectedSlots.add(slot);
		pngPixelsMap.put(slot, pngPixels);
		log.info("ObjModel/TextureInjector: injected {} → slot {} (no overwrite)",
			pngFile.getName(), slot);
		return slot;
	}

	/**
	 * Shallow-copies a Texture. Uses Unsafe.allocateInstance to skip the
	 * constructor, since the deob Texture class has no accessible one.
	 */
	private static Texture cloneTexture(Texture donor) throws Exception
	{
		Texture clone = (Texture) unsafe.allocateInstance(donor.getClass());
		for (Field f : allTexFields)
		{
			f.set(clone, f.get(donor));
		}
		return clone;
	}

	/** Re-points the injected slots' pixel fields at their PNG data (CPU side only). */
	void reinjectPixels(TextureProvider provider)
	{
		if (injectedSlots.isEmpty())
		{
			return;
		}
		Texture[] textures = provider.getTextures();
		for (int slot : injectedSlots)
		{
			int[] png = pngPixelsMap.get(slot);
			if (png == null || slot >= textures.length || textures[slot] == null)
			{
				continue;
			}
			try
			{
				fPixels.set(textures[slot], png);
			}
			catch (Exception ignored)
			{
			}
		}
	}

	/** Uploads all injected textures to the GPU texture array. */
	void uploadToGpuArray(int texArrayId)
	{
		for (int slot : injectedSlots)
		{
			int[] png = pngPixelsMap.get(slot);
			if (png != null)
			{
				uploadToGpu(texArrayId, slot, png);
			}
		}
	}

	/**
	 * Deletes the GPU plugin's texture array and resets textureArrayId to -1.
	 * This is needed because the array is allocated once with a fixed layer
	 * count; forcing a re-init makes it lazily rebuild with the 256 layers the
	 * expanded provider array now reports.
	 */
	static void forceGpuReinit(PluginManager pluginManager, int oldArrayId)
	{
		for (Plugin plugin : pluginManager.getPlugins())
		{
			String name = plugin.getClass().getSimpleName();
			if (name.equals("GpuPlugin") || name.equals("HdPlugin"))
			{
				try
				{
					Field f = findField(plugin.getClass(), "textureArrayId");
					if (f != null)
					{
						f.setAccessible(true);
						glDeleteTextures(oldArrayId);
						f.setInt(plugin, -1);
						log.info("ObjModel/TextureInjector: forced GPU texture re-init (256 layers)");
					}
				}
				catch (Exception e)
				{
					log.debug("ObjModel/TextureInjector: GPU re-init failed", e);
				}
			}
		}
	}

	private static void uploadToGpu(int texArrayId, int slot, int[] srcPixels)
	{
		byte[] rgba = new byte[TEX_DIM * TEX_DIM * 4];
		for (int i = 0; i < srcPixels.length && i < TEX_DIM * TEX_DIM; i++)
		{
			int rgb = srcPixels[i];
			if (rgb != 0)
			{
				rgba[i * 4] = (byte) (rgb >> 16);
				rgba[i * 4 + 1] = (byte) (rgb >> 8);
				rgba[i * 4 + 2] = (byte) rgb;
				rgba[i * 4 + 3] = (byte) 0xFF;
			}
		}

		glBindTexture(GL_TEXTURE_2D_ARRAY, texArrayId);

		// The array uses mipmapped minification, so minified faces sample the
		// coarser levels. glGenerateMipmap runs only once at array init, while
		// the slot still held the donor texture, so writing only level 0 leaves
		// levels 1..N showing the donor texture from far away. Upload every level
		// from locally downsampled pixels instead.
		int dim = TEX_DIM;
		byte[] level = rgba;
		for (int lvl = 0; lvl < MIP_LEVELS; lvl++)
		{
			ByteBuffer buf = ByteBuffer.allocateDirect(level.length);
			buf.put(level);
			buf.flip();
			glTexSubImage3D(GL_TEXTURE_2D_ARRAY, lvl, 0, 0, slot, dim, dim, 1,
				GL_RGBA, GL_UNSIGNED_BYTE, buf);

			if (dim > 1)
			{
				level = downsample(level, dim);
				dim >>= 1;
			}
		}
	}

	/**
	 * Box-filters an RGBA image (dim x dim, tightly packed) down to (dim/2 x dim/2)
	 * by averaging each 2x2 texel block.
	 */
	static byte[] downsample(byte[] src, int dim)
	{
		int half = dim >> 1;
		byte[] dst = new byte[half * half * 4];
		for (int y = 0; y < half; y++)
		{
			for (int x = 0; x < half; x++)
			{
				int sx = x << 1;
				int sy = y << 1;
				int tl = (sy * dim + sx) * 4;
				int tr = (sy * dim + sx + 1) * 4;
				int bl = ((sy + 1) * dim + sx) * 4;
				int br = ((sy + 1) * dim + sx + 1) * 4;
				int d = (y * half + x) * 4;
				for (int c = 0; c < 4; c++)
				{
					int sum = (src[tl + c] & 0xFF) + (src[tr + c] & 0xFF)
						+ (src[bl + c] & 0xFF) + (src[br + c] & 0xFF);
					dst[d + c] = (byte) ((sum + 2) >> 2);
				}
			}
		}
		return dst;
	}

	static int findGpuTextureArrayId(PluginManager pluginManager)
	{
		for (Plugin plugin : pluginManager.getPlugins())
		{
			String name = plugin.getClass().getSimpleName();
			if (name.equals("GpuPlugin") || name.equals("HdPlugin"))
			{
				try
				{
					Field f = findField(plugin.getClass(), "textureArrayId");
					if (f != null)
					{
						f.setAccessible(true);
						return f.getInt(plugin);
					}
				}
				catch (Exception ignored)
				{
				}
			}
		}
		return -1;
	}

	@Nullable
	private static Field findField(Class<?> cls, String name)
	{
		for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass())
		{
			try { return c.getDeclaredField(name); }
			catch (NoSuchFieldException ignored) {}
		}
		return null;
	}

	void cleanup(TextureProvider provider)
	{
		if (injectedSlots.isEmpty())
		{
			return;
		}
		int count = injectedSlots.size();

		// Restore donor pixels on the used slots. The array is left at 256 slots
		// so the GPU texture array stays valid.
		Texture[] textures = provider.getTextures();
		Texture donor = null;
		for (int i = 0; i < Math.min(originalArraySize, textures.length); i++)
		{
			if (textures[i] != null && textures[i].getPixels() != null)
			{
				donor = textures[i];
				break;
			}
		}
		if (donor != null)
		{
			for (int slot : injectedSlots)
			{
				if (slot < textures.length && textures[slot] != null)
				{
					try
					{
						fPixels.set(textures[slot], donor.getPixels());
					}
					catch (Exception ignored)
					{
					}
				}
			}
		}

		injectedSlots.clear();
		pngPixelsMap.clear();
		nextSlot = originalArraySize;
		log.debug("ObjModel/TextureInjector: cleaned up {} slot(s)", count);
	}

	@Nullable
	private static int[] loadAndScalePng(File file, int dim)
	{
		try
		{
			BufferedImage src = ImageIO.read(file);
			if (src == null) return null;
			BufferedImage scaled = new BufferedImage(dim, dim, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = scaled.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(src, 0, 0, dim, dim, null);
			g.dispose();
			int[] pixels = new int[dim * dim];
			scaled.getRGB(0, 0, dim, dim, pixels, 0, dim);
			for (int i = 0; i < pixels.length; i++)
			{
				int rgb = pixels[i] & 0x00FFFFFF;
				pixels[i] = (rgb == 0) ? 0x010101 : rgb;
			}
			return pixels;
		}
		catch (Exception e)
		{
			log.error("ObjModel/TextureInjector: failed to load {}", file.getName(), e);
			return null;
		}
	}
}
