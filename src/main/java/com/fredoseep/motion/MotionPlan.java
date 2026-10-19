package com.fredoseep.motion;

import java.util.List;
import java.util.Objects;

/** Every frame predicts the state AFTER its input; initial predicts the state BEFORE frame zero. */
public record MotionPlan(String id,MotionState initial,List<Frame> frames,
        double positionTolerance,double velocityTolerance) {
    public record Frame(MotionInput input,MotionState after) {
        public Frame {Objects.requireNonNull(input);Objects.requireNonNull(after);}
    }
    public MotionPlan {
        Objects.requireNonNull(id);Objects.requireNonNull(initial);
        frames=List.copyOf(frames);
        if(id.isBlank() || frames.isEmpty() || frames.size()>1200)
            throw new IllegalArgumentException("Plan needs an id and 1..1200 frames");
        if(!Double.isFinite(positionTolerance) || positionTolerance<=0 || positionTolerance>.5
                || !Double.isFinite(velocityTolerance) || velocityTolerance<=0 || velocityTolerance>.5)
            throw new IllegalArgumentException("Tolerance must be in (0,.5]");
    }
}
