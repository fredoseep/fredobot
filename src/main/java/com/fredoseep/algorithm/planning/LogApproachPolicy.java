package com.fredoseep.algorithm.planning;

/** Initial treasure-opening wood collection uses normal growing search limits. */
public final class LogApproachPolicy {
    private LogApproachPolicy(){}
    public static boolean firstTierOnly(boolean logApproach,boolean initialTreeStage){
        return logApproach && !initialTreeStage;
    }
}
