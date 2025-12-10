package net.runelite.client.plugins.pathmaker;

import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;

// Ref: GroundMarkerPoint - https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/plugins/groundmarkers/GroundMarkerPoint.java#L38
public class PathPoint
{
    private int pathDrawIndex = -1;
    private int regionId;
    private int regionX;
    private int regionY;
    private int z;

	private final String pathOwner;
    private String label;

    PathPoint(String path, int regID, int regX, int regY, int plane)
    {
		this.pathOwner = path;
        this.regionId = regID;
        this.regionX = regX;
        this.regionY = regY;
        this.z = plane;
    }

    PathPoint(String path, WorldPoint worldPoint)
    {
		this.pathOwner = path;
        this.regionId = worldPoint.getRegionID();
        this.regionX = worldPoint.getRegionX();
        this.regionY = worldPoint.getRegionY();
        this.z = worldPoint.getPlane();
    }

	String getPathOwnerName()
	{
		return this.pathOwner;
	}

    int getRegionId()
    {
        return regionId;
    }

    int getX()
    {
        return regionX;
    }
    int getY()
    {
        return regionY;
    }
    int getZ()
    {
        return z;
    }

    WorldPoint getWorldPoint()
    {
        return WorldPoint.fromRegion(regionId, regionX, regionY, z);
    }

    void setLabel(String newLabel)
    {
        this.label = newLabel;
    }

    @Nullable
    String getLabel()
    {
        return label;
    }

    void setDrawIndex(int index)
    {
        this.pathDrawIndex = index;
    }

    int getDrawIndex()
    {
        return pathDrawIndex;
    }

    void updateRegionLocation(int region, int x, int y, int z)
    {
        // DONT FORGET TO ALSO UPDATE BELONGING PathmakerPath
        this.regionId = region;
        this.regionX = x;
        this.regionY = y;
        this.z = z;
    }
}
