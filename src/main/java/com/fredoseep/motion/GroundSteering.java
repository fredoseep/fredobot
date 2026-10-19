package com.fredoseep.motion;

/** Camera follows travel intent; acceleration/braking is expressed with WASD in that view frame. */
public final class GroundSteering {
    private GroundSteering(){}
    public static float direction(double dx,double dz){return (float)Math.toDegrees(Math.atan2(-dx,dz));}
    public static float difference(float target,float current){
        float delta=(target-current)%360;if(delta>=180)delta-=360;if(delta< -180)delta+=360;return delta;
    }
    public static float turn(float current,float target){return current+difference(target,current);}
    public static MotionInput accelerate(double ax,double az,float yaw,boolean jump,boolean sprint){
        double angle=Math.toRadians(yaw),forward=-Math.sin(angle)*ax+Math.cos(angle)*az;
        double left=Math.cos(angle)*ax+Math.sin(angle)*az,magnitude=Math.hypot(forward,left);
        if(magnitude<=.009)return new MotionInput(false,false,false,false,jump,false,false,yaw,0);
        double threshold=Math.max(Math.abs(forward),Math.abs(left))*.41421356237;
        boolean f=forward>threshold,b=forward< -threshold,l=left>threshold,r=left< -threshold;
        return new MotionInput(f,b,l,r,jump,false,sprint && f && !b,yaw,0);
    }
}
