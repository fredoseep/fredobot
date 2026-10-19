package com.fredoseep.motion;

/** One tick's absolute look and movement input. No teleport, velocity write or item interaction. */
public record MotionInput(boolean forward,boolean back,boolean left,boolean right,
        boolean jump,boolean sneak,boolean sprint,float yaw,float pitch) {
    public MotionInput {
        if(!Float.isFinite(yaw) || !Float.isFinite(pitch) || pitch < -90 || pitch > 90)
            throw new IllegalArgumentException("Invalid look angle");
    }
}
