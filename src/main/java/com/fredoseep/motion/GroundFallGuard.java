package com.fredoseep.motion;

/** Track each actual airborne interval, not total height lost over the entire route.
 * Ground contact refreshes the anchor only after the landing itself passed the drop check. */
public final class GroundFallGuard {
    private double anchorY;
    public GroundFallGuard(double startY){anchorY=startY;}
    public GroundFallGuard copy(){return new GroundFallGuard(anchorY);}
    public void beforeTick(double y,boolean grounded){if(grounded)anchorY=y;}
    public void afterTick(double y,boolean grounded,float fallDistance){
        if(y<anchorY-2.1 || fallDistance>3)
            throw new IllegalArgumentException("UNSAFE_FALL anchor="+anchorY+" y="+y+" drop="+(anchorY-y)+" fallDistance="+fallDistance);
        if(grounded)anchorY=y;
    }
}
