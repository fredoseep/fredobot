package com.fredoseep.algorithm.planning;

import com.fredoseep.motion.GroundSteering;
import com.fredoseep.motion.MotionInput;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Local horizontal entry geometry and a stable, precise handoff to portal construction. */
public final class MagmaEntryControl {
    public static final double POSITION_TOLERANCE=.05;
    public static final double SPEED_TOLERANCE=.012;
    public static final int SETTLE_TICKS=4;
    private MagmaEntryControl(){}
    public static boolean nearDoor(BlockPos door,BlockPos column,Vec3d player){
        return door!=null && Math.hypot(door.getX()-column.getX(),door.getZ()-column.getZ())<10
                && Math.hypot(player.x-column.getX()-.5,player.z-column.getZ()-.5)<12;
    }
    public static int entryY(int seabedY,double playerY){return Math.max(seabedY,(int)Math.floor(playerY+.2));}
    public static boolean ready(double dx,double dy,double dz,double vx,double vy,double vz,boolean supported){
        return supported && Math.hypot(dx,dz)<=POSITION_TOLERANCE && Math.abs(dy)<=.08
                && Math.hypot(vx,vz)<=SPEED_TOLERANCE && Math.abs(vy)<=.1
                && Math.hypot(dx-4*vx,dz-4*vz)<=POSITION_TOLERANCE;
    }
    public static MotionInput align(double dx,double dz,double vx,double vz,float yaw){
        return GroundSteering.accelerate(limit(.6*dx)-2*vx,limit(.6*dz)-2*vz,yaw,false,false);
    }
    private static double limit(double value){return Math.max(-.05,Math.min(.05,value));}
}
