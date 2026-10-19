package com.fredoseep.motion;

/** Soft search guidance only. Every generated input still runs through vanilla physics. */
public final class FusionGuidance {
    public record Water(double surface,double bottom,double eyeY,boolean swimming,boolean submerged) {}
    private FusionGuidance(){}
    public static boolean surfaceRoute(Water water,boolean deepGoal,MotionState state,LocalGroundSearch.Goal goal){
        if(water==null)return false;
        double horizontal=Math.hypot(goal.x()-state.x(),goal.z()-state.z());
        return !deepGoal || horizontal>Math.max(6,(water.surface()-goal.y())*1.5);
    }
    public static MotionInput surface(MotionState s,Water w,LocalGroundSearch.Goal goal,float offset){
        double dx=goal.x()-s.x(),dz=goal.z()-s.z();float yaw=GroundSteering.direction(dx,dz)+offset;
        boolean deep=w.surface()-w.bottom()>=1.75;
        // Acquire the low swimming pose only in a sufficiently deep current column.
        // Once swimming, bottom proximity is irrelevant: never re-enter the dipping cycle.
        boolean headSubmerged=w.submerged() || w.eyeY()<w.surface()-.02;
        boolean dip=deep && !w.swimming() && !headSubmerged && s.y()>w.bottom()+.06;
        if(dip)return new MotionInput(true,false,false,false,false,true,true,yaw,0);
        if(!deep && !w.swimming())
            return new MotionInput(true,false,false,false,s.y()<w.surface()-.75 || s.vy()<-.06,false,true,yaw,0);
        // Vanilla tests water at eyeY - 0.111111..., so a 0.10 eye margin is still submerged.
        // Swimming eye height is 0.4: feet at surface - 0.22 leave ~0.18 eye clearance.
        double target=w.surface()-.22,error=target-s.y();
        double drive=error*.8-s.vy()*2.5;
        float pitch=(float)Math.max(-35,Math.min(35,-Math.toDegrees(Math.atan2(drive,.5))));
        boolean breathingCorrection=w.swimming() && w.submerged() && w.eyeY()>w.surface()-.1;
        boolean jump=(error>.12 && s.vy()<.08) || (breathingCorrection && s.vy()<.04);
        return new MotionInput(true,false,false,false,jump,false,true,yaw,pitch);
    }
    public static double penalty(MotionState s,Water water,boolean deepGoal,double escapeHeight){
        double penalty=Double.isFinite(escapeHeight)?Math.max(0,escapeHeight-s.y())*8:0;
        if(water!=null && !deepGoal)penalty+=Math.max(0,water.surface()-.45-s.y())*8;
        return penalty;
    }
}
