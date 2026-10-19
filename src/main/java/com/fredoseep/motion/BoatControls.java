package com.fredoseep.motion;

/** Steering intent only; acceleration, buoyancy, yaw inertia and collisions are vanilla BoatEntity physics. */
public final class BoatControls {
    private BoatControls(){}
    public static MotionInput choose(MotionState s,float boatYaw,float yawVelocity,float desiredYaw,double goalX,double goalZ){
        double distance=Math.hypot(goalX-s.x(),goalZ-s.z());
        double error=GroundSteering.difference(desiredYaw,boatYaw)-yawVelocity*4;
        double angle=Math.toRadians(boatYaw),forward=-Math.sin(angle)*s.vx()+Math.cos(angle)*s.vz();
        double wanted=Math.min(.38,Math.max(0,(distance-.12)*.10));
        boolean left=error< -2,right=error>2;
        boolean thrust=Math.abs(error)<65 && forward<wanted-.012;
        boolean brake=forward>wanted+.025;
        return new MotionInput(thrust && !brake,brake,left,right,false,false,false,desiredYaw,10);
    }
}
