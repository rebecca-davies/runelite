/*
 * Copyright (c) 2026, Rebecca Davies <rebeccad1558@gmail.com>
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
package net.runelite.client.plugins.objecthider;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Object Hider",
	description = "Hide game objects from the scene by shift right-clicking them",
	tags = {"objects", "hide", "scenery", "remove", "render"},
	enabledByDefault = false
)
@Slf4j
public class ObjectHiderPlugin extends Plugin
{
	static final String CONFIG_GROUP = "objecthider";
	private static final String KEY_PREFIX = "region_";
	private static final String HIDE = "Hide";
	private static final String UNHIDE = "Unhide";

	private final Map<Integer, Set<HiddenObject>> points = new HashMap<>();

	// GameObjects that are currently revealed (matched a hidden point but left in the scene
	// because the reveal toggle is on). Used by the overlay to tint them.
	@Getter(AccessLevel.PACKAGE)
	private final List<GameObject> revealedObjects = new ArrayList<>();

	@Inject
	private Client client;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ObjectHiderConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ObjectHiderOverlay overlay;

	@Inject
	private ClientThread clientThread;

	@Inject
	private Gson gson;

	@Provides
	ObjectHiderConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ObjectHiderConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		clientThread.invokeLater(() ->
		{
			loadPoints();
			reloadScene();
		});
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		points.clear();
		revealedObjects.clear();
		// Reload the scene to restore any objects we removed from it.
		clientThread.invokeLater(this::reloadScene);
	}

	@Subscribe
	public void onProfileChanged(ProfileChanged e)
	{
		clientThread.invokeLater(() ->
		{
			loadPoints();
			reloadScene();
		});
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (e.getGroup().equals(CONFIG_GROUP))
		{
			// The hide is applied during scene load, so reload to apply config changes.
			clientThread.invokeLater(this::reloadScene);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOADING)
		{
			// On region changes the tiles get set to null; refresh the points for the new regions.
			revealedObjects.clear();
			loadPoints();
		}
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		applyHide(event.getGameObject());
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!config.enabled() || event.getType() != MenuAction.EXAMINE_OBJECT.getId() || !client.isKeyPressed(KeyCode.KC_SHIFT))
		{
			return;
		}

		int worldId = event.getMenuEntry().getWorldViewId();
		WorldView wv = client.getWorldView(worldId);
		if (wv == null)
		{
			return;
		}

		final GameObject gameObject = findGameObject(wv, event.getActionParam0(), event.getActionParam1(), event.getIdentifier());
		if (gameObject == null)
		{
			return;
		}

		final boolean hidden = isHidden(gameObject);
		client.createMenuEntry(-1)
			.setOption(hidden ? UNHIDE : HIDE)
			.setTarget(event.getTarget())
			.setWorldViewId(worldId)
			.setParam0(event.getActionParam0())
			.setParam1(event.getActionParam1())
			.setIdentifier(event.getIdentifier())
			.setType(MenuAction.RUNELITE)
			.onClick(this::toggleObject);
	}

	private void toggleObject(MenuEntry entry)
	{
		WorldView wv = client.getWorldView(entry.getWorldViewId());
		if (wv == null)
		{
			return;
		}

		GameObject object = findGameObject(wv, entry.getParam0(), entry.getParam1(), entry.getIdentifier());
		if (object == null)
		{
			return;
		}

		// object.getId() is always the base object id, getObjectComposition transforms it to
		// the correct object we see
		ObjectComposition composition = getObjectComposition(object.getId());
		if (composition == null)
		{
			return;
		}

		String name = composition.getName();
		// Name is probably never "null" - however prevent adding it if it is, as it will
		// become ambiguous as objects with no name are assigned name "null"
		if (Strings.isNullOrEmpty(name) || name.equals("null"))
		{
			return;
		}

		final WorldPoint worldPoint = WorldPoint.fromLocalInstance(client, object.getLocalLocation());
		final int regionId = worldPoint.getRegionID();
		final HiddenObject point = new HiddenObject(
			object.getId(),
			name,
			regionId,
			worldPoint.getRegionX(),
			worldPoint.getRegionY(),
			worldPoint.getPlane());

		Set<HiddenObject> regionPoints = points.computeIfAbsent(regionId, k -> new HashSet<>());

		if (regionPoints.removeIf(p -> matches(p, object, worldPoint)))
		{
			log.debug("Unhiding object: {}", point);
		}
		else
		{
			regionPoints.add(point);
			log.debug("Hiding object: {}", point);
		}

		savePoints(regionId, regionPoints);

		// Reload to actually apply (remove from / restore to) the scene.
		reloadScene();
	}

	/**
	 * Removes the object from the scene if it is hidden and not being revealed; otherwise tracks
	 * it for the reveal overlay.
	 */
	private void applyHide(GameObject object)
	{
		if (!config.enabled() || !isHidden(object))
		{
			return;
		}

		if (config.revealHidden())
		{
			revealedObjects.add(object);
			return;
		}

		WorldView wv = object.getWorldView();
		Scene scene = wv != null ? wv.getScene() : client.getScene();
		if (scene != null)
		{
			log.debug("Removing hidden object {} from scene", object.getId());
			scene.removeGameObject(object);
		}
	}

	private boolean isHidden(GameObject object)
	{
		if (object.getPlane() < 0)
		{
			return false;
		}

		final WorldPoint worldPoint = WorldPoint.fromLocalInstance(client, object.getLocalLocation(), object.getPlane());
		final Set<HiddenObject> regionPoints = points.get(worldPoint.getRegionID());
		if (regionPoints == null)
		{
			return false;
		}

		for (HiddenObject point : regionPoints)
		{
			if (matches(point, object, worldPoint))
			{
				return true;
			}
		}

		return false;
	}

	private boolean matches(HiddenObject point, GameObject object, WorldPoint worldPoint)
	{
		return point.getRegionX() == worldPoint.getRegionX()
			&& point.getRegionY() == worldPoint.getRegionY()
			&& point.getZ() == worldPoint.getPlane()
			&& objectIdEquals(object, point.getId());
	}

	private boolean objectIdEquals(GameObject object, int id)
	{
		if (object.getId() == id)
		{
			return true;
		}

		// EXAMINE_OBJECT sends the transformed object id, while the spawn event and getId() use the
		// base id, so check against the impostor ids for multilocs.
		final ObjectComposition comp = client.getObjectDefinition(object.getId());
		if (comp != null && comp.getImpostorIds() != null)
		{
			for (int impostorId : comp.getImpostorIds())
			{
				if (impostorId == id)
				{
					return true;
				}
			}
		}

		return false;
	}

	@Nullable
	private GameObject findGameObject(WorldView wv, int x, int y, int id)
	{
		Scene scene = wv.getScene();
		Tile[][][] tiles = scene.getTiles();
		final Tile tile = tiles[wv.getPlane()][x][y];
		if (tile == null)
		{
			return null;
		}

		for (GameObject object : tile.getGameObjects())
		{
			if (object != null && objectIdEquals(object, id))
			{
				return object;
			}
		}

		return null;
	}

	/**
	 * Reloads the scene so that the removal of hidden objects is re-applied (objects are removed
	 * during scene load via {@link #onGameObjectSpawned}). Used after hiding/unhiding and config
	 * changes since {@link Scene#removeGameObject} cannot be reversed in-place.
	 */
	private void reloadScene()
	{
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			client.setGameState(GameState.LOADING);
		}
	}

	private void loadPoints()
	{
		points.clear();

		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return;
		}

		int[] regions = wv.getMapRegions();
		if (regions == null)
		{
			return;
		}

		for (int regionId : regions)
		{
			final Set<HiddenObject> regionPoints = loadPoints(regionId);
			if (regionPoints != null)
			{
				points.put(regionId, regionPoints);
			}
		}
	}

	private void savePoints(final int regionId, final Set<HiddenObject> regionPoints)
	{
		if (regionPoints.isEmpty())
		{
			configManager.unsetConfiguration(CONFIG_GROUP, KEY_PREFIX + regionId);
		}
		else
		{
			final String json = gson.toJson(regionPoints);
			configManager.setConfiguration(CONFIG_GROUP, KEY_PREFIX + regionId, json);
		}
	}

	@Nullable
	private Set<HiddenObject> loadPoints(final int regionId)
	{
		final String json = configManager.getConfiguration(CONFIG_GROUP, KEY_PREFIX + regionId);
		if (Strings.isNullOrEmpty(json))
		{
			return null;
		}

		return gson.fromJson(json, new TypeToken<Set<HiddenObject>>()
		{
		}.getType());
	}

	@Nullable
	private ObjectComposition getObjectComposition(int id)
	{
		ObjectComposition composition = client.getObjectDefinition(id);
		if (composition == null)
		{
			return null;
		}
		return composition.getImpostorIds() == null ? composition : composition.getImpostor();
	}

	@VisibleForTesting
	Map<Integer, Set<HiddenObject>> getPoints()
	{
		return points;
	}
}
