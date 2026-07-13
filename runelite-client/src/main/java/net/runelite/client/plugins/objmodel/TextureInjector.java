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
 * Injects PNG textures by EXPANDING the TextureProvider array with cloned
 * Texture objects. Cloning from a real Texture ensures provider.load()
 * doesn't crash during GPU init. After GPU init, we overwrite our slots
 * with the actual PNG pixels via glTexSubImage3D.
 *
 * No existing game textures are touched.
 */
@Slf4j
class TextureInjector
{
	private static boolean ready = false;
	private static Unsafe unsafe;
	private static Field fPixels;         // int[] pixel field on Texture
	private static Field fProviderArray;  // Texture[] field on TextureProvider
	private static int originalArraySize;
	private static List<Field> allTexFields = new ArrayList<>(); // for cloning

	private final List<Integer> injectedSlots = new ArrayList<>();
	private final Map<Integer, int[]> pngPixelsMap = new HashMap<>();

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

		// Find a loaded texture for field discovery
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

		// Discover pixel field
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

		// Discover provider array field
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

		// Pre-expand array to 256 by reusing a real Texture object for new
		// slots. Using the SAME real texture (not a clone) means provider.load()
		// works normally — no NPE from stale cache refs after setBrightness.
		// Our per-frame glTexSubImage3D overwrites the GPU layer with our PNG.
		if (textures.length < 256)
		{
			try
			{
				Texture[] expanded = Arrays.copyOf(textures, 256);
				for (int i = textures.length; i < 256; i++)
				{
					expanded[i] = sample; // reuse real texture, not clone
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

	/** Next available slot in the pre-allocated range */
	private static int nextSlot = -1;

	/**
	 * Uses a pre-allocated slot from the expanded array (209-255).
	 * Sets its pixel field to our PNG. No array expansion needed here
	 * since init() already expanded to 256.
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

	private static Texture cloneTexture(Texture donor) throws Exception
	{
		Texture clone = (Texture) unsafe.allocateInstance(donor.getClass());
		for (Field f : allTexFields)
		{
			f.set(clone, f.get(donor));
		}
		return clone;
	}

	/**
	 * Re-sets pixel references on cloned Textures (CPU side only).
	 */
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

	/**
	 * Uploads this injector's textures to the GPU array.
	 * Called from the plugin's centralized GPU handler.
	 */
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
	 * Forces the GPU plugin to re-init its texture array by deleting the
	 * current one and setting textureArrayId = -1. On the next frame, the
	 * GPU plugin will lazy-init with provider.getTextures().length layers
	 * (now 256 thanks to our expansion). Cloned textures ensure no crash.
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
		byte[] rgba = new byte[128 * 128 * 4];
		for (int i = 0; i < srcPixels.length && i < 128 * 128; i++)
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
		ByteBuffer buf = ByteBuffer.allocateDirect(rgba.length);
		buf.put(rgba);
		buf.flip();
		glBindTexture(GL_TEXTURE_2D_ARRAY, texArrayId);
		glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, slot, 128, 128, 1,
			GL_RGBA, GL_UNSIGNED_BYTE, buf);
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

		// Restore cloned donor pixels on our used slots (don't shrink array —
		// the pre-allocated 256 slots stay for the GPU array to remain valid)
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
						// Restore to donor pixels so the slot is "clean"
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
