package com.fredoseep.algorithm.planning;

/** Enter above the foundation first; descend only after horizontal centering inside the air pocket. */
public final class DoorArrivalControl {
    public record Keys(boolean forward,boolean sneak,boolean jump) {}
    private DoorArrivalControl() {}
    /** Center of the free half next to the door slab; a full-width axis keeps its original midpoint. */
    public static double freeCenter(double min,double max) {
        if(max-min>=.5)return .5;
        return min<.5?(max+1)*.5:min*.5;
    }
    public static Keys settle(double dx,double dy,double dz,boolean grounded) {
        boolean outside=dx*dx+dz*dz>.005;
        return new Keys(outside, !outside && !grounded && dy>.18,
                !grounded && (outside?dy<.18:dy<-.18));
    }
}
