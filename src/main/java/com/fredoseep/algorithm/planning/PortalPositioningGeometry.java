package com.fredoseep.algorithm.planning;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import java.util.List;

/** Door-relative geometry, independent of camera yaw and the block containing the player's centre. */
public final class PortalPositioningGeometry {
    private PortalPositioningGeometry(){}
    public static boolean touchesRearDoor(Box body,BlockPos door,List<Box> localShapes,int forwardX,int forwardZ){
        Box probe=body.offset(-forwardX*.025,0,-forwardZ*.025);
        for(Box shape:localShapes)if(probe.intersects(shape.offset(door.getX(),door.getY(),door.getZ())))return true;
        return false;
    }
    /** Fraction of the horizontal body footprint inside the second door's block column. */
    public static double overlap(Box body,BlockPos door){
        double width=body.maxX-body.minX,depth=body.maxZ-body.minZ;
        if(width<=0 || depth<=0)return 0;
        double x=Math.max(0,Math.min(body.maxX,door.getX()+1)-Math.max(body.minX,door.getX()));
        double z=Math.max(0,Math.min(body.maxZ,door.getZ()+1)-Math.max(body.minZ,door.getZ()));
        return Math.min(1,x*z/(width*depth));
    }
}
