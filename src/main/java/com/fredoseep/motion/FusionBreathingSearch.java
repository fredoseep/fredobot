package com.fredoseep.motion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Bounded native rollouts for a breathing prefix; no teleport, stop, or full-air wait. */
public final class FusionBreathingSearch {
    public static final String EMERGENCY_PLAN="fusion-breathing-emergency";
    private static final float[] ANGLES={90,15,25,35,45,55,65,75,85};
    public record Prefix(FusionSearch.Agent agent,MotionPlan motion,MotionState breathingPoint,boolean emergency,int minimumAir) {}
    private final FusionSearch.Agent root;
    private final LocalGroundSearch.Goal goal;
    private FusionSearch.Agent trial;
    private final List<MotionPlan.Frame> frames=new ArrayList<>();
    private Prefix best;
    private MotionState breathingPoint;
    private FusionGuidance.Water lastWater;
    private int angleIndex,attempts,simulated,minimumAir,stalled;
    private float bestAngle=90,refineLow,refineHigh,refineAngle;
    private int refinements;
    private boolean emergency,finished,refining;
    private String rejection="NONE";

    public static boolean required(FusionSearch.Agent agent,LocalGroundSearch.Goal goal){
        FusionGuidance.Water water=agent.waterProfile();
        // Air pockets/nearby underwater work retain their direct approach. This prefix
        // serves an underwater departure toward a surface/land destination.
        return agent.water() && agent.submerged() && water!=null && goal.y()>=water.surface()-.75
                && !agent.reachedGoal(goal);
    }
    public FusionBreathingSearch(FusionSearch.Agent root,LocalGroundSearch.Goal goal){this.root=root;this.goal=goal;}
    public boolean finished(){return finished;}
    public Prefix result(){return best;}
    public int simulated(){return simulated;}
    public List<MotionPlan.Frame> preview(){return List.copyOf(frames);}
    public Map<String,Object> metrics(){
        return Map.of("breathing_attempts",attempts,"breathing_simulated_ticks",simulated,
                "breathing_emergency",emergency,"breathing_rejection",rejection,
                "breathing_point",best==null?"none":best.breathingPoint().toString(),
                "breathing_prefix_ticks",best==null?0:best.motion().frames().size(),
                "breathing_ascent_angle",best==null?"none":bestAngle,
                "breathing_min_air",best==null?"none":best.minimumAir(),
                "breathing_endpoint_air",best==null?root.availableAir():best.agent().availableAir());
    }
    public static boolean verticalAscent(MotionInput input){
        return input.forward() && input.sprint() && input.jump() && !input.sneak() && input.pitch()<=-89.9f;
    }
    /** One native tick per call, within the existing paused render work slices. */
    public void step(){
        if(finished)return;
        if(trial==null){
            trial=root.copy();frames.clear();breathingPoint=null;minimumAir=trial.availableAir();stalled=0;attempts++;
            lastWater=trial.waterProfile();
        }
        try{
            MotionState before=trial.state();
            float yaw=GroundSteering.direction(goal.x()-before.x(),goal.z()-before.z());
            FusionGuidance.Water water=trial.waterProfile();if(water!=null)lastWater=water;
            MotionInput input;
            if(lastWater!=null && (!trial.submerged() || !emergency && lastWater.surface()-lastWater.eyeY()<.5)){
                input=FusionGuidance.surface(before,lastWater,goal,0);
            }else{
                float angle=trialAngle();
                if(Math.hypot(goal.x()-before.x(),goal.z()-before.z())<.4)angle=90;
                input=new MotionInput(true,false,false,false,true,false,true,yaw,-angle);
            }
            input=trial.control(input,goal);
            MotionState after=trial.stepBreathing(input,emergency);simulated++;
            frames.add(new MotionPlan.Frame(input,after));minimumAir=Math.min(minimumAir,trial.availableAir());
            if(!trial.submerged()){
                if(breathingPoint==null)breathingPoint=after;
                // Observe real air recovery, while still moving; emergency prefixes
                // recover only enough to resume ordinary guarded search, not full air.
                if(trial.availableAir()>minimumAir && trial.availableAir()>=FusionDivePolicy.AIR_RESERVE){
                    Prefix candidate=new Prefix(trial,new MotionPlan(emergency?EMERGENCY_PLAN:"fusion-breathing",
                            root.state(),List.copyOf(frames),.20,.15),breathingPoint,emergency,minimumAir);
                    if(best==null || progress(candidate)>progress(best)+.01
                            || Math.abs(progress(candidate)-progress(best))<=.01 && candidate.motion().frames().size()<best.motion().frames().size()){
                        best=candidate;bestAngle=trialAngle();
                    }
                    nextTrial(false,true);return;
                }
            }
            stalled=after.y()<=before.y()+.0001 && trial.submerged()?stalled+1:0;
            if(stalled>=24 || frames.size()>=600){rejection=stalled>=24?"ASCENT_BLOCKED":"ASCENT_TICK_LIMIT";nextTrial(false,false);}
        }catch(IllegalArgumentException failure){
            rejection=String.valueOf(failure.getMessage());
            // A straight ascent already cannot preserve the reserve: honor the
            // emergency request immediately rather than trying longer diagonal dives.
            nextTrial(!emergency && !refining && angleIndex==0 && rejection.startsWith("AIR_RESERVE"),false);
        }
    }
    private double progress(Prefix prefix){
        MotionState origin=root.state(),point=prefix.breathingPoint();
        double dx=goal.x()-origin.x(),dz=goal.z()-origin.z(),length=Math.hypot(dx,dz);
        return length<.001?0:((point.x()-origin.x())*dx+(point.z()-origin.z())*dz)/length;
    }
    private float trialAngle(){return emergency?90:refining?refineAngle:ANGLES[angleIndex];}
    private void nextTrial(boolean useEmergency,boolean feasible){
        trial=null;
        if(useEmergency){emergency=true;return;}
        if(emergency){finished=true;return;}
        if(refining){
            if(feasible)refineHigh=refineAngle;else refineLow=refineAngle;
            if(++refinements>=6){finished=true;return;}
            refineAngle=(refineLow+refineHigh)/2;return;
        }
        if(++angleIndex==ANGLES.length){
            if(best==null){finished=true;return;}
            refining=true;refineHigh=bestAngle;refineLow=0;
            for(float angle:ANGLES)if(angle<bestAngle)refineLow=Math.max(refineLow,angle);
            refineAngle=(refineLow+refineHigh)/2;
        }
    }
}
