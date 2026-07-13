package net.runelite.client.plugins.objmodel;

import java.util.WeakHashMap;
import net.runelite.api.Model;

/**
 * Shared state read by the GPU plugin's SceneUploader and FacePrioritySorter: extended UV
 * data for models exceeding the 255 UV-triangle byte limit, plus hidden/replaced tracking.
 */
public class ExtendedUV
{
	public static final WeakHashMap<Model, int[]> texCoords = new WeakHashMap<>();
	public static final WeakHashMap<Model, int[]> texIdx1 = new WeakHashMap<>();
	public static final WeakHashMap<Model, int[]> texIdx2 = new WeakHashMap<>();
	public static final WeakHashMap<Model, int[]> texIdx3 = new WeakHashMap<>();

	/** NPCs to render fully transparent; repopulated each frame. */
	public static final java.util.Set<net.runelite.api.NPC> hiddenNpcs =
		java.util.Collections.newSetFromMap(new WeakHashMap<>());

	/** Object IDs whose drawTemp is skipped because the model was replaced. */
	public static final java.util.Set<Integer> replacedObjectIds = new java.util.HashSet<>();

	/** Whether the model currently being uploaded should be hidden. */
	public static boolean currentModelHidden = false;


	public static boolean isHiddenRenderable(net.runelite.api.Renderable r)
	{
		return r instanceof net.runelite.api.NPC && hiddenNpcs.contains(r);
	}
}
