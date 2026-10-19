package com.fredoseep.algorithm.planning;

import com.fredoseep.motion.GroundSteering;
import com.fredoseep.motion.MotionInput;

/** Low-speed WASD alignment with velocity braking; a shaft has square, not circular, clearance. */
public final class ShaftCenterControl {
    private ShaftCenterControl(){}
    public record Control(MotionInput input,boolean settled) {}
    public static Control align(double dx,double dz,double vx,double vz,double halfWidth,float yaw){
        boolean inside=Math.abs(dx)+halfWidth<=.498 && Math.abs(dz)+halfWidth<=.498;
        boolean settled=inside && Math.hypot(vx,vz)<=.025
                && Math.abs(dx-4*vx)+halfWidth<=.498 && Math.abs(dz-4*vz)+halfWidth<=.498;
        double ax=settled?0:limit(.35*dx)-1.6*vx,az=settled?0:limit(.35*dz)-1.6*vz;
        return new Control(GroundSteering.accelerate(ax,az,yaw,false,false),settled);
    }
    private static double limit(double value){return Math.max(-.05,Math.min(.05,value));}
}
