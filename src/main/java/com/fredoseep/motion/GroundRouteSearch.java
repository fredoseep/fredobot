package com.fredoseep.motion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Coarse A* proposes connections; vanilla-simulated inputs are the sole authority for execution.
 * Each step expands one node or simulates one tick. All calls belong on the paused client thread. */
public final class GroundRouteSearch {
    public record Cell(int x,int y,int z) {
        public LocalGroundSearch.Goal goal(){return new LocalGroundSearch.Goal(x+.5,y,z+.5);}
        public static Cell at(MotionState s){return new Cell((int)Math.floor(s.x()),(int)Math.round(s.y()),(int)Math.floor(s.z()));}
    }
    public interface Terrain {
        boolean stand(Cell cell);
        boolean walkClear(Cell from,Cell to);
    }
    public interface Agent extends LocalGroundSearch.Agent {
        Agent copy();
        MotionState state();
    }
    private record Edge(Cell from,Cell to) {}
    private record Entry(Cell cell,double g,double score) {}
    private final String id;
    private final MotionState initial;
    private final LocalGroundSearch.Goal goal;
    private final Cell target,origin;
    private final Terrain terrain;
    private final Agent root;
    private final boolean continuous;
    private ContinuousGroundSearch optimizer;
    private final long started=System.currentTimeMillis();
    private final List<MotionPlan.Frame> frames=new ArrayList<>();
    private final List<Cell> accepted=new ArrayList<>();
    private final Set<Edge> blocked=new HashSet<>();
    private final PriorityQueue<Entry> open=new PriorityQueue<>(Comparator.comparingDouble(Entry::score));
    private final Map<Cell,Double> costs=new HashMap<>();
    private final Map<Cell,Cell> parents=new HashMap<>();
    private Agent checkpoint,replay;
    private List<Cell> route=List.of();
    private int cursor,expanded,replans,simulatedTicks,replayIndex,totalTrials;
    private LocalGroundSearch local;
    private MotionPlan segment,result;
    private String phase="BLOCK_SEARCH",reason="SEARCHING",lastFailure="";
    private boolean finished;
    public GroundRouteSearch(String id,Agent initial,LocalGroundSearch.Goal goal,Terrain terrain) {
        this(id,initial,goal,terrain,true);
    }
    public GroundRouteSearch(String id,Agent initial,LocalGroundSearch.Goal goal,Terrain terrain,boolean continuous) {
        this.continuous=continuous;root=initial.copy();
        this.id=id;checkpoint=initial;this.initial=initial.state();this.goal=goal;this.terrain=terrain;
        target=new Cell((int)Math.floor(goal.x()),(int)Math.round(goal.y()),(int)Math.floor(goal.z()));
        origin=Cell.at(this.initial);accepted.add(origin);beginCoarse();
    }
    public String phase(){return optimizer==null?phase:optimizer.phase();}
    public int expanded(){return expanded;}
    public int savedFrames(){return optimizer==null?0:optimizer.savedFrames();}
    public int verifiedFrames(){return optimizer==null?frames.size():optimizer.result()==null?0:optimizer.result().frames().size();}
    public boolean finished(){return finished;}
    public MotionPlan result(){return result;}
    public String termination(){return reason;}
    public int trials(){return totalTrials+(local==null?0:local.trials())+(optimizer==null?0:optimizer.trials());}
    public int simulatedTicks(){return simulatedTicks+(local==null?0:local.simulatedTicks())+(optimizer==null?0:optimizer.simulatedTicks());}
    public List<Cell> route(){
        List<Cell> all=new ArrayList<>(accepted);
        if(!route.isEmpty())all.addAll(route.subList(Math.min(cursor+1,route.size()),route.size()));
        return List.copyOf(all);
    }
    public List<MotionPlan.Frame> preview(){
        if(optimizer!=null && optimizer.result()!=null)return optimizer.result().frames();
        if(segment!=null)return segment.frames();
        if(local!=null && local.result()!=null)return local.result().frames();
        return List.copyOf(frames);
    }
    public Map<String,Object> metrics(){
        Map<String,Object> m=new LinkedHashMap<>();m.put("phase",phase());m.put("reason",reason);
        m.put("expanded",expanded);m.put("open",open.size());m.put("known",costs.size());m.put("replans",replans);
        m.put("blocked_edges",blocked.size());m.put("simulated_ticks",simulatedTicks());m.put("trials",trials());
        m.put("verified_frames",verifiedFrames());m.put("route",route().stream().map(c->List.of(c.x,c.y,c.z)).toList());
        m.put("last_failed_edge",lastFailure);m.put("elapsed_ms",System.currentTimeMillis()-started);m.put("optimal",false);
        if(local!=null)m.put("local",local.metrics());
        if(optimizer!=null)m.put("continuity",optimizer.metrics());return m;
    }
    public void cancel(){if(optimizer!=null)optimizer.cancel();finish("CANCELLED");result=null;}
    public void step(){
        if(finished)return;
        if(optimizer!=null){
            optimizer.step();
            if(optimizer.finished()){
                result=optimizer.result();
                finish(Boolean.TRUE.equals(optimizer.metrics().get("selected_continuous"))?"COMPLETE_CONTINUOUS":"COMPLETE_VERIFIED");
            }
            return;
        }
        if(System.currentTimeMillis()-started>90_000){finish("TOTAL_TIME_BUDGET");return;}
        if(frames.size()>1100){finish("EXECUTION_FRAME_LIMIT");return;}
        if(replay!=null){
            MotionPlan.Frame f=segment.frames().get(replayIndex++);
            MotionState actual=replay.step(f.input());simulatedTicks++;
            if(actual.positionError(f.after())>1e-7 || actual.velocityError(f.after())>1e-7 || actual.grounded()!=f.after().grounded())
                throw new IllegalArgumentException("模拟分支重放不一致，拒绝接管");
            if(replayIndex==segment.frames().size()){
                frames.addAll(segment.frames());checkpoint=replay;replay=null;segment=null;
                accepted.add(route.get(++cursor));
                if(cursor==route.size()-1)complete();else beginLocal();
            }
            return;
        }
        if(local!=null){
            local.step();if(!local.finished())return;
            totalTrials+=local.trials();simulatedTicks+=local.simulatedTicks();
            segment=local.result();
            if(segment==null){
                Edge edge=new Edge(route.get(cursor),route.get(cursor+1));blocked.add(edge);
                lastFailure=edge+" "+local.termination()+" "+local.metrics();local=null;
                if(++replans>24){finish("UNVERIFIED_CONNECTIONS");return;}
                beginCoarse();
            }else {
                local=null;
                if(frames.size()+segment.frames().size()>1200){finish("EXECUTION_FRAME_LIMIT");return;}
                phase="VERIFY_REPLAY";replay=checkpoint.copy();replayIndex=0;
            }
            return;
        }
        if(open.isEmpty()){finish("NO_BLOCK_ROUTE");return;}
        Entry entry=open.poll();
        if(entry.g>costs.getOrDefault(entry.cell,Double.POSITIVE_INFINITY)+1e-9)return;
        expanded++;
        if(entry.cell.equals(target)){
            ArrayList<Cell> found=new ArrayList<>();Cell p=target;
            while(p!=null){found.add(p);p=parents.get(p);}Collections.reverse(found);
            route=simplify(found);cursor=0;
            if(route.size()==1){
                // Same block does not mean the required stable position has been reached.
                if(goal.reached(checkpoint.state()))complete();
                else {route=List.of(route.get(0),target);beginLocal();}
            }else beginLocal();
            return;
        }
        if(costs.size()>20000){finish("BLOCK_STATE_LIMIT");return;}
        Cell from=entry.cell;
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
            if(dx==0 && dz==0)continue;
            for(int dy:new int[]{0,1,-1,-2}){
                if(dy!=0 && dx!=0 && dz!=0)continue;
                Cell to=new Cell(from.x+dx,from.y+dy,from.z+dz);
                if(dy==0 && !terrain.walkClear(from,to))continue;
                offer(from,to,entry.g,Math.hypot(dx,dz)+Math.abs(dy)*1.5);
            }
            // Gap jumps stay cardinal in this batch. Clearance/landing are verified by simulation.
            if(dx==0 || dz==0)for(int length=2;length<=4;length++)for(int dy:new int[]{0,1,-1})
                offer(from,new Cell(from.x+dx*length,from.y+dy,from.z+dz*length),entry.g,length+2.0+Math.abs(dy)*1.5);
        }
    }
    private List<Cell> simplify(List<Cell> path){
        // Collapse only collinear, same-height adjacent walks. A failed long connection must
        // remain split on retry, otherwise compression would recreate the blacklisted edge.
        ArrayList<Cell> compact=new ArrayList<>();int i=0;compact.add(path.get(0));
        while(i<path.size()-1){
            Cell from=path.get(i),next=path.get(i+1);int dx=next.x-from.x,dz=next.z-from.z,j=i+1;
            if(next.y==from.y && Math.abs(dx)<=1 && Math.abs(dz)<=1){
                while(j+1<path.size()){
                    Cell a=path.get(j),b=path.get(j+1);
                    if(b.y!=from.y || b.x-a.x!=dx || b.z-a.z!=dz
                            || Math.hypot(b.x-from.x,b.z-from.z)>8
                            || blocked.contains(new Edge(from,b)) || !terrain.walkClear(from,b))break;
                    j++;
                }
            }
            compact.add(path.get(j));i=j;
        }
        return List.copyOf(compact);
    }
    private void offer(Cell from,Cell to,double g,double cost){
        if(to.x<Math.min(origin.x,target.x)-8 || to.x>Math.max(origin.x,target.x)+8
                || to.z<Math.min(origin.z,target.z)-8 || to.z>Math.max(origin.z,target.z)+8 || Math.abs(to.y-origin.y)>8
                || blocked.contains(new Edge(from,to)) || !terrain.stand(to))return;
        double next=g+cost;
        if(next>=costs.getOrDefault(to,Double.POSITIVE_INFINITY))return;
        costs.put(to,next);parents.put(to,from);open.add(new Entry(to,next,next+heuristic(to)));
    }
    private double heuristic(Cell p){return Math.hypot(target.x-p.x,target.z-p.z)+Math.abs(target.y-p.y);}
    private void beginCoarse(){
        phase="BLOCK_SEARCH";route=List.of();open.clear();costs.clear();parents.clear();
        Cell from=Cell.at(checkpoint.state());costs.put(from,0.0);open.add(new Entry(from,0,heuristic(from)));
    }
    private void beginLocal(){
        phase="SIMULATE_CONNECTION";
        Cell end=route.get(cursor+1);Agent start=checkpoint;
        local=new LocalGroundSearch(id+"-edge-"+accepted.size(),start.state(),end.goal(),start::copy,
                new LocalGroundSearch.Limits(160,1024,4000,80),System::currentTimeMillis,true);
    }
    private void complete(){
        if(frames.isEmpty()){
            // Supply an actual tick even for an already-reached goal; the runner validates it too.
            MotionInput idle=new MotionInput(false,false,false,false,false,false,false,Float.isFinite(checkpoint.lookYaw())?checkpoint.lookYaw():0,0);
            frames.add(new MotionPlan.Frame(idle,checkpoint.step(idle)));simulatedTicks++;
        }
        if(!goal.reached(checkpoint.state())){finish("FINAL_GOAL_NOT_REACHED");return;}
        result=new MotionPlan(id,initial,frames,.002,.002);
        if(continuous && accepted.size()>2){
            long remaining=Math.max(0,90_000-(System.currentTimeMillis()-started));
            optimizer=new ContinuousGroundSearch(root,List.copyOf(accepted),goal,result,Math.min(12_000,remaining));
            phase="CONTINUITY_START";
        }else finish("COMPLETE_VERIFIED");
    }
    private void finish(String why){finished=true;reason=why;phase="DONE";if(local!=null)local.cancel();}
}
