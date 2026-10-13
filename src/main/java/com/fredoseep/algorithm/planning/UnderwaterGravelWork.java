package com.fredoseep.algorithm.planning;

/** Door-work quota/loot policy. World and inventory observations stay on the client. */
public final class UnderwaterGravelWork {
    private int broken;
    private boolean flint;
    public void observeFlint(boolean seen){flint|=seen;}
    public void broken(){broken++;}
    public int count(){return broken;}
    public boolean hasFlint(){return flint;}
    public boolean collect(){return flint || broken>=4;}
    public boolean wants(boolean isGravel,boolean isFlint){return flint?isFlint:isGravel;}
    public static boolean inside(double dx,double dy,double dz,boolean grounded,boolean submerged) {
        return dx*dx+dz*dz<=.04 && Math.abs(dy)<=.2 && grounded && !submerged;
    }
}
