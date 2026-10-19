package com.fredoseep.algorithm.planning;

/** Prepare a land collection task without spending its search allowance on an oxygen-starved start. */
public final class TreeBreathingPolicy {
    public static final int EMERGENCY_AIR=100;
    public record Control(float yaw,float pitch,boolean forward,boolean jump,boolean sprint) {}
    private TreeBreathingPolicy(){}
    public static Control control(boolean submerged,boolean touchingWater,boolean roof,boolean horizontalCollision,int age,float yaw){
        if(!submerged)return new Control(yaw,0,false,touchingWater,false);
        if(roof && horizontalCollision && age%10==0)yaw+=45;
        return new Control(yaw,roof?0:-85,true,true,!roof);
    }
    public static boolean start(boolean submerged,boolean touchingWater,int air,int maxAir,boolean routeBusy){
        return air<EMERGENCY_AIR || !routeBusy && (submerged || touchingWater && air<maxAir);
    }
    public static boolean complete(boolean submerged,int air,int maxAir){return !submerged && air>=maxAir;}
}
