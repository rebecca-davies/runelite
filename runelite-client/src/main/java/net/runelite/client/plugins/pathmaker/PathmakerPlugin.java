package net.runelite.client.plugins.pathmaker;

import com.google.common.base.Strings;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.awt.Color;
import java.util.Set;
import javax.inject.Inject;
import javax.swing.JOptionPane;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.ItemLayer;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GroundObjectSpawned;
import net.runelite.api.events.GroundObjectDespawned;
import net.runelite.api.events.DecorativeObjectDespawned;
import net.runelite.api.events.DecorativeObjectSpawned;
import net.runelite.api.events.WallObjectDespawned;
import net.runelite.api.events.WallObjectSpawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.WorldViewLoaded;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.awt.Polygon;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.components.colorpicker.ColorPickerManager;

@Slf4j
@PluginDescriptor(
	name = "Pathmaker",
    description = "Draw lines between marked tiles.",
    tags = {"object,path,line,draw,tile,indicator,navigation"}
)
public class PathmakerPlugin extends Plugin
{
    private static final String ICON_FILE = "panel_icon.png";
    private static final String CONFIG_KEY = "paths";

    public final int  MAX_POINT_LABEL_LENGTH = 50;
    public final int  TILE_SIZE = 128; // Not in net.runelite.api.Constants?
    public final int  TILE_SIZE_HALF = TILE_SIZE / 2;

    private final HashMap<String, PathmakerPath> paths = new HashMap<>();
    private PathmakerPluginPanel pluginPanel;
    private NavigationButton navButton;

    boolean hotKeyPressed = false;

//    enum ObjectType
//    {
//        GROUND,
//        GAME,
//        ITEM,
//        WALL,
//        DECORATIVE,
//    }

	@Inject
	private Client client;

	@Inject
	private PathmakerConfig config;

    @Inject
    private ConfigManager configManager;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private PathmakerOverlay overlay;

    @Inject
    private PathmakerPanelOverlay panelOverlay;

    @Inject
    private EventBus eventBus;

    @Inject
    private ClientToolbar clientToolbar;

    @Getter
    @Inject
    private ColorPickerManager colorPickerManager;

    @Inject
    private ClientThread clientThread;

    @Inject
    public Gson gson;

    @Inject
    private ChatboxPanelManager chatboxPanelManager;

    @Provides
    PathmakerConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(PathmakerConfig.class);
    }

	@Override
	protected void startUp() throws Exception
	{
		//log.info("Starting up Pathmaker plugin");

        overlayManager.add(overlay);
        overlayManager.add(panelOverlay);

		reload(client.getTopLevelWorldView());
		pluginPanel = new PathmakerPluginPanel(client, this);

		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), ICON_FILE);
		navButton = NavigationButton.builder()
			.tooltip("Pathmaker")
			.icon(icon)
			.priority(5)
			.panel(pluginPanel)
			.build();
		clientToolbar.addNavigation(navButton);
	}

	@Override
	protected void shutDown() throws Exception
	{
        overlayManager.remove(overlay);
        overlayManager.remove(panelOverlay);
        clientToolbar.removeNavigation(navButton);

        paths.clear();
    }

    void saveAll()
    {
        configManager.unsetConfiguration(PathmakerConfig.CONFIG_GROUP, CONFIG_KEY);


		if(!paths.isEmpty())
		{
			JsonObject pathsJson = new JsonObject();
			for (String pathName : paths.keySet())
			{
				pathsJson.add(pathName, pathToJson(pathName));
			}

			//String json = gson.toJson(paths);
			configManager.setConfiguration(PathmakerConfig.CONFIG_GROUP, CONFIG_KEY, pathsJson);
		}
        configManager.sendConfig();
    }

	JsonObject pathToJson(String pathName)
	{
		if (!paths.containsKey(pathName))
		{
			return null;
		}

		//String pathJson;
		Set<Integer> regionIds = paths.get(pathName).getRegionIDs();
		JsonObject pathJson = new JsonObject();

		JsonObject regionsJson = new JsonObject();
		for (int regionId : regionIds)
		{
			JsonArray regionJson = new JsonArray();
			for (PathPoint point : paths.get(pathName).getPointsInRegion(regionId))
			{
//				JsonObject pointJson = new JsonObject();
				boolean entityIsObject = point instanceof PathPointObject;
//				pointJson.add(entityIsObject ? "object" : "tile",
//					gson.toJsonTree(point, entityIsObject ? PathPointObject.class : PathPoint.class));
				regionJson.add(gson.toJsonTree(point, entityIsObject ? PathPointObject.class : PathPoint.class));
			}
			regionsJson.add(String.valueOf(regionId), regionJson);
		}

		pathJson.add("regions", regionsJson);
		pathJson.add("color", gson.toJsonTree(paths.get(pathName).color, Color.class));
		pathJson.add("looped", gson.toJsonTree(paths.get(pathName).loopPath, boolean.class));

		//log.debug("Saved path: {}", pathName);
		return pathJson;
	}

	void loadPathFromJson(JsonObject pathJson, String pathName)
	{
		if (pathJson == null) return;

		JsonObject regionsJson = pathJson.get("regions").getAsJsonObject();

		for (String regionIdString : regionsJson.keySet())
		{
			//log.debug("Loading region: {}, for path: {}", regionIdString, pathName);
			for(JsonElement pointElement : regionsJson.get(regionIdString).getAsJsonArray())
			{
				PathPoint pathPoint = null;

				try
				{
					pathPoint = gson.fromJson(pointElement,
						pointElement.getAsJsonObject().has("id") ?
							PathPointObject.class :
							PathPoint.class);
				}
				catch (JsonSyntaxException e){log.debug("Deserialized PathPoint is null.");}

				if (pathPoint != null)
				{
					createOrAddToPath(pathName, pathPoint);
				}
				else
					log.debug("Failed to add deserialized point to path: {}", pathName);
			}
		}

		paths.get(pathName).color = gson.fromJson(pathJson.get("color"), Color.class);
		paths.get(pathName).loopPath = gson.fromJson(pathJson.get("looped"), Boolean.class);
		//log.debug("Loaded path json: {}", pathName);
	}

    private void reload(WorldView wv)
    {
        paths.clear();
        String json = configManager.getConfiguration(PathmakerConfig.CONFIG_GROUP, CONFIG_KEY);

        if (Strings.isNullOrEmpty(json))
        {
            return;
        }

        try
        {
			JsonObject loadedPaths = gson.fromJson(json, new TypeToken<JsonObject>(){}.getType());
			if (loadedPaths.isJsonNull()) return;

			for (String pathName : loadedPaths.keySet())
			{
				log.debug("Loading path: {}", pathName);
				loadPathFromJson(loadedPaths.get(pathName).getAsJsonObject(), pathName);
			}
        }
        catch (IllegalStateException | JsonSyntaxException ignore)
        {
            JOptionPane.showConfirmDialog(pluginPanel,
                    "The paths you are trying to load are malformed",
                    "Warning", JOptionPane.OK_CANCEL_OPTION);
        }
    }

	@Subscribe
	public void onWorldViewLoaded(WorldViewLoaded event)
	{
		//log.debug("onWorldViewLoaded");
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned event)
	{
//		log.debug("GameObjectDespawned");
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
//		log.debug("GameObjectSpawned");
	}

	@Subscribe
	public void onWallObjectDespawned(WallObjectDespawned event)
	{
//		log.debug("WallObjectDespawned");
	}

	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned event)
	{
//		log.debug("WallObjectSpawned");
	}

	@Subscribe
	public void onGroundObjectDespawned(GroundObjectDespawned event)
	{
//		log.debug("GroundObjectDespawned");
	}

	@Subscribe
	public void onGroundObjectSpawned(GroundObjectSpawned event)
	{
//		log.debug("GroundObjectSpawned");
	}

	@Subscribe
	public void onDecorativeObjectSpawned(DecorativeObjectSpawned event)
	{
//		log.debug("DecorativeObjectSpawned");
	}

	@Subscribe
	public void onDecorativeObjectDespawned(DecorativeObjectDespawned event)
	{
//		log.debug("DecorativeObjectDespawned");
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned event)
	{
//		log.debug("ItemSpawned");
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
//		log.debug("ItemDespawned");
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
//		log.debug("NpcSpawned");
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
//		log.debug("NpcDespawned");
	}

    @Subscribe
    public void onGameTick(GameTick gameTick)
    {
		//log.debug("onGameTick");
    }

    // Get marked tiles within the rendered regions
    Collection<PathPoint> getTilesToHighlight()
    {
        Collection<PathPoint>  pathPoints = new ArrayList<>();

        // Get rendered regionIDs
        int[] regionsToLoad = client.getTopLevelWorldView().getMapRegions();
        for (PathmakerPath path : paths.values())
        {
            for (int regionID : regionsToLoad)
            {
                Collection<PathPoint> pathPointsInRegion = path.getPointsInRegion(regionID);
                if  (pathPointsInRegion != null)
                {
                    pathPoints.addAll(path.getPointsInRegion(regionID));
                }
            }
        }
        return pathPoints;
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (event.getGroup().equals(PathmakerConfig.CONFIG_GROUP))
        {
        }
    }

//	@Subscribe
//	public void onGameStateChanged(GameStateChanged gameStateChanged)
//	{
//		if (gameStateChanged.getGameState() == GameState.LOGGED_IN)
//		{
//			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Example says " + config.greeting(), null);
//		}
//	}

    @Subscribe
    public void onMenuEntryAdded(final MenuEntryAdded event)
    {
        // Only add menu option if shift is being held
        MenuAction menuAction = event.getMenuEntry().getType();
        hotKeyPressed = client.isKeyPressed(KeyCode.KC_SHIFT);

        if (!hotKeyPressed || (menuAction != MenuAction.WALK && menuAction != MenuAction.SET_HEADING &&
                menuAction != MenuAction.EXAMINE_NPC && menuAction != MenuAction.EXAMINE_OBJECT &&
			menuAction != MenuAction.EXAMINE_ITEM_GROUND))
        {
            return;
        }

        // Fetch game world
        int worldId = event.getMenuEntry().getWorldViewId();
        WorldView wv = client.getWorldView(worldId);
        if (wv == null)
        {
            log.debug("No world view found for getMenuEntry().getWorldViewId " + worldId);
            return;
        }

        // Fetch selected tile
        final Tile selectedSceneTile = wv.getSelectedSceneTile();
        if (selectedSceneTile == null)
        {
            return;
        }

        // Get the tile under cursor
        PathPoint pathPoint;

        String targetEntityString;
        String targetPathName = getActiveOrDefaultPathColorString(getActivePathName());
        final WorldPoint worldPoint;
        Point toCenterVec = new Point(TILE_SIZE_HALF, TILE_SIZE_HALF);

        // Attempt to get an actor (npc) under the cursor
        if (event.getMenuEntry().getNpc() != null)
        {
            NPC npc = event.getMenuEntry().getNpc();
            worldPoint = WorldPoint.fromLocalInstance(client, event.getMenuEntry().getNpc().getLocalLocation());
            targetEntityString = getActiveOrDefaultPathColorString(event.getMenuEntry().getNpc().getName());
            toCenterVec = getNpcToCenterVector(wv, event.getMenuEntry().getIdentifier());
        }
        else // If not an actor it's a tile OR an object
        {
            if (menuAction == MenuAction.EXAMINE_OBJECT)
            {
                // BIG shoutout to the ObjectIndicatorsPlugin for this implementation.
                // Spent most of the day just fighting chatGPT, and it was not at all fruitful.

                final int sceneX = event.getMenuEntry().getParam0();
                final int sceneY = event.getMenuEntry().getParam1();

                Tile tile = getTile(wv, sceneX, sceneY);

                int targetId = event.getMenuEntry().getIdentifier();


                TileObject tileObject = null;
                if (tile != null)
                {
                    tileObject = getTileObject(tile, targetId);
                }
                if (tileObject != null)
                {
                    worldPoint = WorldPoint.fromLocalInstance(client, tileObject.getLocalLocation());
                    toCenterVec = getObjectToCenterVector(wv, worldPoint, event.getIdentifier());

                } else
                {
                    worldPoint = WorldPoint.fromLocalInstance(client, selectedSceneTile.getLocalLocation());
                }
            }
            else
            {
                worldPoint = WorldPoint.fromLocalInstance(client, selectedSceneTile.getLocalLocation());
            }

            String target = event.getMenuEntry().getTarget(); // Returns an empty string if not an object
            targetEntityString = getActiveOrDefaultPathColorString(target.isEmpty() ? "Tile" : target);
        }

        // See if the point already exists
        pathPoint = getPathPointAtRegionTile(
			getActivePathName(),
			worldPoint.getRegionID(),
			worldPoint.getRegionX(),
			worldPoint.getRegionY(),
			worldPoint.getPlane());

        // If tile is not previously marked by this path, add the "add" option.
        if (pathPoint == null)
        {
            final PathPoint newPoint;

            // Skip if the entityID has already been registered
            if (menuAction == MenuAction.EXAMINE_NPC || menuAction == MenuAction.EXAMINE_OBJECT)
            {
                int entityID = event.getIdentifier();
                final boolean isNpc = menuAction == MenuAction.EXAMINE_NPC;

                newPoint = new PathPointObject(getActivePathName(), worldPoint.getRegionID(), worldPoint.getRegionX(),
                        worldPoint.getRegionY(), worldPoint.getPlane(), entityID, isNpc);

                if(toCenterVec != null)
                    ((PathPointObject) newPoint).setToCenterVector(toCenterVec.getX(), toCenterVec.getY());
            }
            else
            {
                newPoint = new PathPoint(getActivePathName(), worldPoint.getRegionID(), worldPoint.getRegionX(),
                        worldPoint.getRegionY(), worldPoint.getPlane());
            }

            client.getMenu().createMenuEntry(-1)
                    .setOption("Add " + targetEntityString + " to")
                    .setTarget(targetPathName)
                    .setType(MenuAction.RUNELITE)
                    .onClick(e ->
					{
						createOrAddToPath(Text.removeTags(targetPathName), newPoint);
						String targetLabel = Text.removeTags(targetEntityString);
						if (!targetLabel.equals("Tile"))
							newPoint.setLabel(targetLabel);
						rebuildPanel(true);
					});
        }

        // On existing POINTS
        else // actorPoint != null ||
        {
			final String activePathName = getActivePathName();

			// Do this if it belongs to the active path
			if(pathPoint.getPathOwnerName().equals(activePathName))
			{
				// Only configure add loop/unloop/label if point belongs to the active group
				// Only allow loop/unloop with points connected to the last point
				if ((pathPoint.getDrawIndex() == paths.get(activePathName).getSize() - 2 && paths.get(activePathName).loopPath) ||
					pathPoint.getDrawIndex() == 0 && paths.get(activePathName).getSize() > 2)
				{
					client.getMenu().createMenuEntry(-1)
						.setOption(paths.get(activePathName).loopPath ? "Unloop" : "Loop")
						.setTarget(targetPathName)
						.setType(MenuAction.RUNELITE)
						.onClick(e ->
						{
							// Reverse and unloop if target point is second to last in draw order (this preserves the path structure)
							if (pathPoint.getDrawIndex() == paths.get(activePathName).getSize() - 2)
							{
								paths.get(activePathName).setNewIndex(paths.get(activePathName).getPointAtDrawIndex(paths.get(activePathName).getSize() - 1), 0);
								paths.get(activePathName).reverseDrawOrder();
							}

							paths.get(activePathName).loopPath = !paths.get(activePathName).loopPath;
							rebuildPanel(true);
						});
				}

				// Add label rename option
				client.getMenu().createMenuEntry(-1)
					.setOption("Set " + targetEntityString + " label")
					.setTarget(targetPathName)
					.setType(MenuAction.RUNELITE)
					.onClick(e ->
					{
						String currentLabel = pathPoint.getLabel() == null ? "" : pathPoint.getLabel();

						chatboxPanelManager.openTextInput(targetEntityString + " label")
							.value(currentLabel)
							.onDone(label ->
							{
								if (label.length() > MAX_POINT_LABEL_LENGTH)
									label = label.substring(0, MAX_POINT_LABEL_LENGTH);
								pathPoint.setLabel(label); // From
								rebuildPanel(true);
							})
							.build();
					});
			}
        }

		// Add remove option regardless of path
		for(String pathName : paths.keySet())
		{
			PathPoint point = getPathPointAtRegionTile(pathName, worldPoint.getRegionID(), worldPoint.getRegionX(), worldPoint.getRegionY(), worldPoint.getPlane());
			if(point == null) continue;

			// Eliminate double entries when a point is both the position of an entity and entity tile
			if(!(point instanceof PathPointObject) || (!Text.removeTags(targetEntityString).equals("Tile")))
			{
				// Adding delete options, regardless of belonging path
				addRemoveMenuOption(pathName, point, "Remove " +
						ColorUtil.wrapWithColorTag(Text.removeTags(targetEntityString), paths.get(pathName).color) +
						" from",
					ColorUtil.wrapWithColorTag(Text.removeTags(pathName), paths.get(pathName).color));
			}
		}
    }

    void addRemoveMenuOption(String pathName, PathPoint pathPoint, String optionString, String target)
    {
        // Add remove option regardless of belonging path
        client.getMenu().createMenuEntry(-1)
                .setOption(optionString)
                .setTarget(target)
                .setType(MenuAction.RUNELITE)
                .onClick(e -> removePoint(pathName, pathPoint));
    }


    public HashMap<String, PathmakerPath> getStoredPaths()
    {
        return paths;
    }

    public PathmakerPath getActivePath()
    {
        return paths.get(getActivePathName());
    }

    public boolean pathExists(String pathName)
    {
        return paths.containsKey(pathName);
    }

    // Returns pathPoints from all paths within the specified region
	ArrayList<PathPoint> getPathPointsInRegion(int regionId)
    {
		ArrayList<PathPoint> pathPoints = new ArrayList<>();

        for (String pathName : getPathsInRegionKeys(regionId))
        {
            PathmakerPath path = paths.get(pathName);
            pathPoints.addAll(path.getPointsInRegion(regionId));
        }

        return pathPoints;
    }

    // Iterate through the stored paths and find all that have tiles within the specified region
    Collection<String> getPathsInRegionKeys(int regionId)
    {
        Collection<String> pathsInRegionKeys = new ArrayList<>();
        for (String pathName : paths.keySet())
        {
            for (int pathRegionId : paths.get(pathName).getRegionIDs())
            {
                if(pathRegionId == regionId)
                {
                    pathsInRegionKeys.add(pathName);
                    break;
                }
            }
        }
        return pathsInRegionKeys;
    }

    Color getDefaultPathColor()
    {
        return config.pathColor();
    }

    // Return PathPoint if one was previously created on the specified tile
	PathPoint getPathPointAtRegionTile(String path, int regionId, int regionX,  int regionY, int plane)
    {
        if(paths.isEmpty() || !paths.containsKey(path) || !paths.get(path).hasPointsInRegion(regionId))
        {
            return null;
        }

        // Iterate through each region tile to determine if the selected tile has a PathPoint
        for (PathPoint point : paths.get(path).getPointsInRegion(regionId))
        {
            if(point.getX() == regionX && point.getY() == regionY)
            {
				return point;
            }
        }
		return null;
    }

    // Create a new path starting with the given point or add to existing path
    void createOrAddToPath(String pathName, PathPoint point)
    {
		//pluginPanel.activePath.getText();//config.activePath();

        if(pathName == null) return;

        //log.debug("Checking for existing path: {}", activePath);
        PathmakerPath path;
        if(paths.containsKey(pathName))
        {
            path = paths.get(pathName);
            path.addPathPoint(point);
        }
        else
        {
            // Initialize new path with the initial point
            path = new PathmakerPath(point);
            path.color = getDefaultPathColor();
            paths.put(pathName, path);
        }
    }

    void removePoint(String pathName, PathPoint point)
    {
        paths.get(pathName).removePathPoint(point);
        if (paths.get(pathName).getSize() == 0)
        {
            removePath(pathName);
        }
        rebuildPanel(true);
    }

    void removePath(String pathName)
    {
        paths.remove(pathName);
    }

    String getActivePathName()
    {
        return pluginPanel.activePath.getText();
    }

    // Default colour if active path contains no points
    String getActiveOrDefaultPathColorString(String string)
    {
        return (paths.containsKey(getActivePathName()) ?
                ColorUtil.wrapWithColorTag(Text.removeTags(string), paths.get(getActivePathName()).color) :
                ColorUtil.wrapWithColorTag(Text.removeTags(string), config.pathColor()));
    }

    // For moving points
    void updatePointLocation(PathPoint point, int newRegionId, int x, int y, int z)
    {
        if(point.getRegionId() != newRegionId)
        {
            paths.get(getActivePathName()).updatePointRegion(point, newRegionId);
            //log.debug("Entity moved into a new region!");
        }

        point.updateRegionLocation(newRegionId, x, y, z);
    }

    void rebuildPanel(boolean savePaths)
    {
        pluginPanel.rebuild();
		if(savePaths)
			saveAll();
    }

    Tile getTile(WorldView wv, WorldPoint wp)
    {
        LocalPoint lp = LocalPoint.fromWorld(client, wp);
        if (lp == null) return null;

        return getTile(wv, lp.getSceneX(), lp.getSceneY());
    }

    Tile getTile(WorldView wv, int localSceneX, int localSceneY)
    {
        return wv.getScene().getTiles()[wv.getPlane()][localSceneX][localSceneY];
    }

    TileObject getTileObject(WorldView wv, PathPointObject point)
    {
        WorldPoint wp = WorldPoint.fromRegion(point.getRegionId(), point.getX(), point.getY(), wv.getPlane());
        return getTileObject(wv, wp, point.getEntityId());
    }

    TileObject getTileObject(WorldView wv, WorldPoint wp, int objectId)
    {
        return getTileObject(getTile(wv, wp), objectId);
    }

    // THANK YOU ObjectIndicatorsPlugin for this!
    TileObject getTileObject(Tile tile, int id)
    {
        if (tile == null) return null;

        // Get all objects on tile
        final GameObject[] tileGameObjects = tile.getGameObjects();
        final DecorativeObject tileDecorativeObject = tile.getDecorativeObject();
        final WallObject tileWallObject = tile.getWallObject();
        final GroundObject groundObject = tile.getGroundObject();

        // Return the object with a matching ID
        if (isObjectIdEqual(tileWallObject, id)) return tileWallObject;
        if (isObjectIdEqual(tileDecorativeObject, id)) return tileDecorativeObject;
        if (isObjectIdEqual(groundObject, id)) return groundObject;
        for (GameObject object : tileGameObjects)
        {
            if (isObjectIdEqual(object, id))
            {
                return object;
            }
        }

        // Occurs also if an object within the loaded regions despawns (ie, tree chopped)
        //log.debug("No Object found with id " + id);
        return null;
    }

    // THANK YOU ObjectIndicatorsPlugin for this!
    boolean isObjectIdEqual(TileObject tileObject, int id)
    {
        if (tileObject == null) return false;
        if (tileObject.getId() == id) return true;

        // Menu action EXAMINE_OBJECT sends the transformed object id, not the base id, unlike
        // all of the GAME_OBJECT_OPTION actions, so check the id against the impostor ids
        final ObjectComposition comp = client.getObjectDefinition(tileObject.getId());

        if (comp.getImpostorIds() != null)
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

    Polygon getEntityPolygon(WorldView wv, PathPointObject point)
    {
        return getEntityPolygon(wv, point.getWorldPoint(), point.isNpc(), point.getEntityId());
    }

    Polygon getEntityPolygon(WorldView wv, WorldPoint wp, boolean isNpc, int entityId)
    {
        if(isNpc)
        {
            NPC npc = wv.npcs().byIndex(entityId);
            if(npc != null) return npc.getCanvasTilePoly();
        }
        else
        {
            TileObject object = getTileObject(getTile(wv, wp), entityId);

            if (object instanceof GameObject) return ((GameObject) object).getCanvasTilePoly();
            else if (object instanceof DecorativeObject) return ((DecorativeObject) object).getCanvasTilePoly();
            else if (object instanceof GroundObject) return ((GroundObject) object).getCanvasTilePoly();
            else if (object instanceof WallObject) return ((WallObject) object).getCanvasTilePoly();
            else if (object instanceof ItemLayer) return ((ItemLayer) object).getCanvasTilePoly();
        }

        return null;
    }


    // NB LocalPoint is the center of a given tile


    // Cow: 2x2     Tree: 2x2
    // [ ][X]       [ ][X]
    // [ ][ ]       [ ][ ]

    Point getEntityToCenterVector(WorldView wv, WorldPoint wp, int entityId, boolean isNpc)
    {
        return isNpc ? getNpcToCenterVector(wv, entityId) : getObjectToCenterVector(wv, wp, entityId);
    }


    Point getNpcToCenterVector(WorldView wv, int npcId)
    {
		NPC npc = wv.npcs().byIndex(npcId);
		if (npc == null) return new Point(TILE_SIZE_HALF, TILE_SIZE_HALF);

		int offsetX = npc.getWorldArea().getWidth() * -TILE_SIZE_HALF + TILE_SIZE_HALF;
		int offsetY = npc.getWorldArea().getHeight() * -TILE_SIZE_HALF + TILE_SIZE_HALF;

		return new Point(offsetX, offsetY);
    }

    Point getObjectToCenterVector(WorldView wv, WorldPoint wp, int entityId)
    {
        LocalPoint lp = LocalPoint.fromWorld(wv, wp);
        if(lp == null) return new Point(TILE_SIZE_HALF, TILE_SIZE_HALF);

        TileObject object = getTileObject(getTile(wv, wp),  entityId);
        LocalPoint objLp = object.getLocalLocation();

        return new Point(objLp.getX() - lp.getX(), objLp.getY() - lp.getY());
    }

    LocalPoint getEntityCenter(int inradius, LocalPoint lp)
    {
        lp = lp.dx(inradius); lp = lp.dy(inradius);
        return lp;
    }
}
