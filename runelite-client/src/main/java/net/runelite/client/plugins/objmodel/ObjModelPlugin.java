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

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Renderable;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.TextureProvider;
import net.runelite.api.GameObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.Hooks;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;

/**
 * Replaces NPC and game object models with custom .obj files.
 *
 * <p>Config format (one per line): {@code npcName:model.obj} or {@code objectId:model.obj}
 * <br>Example: {@code Banker:Yoshi/Yoshi.obj}
 *
 * <p>Model paths are relative to {@code ~/.runelite/models/}.
 * Each model folder should contain the .obj, .mtl, and texture files.
 */
@Slf4j
@PluginDescriptor(
	name = "OBJ Model Loader",
	description = "Replace NPC and object models with custom .obj files from ~/.runelite/models/",
	tags = {"model", "obj", "3d", "custom", "npc", "object", "replacement"}
)
public class ObjModelPlugin extends Plugin
{
	static final File MODELS_DIR = new File(RuneLite.RUNELITE_DIR, "models");

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ObjModelConfig config;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private Hooks hooks;

	private final Hooks.RenderableDrawListener drawListener = this::shouldDraw;

	private static class NpcEntry
	{
		final String modelFile;
		final int scale;
		/** Rotation offset in 90° increments added to NPC facing (0-3). */
		final int rotationOffset;

		NpcEntry(String modelFile, int scale, int rotationOffset)
		{
			this.modelFile = modelFile;
			this.scale = scale;
			this.rotationOffset = rotationOffset;
		}
	}

	private static class ObjEntry
	{
		final String modelFile;
		final int scale;
		final int rotationOffset;

		ObjEntry(String modelFile, int scale, int rotationOffset)
		{
			this.modelFile = modelFile;
			this.scale = scale;
			this.rotationOffset = rotationOffset;
		}
	}

	/** Lower-case NPC name to entry. */
	private Map<String, NpcEntry> npcEntries = new HashMap<>();
	/** Object ID to entry. */
	private Map<Integer, ObjEntry> objectEntries = new HashMap<>();
	/** Cache key "modelFile@scale" to compiled model. */
	private final Map<String, Model> modelCache = new HashMap<>();
	private final Map<String, TextureInjector> modelTexInjectors = new HashMap<>();
	private final Map<NPC, RuneLiteObject> npcOverlays = new HashMap<>();
	private final Map<NPC, NpcEntry> npcEntryMap = new HashMap<>();
	private final Map<Long, RuneLiteObject> objectOverlays = new HashMap<>();
	private final Map<Long, GameObject> replacedObjects = new HashMap<>();

	@Override
	protected void startUp()
	{
		MODELS_DIR.mkdirs();
		hooks.registerRenderableDrawListener(drawListener);
		parseNpcConfig();
		parseObjectConfig();
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			TextureProvider p = client.getTextureProvider();
			if (p != null && !TextureInjector.isReady())
			{
				TextureInjector.init(p);
			}
			clientThread.invokeLater(() -> { spawnNpcReplacements(); spawnObjectReplacements(); return true; });
		}
	}

	@Override
	protected void shutDown()
	{
		hooks.unregisterRenderableDrawListener(drawListener);
		clientThread.invoke(() -> { despawnAll(); return true; });
	}

	/** Flags replaced NPCs in {@link ExtendedUV#hiddenNpcs} so the GPU plugin renders them fully transparent but still clickable. */
	private boolean shouldDraw(Renderable renderable, boolean drawingUI)
	{
		if (!drawingUI && renderable instanceof NPC && npcOverlays.containsKey(renderable))
		{
			ExtendedUV.hiddenNpcs.add((NPC) renderable);
		}
		return true;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOGGED_IN:
			{
				TextureProvider p = client.getTextureProvider();
				if (p != null && !TextureInjector.isReady())
				{
					TextureInjector.init(p);
				}
				clientThread.invokeLater(() -> { spawnNpcReplacements(); spawnObjectReplacements(); return true; });
				break;
			}
			case LOADING:
				for (RuneLiteObject obj : npcOverlays.values())
				{
					obj.setActive(false);
				}
				npcOverlays.clear();
				npcEntryMap.clear();
				for (RuneLiteObject obj : objectOverlays.values()) obj.setActive(false);
				objectOverlays.clear();
				replacedObjects.clear();
				ExtendedUV.replacedObjectIds.clear();
				break;
			case LOGIN_SCREEN:
			case HOPPING:
				despawnAll();
				break;
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!"objmodel".equals(event.getGroup()))
		{
			return;
		}
		parseNpcConfig();
		parseObjectConfig();
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(() -> { despawnAll(); spawnNpcReplacements(); spawnObjectReplacements(); return true; });
		}
	}

	private boolean gpuReinitDone = false;
	private int gpuFrameDelay = 0;

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		TextureProvider p = client.getTextureProvider();
		if (p == null || modelTexInjectors.isEmpty())
		{
			return;
		}

		for (TextureInjector ti : modelTexInjectors.values())
		{
			ti.reinjectPixels(p);
		}

		// Force one GPU re-init so it allocates all 256 layers, then upload every frame.
		if (client.isGpu())
		{
			int texArrayId = TextureInjector.findGpuTextureArrayId(pluginManager);

			if (!gpuReinitDone && texArrayId > 0)
			{
				TextureInjector.forceGpuReinit(pluginManager, texArrayId);
				gpuReinitDone = true;
				gpuFrameDelay = 3;
				return;
			}
			if (gpuFrameDelay > 0) { gpuFrameDelay--; return; }
			texArrayId = TextureInjector.findGpuTextureArrayId(pluginManager);

			if (texArrayId > 0)
			{
				org.lwjgl.opengl.GL33C.glBindTexture(
					org.lwjgl.opengl.GL33C.GL_TEXTURE_2D_ARRAY, texArrayId);
				for (TextureInjector ti : modelTexInjectors.values())
				{
					ti.uploadToGpuArray(texArrayId);
				}
			}
		}
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		updateNpcOverlays();

		if (!npcEntries.isEmpty())
		{
			for (NPC npc : client.getNpcs())
			{
				tryReplaceNpc(npc);
			}
		}

	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		tryReplaceNpc(event.getNpc());
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		NPC npc = event.getNpc();
		npcEntryMap.remove(npc);
		RuneLiteObject obj = npcOverlays.remove(npc);
		if (obj != null)
		{
			obj.setActive(false);
		}
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		tryReplaceObject(event.getGameObject());
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned event)
	{
		GameObject go = event.getGameObject();
		long key = objectKey(go);
		RuneLiteObject obj = objectOverlays.remove(key);
		if (obj != null)
		{
			obj.setActive(false);
		}
		if (replacedObjects.remove(key) != null)
		{
			ExtendedUV.replacedObjectIds.remove(go.getId());
		}
	}

	/**
	 * Parses config lines: {@code npcName:model.obj:scale:direction}
	 * <br>Scale and direction are optional. Direction: 0=follow NPC, 1=N, 2=E, 3=S, 4=W.
	 */
	private void parseNpcConfig()
	{
		npcEntries.clear();
		String raw = config.npcReplacements().trim();
		if (raw.isEmpty())
		{
			return;
		}
		for (String line : raw.split("\n"))
		{
			line = line.trim();
			if (line.isEmpty() || line.startsWith("#"))
			{
				continue;
			}
			String[] parts = line.split(":");
			if (parts.length < 2)
			{
				continue;
			}
			String npcName = parts[0].trim().toLowerCase();
			String modelFile = parts[1].trim();
			int scale = config.scale();
			int rotation = 0;
			if (parts.length >= 3)
			{
				try { scale = Integer.parseInt(parts[2].trim()); }
				catch (NumberFormatException ignored) {}
			}
			if (parts.length >= 4)
			{
				try { rotation = Integer.parseInt(parts[3].trim()); }
				catch (NumberFormatException ignored) {}
			}
			if (!npcName.isEmpty() && !modelFile.isEmpty())
			{
				npcEntries.put(npcName, new NpcEntry(modelFile, scale, rotation));
			}
		}
		if (!npcEntries.isEmpty())
		{
			log.info("ObjModel: NPC replacements: {} entries", npcEntries.size());
		}
	}

	private boolean spawnNpcReplacements()
	{
		if (npcEntries.isEmpty() || client.getGameState() != GameState.LOGGED_IN)
		{
			return true;
		}
		for (NPC npc : client.getNpcs())
		{
			tryReplaceNpc(npc);
		}
		return true;
	}

	private void tryReplaceNpc(NPC npc)
	{
		if (npcEntries.isEmpty() || npcOverlays.containsKey(npc))
		{
			return;
		}
		String name = npc.getName();
		if (name == null)
		{
			return;
		}
		NpcEntry entry = npcEntries.get(name.toLowerCase());
		if (entry == null)
		{
			return;
		}

		String cacheKey = entry.modelFile + "@" + entry.scale;
		Model model = getOrLoadModel(entry.modelFile, entry.scale, cacheKey);
		if (model == null)
		{
			return;
		}

		RuneLiteObject obj = client.createRuneLiteObject();
		obj.setModel(model);
		// Expand radius so the model covers all tiles of a multi-tile NPC.
		NPCComposition comp = npc.getTransformedComposition();
		if (comp != null && comp.getSize() > 1)
		{
			obj.setRadius(comp.getSize() * 64);
		}
		npcEntryMap.put(npc, entry);
		updateNpcObject(npc, obj);
		obj.setActive(true);
		npcOverlays.put(npc, obj);
	}

	private void updateNpcOverlays()
	{
		Iterator<Map.Entry<NPC, RuneLiteObject>> it = npcOverlays.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<NPC, RuneLiteObject> entry = it.next();
			NPC npc = entry.getKey();
			RuneLiteObject obj = entry.getValue();

			LocalPoint lp = npc.getLocalLocation();
			if (lp == null)
			{
				obj.setActive(false);
				it.remove();
				continue;
			}
			updateNpcObject(npc, obj);
		}
	}

	private void updateNpcObject(NPC npc, RuneLiteObject obj)
	{
		LocalPoint lp = npc.getLocalLocation();
		if (lp == null)
		{
			return;
		}
		int plane = client.getTopLevelWorldView().getPlane();
		obj.setLocation(lp, plane);

		NpcEntry entry = npcEntryMap.get(npc);
		int rotOffset = (entry != null) ? entry.rotationOffset : 0;
		// +1024 corrects OBJ facing; each rotationOffset unit is 512 (90°).
		int orientation = (npc.getCurrentOrientation() + 1024 + rotOffset * 512) % 2048;
		obj.setOrientation(orientation);
	}

	private void parseObjectConfig()
	{
		objectEntries.clear();
		String raw = config.objectReplacements().trim();
		if (raw.isEmpty())
		{
			return;
		}
		for (String line : raw.split("\n"))
		{
			line = line.trim();
			if (line.isEmpty() || line.startsWith("#"))
			{
				continue;
			}
			String[] parts = line.split(":");
			if (parts.length < 2)
			{
				continue;
			}
			int objectId;
			try
			{
				objectId = Integer.parseInt(parts[0].trim());
			}
			catch (NumberFormatException e)
			{
				continue;
			}
			String modelFile = parts[1].trim();
			int scale = config.scale();
			int rotation = 0;
			if (parts.length >= 3)
			{
				try { scale = Integer.parseInt(parts[2].trim()); }
				catch (NumberFormatException ignored) {}
			}
			if (parts.length >= 4)
			{
				try { rotation = Integer.parseInt(parts[3].trim()); }
				catch (NumberFormatException ignored) {}
			}
			if (!modelFile.isEmpty())
			{
				objectEntries.put(objectId, new ObjEntry(modelFile, scale, rotation));
			}
		}
		if (!objectEntries.isEmpty())
		{
			log.info("ObjModel: object replacements: {} entries", objectEntries.size());
		}
	}

	private void spawnObjectReplacements()
	{
		if (objectEntries.isEmpty() || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		var wv = client.getTopLevelWorldView();
		var scene = wv.getScene();
		var tiles = scene.getTiles();
		int plane = wv.getPlane();

		for (int x = 0; x < tiles[plane].length; x++)
		{
			for (int y = 0; y < tiles[plane][x].length; y++)
			{
				var tile = tiles[plane][x][y];
				if (tile == null) continue;

				for (GameObject go : tile.getGameObjects())
				{
					if (go != null)
					{
						tryReplaceObject(go);
					}
				}
			}
		}
	}

	private void tryReplaceObject(GameObject go)
	{
		if (objectEntries.isEmpty() || go == null)
		{
			return;
		}

		int id = go.getId();
		ObjEntry entry = objectEntries.get(id);
		if (entry == null)
		{
			ObjectComposition comp = client.getObjectDefinition(id);
			if (comp != null && comp.getImpostorIds() != null)
			{
				ObjectComposition impostor = comp.getImpostor();
				if (impostor != null)
				{
					entry = objectEntries.get(impostor.getId());
				}
			}
		}
		if (entry == null)
		{
			return;
		}

		long key = objectKey(go);
		if (objectOverlays.containsKey(key))
		{
			return;
		}

		String cacheKey = entry.modelFile + "@" + entry.scale;
		Model model = getOrLoadModel(entry.modelFile, entry.scale, cacheKey);
		if (model == null)
		{
			return;
		}

		LocalPoint lp = go.getLocalLocation();
		if (lp == null)
		{
			return;
		}

		ExtendedUV.replacedObjectIds.add(go.getId());

		var dc = client.getDrawCallbacks();
		if (dc != null)
		{
			var wv = client.getTopLevelWorldView();
			if (wv != null)
			{
				int sceneOffset = (net.runelite.api.Constants.EXTENDED_SCENE_SIZE
					- net.runelite.api.Constants.SCENE_SIZE) / 2 / 8;
				dc.invalidateZone(wv.getScene(),
					(go.getX() >> 10) + sceneOffset,
					(go.getY() >> 10) + sceneOffset);
			}
		}

		RuneLiteObject obj = client.createRuneLiteObject();
		obj.setModel(model);

		int plane = client.getTopLevelWorldView().getPlane();
		obj.setLocation(lp, plane);
		// +1024 corrects OBJ facing; each rotationOffset unit is 512 (90°).
		int orientation = (1024 + entry.rotationOffset * 512) % 2048;
		obj.setOrientation(orientation);
		obj.setActive(true);

		objectOverlays.put(key, obj);
		replacedObjects.put(key, go);
	}

	/** Unique key for a game object based on its world location + ID. */
	private long objectKey(GameObject go)
	{
		WorldPoint wp = go.getWorldLocation();
		return ((long) go.getId() << 36) | ((long) wp.getX() << 20) | ((long) wp.getY() << 4) | wp.getPlane();
	}

	private Model getOrLoadModel(String modelFile, int scale, String cacheKey)
	{
		Model cached = modelCache.get(cacheKey);
		if (cached != null)
		{
			return cached;
		}

		File file = resolveFile(modelFile);
		if (!file.exists())
		{
			log.warn("ObjModel: model file not found: {}", file.getAbsolutePath());
			return null;
		}

		if (!ModelDataFactory.isReady() && !ModelDataFactory.init(client))
		{
			return null;
		}

		Map<String, Short> texSlots = Collections.emptyMap();
		TextureProvider provider = client.getTextureProvider();
		if (provider != null)
		{
			if (!TextureInjector.isReady())
			{
				TextureInjector.init(provider);
			}
			if (TextureInjector.isReady())
			{
				TextureInjector injector = new TextureInjector();
				texSlots = injectTextures(file, provider, injector);
				if (!texSlots.isEmpty())
				{
					modelTexInjectors.put(cacheKey, injector);
				}
			}
		}

		Map<String, BufferedImage> texImages = loadTextureImages(file);
		ObjData data;
		try
		{
			if (!texSlots.isEmpty())
			{
				data = new ObjImporter().loadWithTextures(file, scale, texSlots, texImages);
			}
			else
			{
				data = new ObjImporter().load(file, scale, texImages);
			}
		}
		catch (IOException ex)
		{
			log.error("ObjModel: failed to parse {}: {}", file.getName(), ex.getMessage());
			return null;
		}

		ModelData md = ModelDataFactory.create(data);
		if (md == null)
		{
			return null;
		}

		Model model = md.light();
		if (model == null)
		{
			return null;
		}

		if (data.extTexCoords != null)
		{
			ExtendedUV.texCoords.put(model, data.extTexCoords);
			ExtendedUV.texIdx1.put(model, data.extTexIdx1);
			ExtendedUV.texIdx2.put(model, data.extTexIdx2);
			ExtendedUV.texIdx3.put(model, data.extTexIdx3);
		}
		int[] c3 = model.getFaceColors3();
		if (c3 != null) for (int i = 0; i < c3.length; i++) if (c3[i] == -2) c3[i] = -1;

		modelCache.put(cacheKey, model);
		log.info("ObjModel: loaded {} (scale={}, {} verts, {} tris)",
			file.getName(), scale, data.getVerticesX().length, data.getFaceIndices1().length);
		return model;
	}

	private void despawnAll()
	{
		for (RuneLiteObject obj : npcOverlays.values())
		{
			obj.setActive(false);
		}
		npcOverlays.clear();
		npcEntryMap.clear();
		for (RuneLiteObject obj : objectOverlays.values()) obj.setActive(false);
		objectOverlays.clear();
		replacedObjects.clear();
		ExtendedUV.replacedObjectIds.clear();
		modelCache.clear();

		TextureProvider p = client.getTextureProvider();
		for (TextureInjector ti : modelTexInjectors.values())
		{
			if (p != null)
			{
				ti.cleanup(p);
			}
		}
		modelTexInjectors.clear();
	}

	private Map<String, Short> injectTextures(File objFile, TextureProvider provider,
		TextureInjector injector)
	{
		Map<String, File> texFiles;
		try
		{
			texFiles = new ObjImporter().scanTextureFiles(objFile);
		}
		catch (IOException e)
		{
			return Collections.emptyMap();
		}
		if (texFiles.isEmpty())
		{
			return Collections.emptyMap();
		}
		Map<String, Short> slots = new HashMap<>();
		for (Map.Entry<String, File> entry : texFiles.entrySet())
		{
			File texFile = entry.getValue();
			if (!texFile.exists())
			{
				continue;
			}
			int slot = injector.inject(texFile, provider);
			if (slot >= 0)
			{
				slots.put(entry.getKey(), (short) slot);
			}
		}
		return slots;
	}

	private Map<String, BufferedImage> loadTextureImages(File objFile)
	{
		Map<String, File> texFiles;
		try
		{
			texFiles = new ObjImporter().scanTextureFiles(objFile);
		}
		catch (IOException e)
		{
			return Collections.emptyMap();
		}
		Map<String, BufferedImage> images = new HashMap<>();
		for (Map.Entry<String, File> entry : texFiles.entrySet())
		{
			File texFile = entry.getValue();
			if (!texFile.exists())
			{
				continue;
			}
			try
			{
				BufferedImage img = ImageIO.read(texFile);
				if (img != null)
				{
					images.put(entry.getKey(), img);
				}
			}
			catch (IOException ignored)
			{
			}
		}
		return images;
	}

	private File resolveFile(String path)
	{
		if (path.contains(File.separator) || path.contains("/"))
		{
			File abs = new File(path);
			if (abs.isAbsolute())
			{
				return abs;
			}
			return new File(MODELS_DIR, path);
		}
		return new File(MODELS_DIR, path);
	}

	@Provides
	ObjModelConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ObjModelConfig.class);
	}
}
