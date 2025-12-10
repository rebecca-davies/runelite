package net.runelite.client.plugins.pathmaker;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Set;

// Collection of path points
@Slf4j
public class PathmakerPath
{
    // Map with RegionIDs for keys with an ArrayList<PathPoint> for the specified region
    // Because a path might be spread across multiple regions
    private final HashMap<Integer, ArrayList<PathPoint>> pathPoints = new HashMap<>();
    Color color;
    boolean loopPath = false;
    boolean hidden = false;
    boolean panelExpanded = true;

    PathmakerPath(PathPoint initialPathPoint)
    {
        addPathPoint(initialPathPoint);
    }

    // Add point to existing path
    void addPathPoint(PathPoint pathPoint)
    {
        addPathPoint(pathPoint, pathPoint.getRegionId());
    }

    void addPathPoint(PathPoint pathPoint, int regionId)
    {
        // Add the tile's regionID as key for the belonging tile(s) if it doesn't already exist.
        if (!pathPoints.containsKey(regionId))
        {
            pathPoints.put(regionId, new ArrayList<PathPoint>());
        }
        pathPoints.get(regionId).add(pathPoint);

        if(pathPoint.getDrawIndex() == -1)
            pathPoint.setDrawIndex(getSize()-1);
    }

    void removePathPoint(PathPoint point)
    {
        int removedIndex = point.getDrawIndex();

        innerRemovePathPoint(point);

        ArrayList<PathPoint> drawOrder = getDrawOrder(null);
        for(int i = removedIndex; i < drawOrder.size(); i++)
        {
            drawOrder.get(i).setDrawIndex(i);
        }
    }

    // ONLY use this if moving tiles between regions! Removes point without reordering the draw order
    private void innerRemovePathPoint(PathPoint point)
    {
        int regionId = point.getRegionId();

        // Remove pathPoint from the ArrayList<PathPoint>
        pathPoints.get(regionId).remove(point);
        pathPoints.get(regionId).trimToSize();

        // Remove RegionID key if the ArrayList is empty.
        if (pathPoints.get(regionId).isEmpty())
        {
            pathPoints.remove(regionId);
        }
    }

    // Fetch all path points and close any draw index gaps
    void reconstructDrawOrder()
    {
        ArrayList<PathPoint> drawOrder = getDrawOrder(null);
        boolean startRearrangement = false;
        for  (int i = 0; i < drawOrder.size(); i++)
        {
            if (drawOrder.get(i).getDrawIndex() != i)
            {
                startRearrangement = true;
            }
            if (startRearrangement)
            {
                drawOrder.get(i).setDrawIndex(i);
            }
        }
    }

    // Remove point from old and add to new region. Reordering for iteration convenience.
    // The point xyz is moved independently.
    void updatePointRegion(PathPoint point, int newRegionId)
    {
        int oldRegionId = point.getRegionId();
        innerRemovePathPoint(point);
        addPathPoint(point, newRegionId);
        reconstructRegionDrawOrder(newRegionId);
    }

    // Return the relevant region IDs for this path
    Set<Integer> getRegionIDs()
    {
        return  pathPoints.keySet();
    }

    // Return tiles based on their regionID
    ArrayList<PathPoint> getPointsInRegion(int regionID)
    {
        return  pathPoints.get(regionID);
    }

    boolean hasPointsInRegion(int regionID)
    {
        return  pathPoints.containsKey(regionID);
    }

    boolean hasPointInRegion(int regionID, PathPoint point)
    {
        if(!pathPoints.containsKey(regionID)) return false;
        return pathPoints.get(regionID).contains(point);
    }


    // 2 -> 0
    // !newGreater
    // start = newIndex
    // target = oldIndex
    // i < target
    // idx += 1

    // 0 -> 2
    // newGreater
    // start = oldIndex + 1
    // target = newIndex + 1
    // i < target
    // idx += -1

// An easier look at what's going on in the uncommented for loop below
//        if(newGreater)
//        {
//            // 3p
//            // 0 -> 2
//            for(int i = oldIndex + 1; i <= newIndex; i++)
//            {
//                getPointAtDrawIndex(i).setDrawIndex(i - 1);
//            }
//        }
//        else
//        {
//            // 3p
//            // 2 -> 0
//            for(int i = oldIndex - 1; i >= newIndex; i--)
//            {
//                getPointAtDrawIndex(i).setDrawIndex(i + 1);
//            }
//        }

    // Set new draw index for a specific point and move the other point's indices accordingly
    void setNewIndex(PathPoint point, final int newIndex)
    {
        int oldIndex = point.getDrawIndex();

        if (oldIndex == newIndex){return;}

        boolean newGreater = newIndex > oldIndex;

        int indexMoveDir = newGreater ? -1 : 1;
        int startIndex = newGreater ? oldIndex + 1 : newIndex;
        int targetIndex = newGreater ? newIndex + 1 : oldIndex;

        ArrayList<PathPoint> pointsToMove = new ArrayList<>();
        ArrayList<Integer> regionsToReconstruct = new ArrayList<>();

        regionsToReconstruct.add(point.getRegionId());

        for(int i = startIndex; i < targetIndex; i ++)
        {
            pointsToMove.add(getPointAtDrawIndex(i));
        }

        // Changing draw index above is total bait, as it messes with getPointAtDrawIndex
        // So doing it here.
        for(int i = 0; i < pointsToMove.size(); i ++)
        {
            pointsToMove.get(i).setDrawIndex(pointsToMove.get(i).getDrawIndex() + indexMoveDir); // int indexMoveDir = newGreater ? -1 : 1;

            if(!regionsToReconstruct.contains(pointsToMove.get(i).getRegionId()))
            {
                regionsToReconstruct.add(pointsToMove.get(i).getRegionId());
            }
        }

        // Assign the specified index to the specified point
        point.setDrawIndex(newIndex);

        // Once the points have been reassigned their draw order, reorder the affected ArrayList to match
        // as this will make it easier for our getDrawOrder later
        for (int regionId : regionsToReconstruct)
        {
            reconstructRegionDrawOrder(regionId);
        }
    }

    // Sort the specified ArrayList in the order of draw indices
    void reconstructRegionDrawOrder(int regionId)
    {
        if (pathPoints.get(regionId).size() < 2) {return;}

        while(true)
        {
            boolean regionOrdered = true;
            for (int i = 1; i < pathPoints.get(regionId).size(); i++)
            {
                if(pathPoints.get(regionId).get(i).getDrawIndex() < pathPoints.get(regionId).get(i-1).getDrawIndex())
                {
                    Collections.swap(pathPoints.get(regionId), i, i-1);
                    regionOrdered = false;
                }
            }

            if(regionOrdered){break;}
        }
    }

    boolean isPointInRegions(PathPoint point, int[] regionIDs)
    {
        for (int regionID : regionIDs)
        {
            if (point.getRegionId() == regionID) {
                return true;
            }
        }
        return false;
    }

    boolean isPointInRegions(PathPoint point, ArrayList<Integer>regionIDs)
    {
        return regionIDs.contains(point.getRegionId());
    }

    boolean containsPoint(PathPoint point)
    {
        for (ArrayList<PathPoint> regionPoints : pathPoints.values())
            if (regionPoints.contains(point))
                return true;

        return false;
    }

    boolean containsEntity(int[] loadedRegions, boolean isNpc, int id)
    {
        for (int regionId : loadedRegions)
        {
            if (containsEntity(regionId, isNpc, id))
            {
                return true;
            }
        }
        return false;
    }

    // Only checking points within the loaded regions.
    boolean containsEntity(int region, boolean isNpc, int id)
    {
        if (!pathPoints.containsKey(region)) return false;

        for (PathPoint regionPoint : pathPoints.get(region))
        {
            if (regionPoint instanceof PathPointObject &&
                        ((PathPointObject) regionPoint).getEntityId() == id &&
                        ((PathPointObject) regionPoint).isNpc() == isNpc)
                return true;
        }
        return false;
    }

    PathPoint getPointAtDrawIndex(int index)
    {
        for(int regionId : pathPoints.keySet())
        {
            for (PathPoint point : pathPoints.get(regionId))
            {
                if (point.getDrawIndex() == index)
                {
                    return point;
                }
            }
        }

        log.debug("Could not find point at index: {}", index);
        return null;
    }

    public ArrayList<PathPoint> getReversedDrawOrder()
    {
        ArrayList<PathPoint> reverseDrawOrder = new ArrayList<>(getDrawOrder(null));
        Collections.reverse(reverseDrawOrder);
        return reverseDrawOrder;
    }

    public ArrayList<PathPoint> reverseDrawOrder()
    {
        ArrayList<PathPoint> reverseDrawOrder = getDrawOrder(null);
        for(int i = 0; i < getSize(); i++)
        {
            reverseDrawOrder.get(i).setDrawIndex(getSize() - 1 - i);
        }

        for (int regionId : pathPoints.keySet())
        {
            Collections.reverse(pathPoints.get(regionId));
        }

        return reverseDrawOrder;
    }

    // Return the size of all stored points (across all relevant regions) for this path
    int getSize()
    {
        int numPoints = 0;
        if(!pathPoints.isEmpty())
        {
            for (int regionId : pathPoints.keySet()) {
                numPoints += pathPoints.get(regionId).size();
            }
        }
        return numPoints;
    }

    // Return an ArrayList with the PathPoints in the order they should be drawn
    // NB! If param loadedRegions is null, then getDrawOrder will return the tiles also
    // NOT in loaded regions. (which you don't want to render, but is for sorting. See reconstructDrawOrder())
    ArrayList<PathPoint> getDrawOrder(@Nullable ArrayList<Integer> loadedRegions)
    {
        ArrayList<PathPoint> drawOrder = new ArrayList<>();

        // Calculate the number of points to collect (points that are inside the loaded regions)
        int numPointsToLoad = loadedRegions == null ? getSize() : 0;
        int searchIndex = 0;

        // Creating a map for tracking the last index checked in each of the RegionIDs
        // (which is used as keys for pathPoint) so the loops do not start at 0 every time
        // This works because stored points are sorted in their individual region ArrayLists
        // based on their draw order.
        final HashMap<Integer, Integer> loopIndexTracker = new HashMap<>();


        // Get the highest index value to be used as target, mostly in case of gaps
        int endIndex = 0;

        // If loadedRegions is null then return the full list of points in draw order regardless of region
        if(loadedRegions == null)
        {
            for (int regionId : pathPoints.keySet())
            {
                loopIndexTracker.put(regionId, 0);

                int numInRegion = pathPoints.get(regionId).size();
                int lastRegionIndex = pathPoints.get(regionId).get(numInRegion - 1).getDrawIndex();
                endIndex = Math.max(lastRegionIndex, endIndex);
            }

        }
        else
        {
            searchIndex = getSize();

            // Collect relevant regionIds with points that are both loaded and stored
            for (Integer loadedRegion : loadedRegions)
            {
                // Skip if region isn't loaded
                if (!pathPoints.containsKey(loadedRegion))
                {continue;}

                // Add regionID to the loop tracker
                loopIndexTracker.put(loadedRegion, 0);

                // Get final draw index. This will be used to limit the following while-loop
                int numInRegion = pathPoints.get(loadedRegion).size();
                int lastRegionIndex = pathPoints.get(loadedRegion).get(numInRegion - 1).getDrawIndex();
                endIndex = Math.max(lastRegionIndex, endIndex);

                numPointsToLoad += numInRegion;

                // Determine the starting draw index (may not be 0 if that tile is in an unloaded region)
                searchIndex = Math.min(pathPoints.get(loadedRegion).get(0).getDrawIndex(), searchIndex);

            }
        }

        // Iterate through the relevant list of points, collecting the points in the order of their draw index
        int lastSize = -1;
        while(drawOrder.size() < numPointsToLoad)
        {
            // If the next draw index cant be found, increase the index search gap
            if (lastSize == drawOrder.size())
            {
                searchIndex += 1;

                // Break if failed to find point within the scope
                if (searchIndex > endIndex)
                {
                    log.debug("Missing draw indices {}, out of: {}", numPointsToLoad- drawOrder.size(), numPointsToLoad);
                    break;
                }
            }


            lastSize = drawOrder.size();

            // Look for point with draw index equal to searchIndex. Store and break the current iterator index for a given ArrayList in
            // loopIndexTracker if the point found has an index that is greater than searchIndex.
            for (int relevantRegionId : loopIndexTracker.keySet())
            {
                for (int i = loopIndexTracker.get(relevantRegionId); i < pathPoints.get(relevantRegionId).size(); i++)
                {
                    PathPoint point = pathPoints.get(relevantRegionId).get(i);
                    int pointIndex = point.getDrawIndex();

                    if (pointIndex == searchIndex)
                    {
                        drawOrder.add(point);
                        searchIndex += 1;
                    }
                    else if (pointIndex > drawOrder.size())
                    {
                        loopIndexTracker.put(relevantRegionId, i);
                        break;
                    }
                    loopIndexTracker.put(relevantRegionId, i+1);
                }
            }
        }
        return drawOrder;
    }

	void loadPoints(HashMap<Integer, ArrayList<PathPoint>> pointsToLoad)
	{
		pathPoints.clear();
		pathPoints.putAll(pointsToLoad);
	}
}

