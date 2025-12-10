package net.runelite.client.plugins.pathmaker;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;


@ConfigGroup(PathmakerConfig.CONFIG_GROUP)
public interface PathmakerConfig extends Config
{
    String CONFIG_GROUP = "pathmaker";
    String SHOW_MAP_ORB_MENU_OPTIONS = "showMapOrbMenuOptions";

    //------------------------------------------------------------//
    // Player Tile Section
    //------------------------------------------------------------//
    @ConfigSection(
            name = "Player tile",
            description = "Player tile configuration.",
            position = 3
    )
    String playerTile = "playerTile";

    @ConfigItem(
            keyName = "highlightCurrentTile",
            name = "Highlight true tile",
            description = "Highlights true tile player is on as seen by server.",
            position = 1,
            section = playerTile
    )
    default boolean highlightPlayerTile()
    {
        return false;
    }

    @Alpha
    @ConfigItem(
            keyName = "highlightCurrentColor",
            name = "Highlight color",
            description = "Configures the highlight color of current true tile.",
            position = 2,
            section = playerTile
    )
    default Color highlightPlayerColor()
    {
        return Color.CYAN;
    }

    @Alpha
    @ConfigItem(
            keyName = "currentTileFillColor",
            name = "Fill color",
            description = "Configures the fill color of current true tile.",
            position = 3,
            section = playerTile
    )
    default Color playerTileFillColor()
    {
        return new Color(0, 0, 0, 50);
    }

    @ConfigItem(
            keyName = "currentTileBorderWidth",
            name = "Border width",
            description = "Width of the true tile marker border.",
            position = 4,
            section = playerTile
    )
    @Range(max = 10)
    default int playerTileBorderWidth()
    {
        return 2;
    }

    //------------------------------------------------------------//
    // Hovered Tile Section
    //------------------------------------------------------------//
    @ConfigSection(
            name = "Hovered tile",
            description = "Cursor hovered tile configuration.",
            position = 2
    )
    String hoveredTile = "hoveredTile";


    enum hoveredTileDrawMode
    {
        NEVER,
        SHIFT_DOWN,
        ALWAYS,
    }
    @ConfigItem(
            keyName = "hoveredTileDrawModeSelect",
            name = "Hovered tile mode",
            description = "When the hovered tile elements should be drawn",
            position = 1,
            section = hoveredTile
    )
    default hoveredTileDrawMode hoveredTileDrawModeSelect()
    {
        return hoveredTileDrawMode.SHIFT_DOWN;
    }

    @ConfigItem(
            keyName = "highlightHoveredTile",
            name = "Highlight tile",
            description = "Highlights the tile that the player is hovering over.",
            position = 2,
            section = hoveredTile
    )
    default boolean highlightHoveredTile()
    {
        return false;
    }

    @Alpha
    @ConfigItem(
            keyName = "highlightHoveredColor",
            name = "Highlight color",
            description = "Configures the highlight color of hovered tile.",
            position = 3,
            section = hoveredTile
    )
    default Color highlightHoveredColor()
    {
        return new Color(255, 0, 0, 255);
    }

    @Alpha
    @ConfigItem(
            keyName = "hoveredTileFillColor",
            name = "Fill color",
            description = "Configures the fill color of hovered tile.",
            position = 4,
            section = hoveredTile
    )
    default Color hoveredTileFillColor()
    {
        return new Color(255, 0, 0, 50);
    }

    @ConfigItem(
            keyName = "hoveredTileBorderWidth",
            name = "Tile border width",
            description = "Width of the hovered tile marker border.",
            position = 5,
            section = hoveredTile
    )
    @Range(max = 10)
    default int hoveredTileBorderWidth()
    {
        return 2;
    }

    enum hoveredTileLabelMode
    {
        NONE,
        REGION,
        LOCATION,
        OFFSET,
        DISTANCE,
        ALL,
    }
    @ConfigItem(
            keyName = "hoveredTileLabelModeSelect",
            name = "Tile label mode",
            description = "Label to be placed on the hovered tile.",
            position = 6,
            section = hoveredTile
    )
    default hoveredTileLabelMode hoveredTileLabelModeSelect()
    {
        return hoveredTileLabelMode.NONE;
    }

    @Alpha
    @ConfigItem(
            keyName = "hoveredTileLabelColor",
            name = "Tile label color",
            description = "Configures the fill color of hovered tile label.",
            position = 7,
            section = hoveredTile
    )
    default Color hoveredTileLabelColor()
    {
        return new Color(255, 255, 0, 255);
    }

    @Alpha
    @ConfigItem(
            keyName = "drawHoverLine",
            name = "Draw line",
            description = "Draw line to hovered tile",
            position = 8,
            section = hoveredTile
    )
    default boolean drawHoverLine()
    {
        return true;
    }

    enum hoveredTileLineOrigin
    {
        TRUE_TILE,
        PATH_END,
    }
    @ConfigItem(
            keyName = "hoveredTileLineModeSelect",
            name = "Line origin",
            description = "Origin of hovered tile line.",
            position = 9,
            section = hoveredTile
    )
    default hoveredTileLineOrigin hoveredTileLineOriginSelect()
    {
        return hoveredTileLineOrigin.PATH_END;
    }

    @Alpha
    @ConfigItem(
            keyName = "hoveredTileLineColor",
            name = "Line color",
            description = "Configures the line to the hovered tile color.",
            position = 10,
            section = hoveredTile
    )
    default Color hoveredTileLineColor()
    {
        return new Color(255, 0, 0, 255);
    }

    @Alpha
    @ConfigItem(
            keyName = "hoverLineColorMatchPath",
            name = "Match active path",
            description = "Match the active path color",
            position = 11,
            section = hoveredTile
    )
    default boolean hoverLineColorMatchPath()
    {
        return false;
    }

    //------------------------------------------------------------//
    // Path Line Section
    //------------------------------------------------------------//
    @ConfigSection(
            name = "Path",
            description = "Path configuration.",
            position = 1
    )
    String path = "path";

    @ConfigItem(
            keyName = "drawPath",
            name = "Draw",
            description = "Render path lines",
            position = 1,
            section = path
    )
    default boolean drawPath()
    {
        return true;
    }

    @ConfigItem(
            keyName = "drawPathPoints",
            name = "Draw point tiles",
            description = "Highlight path point tiles.",
            position = 2,
            section = path
    )
    default boolean drawPathPoints()
    {
        return false;
    }

    @ConfigItem(
            keyName = "pathWidth",
            name = "Path width",
            description = "Width of the path line.",
            position = 3,
            section = path
    )
    @Range(max = 10)
    default int pathLineWidth()
    {
        return 2;
    }

    @Alpha
    @ConfigItem(
            keyName = "pathLineColor",
            name = "Default path color",
            description = "Configures the default path color.",
            position = 4,
            section = path
    )
    default Color pathColor()
    {
        return new Color(0, 255, 0, 255);
    }

    @Alpha
    @ConfigItem(
            keyName = "pathLinePointColor",
            name = "Path point tile color",
            description = "Configures the path line point tile color.",
            position = 6,
            section = path
    )
    default Color pathLinePointColor()
    {
        return new Color(0, 150, 0, 255);
    }

	@ConfigItem(
		keyName = "pointMatchPathColor",
		name = "Points match path color",
		description = "Set path points to match path color.",
		position = 7,
		section = path
	)
	default boolean pointMatchPathColor()
	{
		return true;
	}

//    @Alpha
//    @ConfigItem(
//            keyName = "pathLinePointFillColor",
//            name = "Path point tile fill color",
//            description = "Configures the path line point tile fill color.",
//            position = 8,
//            section = path
//    )
//    default Color pathLinePointFillColor()
//    {
//        return new Color(0, 255, 0, 50);
//    }

    @ConfigItem(
            keyName = "pathLinePointWidth",
            name = "Path point border width",
            description = "Width of the path line tile border.",
            position = 8,
            section = path
    )
    @Range(min = 0, max = 10)
    default int pathLinePointWidth()
    {
        return 2;
    }

    @ConfigItem(
            keyName = "pathZOffset",
            name = "Path draw height",
            description = "Configure the default Z offset for paths.",
            position = 9,
            section = path
    )
    @Range(min = 0, max = 10)
    default int pathZOffset()
    {
        return 0;
    }

    enum pathPointLabelMode
    {
        NONE,
        INDEX,
        LABEL,
        BOTH,
    }
    @ConfigItem(
            keyName = "pathPointLabelModeSelect",
            name = "Point label",
            description = "Add point labels.",
            position = 10,
            section = path
    )
    default pathPointLabelMode pathPointLabelModeSelect()
    {
        return pathPointLabelMode.BOTH;
    }

    @ConfigItem(
            keyName = "labelZOffset",
            name = "Label height offset",
            description = "Set label height offset from tile.",
            position = 11,
            section = path
    )
    @Range(max = 10)
    default int labelZOffset()
    {
        return 0;
    }

    @Alpha
    @ConfigItem(
            keyName = "pathPointLabelColor",
            name = "Path point label color",
            description = "Configures default the color of point labels.",
            position = 12,
            section = path
    )
    default Color pathPointLabelColor()
    {
        return Color.YELLOW;
    }

    @ConfigItem(
            keyName = "labelMatchPathColor",
            name = "label match path color",
            description = "Set labels to match path color.",
            position = 13,
            section = path
    )
    default boolean labelMatchPathColor()
    {
        return false;
    }

    /*
    // Buttons appear, but missing func
    @ConfigItem(
            keyName = SHOW_MAP_ORB_MENU_OPTIONS,
            name = "Show map orb menu options",
            description = "Adds import/export/clear options to the world map orb.",
            position = 10,
            section = path
    )
    default boolean showMapOrbMenuOptions()
    {
        return true;
    }
     */

    //------------------------------------------------------------//
    // Path Container Section
    //------------------------------------------------------------//
//    @ConfigSection(
//            name = "Path Container",
//            description = "Contains all paths.",
//            position = 4
//    )
//    String pathContainer = "pathContainer";

//    @ConfigItem(
//            keyName = "activePath",
//            name = "Active path",
//            description = "The currently selected path to add points to.",
//            position = 2,
//            section = pathContainer
//    )
//    default String activePath()
//    {
//        return "Unnamed";
//    }
//
//    @ConfigItem(
//            keyName = "storedPaths",
//            name = "Stored paths",
//            description = "A list of all of the stored paths. !NB Updates by returning to the plugin list and changing focus away from the plugin panel.",
//            position = 3,
//            section = pathContainer
//    )
//    default String storedPaths()
//    {
//        return "";
//    }
//
//    @ConfigItem(
//            keyName = "storedPaths",
//            name = "",
//            description = ""
//    )
//    void setStoredPaths(String pathString);

    //------------------------------------------------------------//
    // Info Box Section
    //------------------------------------------------------------//
    @ConfigSection(
            name = "Info Box",
            description = "Info Box configuration.",
            position = 4
    )
    String infoBox = "infoBox";

    @ConfigItem(
            keyName = "infoBoxEnabled",
            name = "Enabled",
            description = "Render info box",
            position = 1,
            section = infoBox
    )
    default boolean infoBoxEnabled()
    {
        return false;
    }

    @ConfigItem(
            keyName = "infoBoxSpeed",
            name = "Show Speed",
            description = "Print how many tiles the player moved since last tick.",
            position = 2,
            section = infoBox
    )
    default boolean infoBoxSpeed()
    {
        return true;
    }
}
