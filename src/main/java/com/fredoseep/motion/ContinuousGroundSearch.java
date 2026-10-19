package com.fredoseep.motion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Speculative whole-route optimization. A verified stopped baseline is retained until a shorter
 * complete input sequence has also replayed from the original state. No real entity is moved. */
public final class ContinuousGroundSearch {
    private static final double[] SPEEDS={.22,.14,.28};
    private final GroundRouteSearch.Agent root;
    private final List<GroundRouteSearch.Cell> route;
    private final LocalGroundSearch.Goal goal;
    private final MotionPlan baseline;
    private final LongSupplier clock;
    private final long started,budgetMillis;
    private final List<MotionPlan.Frame> frames=new ArrayList<>();
    private final List<Map<String,Object>> joins=new ArrayList<>();
    private final List<Map<String,Object>> attempts=new ArrayList<>();
    private List<Map<String,Object>> selectedJoins=List.of();
    private GroundRouteSearch.Agent checkpoint,replay;
    private MotionPlan segment,best;
    private LocalGroundSearch local;
    private int profile=-1,cursor,connectionEnd,replayIndex,simulatedTicks,trials,completedCandidates,stopFallbacks,shortcutRetries;
    private boolean fallback,verifyAll,finished,cancelled;
    private String phase="CONTINUITY_START",reason="SEARCHING",lastFailure="";
    public ContinuousGroundSearch(GroundRouteSearch.Agent root,List<GroundRouteSearch.Cell> route,
            LocalGroundSearch.Goal goal,MotionPlan baseline,long budgetMillis){
        this(root,route,goal,baseline,budgetMillis,System::currentTimeMillis);
    }
    public ContinuousGroundSearch(GroundRouteSearch.Agent root,List<GroundRouteSearch.Cell> route,
            LocalGroundSearch.Goal goal,MotionPlan baseline,long budgetMillis,LongSupplier clock){
        this.root=Objects.requireNonNull(root);this.route=List.copyOf(route);this.goal=Objects.requireNonNull(goal);
        this.baseline=Objects.requireNonNull(baseline);this.clock=Objects.requireNonNull(clock);this.budgetMillis=Math.max(0,budgetMillis);
        MotionState start=root.state();
        if(start.positionError(baseline.initial())>1e-7 || start.velocityError(baseline.initial())>1e-7
                || start.grounded()!=baseline.initial().grounded() || !goal.reached(baseline.frames().get(baseline.frames().size()-1).after()))
            throw new IllegalArgumentException("Continuity baseline must match original state and full stable goal");
        best=baseline;started=clock.getAsLong();
        if(route.size()<3){finish("NO_INTERMEDIATE_WAYPOINT");return;}
        nextProfile();
    }
    public boolean finished(){return finished;}
    public MotionPlan result(){return cancelled?null:best;}
    public String phase(){return phase;}
    public int simulatedTicks(){return simulatedTicks+(local==null?0:local.simulatedTicks());}
    public int savedFrames(){return baseline.frames().size()-best.frames().size();}
    public int trials(){return trials+(local==null?0:local.trials());}
    public Map<String,Object> metrics(){
        Map<String,Object> m=new LinkedHashMap<>();m.put("phase",phase);m.put("reason",reason);
        m.put("baseline_frames",baseline.frames().size());m.put("selected_frames",best.frames().size());
        m.put("saved_frames",baseline.frames().size()-best.frames().size());m.put("selected_optimized",best!=baseline);
        m.put("selected_continuous",selectedJoins.stream().anyMatch(j->"TRANSIT".equals(j.get("arrival")) || ((Number)j.get("skipped_waypoints")).intValue()>0));
        m.put("profiles_started",profile+1);m.put("completed_candidates",completedCandidates);
        m.put("stop_fallbacks",stopFallbacks);m.put("shortcut_retries",shortcutRetries);m.put("connection_end",connectionEnd);
        m.put("skipped_waypoints",selectedJoins.stream().mapToInt(j->((Number)j.get("skipped_waypoints")).intValue()).sum());m.put("waypoint",cursor);m.put("waypoints",route.size());
        m.put("simulated_ticks",simulatedTicks());m.put("trials",trials());m.put("last_failure",lastFailure);
        m.put("selected_joins",selectedJoins);m.put("attempts",List.copyOf(attempts));m.put("optimal",false);
        m.put("elapsed_ms",clock.getAsLong()-started);m.put("budget_ms",budgetMillis);
        if(local!=null)m.put("local",local.metrics());return m;
    }
    public void cancel(){cancelled=true;finish("CANCELLED");}
    public void step(){
        if(finished)return;
        if(clock.getAsLong()-started>=budgetMillis){finish("OPTIMIZATION_BUDGET");return;}
        try {advance();}
        catch(IllegalArgumentException unsafe){abandon("UNSAFE_OR_UNSUPPORTED phase="+phase+" waypoint="+cursor+" end="+connectionEnd+" replay_frame="+(replayIndex-1)+" whole_route="+verifyAll+": "+unsafe.getMessage());}
    }
    private void advance(){
        if(replay!=null){
            MotionPlan.Frame f=segment.frames().get(replayIndex++);
            MotionState actual=replay.step(f.input());simulatedTicks++;
            if(actual.positionError(f.after())>1e-7 || actual.velocityError(f.after())>1e-7 || actual.grounded()!=f.after().grounded()){
                abandon("CONTINUITY_REPLAY_DIVERGED frame="+(replayIndex-1)+" whole_route="+verifyAll
                        +" expected="+f.after()+" actual="+actual+" input="+f.input());return;
            }
            if(replayIndex<segment.frames().size())return;
            if(verifyAll){
                if(!goal.reached(replay.state())){abandon("FINAL_GOAL_NOT_STABLE");return;}
                best=segment;selectedJoins=List.copyOf(joins);completedCandidates++;
                attempts.add(Map.of("speed",SPEEDS[profile],"result","SHORTER_VERIFIED","frames",best.frames().size()));
                nextProfile();return;
            }
            frames.addAll(segment.frames());checkpoint=replay;replay=null;segment=null;
            MotionState state=checkpoint.state();int from=cursor;cursor=connectionEnd;
            joins.add(Map.of("from_waypoint",from,"waypoint",cursor,"skipped_waypoints",cursor-from-1,
                    "frame",frames.size(),"speed",Math.hypot(state.vx(),state.vz()),
                    "grounded",state.grounded(),"arrival",isFinal()?"STOP":fallback?"STOP_FALLBACK":"TRANSIT"));
            if(isFinal()){
                if(!goal.reached(state)){abandon("FINAL_GOAL_NOT_STABLE");return;}
                segment=new MotionPlan(baseline.id(),baseline.initial(),frames,baseline.positionTolerance(),baseline.velocityTolerance());
                replay=root.copy();replayIndex=0;verifyAll=true;phase="CONTINUITY_VERIFY_ALL";
            }else {fallback=false;chooseEnd();beginLocal();}
            return;
        }
        local.step();if(!local.finished())return;
        trials+=local.trials();simulatedTicks+=local.simulatedTicks();segment=local.result();
        String failure=local.termination();local=null;
        if(segment==null){
            if(connectionEnd<route.size()-1 && !fallback){fallback=true;stopFallbacks++;beginLocal();}
            else if(shorten()){fallback=false;beginLocal();}
            else abandon("CONNECTION_FAILED waypoint="+connectionEnd+" "+failure);
            return;
        }
        if(frames.size()+segment.frames().size()>=best.frames().size()){
            abandon("NOT_SHORTER_THAN_INCUMBENT");return;
        }
        replay=checkpoint.copy();replayIndex=0;phase="CONTINUITY_VERIFY_JOIN";
    }
    private boolean isFinal(){return cursor==route.size()-1;}
    private boolean eligible(int index){
        MotionState state=checkpoint.state();var target=route.get(index).goal();
        return Math.hypot(target.x()-state.x(),target.z()-state.z())<=9.5
                && target.y()-state.y()<=1.01 && target.y()-state.y()>=-2.01;
    }
    private void chooseEnd(){
        connectionEnd=cursor+1;
        // Pure geometric guides are not mandatory landings. Propose a longer connection,
        // and let the actual vanilla collision/fall simulation approve or reject it.
        for(int i=cursor+2;i<Math.min(route.size(),cursor+6);i++)if(eligible(i))connectionEnd=i;
    }
    private boolean shorten(){
        for(int i=connectionEnd-1;i>cursor;i--)if(eligible(i)){connectionEnd=i;shortcutRetries++;return true;}
        return false;
    }
    private void beginLocal(){
        phase="CONTINUITY_CONNECTION";GroundRouteSearch.Agent start=checkpoint;
        LocalGroundSearch.Goal target=route.get(connectionEnd).goal();
        LocalGroundSearch.Transit transit=connectionEnd==route.size()-1 || fallback?null:
                new LocalGroundSearch.Transit(route.get(connectionEnd+1).goal(),SPEEDS[profile],target.y()<start.state().y()-.1);
        local=new LocalGroundSearch(baseline.id()+"-continuous-"+profile+"-"+cursor,start.state(),target,start::copy,
                new LocalGroundSearch.Limits(160,512,1500,40),clock,true,transit);
    }
    private void abandon(String failure){
        lastFailure=failure;
        attempts.add(Map.of("speed",SPEEDS[profile],"result",failure,"verified_prefix_frames",frames.size(),
                "from_waypoint",cursor,"to_waypoint",connectionEnd,"phase",phase));
        nextProfile();
    }
    private void nextProfile(){
        if(local!=null){trials+=local.trials();simulatedTicks+=local.simulatedTicks();local.cancel();local=null;}
        replay=null;segment=null;verifyAll=false;fallback=false;cursor=0;frames.clear();joins.clear();
        if(profile+1==SPEEDS.length){finish("PROFILES_EXHAUSTED");return;}
        profile++;checkpoint=root.copy();chooseEnd();beginLocal();
    }
    private void finish(String why){finished=true;reason=why;phase="CONTINUITY_DONE";if(local!=null){trials+=local.trials();simulatedTicks+=local.simulatedTicks();local.cancel();local=null;}}
}
