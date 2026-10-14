package com.fredoseep.algorithm.planning;

/** Client-observed drift compensation. Coordinates and velocity are feet based. */
public final class WaterSteering {
    private WaterSteering() {}
    public record Aim(float yaw,float pitch,boolean rise) {}
    public static Aim aim(double dx,double dy,double dz,double vx,double vy,double vz,
                          double flowX,double flowY,double flowZ,float currentYaw) {
        // FluidState flow is a direction vector, not the player's per-tick drift speed.
        double x=limit(.8*dx-2*vx-.04*flowX);
        double y=limit(.9*dy-2*vy-.08*flowY);
        double z=limit(.8*dz-2*vz-.04*flowZ);
        // A one-block ascent/descent must not flatten out under lateral current compensation.
        if(dy>.25)y=Math.max(.6,y);
        if(dy<-.25)y=Math.min(-.6,y);
        double horizontal=Math.hypot(x,z);
        float yaw=horizontal>1e-6?(float)Math.toDegrees(Math.atan2(-x,z)):currentYaw;
        float pitch=horizontal>1e-6 || Math.abs(y)>1e-6?(float)-Math.toDegrees(Math.atan2(y,horizontal)):0;
        return new Aim(yaw,pitch,dy>.25);
    }
    private static double limit(double value){return Math.max(-1,Math.min(1,value));}
    public static boolean enterSwimming(boolean touchingWater,boolean swimming,double verticalError) {
        return touchingWater && !swimming && verticalError<=.25;
    }
}
