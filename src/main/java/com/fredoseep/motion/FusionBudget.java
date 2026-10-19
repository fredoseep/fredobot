package com.fredoseep.motion;

/** Physics states are much heavier than block-grid nodes. Scale by distance with a heap ceiling. */
public record FusionBudget(long searchMillis,int maxKnown,long jobMillis) {
    /** Adjustable known physics states per block of 3-D straight-line distance. */
    public static double INITIAL_STATES_PER_BLOCK=256;
    public static int MIN_INITIAL_STATES=256;
    public int initialKnown(double distance){
        if(!Double.isFinite(distance) || distance<0 || !Double.isFinite(INITIAL_STATES_PER_BLOCK)
                || INITIAL_STATES_PER_BLOCK<=0 || MIN_INITIAL_STATES<1)throw new IllegalArgumentException("invalid initial search limit");
        return (int)Math.min(maxKnown,Math.max(MIN_INITIAL_STATES,Math.ceil(distance*INITIAL_STATES_PER_BLOCK)));
    }
    public static FusionBudget forDistance(double distance,long maxHeap){
        if(!Double.isFinite(distance) || distance<0 || maxHeap<=0)throw new IllegalArgumentException("invalid fusion budget inputs");
        long requested=(long)Math.ceil(32000*Math.sqrt(1+distance/8));
        long heapCeiling=Math.max(1024,maxHeap/65536);
        int known=(int)Math.min(160000,Math.min(requested,heapCeiling));
        long millis=Math.min(180000,60000+(long)Math.ceil(distance*1500));
        return new FusionBudget(millis,known,millis+155000);
    }
}
