package com.fredoseep.motion;

import java.util.LinkedHashMap;
import java.util.Map;

/** Compact final diagnostics; no per-tick logging. */
public final class MotionPlanStats {
    private MotionPlanStats(){}
    public static Map<String,Object> summarize(MotionPlan plan){
        int reversals=0,back=0,idle=0,stops=0;double maximum=0;
        for(int i=0;i<plan.frames().size();i++){
            var frame=plan.frames().get(i);var input=frame.input();var state=frame.after();
            if(i>0){double angle=Math.abs(GroundSteering.difference(input.yaw(),plan.frames().get(i-1).input().yaw()));
                maximum=Math.max(maximum,angle);if(angle>90)reversals++;}
            if(input.back())back++;
            if(!input.forward() && !input.back() && !input.left() && !input.right())idle++;
            if(state.grounded() && Math.hypot(state.vx(),state.vz())<.045)stops++;
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("frames",plan.frames().size());
        result.put("max_yaw_change",maximum);result.put("look_reversals_over_90",reversals);
        result.put("backward_input_ticks",back);result.put("neutral_input_ticks",idle);result.put("grounded_low_speed_ticks",stops);
        return result;
    }
}
