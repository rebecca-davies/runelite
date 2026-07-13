package net.runelite.client.plugins.objmodel;

import java.util.WeakHashMap;
import net.runelite.api.Model;

/**
 * Extended UV data for models exceeding the 255 UV-triangle byte limit.
 * The GPU plugin's SceneUploader checks this before falling back to
 * the standard byte[] textureCoords.
 */
public class ExtendedUV
{
	public static final WeakHashMap<Model, int[]> texCoords = new WeakHashMap<>();
	public static final WeakHashMap<Model, int[]> texIdx1 = new WeakHashMap<>();
	public static final WeakHashMap<Model, int[]> texIdx2 = new WeakHashMap<>();
	public static final WeakHashMap<Model, int[]> texIdx3 = new WeakHashMap<>();

	/** NPCs that should be fully transparent (set each frame by shouldDraw). */
	public static final java.util.Set<net.runelite.api.NPC> hiddenNpcs =
		java.util.Collections.newSetFromMap(new WeakHashMap<>());

	/** Object IDs whose drawTemp should be skipped (model replaced). */
	public static final java.util.Set<Integer> replacedObjectIds = new java.util.HashSet<>();

	/** Set by GpuPlugin.drawTemp before calling uploadSortedModel. */
	public static boolean currentModelHidden = false;

	/** Check if a renderable belongs to a hidden NPC. */
	public static boolean isHiddenRenderable(net.runelite.api.Renderable r)
	{
		return r instanceof net.runelite.api.NPC && hiddenNpcs.contains(r);
	}
}
