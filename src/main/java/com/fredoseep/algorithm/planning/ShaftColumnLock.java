package com.fredoseep.algorithm.planning;

/** Once the body enters the shaft, ordinary swim inertia does not restart centering every layer. */
public final class ShaftColumnLock {
    private boolean locked;
    public boolean update(double dx,double dz,double halfWidth){
        if(locked && (Math.abs(dx)>.45 || Math.abs(dz)>.45))locked=false;
        if(!locked && Math.abs(dx)+halfWidth<=.50001 && Math.abs(dz)+halfWidth<=.50001)locked=true;
        return locked;
    }
}
