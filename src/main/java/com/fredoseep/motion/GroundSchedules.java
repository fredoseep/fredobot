package com.fredoseep.motion;

import java.util.ArrayList;
import java.util.List;

/** Small, explicit action schedules for conformance tests. Not a replacement for global pathfinding. */
public final class GroundSchedules {
    private GroundSchedules() {}
    public static List<MotionInput> probe(String kind,int ticks,float yaw,float pitch) {
        if(ticks<1 || ticks>40)throw new IllegalArgumentException("ticks 必须在 1..40");
        if(!List.of("walk","sprint","jump").contains(kind))throw new IllegalArgumentException("未知动作 "+kind);
        List<MotionInput> inputs=new ArrayList<>();
        boolean sprint=!kind.equals("walk");
        int runup=kind.equals("jump")?Math.min(6,ticks):0;
        for(int i=0;i<runup;i++)inputs.add(new MotionInput(true,false,false,false,false,false,true,yaw,pitch));
        for(int i=0;i<ticks;i++)inputs.add(new MotionInput(true,false,false,false,kind.equals("jump") && i==0,false,sprint,yaw,pitch));
        // Preserve landing rather than firing another jump, then allow ordinary ground friction to stop.
        for(int i=0;i<35;i++)inputs.add(new MotionInput(false,false,false,false,false,false,false,yaw,pitch));
        return List.copyOf(inputs);
    }
}
