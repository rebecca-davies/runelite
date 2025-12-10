package net.runelite.client.plugins.pathmaker;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.NPC;
import net.runelite.api.TileObject;

import javax.annotation.Nonnull;

@Slf4j
public class PathPointObject extends PathPoint
{
    // Should be assigned / unassigned as the player enters the area
    //private TileObject tileObject;

    private int id = -1;
    private final boolean isNpc;
    //private int inradius;
    private int toCenterVectorX = 64;
    private int toCenterVectorY = 64;

    PathPointObject(String path, @Nonnull TileObject tileObject)
    {
        super(path, tileObject.getWorldLocation().getRegionID(), tileObject.getWorldLocation().getRegionX(),
                tileObject.getWorldLocation().getRegionY(), tileObject.getWorldView().getPlane());

        isNpc = false;
        id = tileObject.getId();
    }

    PathPointObject(String path, @Nonnull NPC npc, int id)
    {
        super(path, npc.getWorldLocation().getRegionID(),
                npc.getWorldLocation().getRegionX(),
                npc.getWorldLocation().getRegionY(),
                npc.getWorldView().getPlane());

        this.isNpc = true;
        this.id = id;
    }

    PathPointObject(String p, int r, int x, int y, int z, int id, boolean isNpc)
    {
        super(p, r, x, y, z);

        this.isNpc = isNpc;
        this.id = id;
    }

    // footprint * TileSize / 2
    void setToCenterVector(int x, int y)
    {
        this.toCenterVectorX = x;
        this.toCenterVectorY = y;
    }

    int getToCenterVectorX()
    {
        return this.toCenterVectorX;
    }
    int getToCenterVectorY()
    {
        return this.toCenterVectorY;
    }

//    private Renderable getRenderableObject(TileObject tileObject)
//    {
//        Renderable renderObj = null;
//
//        if (tileObject instanceof GameObject) {renderObj = ((GameObject) tileObject).getRenderable();} // Boxes, trees
//        else if (tileObject instanceof GroundObject) {renderObj = ((GroundObject) tileObject).getRenderable();} // Grass
//        else if (tileObject instanceof ItemLayer) {renderObj = ((ItemLayer) tileObject).getBottom();}  // Items held by tile
//        else if (tileObject instanceof DecorativeObject) {renderObj = ((DecorativeObject) tileObject).getRenderable();}
//        else if (tileObject instanceof WallObject) {renderObj = ((WallObject) tileObject).getRenderable1();}
//        return renderObj;
//    }

    int getEntityId()
    {
        return this.id;
    }

	void setEntityId(int entityId)
	{
		this.id = entityId;
	}

    boolean isNpc()
    {
        return isNpc;
    }

//    TileObject getObject()
//    {
//        return tileObject;
//    }
//
//
//    TileObject loadObject(TileObject object)
//    {
//        this.tileObject = object;
//        return this.tileObject;
//    }
//
//    void unloadObject()
//    {
//        this.tileObject = null;
//    }

//    void loadNpc (NPC npc)
//    {
//        this.npc = npc;
//    }
//
//    void unloadNpc()
//    {
//        npc = npc;
//    }
}
