package com.fredoseep.motion;

/** Immutable state at a physics tick boundary; Minecraft objects stay outside the executor. */
public record MotionState(double x,double y,double z,double vx,double vy,double vz,boolean grounded) {
    public MotionState {
        if(!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Double.isFinite(vx) || !Double.isFinite(vy) || !Double.isFinite(vz))
            throw new IllegalArgumentException("Non-finite motion state");
    }
    public double positionError(MotionState other) {
        return Math.sqrt(square(x-other.x)+square(y-other.y)+square(z-other.z));
    }
    public double velocityError(MotionState other) {
        return Math.sqrt(square(vx-other.vx)+square(vy-other.vy)+square(vz-other.vz));
    }
    private static double square(double value){return value*value;}
}
