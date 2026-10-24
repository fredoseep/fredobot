package com.fredoseep.algorithm.planning;

import com.fredoseep.motion.MotionInput;

/** Steep downward steering: correct predicted drift without horizontal sprint lunges. */
public final class ShaftWaterCenterControl {
    private ShaftWaterCenterControl(){}
    public static MotionInput align(double dx,double dz,double vx,double vz,float yaw,boolean precise){
        double distance=Math.hypot(dx,dz),speed=Math.hypot(vx,vz);
        if(distance<=.025 && speed<=.006)
            return new MotionInput(!precise,false,false,false,false,false,!precise,yaw,90);
        double wanted=Math.min(precise?.04:.05,distance*.18);
        double tx=distance<1e-8?0:dx/distance*wanted,tz=distance<1e-8?0:dz/distance*wanted;
        // Brake residual movement before accelerating back through the centre.
        double ax=tx-vx*.8,az=tz-vz*.8,amount=Math.hypot(ax,az);
        if(amount<1e-6)return new MotionInput(!precise,false,false,false,false,false,!precise,yaw,90);
        float direction=(float)Math.toDegrees(Math.atan2(-ax,az));
        // Keep the horizontal component small; ordinary shaft descent still requests sprint-swimming.
        double tilt=Math.min(precise?6:10,amount*180);
        return new MotionInput(true,false,false,false,false,false,!precise,direction,(float)(90-tilt));
    }
}
