package com.fredoseep.algorithm.planning;

/** The executor must settle in the same grounded air pocket as the gravel task. */
public final class DoorArrivalControl {
    public record Keys(boolean forward,boolean sneak,boolean jump) {}
    private DoorArrivalControl() {}
    public static Keys settle(double dx,double dy,double dz,boolean grounded) {
        return new Keys(dx*dx+dz*dz>.025, !grounded && dy>.18, dy<-.18);
    }
}
