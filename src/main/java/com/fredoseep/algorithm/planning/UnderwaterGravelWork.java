package com.fredoseep.algorithm.planning;

/** Door-work quota/loot policy. World and inventory observations stay on the client. */
public final class UnderwaterGravelWork {
    /** Count real player ticks, including time spent following a pickup route; paused searches cost none. */
    public static boolean finalRecoveryExpired(int startAge,int age,int air,int maxAir) {
        return startAge>=0 && (age-startAge>=200 || (maxAir>0 && air<=Math.min(40,maxAir/5)));
    }
    /** Short spawn grace initially; after observing/picking up loot, only wait for delayed neighbours. */
    public static int finalRecoveryQuietTicks(boolean sawDrop){return sawDrop?6:12;}
    private int broken;
    private boolean flint;
    private int removedObservations;
    public void issued(){removedObservations=0;}
    public boolean confirmRemoval(boolean removed) {
        removedObservations=removed?removedObservations+1:0;
        return removedObservations>=3;
    }
    public void observeFlint(boolean seen){flint|=seen;}
    public void broken(){broken++;}
    public int count(){return broken;}
    public boolean hasFlint(){return flint;}
    public boolean collect(){return flint || broken>=4;}
    public boolean wants(boolean isGravel,boolean isFlint){return flint?isFlint:isGravel;}
    /** Evaluate before any return/oxygen check: a removed foundation cannot support the old door pose. */
    public static boolean teardownComplete(boolean foundationSolid,boolean doorPresent) {
        return !foundationSolid || !doorPresent;
    }
    public static boolean inside(double dx,double dy,double dz,boolean grounded,boolean submerged) {
        return dx*dx+dz*dz<=.04 && Math.abs(dy)<=.2 && grounded && !submerged;
    }
}
