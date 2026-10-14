package com.fredoseep.algorithm.planning;

import com.fredoseep.algorithm.SimplePathfinder;
import net.minecraft.tag.FluidTags;
import net.minecraft.world.World;
import java.util.List;

/** A surface crossing keeps its physical length even when nodes are merged. */
public final class SurfaceBoatRoute {
    private SurfaceBoatRoute() {}
    public static boolean allowed(World world,SimplePathfinder.Node node) {
        return node.state==SimplePathfinder.MovementState.SWIMMING
                && (node.plannedAction==null || node.plannedAction.breaks.isEmpty() && node.plannedAction.material==null)
                && world.getBlockState(node.pos).getFluidState().isIn(FluidTags.WATER)
                && !world.getBlockState(node.pos.up()).getFluidState().isIn(FluidTags.WATER);
    }
    public static int launchEntry(World world,List<SimplePathfinder.Node> path,int index) {
        for(int i=index;i<Math.min(path.size(),index+6);i++) {
            var node=path.get(i);
            if(allowed(world,node))return i;
            if(node.state!=SimplePathfinder.MovementState.WALKING || node.plannedAction!=null
                    && (!node.plannedAction.breaks.isEmpty() || node.plannedAction.material!=null))break;
        }
        return -1;
    }
    public static double distance(World world,List<SimplePathfinder.Node> path,int index,double x,double z) {
        double length=0;
        for(int i=index;i<path.size();i++) {
            var node=path.get(i);
            if(!allowed(world,node)) break;
            double nextX=node.pos.getX()+.5,nextZ=node.pos.getZ()+.5;
            length+=Math.hypot(nextX-x,nextZ-z);x=nextX;z=nextZ;
        }
        return length;
    }
}
