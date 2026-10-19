package com.fredoseep.motion;

import java.util.List;

/** Read-only render data, published on the client thread. Retained after failure for inspection. */
public final class MotionDebug {
    private MotionDebug(){}
    private static Object world;
    private static LocalGroundSearch.Goal goal;
    private static List<GroundRouteSearch.Cell> route=List.of();
    private static List<MotionPlan.Frame> frames=List.of();
    private static MotionState expected,actual;
    private static int index;
    private static boolean executing;
    private static boolean searching;
    private static List<MotionState> explored=List.of();
    public static void begin(Object owner,LocalGroundSearch.Goal target){
        world=owner;goal=target;route=List.of();frames=List.of();expected=actual=null;index=0;executing=false;searching=false;explored=List.of();
    }
    public static void search(List<GroundRouteSearch.Cell> coarse,List<MotionPlan.Frame> preview){
        route=List.copyOf(coarse);frames=List.copyOf(preview);
        searching=true;
    }
    public static void searchFusion(List<MotionPlan.Frame> preview,List<MotionState> visited){
        search(List.of(),preview);explored=List.copyOf(visited);
    }
    public static List<MotionState> explored(){return explored;}
    public static boolean searching(){return searching;}
    public static void acquire(Object owner,MotionPlan plan){
        if(world!=owner)begin(owner,null);
        frames=plan.frames();index=0;executing=true;searching=false;explored=List.of();
    }
    public static void advance(int completed){index=completed;}
    public static void finish(MotionSequence.Deviation divergence){
        executing=false;searching=false;
        if(divergence!=null){expected=divergence.expected();actual=divergence.actual();}
    }
    public static boolean visible(Object owner){if(world!=owner){begin(owner,null);return false;}return goal!=null || !frames.isEmpty();}
    public static LocalGroundSearch.Goal goal(){return goal;}
    public static List<GroundRouteSearch.Cell> route(){return route;}
    public static List<MotionPlan.Frame> frames(){return frames;}
    public static int index(){return index;}
    public static boolean executing(){return executing;}
    public static MotionState expected(){return expected;}
    public static MotionState actual(){return actual;}
}
