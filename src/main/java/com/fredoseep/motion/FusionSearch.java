package com.fredoseep.motion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.LinkedHashMap;
import java.util.ArrayDeque;

/** Experimental single frontier over physics states AND branch-local terrain/inventory.
 * No geometric route is computed first. Candidate pruning is deliberately approximate. */
public final class FusionSearch {
    public static final double DEFAULT_HEURISTIC_WEIGHT=1.6;
    public record Operation(String name,long since) {}
    private volatile Operation operation=new Operation("CREATED",System.currentTimeMillis());
    private final ArrayDeque<MotionState> explored=new ArrayDeque<>();
    private Node previewNode;
    private Node expandedPreview;
    public Operation operation(){return operation;}
    private void operation(String name){operation=new Operation(name,System.currentTimeMillis());}
    /** Client-thread render snapshots: speculative, never executable route results. */
    public List<MotionState> explored(){return List.copyOf(explored);}
    public List<MotionPlan.Frame> preview(){
        if(breathingSearch!=null)return breathingSearch.preview();
        Result ready=result==null?verifying:result;
        if(ready!=null){
            List<MotionPlan.Frame> frames=new ArrayList<>();
            for(Leg leg:ready.legs())if(leg.motion()!=null)frames.addAll(leg.motion().frames());
            return List.copyOf(frames.subList(Math.max(0,frames.size()-1200),frames.size()));
        }
        List<Node> reverse=new ArrayList<>();for(Node n=expandedPreview==null?previewNode:expandedPreview;n!=null && n.parent()!=null;n=n.parent())reverse.add(n);
        Collections.reverse(reverse);List<MotionPlan.Frame> frames=new ArrayList<>();
        for(Node n:reverse)for(Leg leg:expand(n.leg()))if(leg.motion()!=null)frames.addAll(leg.motion().frames());
        return List.copyOf(frames.subList(Math.max(0,frames.size()-1200),frames.size()));
    }
    public interface Interaction {
        int ticks();
        double price();
        String kind();
    }
    public interface Agent {
        Agent copy();
        default boolean reachedGoal(LocalGroundSearch.Goal goal){
            MotionState s=state();double distance=Math.hypot(goal.x()-s.x(),goal.z()-s.z());
            return water()?distance<=.32 && Math.abs(goal.y()-s.y())<=.65 && Math.hypot(s.vx(),s.vz())<=.16:goal.reached(s);
        }
        MotionState state();
        boolean water();
        default boolean pitExitSafe(double originY,double rimY){return false;}
        default boolean climbing(){return false;}
        default boolean horizontalCollision(){return false;}
        default boolean submerged(){return water();}
        default int availableAir(){return 300;}
        default FusionGuidance.Water waterProfile(){return null;}
        default boolean underwaterGoal(LocalGroundSearch.Goal goal){return false;}
        default double escapeHeight(){return Double.NaN;}
        default Map<String,Integer> interactionDiagnostics(){return Map.of();}
        /** Must be safe to stop and pause here; production adds breathing/pose checks. */
        default boolean checkpointSafe(){
            MotionState s=state();return !water() && s.grounded() && Math.hypot(s.vx(),s.vz())<=.035 && Math.abs(s.vy())<=.1;
        }
        Object contextKey(); // pose, fluid/input history, edits, inventory, durability
        default MotionInput control(MotionInput proposed,LocalGroundSearch.Goal goal){return proposed;}
        MotionState step(MotionInput input);
        default MotionState stepBreathing(MotionInput input,boolean emergency){return step(input);}
        List<? extends Interaction> interactions();
        default List<? extends Interaction> interactions(LocalGroundSearch.Goal goal){return interactions();}
        default Interaction prepare(Interaction interaction){return interaction;}
        Agent apply(Interaction interaction);
    }
    public record Leg(MotionPlan motion,Interaction interaction) {
        public Leg {if((motion==null)==(interaction==null))throw new IllegalArgumentException("one leg kind required");}
    }
    public record Program(List<Leg> steps,boolean escape) implements Interaction {
        public Program(List<Leg> steps){this(steps,false);}
        public Program {steps=List.copyOf(steps);if(steps.isEmpty())throw new IllegalArgumentException("empty clearance program");}
        public int ticks(){return steps.stream().mapToInt(l->l.motion()!=null?l.motion().frames().size():l.interaction().ticks()).sum();}
        public double price(){return steps.stream().filter(l->l.interaction()!=null).mapToDouble(l->l.interaction().price()).sum();}
        public String kind(){return escape?"PIT_ESCAPE":"CLEARANCE";}
        public int editTicks(){return steps.stream().filter(l->l.interaction()!=null).mapToInt(l->l.interaction().ticks()).sum();}
    }
    private static List<Leg> expand(Leg leg){return leg.interaction() instanceof Program p?p.steps():List.of(leg);}
    public record Result(List<Leg> legs,double cost,int expanded,int simulated,boolean complete,MotionState endpoint,boolean pitExit) {}
    private record Key(long x,long y,long z,long vx,long vy,long vz,boolean ground,boolean water,boolean submerged,boolean collision,Object context) {}
    private static final class Node {
        private final Agent agent;
        private final Node parent;
        private final Leg leg;
        private final double g,rank;
        private double leftRank=Double.NaN,rightRank=Double.NaN;
        // The three frontiers reference the same node. Expand each version only once.
        private boolean selected;
        Node(Agent agent,Node parent,Leg leg,double g,double rank){
            this.agent=agent;this.parent=parent;this.leg=leg;this.g=g;this.rank=rank;
        }
        Agent agent(){return agent;}Node parent(){return parent;}Leg leg(){return leg;}
        double g(){return g;}double rank(){return rank;}
    }
    private enum Mode {MOVE,BRAKE,SHORE,RISE,SURFACE,ESCAPE}
    private record Candidate(float offset,boolean jump,int ticks,Mode mode) {
        Candidate(float offset,boolean jump,int ticks,boolean brake){this(offset,jump,ticks,brake?Mode.BRAKE:Mode.MOVE);}
        Candidate(float offset,boolean jump,int ticks,boolean brake,boolean shore,boolean rise){
            this(offset,jump,ticks,rise?Mode.RISE:shore?Mode.SHORE:brake?Mode.BRAKE:Mode.MOVE);
        }
        boolean brake(){return mode==Mode.BRAKE;}
        boolean shore(){return mode==Mode.SHORE || mode==Mode.RISE;}
        boolean rise(){return mode==Mode.RISE;}
    }
    private final PriorityQueue<Node> open=new PriorityQueue<>(Comparator.comparingDouble(Node::rank));
    private final PriorityQueue<Node> leftOpen=new PriorityQueue<>(Comparator.comparingDouble(n->directionRank(n,-1)));
    private final PriorityQueue<Node> rightOpen=new PriorityQueue<>(Comparator.comparingDouble(n->directionRank(n,1)));
    private boolean threeWay;
    private int nextLane,activeLane,middleExpanded,leftExpanded,rightExpanded;
    private Node[] sideSeeds;
    private int sideSeedIndex;
    private final Map<Key,Double> best=new HashMap<>();
    private final LocalGroundSearch.Goal goal;
    private final long started=System.currentTimeMillis(),budget;
    private final int maxKnown;
    private int stageKnown,stage=1;
    private boolean growStages,allowPartial=true,stagedPolicy;
    private final double heuristicWeight;
    private final Agent root;
    private final boolean deepGoal;private final double escapeHeight;
    private final Map<String,Integer> editSkips=new LinkedHashMap<>();
    private final Map<String,Integer> rejected=new LinkedHashMap<>();
    private Node incumbent,checkpoint,pitExitNode;
    private boolean pitExitResult;
    private final double initialDistance,minProgress;
    private double checkpointDistance=Double.POSITIVE_INFINITY;
    private int retainedKnown=-1,retainedOpen,breakCandidates,placeCandidates;
    private int shoreCandidates,waterExitTicks,landingJumpTicks,surfaceCandidates,escapeCandidates,clearanceCandidates,clearanceAttempts,pitEscapeAttempts,pitEscapeCandidates,goalConnectionAttempts,goalConnectionCandidates;
    private double bestDistance=Double.POSITIVE_INFINITY;
    private MotionState bestState;
    private String limitReason="NONE",checkpointBrakeReason="NOT_TRIED";
    private Agent replay;
    private Result verifying;
    private int verifyLeg,verifyFrame;
    private Node expanding;
    private List<Candidate> candidates=List.of();
    private List<? extends Interaction> edits=List.of();
    private int cursor,expanded,simulated,delayedDiveCandidates;
    private boolean finished;
    private String reason="SEARCHING";
    private Result result;
    private FusionBreathingSearch breathingSearch;
    private Map<String,Object> breathingMetrics=Map.of();
    public FusionSearch(Agent start,LocalGroundSearch.Goal goal,long budget,int maxKnown){
        this(start,goal,budget,maxKnown,DEFAULT_HEURISTIC_WEIGHT);
    }
    public FusionSearch(Agent start,LocalGroundSearch.Goal goal,long budget,int maxKnown,double heuristicWeight){
        if(!Double.isFinite(heuristicWeight) || heuristicWeight<1 || heuristicWeight>4)throw new IllegalArgumentException("heuristic weight must be 1..4");
        this.goal=goal;this.budget=budget;this.maxKnown=maxKnown;this.stageKnown=maxKnown;this.heuristicWeight=heuristicWeight;
        deepGoal=start.underwaterGoal(goal);
        double localSurface=start.escapeHeight();
        escapeHeight=!start.water() && !deepGoal && goal.y()>start.state().y()+.5
                && localSurface>start.state().y()+.5?localSurface:Double.NaN;
        root=start.copy();initialDistance=distance(start.state());minProgress=Math.min(8,Math.max(3,initialDistance*.2));
        if(FusionBreathingSearch.required(start,goal))breathingSearch=new FusionBreathingSearch(root,goal);
        else offer(new Node(start,null,null,0,priority(start)));
    }
    /** Configure before stepping; escalation retains this exact frontier and in-progress rollout. */
    public FusionSearch staged(int firstLimit,boolean allowGrowth,boolean partialRoutes){
        stageKnown=Math.max(1,Math.min(firstLimit,maxKnown));growStages=allowGrowth;allowPartial=partialRoutes;stagedPolicy=true;
        return this;
    }
    private boolean optimizingFirstTier(){return stagedPolicy && stage==1;}
    public int stage(){return stage;}
    public boolean finished(){return finished;}
    public Result result(){return result;}
    public String reason(){return reason;}
    public Map<String,Object> metrics(){
        Map<String,Object> data=new LinkedHashMap<>();
        data.putAll(breathingSearch==null?breathingMetrics:breathingSearch.metrics());
        data.put("search_phase",breathingSearch==null?"TARGET_ROUTE":"BREATHING_POINT");
        data.put("expanded",expanded);data.put("known",retainedKnown<0?best.size():retainedKnown);
        data.put("open",retainedKnown<0?open.size():retainedOpen);data.put("max_known",maxKnown);
        data.put("optimizing_first_tier",optimizingFirstTier());
        data.put("search_lanes",threeWay?"MIDDLE_LEFT_RIGHT":optimizingFirstTier()?"MIDDLE_FIRST_TIER":"MIDDLE");
        data.put("active_lane",activeLane==1?"LEFT":activeLane==2?"RIGHT":"MIDDLE");
        data.put("middle_expanded",middleExpanded);data.put("left_expanded",leftExpanded);data.put("right_expanded",rightExpanded);
        data.put("left_open",leftOpen.size());data.put("right_open",rightOpen.size());
        data.put("side_seed_remaining",sideSeeds==null?0:sideSeeds.length-sideSeedIndex);
        data.put("complete_cost_ticks",incumbent==null?"none":incumbent.g());
        data.put("stage_known_limit",stageKnown);data.put("search_stage",stage);data.put("grow_stages",growStages);
        data.put("heuristic_weight",heuristicWeight);data.put("budget_ms",budget);data.put("simulated_ticks",simulated);data.put("reason",reason);
        data.put("checkpoint_kind",pitExitResult?"PIT_EXIT":"PROGRESS");
        data.put("pit_exit_height_gain",pitExitNode==null?0:pitExitNode.agent().state().y()-root.state().y());
        data.put("checkpoint_brake",checkpointBrakeReason);
        data.put("checkpoint_progress",checkpoint==null?0:initialDistance-checkpointDistance);
        data.put("checkpoint_state",checkpoint==null?"none":checkpoint.agent().state().toString());
        data.put("limit_reason",limitReason);data.put("elapsed_ms",System.currentTimeMillis()-started);
        data.put("best_goal_distance",bestDistance);data.put("best_state",bestState==null?"none":bestState.toString());
        data.put("goal_connection_attempts",goalConnectionAttempts);data.put("goal_connection_candidates",goalConnectionCandidates);
        data.put("pit_escape_attempts",pitEscapeAttempts);data.put("pit_escape_candidates",pitEscapeCandidates);
        data.put("clearance_attempts",clearanceAttempts);data.put("clearance_candidates",clearanceCandidates);data.put("surface_candidates",surfaceCandidates);data.put("escape_candidates",escapeCandidates);
        data.put("deep_goal",deepGoal);data.put("escape_height",Double.isFinite(escapeHeight)?escapeHeight:"none");
        data.put("delayed_dive_candidates",delayedDiveCandidates);
        data.put("best_air",previewNode==null?root.availableAir():previewNode.agent().availableAir());
        data.put("estimated_dive_ticks",previewNode==null?0:FusionDivePolicy.estimatedTicks(previewNode.agent().state(),goal));
        data.put("best_water",previewNode==null || previewNode.agent().waterProfile()==null?"none":previewNode.agent().waterProfile().toString());
        data.put("edit_skips",Map.copyOf(editSkips));data.put("shore_candidates",shoreCandidates);data.put("water_exit_ticks",waterExitTicks);data.put("landing_jump_ticks",landingJumpTicks);
        data.put("rejections",Map.copyOf(rejected));data.put("break_candidates",breakCandidates);data.put("place_candidates",placeCandidates);
        return data;
    }
    public void cancel(){finished=true;reason="CANCELLED";result=null;retainCounts();clearFrontiers();best.clear();}
    public boolean reached(Agent agent){
        return agent.reachedGoal(goal);
    }
    /** One short rollout per call, so paused render frames can slice the work. */
    public void step(){
        if(finished)return;
        if(verifying!=null){verify();return;}
        if(breathingSearch!=null){
            operation("PLANNING_BREATHING_POINT");
            if(budget>0 && System.currentTimeMillis()-started>=budget){finish(null,"BREATHING_TIME_LIMIT");return;}
            int before=breathingSearch.simulated();breathingSearch.step();simulated+=breathingSearch.simulated()-before;
            if(!breathingSearch.finished())return;
            breathingMetrics=breathingSearch.metrics();FusionBreathingSearch.Prefix prefix=breathingSearch.result();breathingSearch=null;
            if(prefix==null){offer(new Node(root.copy(),null,null,0,priority(root)));return;}
            Node origin=new Node(root,null,null,0,0);double cost=prefix.motion().frames().size();
            offer(new Node(prefix.agent(),origin,new Leg(prefix.motion(),null),cost,cost+priority(prefix.agent())));
            return;
        }
        operation("SELECT_STATE");
        // First tier retains the cheapest complete candidate while spending its optimization budget.
        // Only a search that exceeded that tier commits the next full solution immediately.
        if(incumbent!=null && !optimizingFirstTier()){finish(incumbent,"FIRST_SOLUTION");return;}
        if(incumbent!=null && best.size()>=stageKnown){finish(incumbent,"FIRST_TIER_LIMIT");return;}
        while(best.size()>=stageKnown && stageKnown<maxKnown && growStages){
            if(stage==1)enableThreeWay();
            stageKnown=(int)Math.min(maxKnown,Math.max((long)stageKnown+1,(long)stageKnown*2));stage++;
        }
        if((budget>0 && System.currentTimeMillis()-started>=budget) || best.size()>=stageKnown){
            limitReason=best.size()>=stageKnown?"STATE_LIMIT":"TIME_LIMIT";
            if(incumbent!=null){finish(incumbent,limitReason);return;}
            if(!allowPartial){finish(null,"SHORT_TASK_"+limitReason);return;}
            if(incumbent==null && pitExitNode==null)brakeCheckpoint();
            if(incumbent==null && pitExitNode!=null)finish(pitExitNode,"PIT_EXIT");
            else finish(incumbent!=null?incumbent:checkpoint,limitReason);return;
        }
        if(sideSeeds!=null){seedSideFrontiers();return;}
        if(expanding==null){
            Node n=pollFrontier();
            if(n==null){finish(incumbent,"EXHAUSTED");return;}
            if(reached(n.agent())){
                if(!optimizingFirstTier()){finish(n,"COMPLETE");return;}
                return; // Goal states are retained as incumbents, never expanded into detours.
            }
            if(optimizingFirstTier() && incumbent!=null && n.g()>=incumbent.g())return;
            explored.addLast(n.agent().state());if(explored.size()>256)explored.removeFirst();
            expandedPreview=n;
            expanding=n;expanded++;cursor=0;
            if(activeLane==1)leftExpanded++;else if(activeLane==2)rightExpanded++;else middleExpanded++;
            List<Candidate> list=new ArrayList<>();
            list.add(new Candidate(0,false,3,true));
            if(n.agent().water())list.add(new Candidate(0,false,12,true));
            boolean delayDive=FusionDivePolicy.delay(n.agent().waterProfile(),deepGoal,n.agent().state(),goal,n.agent().availableAir());
            if(delayDive)delayedDiveCandidates+=3;
            if(delayDive || FusionGuidance.surfaceRoute(n.agent().waterProfile(),deepGoal,n.agent().state(),goal))
                for(float offset:new float[]{0,-25,25})list.add(new Candidate(offset,false,16,Mode.SURFACE));
            if(Double.isFinite(escapeHeight) && !n.agent().water() && rootRange(n.agent().state())<8
                    && n.agent().state().y()<escapeHeight-.2 && n.agent().state().grounded())
                for(float offset:new float[]{0,-25,25})list.add(new Candidate(offset,true,12,Mode.ESCAPE));
            for(float offset:new float[]{0,-25,25,-60,60,-100,100,180}){
                list.add(new Candidate(offset,false,8,false));
                if(!n.agent().water())list.add(new Candidate(offset,true,8,false));
            }
            if(n.agent().water() && goal.y()>n.agent().state().y()+.3)
                for(float offset:new float[]{0,-25,25})list.add(new Candidate(offset,true,8,false));
            if(n.agent().water() && (n.agent().horizontalCollision() || !n.agent().submerged())){
                // Surface/collision only: do not multiply branches throughout deep-water travel.
                for(float offset:new float[]{0,-25,25})
                    list.add(new Candidate(offset,true,12,false,true,false));
                list.add(new Candidate(0,true,8,false,true,true));
            }
            list.add(new Candidate(0,false,2,false));
            candidates=list;operation("GENERATE_INTERACTIONS");edits=n.agent().interactions(goal);
            n.agent().interactionDiagnostics().forEach((label,count)->editSkips.merge(label,count,Integer::sum));
        }
        Node parent=expanding;
        try {
            if(cursor<candidates.size()){
                Candidate candidate=candidates.get(cursor++);operation("COPY_PLAYER");Agent agent=parent.agent().copy();
                List<MotionPlan.Frame> frames=new ArrayList<>();MotionState initial=agent.state();
                boolean startedWater=agent.water();if(candidate.shore())shoreCandidates++;
                if(candidate.mode()==Mode.SURFACE)surfaceCandidates++;if(candidate.mode()==Mode.ESCAPE)escapeCandidates++;
                for(int i=0;i<candidate.ticks();i++){
                    MotionState s=agent.state();double dx=goal.x()-s.x(),dz=goal.z()-s.z();
                    float yaw=GroundSteering.direction(dx,dz)+candidate.offset();
                    MotionInput input;
                    boolean wet=agent.water();
                    if(candidate.mode()==Mode.SURFACE && wet && agent.waterProfile()!=null){
                        input=FusionGuidance.surface(s,agent.waterProfile(),goal,candidate.offset());
                    }else if(candidate.mode()==Mode.ESCAPE){
                        input=new MotionInput(i>=3,false,false,false,true,false,true,yaw,0);
                    }else if(candidate.shore()){
                        // No sneak/sprint keys: try floating upright before pushing over the lip.
                        // The first neutral tick also breaks a sprint copied from the real player.
                        boolean forward=i>0 && !candidate.rise();
                        input=new MotionInput(forward,false,false,false,true,false,false,yaw,-30);
                    }else if(candidate.brake())input=new MotionInput(false,false,false,false,
                            candidate.ticks()>3 && wet && (agent.submerged() || s.vy()<-.02),false,false,yaw,0);
                    else if(wet){
                        float pitch=(float)-Math.toDegrees(Math.atan2(goal.y()+.15-s.y(),Math.max(.3,Math.hypot(dx,dz))));
                        input=new MotionInput(true,false,false,false,candidate.jump() && goal.y()>s.y()+.15,false,true,yaw,pitch);
                    }else{
                        double angle=Math.toRadians(candidate.offset()),length=Math.max(.001,Math.hypot(dx,dz));
                        double speed=Math.min(.28,length*.65),tx=dx/length*speed,tz=dz/length*speed;
                        input=GroundSteering.accelerate((tx*Math.cos(angle)-tz*Math.sin(angle)-s.vx())*.65,
                                (tx*Math.sin(angle)+tz*Math.cos(angle)-s.vz())*.65,yaw,
                                candidate.jump() && (i==0 || agent.climbing() || startedWater
                                        || (!initial.grounded() && s.grounded())),true);
                    }
                    operation("SIMULATE_TICK");input=agent.control(input,goal);MotionState after=agent.step(input);simulated++;
                    if(wet && !agent.water())waterExitTicks++;
                    if(!wet && s.grounded() && !initial.grounded() && i>0 && input.jump())landingJumpTicks++;
                    frames.add(new MotionPlan.Frame(input,after));
                    if(reached(agent))break;
                }
                double g=parent.g()+frames.size();
                offer(new Node(agent,parent,new Leg(new MotionPlan("fusion-motion",initial,frames,.20,.15),null),g,g+priority(agent)));
            }else{
                int index=cursor++-candidates.size();
                if(index<edits.size()){
                    operation("PREPARE_EDIT");
                    if(edits.get(index).kind().equals("CLEARANCE_REQUEST"))clearanceAttempts++;
                    if(edits.get(index).kind().equals("ESCAPE_REQUEST"))pitEscapeAttempts++;
                    if(edits.get(index).kind().equals("GOAL_CONNECTION_REQUEST"))goalConnectionAttempts++;
                    Interaction edit=parent.agent().prepare(edits.get(index));
                    operation("SIMULATE_EDIT");Agent next=parent.agent().apply(edit);
                    if(edits.get(index).kind().equals("GOAL_CONNECTION_REQUEST"))goalConnectionCandidates++;
                    double g=parent.g()+edit.ticks()+edit.price();
                    // A bounded preference for a verified local connection; real tick/price g is unchanged.
                    double preference=edit instanceof Program p?(p.escape()?Math.min(96,32+p.editTicks()*.9):Math.min(32,p.editTicks()*.75)):0;
                    Node successor=new Node(next,parent,new Leg(null,edit),g,g+priority(next)-preference);
                    offer(successor);
                    if(edit instanceof Program p && p.escape() && Double.isFinite(escapeHeight)
                            && escapeHeight-root.state().y()>=1.5 && next.pitExitSafe(root.state().y(),escapeHeight)
                            && (pitExitNode==null || g<pitExitNode.g()))pitExitNode=successor;
                    if(edit instanceof Program && reached(next) && !optimizingFirstTier()){
                        // A complete physical connection is already available. This weighted,
                        // approximate search need not exhaust cheaper dead-end microstates first.
                        finish(incumbent,"COMPLETE_LOCAL_CONNECTION");return;
                    }
                }
            }
        }catch(IllegalArgumentException failure){
            String label=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();
            int boundary=label.indexOf('@');if(boundary>=0)label=label.substring(0,boundary).trim();
            label=label.substring(0,Math.min(100,label.length()));
            if(!rejected.containsKey(label) && rejected.size()>=16)label="OTHER";
            rejected.merge(label,1,Integer::sum);
        }
        if(cursor>=candidates.size()+edits.size()){
            expanding=null;
            // Compare the prepared directions from this expansion, then commit a verified
            // emergence prefix instead of exhausting cheap underground microstates.
            if(allowPartial && pitExitNode!=null){
                if(incumbent==null)finish(pitExitNode,"PIT_EXIT");
                else if(!optimizingFirstTier())finish(incumbent,"COMPLETE");
            }
        }
    }
    private double distance(MotionState s){return Math.sqrt(Math.pow(goal.x()-s.x(),2)+Math.pow(goal.y()-s.y(),2)+Math.pow(goal.z()-s.z(),2));}
    private double heuristic(MotionState s){return distance(s)*4;}
    /** Only entered after a growable first tier failed to produce a complete route. */
    private void enableThreeWay(){
        if(threeWay)return;
        threeWay=true;nextLane=0;
        // Keep the middle frontier and in-progress rollout; no repeated root search.
        sideSeeds=open.toArray(Node[]::new);sideSeedIndex=0;
    }
    private void seedSideFrontiers(){
        operation("SEED_SIDE_FRONTIERS");
        // Keep heap construction inside the existing paused-frame work slices.
        int end=Math.min(sideSeeds.length,sideSeedIndex+128);
        while(sideSeedIndex<end){
            Node n=sideSeeds[sideSeedIndex++];
            if(!n.selected && n.g()<=best.getOrDefault(key(n.agent()),Double.POSITIVE_INFINITY)+1e-8){
                leftOpen.add(n);rightOpen.add(n);
            }
        }
        if(sideSeedIndex==sideSeeds.length)sideSeeds=null;
    }
    /** Soft initial +/-45 degree guide, fading with travel and proximity to the real goal. */
    private double directionRank(Node n,int side){
        double cached=side<0?n.leftRank:n.rightRank;
        if(!Double.isNaN(cached))return cached;
        MotionState origin=root.state(),s=n.agent().state();
        double dx=goal.x()-origin.x(),dz=goal.z()-origin.z(),length=Math.hypot(dx,dz);
        if(length<1e-6)return n.rank();
        double reach=Math.min(16,Math.max(4,length*.35)),angle=Math.toRadians(side*45);
        double gx=origin.x()+(dx*Math.cos(angle)-dz*Math.sin(angle))/length*reach;
        double gz=origin.z()+(dx*Math.sin(angle)+dz*Math.cos(angle))/length*reach;
        double fade=Math.max(0,1-Math.hypot(s.x()-origin.x(),s.z()-origin.z())/(reach*2))
                *Math.min(1,Math.hypot(goal.x()-s.x(),goal.z()-s.z())/reach);
        double rank=n.rank()+(Math.hypot(s.x()-gx,s.z()-gz)-reach)*12*fade;
        if(side<0)n.leftRank=rank;else n.rightRank=rank;
        return rank;
    }
    private Node pollFrontier(){
        int count=threeWay?3:1;
        for(int attempt=0;attempt<count;attempt++){
            int lane=threeWay?nextLane:0;nextLane=(lane+1)%count;
            PriorityQueue<Node> queue=lane==1?leftOpen:lane==2?rightOpen:open;
            Node n;
            while((n=queue.poll())!=null){
                if(n.selected)continue;
                if(n.g()>best.getOrDefault(key(n.agent()),Double.POSITIVE_INFINITY)+1e-8){n.selected=true;continue;}
                n.selected=true;activeLane=lane;return n;
            }
        }
        return null;
    }
    private void clearFrontiers(){
        open.clear();leftOpen.clear();rightOpen.clear();sideSeeds=null;
        if(breathingSearch!=null){breathingMetrics=breathingSearch.metrics();breathingSearch=null;}
    }
    private double rootRange(MotionState s){return Math.hypot(s.x()-root.state().x(),s.z()-root.state().z());}
    private double priority(Agent a){
        MotionState s=a.state();
        // The pit hint fades locally; it must not pull a later ocean crossing back up to the pit's land height.
        double localHint=Double.isFinite(escapeHeight)?s.y()+Math.max(0,escapeHeight-s.y())*Math.max(0,1-rootRange(s)/8):Double.NaN;
        return heuristic(s)*heuristicWeight+FusionGuidance.penalty(s,a.waterProfile(),deepGoal,localHint)
                +FusionDivePolicy.penalty(a.waterProfile(),deepGoal,s,goal,a.availableAir());
    }
    private Key key(Agent a){MotionState s=a.state();return new Key(Math.round(s.x()*8),Math.round(s.y()*8),Math.round(s.z()*8),
            Math.round(s.vx()*40),Math.round(s.vy()*40),Math.round(s.vz()*40),s.grounded(),a.water(),a.submerged(),a.horizontalCollision(),a.contextKey());}
    private void offer(Node n){
        MotionState s=n.agent().state();double distance=Math.sqrt(Math.pow(goal.x()-s.x(),2)+Math.pow(goal.y()-s.y(),2)+Math.pow(goal.z()-s.z(),2));
        if(distance<bestDistance){bestDistance=distance;bestState=s;previewNode=n;}
        if(reached(n.agent()) && (incumbent==null || n.g()<incumbent.g()))incumbent=n;
        considerCheckpoint(n,distance);
        if(n.leg()!=null && n.leg().interaction()!=null){
            if(n.leg().interaction() instanceof Program p){if(p.escape())pitEscapeCandidates++;else clearanceCandidates++;}
            for(Leg leg:expand(n.leg()))if(leg.interaction()!=null){if(leg.interaction().kind().equals("BREAK"))breakCandidates++;else placeCandidates++;}
        }
        Key key=key(n.agent());if(n.g()+1e-8>=best.getOrDefault(key,Double.POSITIVE_INFINITY))return;
        best.put(key,n.g());open.add(n);
        if(threeWay){leftOpen.add(n);rightOpen.add(n);}}
    private void considerCheckpoint(Node n,double distance){
        // Never cut at an edit boundary or select a tiny oscillating prefix just to reset the budget.
        if(n.leg()!=null && n.leg().motion()!=null && initialDistance-distance>=minProgress
                && n.agent().checkpointSafe() && (distance<checkpointDistance-.05
                || Math.abs(distance-checkpointDistance)<=.05 && checkpoint!=null && n.g()<checkpoint.g())){
            checkpoint=n;checkpointDistance=distance;
        }
    }
    /** A bounded neutral/braking tail, never digging merely to manufacture an intermediate endpoint. */
    private void brakeCheckpoint(){
        if(previewNode==null || initialDistance-distance(previewNode.agent().state())<minProgress){
            checkpointBrakeReason="NO_MEANINGFUL_PROGRESS";return;
        }
        operation("CHECKPOINT_BRAKING");
        try{
            Agent agent=previewNode.agent().copy();MotionState initial=agent.state();
            List<MotionPlan.Frame> frames=new ArrayList<>();int stable=0;
            for(int i=0;i<24;i++){
                MotionState s=agent.state();float yaw=GroundSteering.direction(goal.x()-s.x(),goal.z()-s.z());
                MotionInput input=new MotionInput(false,false,false,false,
                        agent.water() && (agent.submerged() || s.vy()<-.02),false,false,yaw,0);
                input=agent.control(input,goal);MotionState after=agent.step(input);simulated++;frames.add(new MotionPlan.Frame(input,after));
                stable=agent.checkpointSafe()?stable+1:0;
                if(reached(agent)){
                    checkpointBrakeReason="FINAL_GOAL_REACHED";double g=previewNode.g()+frames.size();incumbent=new Node(agent,previewNode,
                            new Leg(new MotionPlan("fusion-stop",initial,frames,.2,.15),null),g,g);return;
                }
                if(stable>=4){
                    checkpointBrakeReason="SAFE_STOP";double g=previewNode.g()+frames.size();considerCheckpoint(new Node(agent,previewNode,
                            new Leg(new MotionPlan("fusion-stop",initial,frames,.2,.15),null),g,g+priority(agent)),distance(after));return;
                }
            }
            checkpointBrakeReason="NO_STABLE_STOP_IN_24_TICKS";
        }catch(IllegalArgumentException unsafe){
            // Keep any previously simulated safe checkpoint; never execute this failed tail.
            String message=String.valueOf(unsafe.getMessage());checkpointBrakeReason=message.substring(0,Math.min(160,message.length()));
        }
    }
    private void finish(Node n,String why){
        reason=why;pitExitResult=why.equals("PIT_EXIT");
        if(n!=null){boolean complete=reached(n.agent());MotionState endpoint=n.agent().state();List<Leg> legs=new ArrayList<>();double cost=n.g();
            while(n.parent()!=null){legs.add(n.leg());n=n.parent();}Collections.reverse(legs);legs=legs.stream().flatMap(l->expand(l).stream()).toList();
            verifying=new Result(List.copyOf(legs),cost,expanded,simulated,complete,endpoint,pitExitResult);replay=root.copy();reason="VERIFYING";
        }else finished=true;
        expanding=null;retainCounts();clearFrontiers();best.clear();incumbent=null;
    }
    private void retainCounts(){if(retainedKnown<0){retainedKnown=best.size();retainedOpen=open.size();}}
    private void verify(){
        operation("VERIFY_ROUTE");
        try{
            if(verifyLeg==verifying.legs().size()){
                if(verifying.complete()){
                    if(!reached(replay))throw new IllegalArgumentException("final goal mismatch");
                }else if(!replay.checkpointSafe() || replay.state().positionError(verifying.endpoint())>1e-7
                        || (verifying.pitExit()?!replay.pitExitSafe(root.state().y(),escapeHeight):initialDistance-distance(replay.state())<minProgress))
                    throw new IllegalArgumentException("unsafe or non-progressing checkpoint");
                result=verifying;verifying=null;finished=true;reason=result.complete()?"COMPLETE":result.pitExit()?"PIT_EXIT":"CHECKPOINT";return;
            }
            Leg leg=verifying.legs().get(verifyLeg);
            if(leg.interaction()!=null){replay=replay.apply(leg.interaction());verifyLeg++;return;}
            MotionPlan plan=leg.motion();
            if(verifyFrame==0 && replay.state().positionError(plan.initial())>1e-7)throw new IllegalArgumentException("initial replay mismatch");
            MotionPlan.Frame frame=plan.frames().get(verifyFrame++);
            MotionState s=FusionBreathingSearch.EMERGENCY_PLAN.equals(plan.id())
                    ?replay.stepBreathing(frame.input(),true):replay.step(frame.input());
            if(s.positionError(frame.after())>1e-7 || s.velocityError(frame.after())>1e-7 || s.grounded()!=frame.after().grounded())
                throw new IllegalArgumentException("physics replay mismatch");
            if(verifyFrame==plan.frames().size()){verifyFrame=0;verifyLeg++;}
        }catch(IllegalArgumentException mismatch){verifying=null;result=null;finished=true;reason="REPLAY_FAILED: "+mismatch.getMessage();}
    }
}
