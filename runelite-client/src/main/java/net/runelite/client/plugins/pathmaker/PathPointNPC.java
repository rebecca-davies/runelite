package net.runelite.client.plugins.pathmaker;

import net.runelite.api.NPC;
import javax.annotation.Nullable;

public class PathPointNPC extends PathPoint
{
    private final NPC npc;

    PathPointNPC(String path, NPC npc)
    {
        super(path, npc.getWorldLocation().getRegionID(), npc.getWorldLocation().getRegionX(),
                npc.getWorldLocation().getRegionY(), npc.getWorldLocation().getPlane());
        this.npc = npc;
    }

    @Nullable
    Object getNPC()
    {
        return npc;
    }
}
