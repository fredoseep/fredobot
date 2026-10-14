package com.fredoseep.algorithm.planning;

/** Original gradual paddle steering with braking for sharp turns and the end of a crossing. */
public final class BoatSteering {
    public record Keys(boolean left,boolean right,boolean forward,boolean back) {}
    private BoatSteering() {}
    public static Keys tick(double yawError,double speed,double stoppingDistance,int age) {
        double angle=Math.abs(yawError);
        boolean turn=angle>30 || angle>10 && age%2==0 || angle>4 && age%3==0 || angle>1 && age%5==0;
        double limit=angle>45?.12:.36;
        if(Double.isFinite(stoppingDistance)) limit=Math.min(limit,Math.max(.035,stoppingDistance*.09));
        boolean brake=speed>limit;
        return new Keys(turn && yawError<0,turn && yawError>0,!brake && angle<60 && stoppingDistance>.2,brake);
    }
}
