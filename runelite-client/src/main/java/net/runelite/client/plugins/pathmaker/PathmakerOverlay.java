package net.runelite.client.plugins.pathmaker;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import javax.annotation.Nullable;
import javax.inject.Inject;

import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;                  // For player position
import net.runelite.api.coords.WorldPoint;

import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.HashMap;

import lombok.extern.slf4j.Slf4j; // https://projectlombok.org/features/log
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;


@Slf4j
public class PathmakerOverlay extends Overlay
{
    private static final int MAX_DRAW_DISTANCE = 32;

    private final Client client;
    private final PathmakerPlugin plugin;
    private final PathmakerConfig config;
    private final ModelOutlineRenderer modelOutlineRenderer;

    private final float tileSize = 128;

    private LocalPoint startPoint;
    private LocalPoint hoveredTile;

    @Inject
    private PathmakerOverlay(Client client, PathmakerPlugin plugin, PathmakerConfig config, ModelOutlineRenderer modelOutlineRenderer)//, WorldView worldview)
    {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        this.modelOutlineRenderer = modelOutlineRenderer;

        //this.worldview = worldview;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(PRIORITY_MED);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        // Fetch player position
        // Doing getWorldLocation instead of getLocalLocation, because world loc. is server-side.
        final WorldPoint playerPos = client.getLocalPlayer().getWorldLocation();
        LocalPoint playerPosLocal = playerPos == null ? null : LocalPoint.fromWorld(client, playerPos);
        startPoint = playerPosLocal == null ? startPoint : playerPosLocal;
        WorldView wv = client.getTopLevelWorldView();

        // Current tile
        if (config.highlightPlayerTile())
        {
            highlightTile(graphics, wv, startPoint, config.highlightPlayerColor(), config.playerTileBorderWidth(), config.playerTileFillColor());
        }

//        // Fetch hovered tile and if successful, assign it to endPoint
//        WorldView wv = client.getTopLevelWorldView();//getLocalPlayer().getWorldView();

		LocalPoint lastActivePathPoint = null;
        // Highlight tiles marked by the right-click menu and draw lines between them
        if(!plugin.getStoredPaths().isEmpty())
        {
			lastActivePathPoint = drawPath(graphics, wv);
        }

		// Draw hovered tile elements
		if(config.hoveredTileDrawModeSelect() == PathmakerConfig.hoveredTileDrawMode.ALWAYS ||
			(config.hoveredTileDrawModeSelect() == PathmakerConfig.hoveredTileDrawMode.SHIFT_DOWN &&
				plugin.hotKeyPressed))
		{
			drawHoveredTile(graphics, wv, lastActivePathPoint);
		}

        return null;
    }

    void drawHoveredTile(Graphics2D graphics, WorldView wv, @Nullable LocalPoint lastPathPoint)
    {
        // Fetch hovered tile
        Tile tile = wv.getSelectedSceneTile();
        // Set hovered tile to be last hovered tile if none is found
        if(tile == null) return;
        //hoveredTile = tile == null ? hoveredTile : tile.getLocalLocation();
        hoveredTile = tile.getLocalLocation();

        // Return here if the distance to hovered tile exceeds the user interactable area.
        // If endPoint height = 0, it likely means it's out of bounds
        if (startPoint.distanceTo(hoveredTile) / tileSize >= MAX_DRAW_DISTANCE) {
            return;
        }

        // Highlight hovered tile
        if (config.highlightHoveredTile() && isRegionLoaded(tile.getWorldLocation().getRegionID())) {
            highlightTile(graphics, wv, hoveredTile, config.highlightHoveredColor(), config.hoveredTileBorderWidth(), config.hoveredTileFillColor());
        }

        // Add label
        if (config.hoveredTileLabelModeSelect() != PathmakerConfig.hoveredTileLabelMode.NONE) {
            String hoveredTileLabel = constructHoveredTileString(tile);
            if (!hoveredTileLabel.isEmpty()) {
                addLabel(graphics, hoveredTile, 0, hoveredTileLabel, config.hoveredTileLabelColor());
            }
        }

        // Draw line to hovered line
        // Set hover line to match the active path color if true
        if(config.drawHoverLine())
        {
            Color hoverLineColor;
            if(config.hoverLineColorMatchPath())
            {
                hoverLineColor = plugin.pathExists(plugin.getActivePathName()) ?
                        plugin.getStoredPaths().get(plugin.getActivePathName()).color :
                        config.pathColor();
            }
            else
            {
                hoverLineColor = config.hoveredTileLineColor();
            }

            switch (config.hoveredTileLineOriginSelect()) {
                case PATH_END: {
                    if (!plugin.pathExists((plugin.getActivePathName()))) {
                        break;
                    }
                    PathmakerPath activePath = plugin.getStoredPaths().get(plugin.getActivePathName());
                    PathPoint lastPoint = activePath.getPointAtDrawIndex(activePath.getSize() - 1);

                    if (!activePath.isPointInRegions(lastPoint, client.getTopLevelWorldView().getMapRegions()))
                    {
                        break;
                    }

					if (lastPathPoint == null)
					{
						lastPathPoint = pathPointToLocal(wv, lastPoint);
						if(lastPathPoint == null) break;

						//Set line origin to be in the center of objects
						if (lastPoint instanceof PathPointObject)
						{
							lastPathPoint = lastPathPoint.dx(((PathPointObject) lastPoint).getToCenterVectorX());
							lastPathPoint = lastPathPoint.dy(((PathPointObject) lastPoint).getToCenterVectorY());
						}
					}

                    drawLine(graphics, lastPathPoint, hoveredTile, hoverLineColor, (float) config.pathLineWidth());
                    break;
                }
                case TRUE_TILE: {
                    drawLine(graphics, startPoint, hoveredTile, hoverLineColor, (float) config.pathLineWidth());
                    break;
                }
                default:
                    break;
            }
        }
    }

    // Highlight tiles marked by the right-click menu and draw lines between them
    LocalPoint drawPath(Graphics2D graphics, WorldView wv)
    {
		if (plugin.getStoredPaths().isEmpty()) return null;
        HashMap<String, PathmakerPath> paths = plugin.getStoredPaths();
		ArrayList<Integer> loadedRegions = new ArrayList<>();
		String activePathName = plugin.getActivePathName();
		LocalPoint lastActivePathPoint = null;

		for (int regionId : client.getTopLevelWorldView().getMapRegions())
		{
			loadedRegions.add(regionId);
		}

        for (String pathName : paths.keySet())
        {
			LocalPoint lastLocalP = null;
            PathmakerPath path = paths.get(pathName);

            if(path.hidden)
            {
                continue;
            }

            int pathSize = path.getSize();

            ArrayList<PathPoint> drawOrder = paths.get(pathName).getDrawOrder(loadedRegions);

            if (config.drawPath() || config.drawPathPoints())
            {
				Color pathPointColor = config.pointMatchPathColor() ? path.color : config.pathLinePointColor();
				Color pathPointFillColor = new Color(pathPointColor.getRed(), pathPointColor.getGreen(), pathPointColor.getBlue(),  pathPointColor.getAlpha() / 5);

                for (int i = 0; i < drawOrder.size(); i++)
                {
                    PathPoint point = drawOrder.get(i);
                    LocalPoint localP = pathPointToLocal(wv, point);
                    //LocalPoint centerLocation;
                    // Draw outlines first, as this also lets us conveniently update the stored point locations
                    if(point instanceof PathPointObject)
                    {
						// Updating NPC world positions AND fetching current client side position to draw on
						localP = LocalPoint.fromWorld(wv, updateMovablePosition(wv, (PathPointObject) point));
                        //localP = pathPointToLocal(wv, point);

						if(localP != null)
						{
							drawOutline((PathPointObject) point, wv, config.pathLineWidth(), path.color, 200);

							if (config.drawPathPoints())
							{
								highlightTile(graphics, wv, plugin.getEntityPolygon(wv, (PathPointObject) point), pathPointColor, config.pathLinePointWidth(), pathPointFillColor);
							}
							localP = localP.dx(((PathPointObject) point).getToCenterVectorX());
							localP = localP.dy(((PathPointObject) point).getToCenterVectorY());
						}

						drawLabel(graphics, wv, localP, point.getDrawIndex(), point.getLabel(), path.color);
                    }
					else if(config.drawPathPoints()) // Draw non-entity tile highlights
					{
						highlightTile(graphics, wv, localP, pathPointColor, config.pathLinePointWidth(), pathPointFillColor);
						//drawLabel(graphics, wv, point, path.color);
					}

                    // Only draw line if the previous point had a draw index that was directly behind this.
                    if ((config.drawPath() && pathSize > 1) && i > 0 && drawOrder.get(i - 1).getDrawIndex() == point.getDrawIndex() - 1)
                        drawLine(graphics, lastLocalP, localP, path.color, (float) config.pathLineWidth());

					drawLabel(graphics, wv, localP, point.getDrawIndex(), point.getLabel(), path.color);

                    lastLocalP = localP;
                }
            }

            // Loop path
            if (path.loopPath && path.getSize() > 2 && config.drawPath())
            {
                // Making sure both ends are loaded
                if(path.isPointInRegions(path.getPointAtDrawIndex(path.getSize() -1), loadedRegions) &&
                        path.isPointInRegions(path.getPointAtDrawIndex(0), loadedRegions))
                {
                    PathPoint lastP = path.getPointAtDrawIndex(path.getSize() - 1);
                    PathPoint firstP = path.getPointAtDrawIndex(0);
                    drawLine(graphics, wv, lastP, firstP, path.color, (float) config.pathLineWidth());
                }
            }

			// Draw hovered tile elements
			if (pathName.equals(activePathName))
			{
				lastActivePathPoint =  lastLocalP;
			}
        }
		return  lastActivePathPoint;
    }

    // Convert PathPoint (region point) to local
    LocalPoint pathPointToLocal(WorldView wv, PathPoint point)
    {
        WorldPoint wp = WorldPoint.fromRegion(point.getRegionId(), point.getX(), point.getY(), point.getZ());
        return LocalPoint.fromWorld(wv, wp);
    }

    boolean highlightTile(final Graphics2D graphics, final WorldView wv, final PathPoint point, final Color color, final double borderWidth, final Color fillColor)
    {
        return highlightTile(graphics, wv, pathPointToLocal(wv, point), color, borderWidth, fillColor);
    }

    boolean highlightTile(final Graphics2D graphics, final WorldView wv, final LocalPoint lp, final Color color, final double borderWidth, final Color fillColor)
    {
        if (lp == null)// || !isLocalPointInScene(lp))
        {
            // Occurs on unload
            //log.debug("Failed to highlight tile, LocalPoint is null.");
            return false;
        }
        return highlightTile(graphics, wv, Perspective.getCanvasTilePoly(client, lp), color, borderWidth, fillColor);
    }

    boolean highlightTile(final Graphics2D graphics, final WorldView wv, final Polygon poly, final Color color, final double borderWidth, final Color fillColor)
    {
        // poly will be null i the tile is within a loaded region, but outside the camera's frustum or not loaded (i.e. despawning npcs)
        if (poly == null) return false;

        int boundsX = (int) poly.getBounds().getLocation().getX();
        int boundsY = (int) poly.getBounds().getLocation().getY();

        if(!isLocalPointInScene(new LocalPoint(boundsX, boundsY, wv))) return false;

        OverlayUtil.renderPolygon(graphics, poly, color, fillColor, new BasicStroke((float) borderWidth));
        return true;
    }

    private void drawLine(final Graphics2D graphics, final WorldView wv, final PathPoint startPoint, final PathPoint endPoint, final Color color, float lineWidth)
    {
        LocalPoint lineStart = pathPointToLocal(wv, startPoint);
        LocalPoint lineEnd = pathPointToLocal(wv, endPoint);
        drawLine(graphics, lineStart, lineEnd, color, lineWidth);
    }

    // Draw a line between the provided start and end points
    private void drawLine(final Graphics2D graphics, final LocalPoint startLoc, final LocalPoint endLoc, final Color color, float lineWidth){ //, int counter) {
        if (startLoc == null || endLoc == null)
        {
            return;
        }

        int z = client.getLocalPlayer().getWorldView().getPlane();

        final int startHeight = Perspective.getTileHeight(client, startLoc, z);
        final int endHeight = Perspective.getTileHeight(client, endLoc, z);

        Point p1 = Perspective.localToCanvas(client, startLoc.getX(), startLoc.getY(), startHeight + config.pathZOffset());
        Point p2 = Perspective.localToCanvas(client, endLoc.getX(), endLoc.getY(), endHeight + config.pathZOffset());

        if (p1 == null || p2 == null)
        {
            return;
        }

        Line2D.Double line = new Line2D.Double(p1.getX(), p1.getY(), p2.getX(), p2.getY());

        graphics.setColor(color);
        graphics.setStroke(new BasicStroke(lineWidth));
        graphics.draw(line);
    }

    // 0 tileObject, 1 npc
    void drawOutline(PathPointObject point, WorldView wv, int width, Color color, int feather)
    {
        if (point == null) {return;}

        if(point.isNpc())
        {
            NPC npc = wv.npcs().byIndex(point.getEntityId());
            if(npc == null){return;}

            modelOutlineRenderer.drawOutline(npc,width,color,feather);
        }
        else
        {
            TileObject tileObject = plugin.getTileObject(wv, point);
            if(tileObject == null){return;}
            modelOutlineRenderer.drawOutline(tileObject,width,color,feather);
        }
    }

	WorldPoint updateMovablePosition(WorldView wv, PathPointObject point)
    {
        if(point.isNpc())
        {
            NPC npc = wv.npcs().byIndex(point.getEntityId());

            if(npc != null)
			{
				final WorldPoint worldNpc = WorldPoint.fromLocalInstance(wv.getScene(), npc.getLocalLocation(), wv.getPlane());

				// Update the stored belonging PathPoint
				plugin.updatePointLocation(
					point,
					worldNpc.getRegionID(),
					worldNpc.getRegionX(),
					worldNpc.getRegionY(),
					wv.getPlane());

				return worldNpc;
			}
        }
		return point.getWorldPoint();
    }

	// (point.getDrawIndex() + 1)
	void drawLabel(Graphics2D graphics, WorldView wv, LocalPoint lp, int drawIndex, @Nullable String pointLabel, Color pathColor)
	{
		Color color = config.labelMatchPathColor() ? pathColor : config.pathPointLabelColor();

		String label = "";
		boolean stringEmpty = pointLabel == null || pointLabel.isEmpty();

		switch (config.pathPointLabelModeSelect())
		{
			case NONE:
				return;
			case BOTH:
			{
				label = "p" + (drawIndex + 1) + (stringEmpty ? "" : (", " + pointLabel));
				break;
			}
			case INDEX:
			{
				label = "p" + (drawIndex + 1);
				break;
			}
			case LABEL:
			{
				if(stringEmpty) return;
				label = pointLabel;
				break;
			}
		}

		addLabel(graphics, wv, lp, config.labelZOffset() * 10, label, color);
	}

//    void drawLabel(Graphics2D graphics, WorldView wv, PathPoint point, Color pathColor)//ArrayList<PathPoint> drawOrder, Color pathColor)
//    {
//		Color color = config.labelMatchPathColor() ? pathColor : config.pathPointLabelColor();
//
//		LocalPoint lp = pathPointToLocal(wv, point);
//		if(lp == null) return;
//
//		if(point instanceof PathPointObject)
//		{
//			lp = lp.dx(((PathPointObject) point).getToCenterVectorX());
//			lp = lp.dy(((PathPointObject) point).getToCenterVectorY());
//		}
//
//		drawLabel(graphics, wv, lp, point.getDrawIndex(), point.getLabel(), pathColor);

		// Draw label. Yes the split of loops here is intentional. More performant? Hopefully
//            switch (config.pathPointLabelModeSelect()) {
//                case INDEX: {
//                    for (PathPoint point : drawOrder)
//                    {
//                        LocalPoint lp = pathPointToLocal(wv, point);
//						if(lp == null) continue;
//
//                        if(point instanceof PathPointObject)
//                        {
//                            lp = lp.dx(((PathPointObject) point).getToCenterVectorX());//plugin.getEntityCenter(((PathPointObject) point).getInradius(), lp);
//                            lp = lp.dy(((PathPointObject) point).getToCenterVectorY());
//                        }
//
//                        addLabel(graphics, wv, lp, config.labelZOffset(), "p" + (point.getDrawIndex() + 1), color);
//                    }
//                    break;
//                }
//                case LABEL: {
//                    for (PathPoint point : drawOrder)
//                    {
//                        LocalPoint lp = pathPointToLocal(wv, point);
//						if(lp == null) continue;
//
//                        if(point instanceof PathPointObject)
//                        {
//                            lp = lp.dx(((PathPointObject) point).getToCenterVectorX());
//                            lp = lp.dy(((PathPointObject) point).getToCenterVectorY());
//                        }
//
//                        if (point.getLabel() != null && !point.getLabel().isEmpty())
//                            addLabel(graphics, wv, lp, config.labelZOffset(), point.getLabel(), color);
//                    }
//                    break;
//                }
//                case BOTH:
//                {
//                    for (PathPoint point : drawOrder)
//                    {
//                        LocalPoint lp = pathPointToLocal(wv, point);
//						if(lp == null) continue;
//
//                        if(point instanceof PathPointObject)
//                        {
//                            lp = lp.dx(((PathPointObject) point).getToCenterVectorX());
//                            lp = lp.dy(((PathPointObject) point).getToCenterVectorY());
//                        }
//
//                        String label = "p" + (point.getDrawIndex() + 1);
//                        if (point.getLabel() != null && !point.getLabel().isEmpty())
//                            label += ", " + point.getLabel();
//
//                        addLabel(graphics, wv, lp, config.labelZOffset() * 10, label, color);
//                    }
//                    break;
//                }
//                default:
//                    break;
//            }
//    }

    String constructHoveredTileString(Tile tile)
    {
        String returnString = "";
        switch (config.hoveredTileLabelModeSelect())
        {
            case REGION: returnString = getTileRegionString(tile); break;
            case LOCATION: returnString = getTileLocationString(tile); break;
            case OFFSET: returnString = getTileOffsetString(hoveredTile, config.hoveredTileLineOriginSelect() ==
                    PathmakerConfig.hoveredTileLineOrigin.PATH_END ?
                    getLastPointInActivePath() : startPoint);
                break;
            case DISTANCE: returnString = getTileDistanceString(startPoint, hoveredTile); break;
            case ALL: returnString = "R: " + getTileRegionString(tile) +
                    ", L: " + getTileLocationString(tile) +
                    ", O: "  + getTileOffsetString(hoveredTile, config.hoveredTileLineOriginSelect() ==
                                    PathmakerConfig.hoveredTileLineOrigin.PATH_END ?
                                    getLastPointInActivePath() : startPoint) +
                    ", D: " + getTileDistanceString(startPoint, hoveredTile); break;
            default: break;
        }

        return returnString;
    }

    LocalPoint getLastPointInActivePath()
    {
        PathmakerPath activePath = plugin.getStoredPaths().get(plugin.getActivePathName());
        PathPoint lastPoint = activePath.getPointAtDrawIndex(activePath.getSize() - 1);
        WorldPoint wp = WorldPoint.fromRegion(lastPoint.getRegionId(),lastPoint.getX(), lastPoint.getY(), lastPoint.getZ());
        return LocalPoint.fromWorld(client.getTopLevelWorldView(), wp);
    }

    String getTileLocationString(Tile tile)
    {
        return "( " + tile.getWorldLocation().getX() + ", " + tile.getWorldLocation().getY() + " )";
    }

    String getTileOffsetString(LocalPoint start, LocalPoint end)
    {
        return "( " + (int) ((start.getX() - end.getX()) / tileSize) + ", " + (int) ((start.getY() - end.getY()) / tileSize) + " )";
    }

    String getTileDistanceString(LocalPoint from,  LocalPoint to)
    {
        return from == null ? "" : String.valueOf((int) (from.distanceTo(to) / tileSize));
    }

    String getTileRegionString(Tile tile)
    {
        return String.valueOf(tile.getWorldLocation().getRegionID());
    }


    boolean addLabel(Graphics2D graphics, WorldView wv, LocalPoint lp, int zOffset, String labelText, Color color)
    {
        return addLabel(graphics, lp, zOffset, labelText, color);
    }

    boolean addLabel(Graphics2D graphics, LocalPoint tileLoc, int zOffset, String labelText, Color color)
    {
        if (tileLoc == null || !isLocalPointInScene(tileLoc))
            return false;

        Point canvasTextLocation = Perspective.getCanvasTextLocation(client, graphics, tileLoc, labelText, zOffset);

        if (canvasTextLocation != null)
        {
            OverlayUtil.renderTextLocation(graphics, canvasTextLocation, labelText, color);
            return true;
        }
        return false;
    }

    boolean isLocalPointInScene(final LocalPoint point)
    {
        WorldPoint wp = WorldPoint.fromLocal(client, point);
        return WorldPoint.isInScene(client.getTopLevelWorldView(), wp.getX(), wp.getY());
    }

    boolean isRegionLoaded(int regionId)
    {
        for(int loadedRegionID : client.getTopLevelWorldView().getMapRegions())
        {
            if (loadedRegionID == regionId)
            {
                return true;
            }
        }
        return false;
    }
}
