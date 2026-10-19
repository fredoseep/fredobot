package com.fredoseep.motion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Bounded local action search over route guides, run-up timing and simulated steering/braking. */
public final class LocalGroundSearch {
    public record Goal(double x,double y,double z) {
        public Goal {
            if(!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))throw new IllegalArgumentException("Invalid goal");
        }
        public boolean reached(MotionState state) {
            return state.grounded() && Math.hypot(x-state.x(),z-state.z())<=.18
                    && Math.abs(y-state.y())<=.12 && Math.hypot(state.vx(),state.vz())<=.025;
        }
    }
    /** An intermediate landing target: retain velocity towards the following connection. */
    public record Transit(Goal next,double speed,boolean descendingGate) {
        public Transit(Goal next,double speed){this(next,speed,false);}
        public Transit {Objects.requireNonNull(next);if(!Double.isFinite(speed) || speed<.08 || speed>.3)throw new IllegalArgumentException("Invalid transit speed");}
    }
    public interface Agent {
        MotionState step(MotionInput input);
        default boolean horizontalCollision(){return false;}
        default float lookYaw(){return Float.NaN;}
    }
    public interface Factory {Agent create();}
    public record Limits(int maxTicks,int maxTrials,long budgetMillis,long optimizeMillis) {
        public static final Limits DEFAULT=new Limits(160,512,30_000,1_500);
        public Limits {if(maxTicks<1 || maxTrials<1 || budgetMillis<1 || optimizeMillis<0)throw new IllegalArgumentException("Invalid local search limits");}
    }
    private record Point(double x,double z) {}
    private record Candidate(List<Point> guides,int jumpAt,boolean sprint,double settling,int cruise) {}
    private final String id;
    private final MotionState initial;
    private final Goal goal;
    private final Factory factory;
    private final Limits limits;
    private final Transit transit;
    private final LongSupplier clock;
    private final long started;
    private final List<Candidate> candidates;
    private final Map<String,Integer> rejections=new LinkedHashMap<>();
    private final List<MotionPlan.Frame> frames=new ArrayList<>();
    private Agent agent;
    private Candidate candidate;
    private MotionState state;
    private MotionPlan best;
    private int candidateIndex,trials,steps,guide,stable,stalled;
    private double bestRemaining=Double.POSITIVE_INFINITY;
    private long firstFound=-1;
    private boolean finished;
    private String termination="SEARCHING",lastRejection="";
    private Candidate winner;
    private float cameraYaw;
    public LocalGroundSearch(String id,MotionState initial,Goal goal,Factory factory) {
        this(id,initial,goal,factory,Limits.DEFAULT,System::currentTimeMillis);
    }
    public LocalGroundSearch(String id,MotionState initial,Goal goal,Factory factory,Limits limits,LongSupplier clock) {
        this(id,initial,goal,factory,limits,clock,false);
    }
    public LocalGroundSearch(String id,MotionState initial,Goal goal,Factory factory,Limits limits,LongSupplier clock,boolean directOnly) {
        this(id,initial,goal,factory,limits,clock,directOnly,null);
    }
    public LocalGroundSearch(String id,MotionState initial,Goal goal,Factory factory,Limits limits,LongSupplier clock,boolean directOnly,Transit transit) {
        this.transit=transit;
        this.id=Objects.requireNonNull(id);this.initial=Objects.requireNonNull(initial);this.goal=Objects.requireNonNull(goal);
        this.factory=Objects.requireNonNull(factory);this.limits=Objects.requireNonNull(limits);this.clock=Objects.requireNonNull(clock);
        double distance=Math.hypot(goal.x-initial.x(),goal.z-initial.z());
        if(distance>10 || goal.y-initial.y()>1.01 || goal.y-initial.y()<-2.01)
            throw new IllegalArgumentException("局部目标限制：水平 10 格以内，高度 -2..+1 格");
        candidates=candidates(initial,goal,directOnly);started=clock.getAsLong();
    }
    public boolean finished(){return finished;}
    public MotionPlan result(){return best;}
    public String termination(){return termination;}
    public int simulatedTicks(){return steps;}
    public int trials(){return trials;}
    public Map<String,Object> metrics() {
        Map<String,Object> data=new LinkedHashMap<>();data.put("trials",trials);data.put("simulated_ticks",steps);
        data.put("arrival",transit==null?"STOP":"TRANSIT");
        if(transit!=null){data.put("transit_speed",transit.speed);data.put("descending_gate",transit.descendingGate);}
        data.put("candidate_count",candidates.size());data.put("best_frames",best==null?null:best.frames().size());
        data.put("goal",List.of(goal.x,goal.y,goal.z));data.put("termination",termination);
        data.put("elapsed_ms",Math.max(0,clock.getAsLong()-started));data.put("budget_ms",limits.budgetMillis);
        data.put("rejections",new LinkedHashMap<>(rejections));data.put("last_rejection",lastRejection);data.put("optimal",false);data.put("steering","WASD_TRAVEL_VIEW_UNCAPPED");
        if(winner!=null){
            int firstJump=-1;
            for(int i=0;i<best.frames().size();i++)if(best.frames().get(i).input().jump()){firstJump=i;break;}
            data.put("jump_tick",firstJump);data.put("candidate_jump_tick",winner.jumpAt);data.put("sprint",winner.sprint);data.put("braking",winner.settling);data.put("launch_cruise_ticks",winner.cruise);
            data.put("guide_points",winner.guides.stream().map(p -> List.of(p.x,p.z)).toList());}
        return data;
    }
    /** Advances at most one physical simulation tick. The caller slices work on the owning thread. */
    public void step() {
        if(finished)return;
        long now=clock.getAsLong();
        if(now-started>=limits.budgetMillis){finish(best==null?"TIME_BUDGET_NO_ROUTE":"TIME_BUDGET_INCUMBENT");return;}
        if(firstFound>=0 && now-firstFound>=limits.optimizeMillis){finish("OPTIMIZATION_BUDGET");return;}
        if(agent==null) {
            if(candidateIndex>=candidates.size() || trials>=limits.maxTrials){finish(best==null?"CANDIDATES_EXHAUSTED_NO_ROUTE":"CANDIDATES_EXHAUSTED");return;}
            candidate=candidates.get(candidateIndex++);trials++;guide=stable=stalled=0;bestRemaining=Double.POSITIVE_INFINITY;
            frames.clear();state=initial;
            try {
                agent=Objects.requireNonNull(factory.create());
                Point first=candidate.guides.get(0);
                cameraYaw=Float.isFinite(agent.lookYaw())?agent.lookYaw():GroundSteering.direction(first.x-initial.x(),first.z-initial.z());
            }catch(IllegalArgumentException error){reject("UNSUPPORTED_START");return;}
        }
        if(frames.size()>=limits.maxTicks || best!=null && frames.size()>=best.frames().size()) {reject("TICK_BOUND");return;}
        Point aim=candidate.guides.get(guide);
        double distance=Math.hypot(aim.x-state.x(),aim.z-state.z());
        if(guide<candidate.guides.size()-1 && distance<.5){aim=candidate.guides.get(++guide);bestRemaining=Double.POSITIVE_INFINITY;stalled=0;}
        Point viewFrom=guide==0?new Point(initial.x(),initial.z()):candidate.guides.get(guide-1);
        float travelYaw=GroundSteering.direction(aim.x-viewFrom.x,aim.z-viewFrom.z);
        cameraYaw=GroundSteering.turn(cameraYaw,travelYaw);
        boolean jump=frames.size()==candidate.jumpAt;
        if(jump && !state.grounded()){reject("MISSED_TAKEOFF");return;}
        // Preserve forward acceleration through takeoff. Immediate velocity braking used to
        // cancel the sprint boost before clearing the face of a one-block step.
        boolean launch=candidate.jumpAt>=0 && frames.size()>=candidate.jumpAt
                && frames.size()<candidate.jumpAt+candidate.cruise;
        if(jump && candidate.sprint && Math.abs(GroundSteering.difference(
                GroundSteering.direction(aim.x-state.x(),aim.z-state.z()),cameraYaw))>20){reject("TURNING_TAKEOFF");return;}
        double launchDistance=Math.hypot(aim.x-state.x(),aim.z-state.z());
        MotionInput input=launch?GroundSteering.accelerate((aim.x-state.x())/Math.max(.001,launchDistance),
                (aim.z-state.z())/Math.max(.001,launchDistance),cameraYaw,jump,candidate.sprint)
                :transit==null?steer(state,aim,jump,candidate.sprint,candidate.settling,cameraYaw)
                :steerTransit(state,aim,jump,candidate.sprint,transit,goal,cameraYaw);
        steps++;
        try {state=agent.step(input);frames.add(new MotionPlan.Frame(input,state));}
        catch(IllegalArgumentException error){lastRejection=String.valueOf(error.getMessage());reject("UNSUPPORTED_OR_UNSAFE_TERRAIN");return;}
        boolean passing=transit!=null && guide==candidate.guides.size()-1 && transitReached(state,goal,transit);
        if(guide==candidate.guides.size()-1 && goal.reached(state)
                && Math.hypot(goal.x-state.x(),goal.z-state.z())<=.16 && Math.abs(goal.y-state.y())<=.1
                && Math.hypot(state.vx(),state.vz())<=.02)stable++;else stable=0;
        if(transit==null?stable>=3:passing) {
            best=new MotionPlan(id,initial,frames,.002,.002);winner=candidate;
            if(firstFound<0)firstFound=clock.getAsLong();
            agent=null;return;
        }
        double remaining=Math.hypot(aim.x-state.x(),aim.z-state.z());
        if(remaining<bestRemaining-.015){bestRemaining=remaining;stalled=0;}else stalled++;
        if(stalled>24 || agent.horizontalCollision() && state.grounded() && stalled>8)reject("NO_PROGRESS");
    }
    public void cancel(){if(!finished){finished=true;termination="CANCELLED";best=null;agent=null;}}
    private void reject(String reason){rejections.merge(reason,1,Integer::sum);agent=null;}
    private void finish(String reason){finished=true;termination=reason;agent=null;}
    /** Grounded passage, correct floor, bounded speed, and velocity aligned with the exit.
     * Airborne crossings cannot consume a landing waypoint. The next connection is simulated too. */
    public static boolean transitReached(MotionState state,Goal goal,Transit transit) {
        double speed=Math.hypot(state.vx(),state.vz());
        double dx=transit.next.x-goal.x,dz=transit.next.z-goal.z,length=Math.hypot(dx,dz);
        double ox=state.x()-goal.x,oz=state.z()-goal.z;
        boolean position=Math.hypot(ox,oz)<=.28;
        if(transit.descendingGate && length>.001){
            double along=(ox*dx+oz*dz)/length,lateral=Math.abs(ox*dz-oz*dx)/length;
            position |= along>=-.28 && along<=.9 && lateral<=.35;
        }
        return state.grounded() && Math.abs(state.y()-goal.y)<=.1
                && position && speed>=.045 && speed<=.36
                && length>.001 && (state.vx()*dx+state.vz()*dz)/(speed*length)>=.35;
    }
    private static MotionInput steerTransit(MotionState state,Point aim,boolean jump,boolean sprint,Transit transit,Goal goal,float cameraYaw) {
        double dx=aim.x-state.x(),dz=aim.z-state.z(),distance=Math.hypot(dx,dz);
        double nx=transit.next.x-goal.x,nz=transit.next.z-goal.z,n=Math.hypot(nx,nz);
        double blend=Math.max(0,Math.min(.65,(.9-distance)/1.0));
        double tx=distance>.001?dx/distance:0,tz=distance>.001?dz/distance:0;
        if(n>.001){tx=tx*(1-blend)+nx/n*blend;tz=tz*(1-blend)+nz/n*blend;}
        double length=Math.hypot(tx,tz);
        double ax=(length>.001?tx/length*transit.speed:0)-state.vx();
        double az=(length>.001?tz/length*transit.speed:0)-state.vz();
        return GroundSteering.accelerate(ax,az,cameraYaw,jump,sprint);
    }
    private static MotionInput steer(MotionState state,Point aim,boolean jump,boolean sprint,double settling,float cameraYaw) {
        double dx=aim.x-state.x(),dz=aim.z-state.z(),distance=Math.hypot(dx,dz);
        if(distance<.12 && Math.hypot(state.vx(),state.vz())<.055 && !jump)
            return new MotionInput(false,false,false,false,false,false,false,cameraYaw,0);
        double desiredSpeed=Math.min(sprint?.22:.15,distance/settling);
        double ax=(distance>.000001?dx/distance*desiredSpeed:0)-state.vx();
        double az=(distance>.000001?dz/distance*desiredSpeed:0)-state.vz();
        return GroundSteering.accelerate(ax,az,cameraYaw,jump,sprint && (distance>1.2 || jump));
    }
    private static List<Candidate> candidates(MotionState start,Goal goal,boolean directOnly) {
        double dx=goal.x-start.x(),dz=goal.z-start.z(),length=Math.hypot(dx,dz);
        double px=length>.001?-dz/length:1,pz=length>.001?dx/length:0;
        Point end=new Point(goal.x,goal.z);
        List<List<Point>> guides=new ArrayList<>();guides.add(List.of(end));
        for(double side:directOnly?new double[]{}:new double[]{1.5,-1.5,3,-3}) {
            guides.add(List.of(new Point((start.x()+goal.x)*.5+px*side,(start.z()+goal.z)*.5+pz*side),end));
            guides.add(List.of(new Point(start.x()+px*side,start.z()+pz*side),new Point(goal.x+px*side,goal.z+pz*side),end));
        }
        List<Candidate> list=new ArrayList<>();
        // Direct ballistic candidates come first, including a non-sprinting short ascent.
        for(boolean sprint:new boolean[]{false,true})for(int cruise:new int[]{3,5,7,9,12})
            for(int jump:new int[]{0,1,2,4,6,8,10,12})for(double settling:new double[]{1.8,3.0})
                list.add(new Candidate(List.of(end),jump,sprint,settling,cruise));
        // Prefer walking on flat ground and down stairs; do not force a speculative jump before testing it.
        if(goal.y<=start.y()+.1)for(boolean sprint:new boolean[]{false,true})
            list.add(0,new Candidate(List.of(end),-1,sprint,3,0));
        for(List<Point> route:guides)for(int jump:new int[]{-1,0,1,2,3,4,6,8,10,12,16})
            for(boolean sprint:new boolean[]{true,false})for(double settling:new double[]{1.8,3.0})
                list.add(new Candidate(route,jump,sprint,settling,0));
        return List.copyOf(list);
    }
}
