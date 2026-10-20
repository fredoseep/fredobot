package com.fredoseep.motion;

/** Search guidance, never an oxygen bypass: native simulation still rejects unsafe branches. */
public final class FusionDivePolicy {
    public static final int AIR_RESERVE=40;
    public static final int DIVE_MARGIN_TICKS=30;
    public static final double ESTIMATED_UNDERWATER_SPEED=.10;
    private FusionDivePolicy(){}

    public static boolean delay(FusionGuidance.Water water,boolean deepGoal,MotionState state,
                                LocalGroundSearch.Goal goal,int air){
        return deepGoal && water!=null && estimatedTicks(state,goal)>Math.max(0,air-AIR_RESERVE);
    }
    public static int estimatedTicks(MotionState state,LocalGroundSearch.Goal goal){
        double distance=Math.sqrt(Math.pow(goal.x()-state.x(),2)+Math.pow(goal.y()-state.y(),2)
                +Math.pow(goal.z()-state.z(),2));
        return (int)Math.ceil(distance/ESTIMATED_UNDERWATER_SPEED)+DIVE_MARGIN_TICKS;
    }
    public static double penalty(FusionGuidance.Water water,boolean deepGoal,MotionState state,
                                 LocalGroundSearch.Goal goal,int air){
        if(!delay(water,deepGoal,state,goal,air))return 0;
        // While the remaining dive exceeds the air budget, reward horizontal progress
        // at breathing height. Direct underwater alternatives remain in the frontier.
        return Math.max(0,water.surface()-.22-state.y())*12
                +Math.max(0,estimatedTicks(state,goal)-Math.max(0,air-AIR_RESERVE))*.8;
    }
}
