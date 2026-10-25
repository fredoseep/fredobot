package com.fredoseep.testing.combat;

import com.fredoseep.motion.MotionInput;
import com.fredoseep.motion.MotionState;
import com.fredoseep.motion.sim.FusionWorld;
import com.fredoseep.motion.sim.GroundAgent;
import com.fredoseep.mixin.MotionClientAccess;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import java.util.Comparator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Test-only receding-horizon sidestep. Player physics reuse Fusion's detached GroundAgent;
 * zombies use an explicitly approximate pursuit model, not player physics or vanilla AI execution. */
public final class CombatSidestepExperiment {
    public record Enemy(UUID id,double x,double z,double vx,double vz,float health) {}
    private record Forecast(UUID id,long source,long due,double x,double z) {}
    private record Candidate(double dx,double dz,double score,List<MotionState> path,List<Enemy> enemies) {}
    private record Branch(GroundAgent agent,List<Enemy> enemies,List<MotionState> path,List<Integer> directions,
                          double minimum,double risk,double score) {}
    private static final int HORIZON=8;
    private final List<Forecast> forecasts=new ArrayList<>();
    private final Map<UUID,Long> attacked=new HashMap<>();
    private ClientPlayerEntity owner;
    private Input saved;
    private WorldInput controlled;
    private boolean oldAutoJump;
    private boolean jumpExperiment;
    private int jumpRejected;
    public void setJumpExperiment(boolean enabled){jumpExperiment=enabled;}
    private double chosenX,chosenZ,errorSum,errorMax,travel,lastX,lastZ;
    private int errorCount,excluded,movingUpdates,rejected;
    private long lastTick=-1;
    private String simulationFailure="";
    private long lastSearchTick=-1000;
    private Candidate cachedLongPlan;
    private int searchExpanded,earlyUpdates;
    private double searchMillis;
    private boolean searchLimited;
    private List<Integer> searchDirections=List.of();
    private int continuedTurns,continuationInterrupts;
    private int validationChecks,validationRejects;
    private long lastTurnSource=-1;
    private int lastTurnSegment=-1;

    /** Keeps the requested world direction while attack aiming changes the camera yaw. */
    private static final class WorldInput extends Input {
        private final ClientPlayerEntity player;
        double dx,dz;
        int validUntilAge;
        boolean jumpPulse;
        WorldInput(ClientPlayerEntity player){this.player=player;}
        @Override public void tick(boolean slowDown){
            if(player.age>validUntilAge)dx=dz=0;
            player.setSprinting(false);((MotionClientAccess)(Object)player).fredobot$setSprintWindow(0);
            double yaw=Math.toRadians(player.yaw);
            movementForward=(float)(-dx*Math.sin(yaw)+dz*Math.cos(yaw));
            movementSideways=(float)(dx*Math.cos(yaw)+dz*Math.sin(yaw));
            pressingForward=movementForward>.01;pressingBack=movementForward<-.01;
            pressingLeft=movementSideways>.01;pressingRight=movementSideways<-.01;
            jumping=jumpPulse && player.age<=validUntilAge;jumpPulse=false;sneaking=false;
            if(slowDown){movementForward*=.3f;movementSideways*=.3f;}
        }
    }
    public void markAttack(UUID id,long tick){attacked.put(id,tick);}
    private void acquire(MinecraftClient c){
        if(owner!=null)return;owner=c.player;saved=owner.input;controlled=new WorldInput(owner);owner.input=controlled;
        oldAutoJump=c.options.autoJump;c.options.autoJump=false;owner.setSprinting(false);
        c.options.keySprint.setPressed(false);
    }
    public void stop(MinecraftClient c){
        if(owner!=null){controlled.dx=controlled.dz=0;controlled.jumpPulse=false;controlled.tick(false);
            if(owner.input==controlled)owner.input=saved;c.options.autoJump=oldAutoJump;}
        owner=null;controlled=null;saved=null;
    }
    public void reset(MinecraftClient c){stop(c);forecasts.clear();attacked.clear();chosenX=chosenZ=errorSum=errorMax=travel=0;
        errorCount=excluded=movingUpdates=rejected=earlyUpdates=jumpRejected=0;lastTick=-1;simulationFailure="";
        continuedTurns=continuationInterrupts=validationChecks=validationRejects=0;lastTurnSource=-1;lastTurnSegment=-1;lastSearchTick=-1000;cachedLongPlan=null;searchExpanded=0;searchMillis=0;searchLimited=false;searchDirections=List.of();}
    public Map<String,Object> update(MinecraftClient c,long tick,double px,double pz,List<Enemy> enemies,boolean move){
        return update(c,tick,px,pz,enemies,move,false);
    }
    public Map<String,Object> update(MinecraftClient c,long tick,double px,double pz,List<Enemy> enemies,boolean move,boolean escape){
        return update(c,tick,px,pz,enemies,move,escape,false);
    }
    public Map<String,Object> update(MinecraftClient c,long tick,double px,double pz,List<Enemy> enemies,boolean move,boolean escape,boolean continuePlan){
        return update(c,tick,px,pz,enemies,move,escape,continuePlan,false);
    }
    public Map<String,Object> update(MinecraftClient c,long tick,double px,double pz,List<Enemy> enemies,boolean move,boolean escape,boolean continuePlan,boolean validateContinuation){
        if(owner!=null && owner.input!=controlled)throw new IllegalStateException("combat test input ownership lost");
        if(lastTick>=0)travel+=Math.hypot(px-lastX,pz-lastZ);lastX=px;lastZ=pz;lastTick=tick;
        for(Forecast f:List.copyOf(forecasts))if(tick>=f.due()){
            forecasts.remove(f);Enemy actual=enemies.stream().filter(e->e.id().equals(f.id())).findFirst().orElse(null);
            if(tick!=f.due() || actual==null || actual.health()<=0 || attacked.getOrDefault(f.id(),-1L)>=f.source()){excluded++;continue;}
            double error=Math.hypot(actual.x()-f.x(),actual.z()-f.z());errorSum+=error;errorMax=Math.max(errorMax,error);errorCount++;
        }
        List<Enemy> alive=enemies.stream().filter(e->e.health()>0).toList();
        Candidate best=null;
        int blocked=escape?blockedDirections(c,px,c.player.getY(),pz):0;
        boolean constrained=blocked>=2;
        boolean evade=move && alive.size()>=2 && alive.stream().anyMatch(e->Math.hypot(e.x()-px,e.z()-pz)<(escape&&constrained?7:4.5));
        Map<String,Object> validation=Map.of("checked",false);
        boolean reused=false;String replanReason="INACTIVE";double planDeviation=0;int planSegment=0;
        if(evade){
            acquire(c);
            if(escape){
                if(constrained)earlyUpdates++;
                long age=tick-lastSearchTick;
                if(continuePlan){
                    replanReason=continuationReason(cachedLongPlan,searchDirections,age,px,c.player.getY(),pz,alive);
                    if(cachedLongPlan!=null && age>0 && age<=cachedLongPlan.path().size()){
                        MotionState expected=cachedLongPlan.path().get((int)age-1);
                        planDeviation=Math.sqrt(sq(px-expected.x())+sq(c.player.getY()-expected.y())+sq(pz-expected.z()));
                    }
                    if(replanReason.equals("REUSED") && validateContinuation){
                        validation=validateRemaining(c,cachedLongPlan,searchDirections,age,alive);validationChecks++;
                        if(!validation.get("safe").equals(true)){replanReason="VALIDATION_"+validation.get("reason");validationRejects++;}
                    }
                    if(replanReason.equals("REUSED")){
                        planSegment=(int)(age/8);int direction=searchDirections.get(planSegment);double a=direction*Math.PI/4;
                        best=new Candidate(direction<0?0:Math.cos(a),direction<0?0:Math.sin(a),cachedLongPlan.score(),cachedLongPlan.path(),cachedLongPlan.enemies());reused=true;
                        if(lastTurnSource!=lastSearchTick || lastTurnSegment!=planSegment){if(planSegment>0 && !searchDirections.get(planSegment).equals(searchDirections.get(planSegment-1)))continuedTurns++;lastTurnSource=lastSearchTick;lastTurnSegment=planSegment;}
                    }else{
                        if(replanReason.equals("DEVIATION") || replanReason.equals("ENEMY_CLOSE") || replanReason.startsWith("VALIDATION_"))continuationInterrupts++;
                        best=searchEscape(c,alive,constrained);cachedLongPlan=best;lastSearchTick=tick;planSegment=0;lastTurnSource=tick;lastTurnSegment=0;
                    }
                }else{
                    replanReason=cachedLongPlan!=null && age<6?"REUSED":"CADENCE";
                    if(cachedLongPlan!=null && age<6){best=cachedLongPlan;reused=true;}
                    else {best=searchEscape(c,alive,constrained);cachedLongPlan=best;lastSearchTick=tick;}
                }
            }else{
            for(int direction=-1;direction<8;direction++){
                double angle=direction*Math.PI/4,dx=direction<0?0:Math.cos(angle),dz=direction<0?0:Math.sin(angle);
                try {
                    Candidate candidate=simulate(c,alive,dx,dz);
                    if(candidate!=null && (best==null || candidate.score()>best.score()))best=candidate;
                }catch(IllegalArgumentException unsupported){rejected++;simulationFailure=unsupported.toString();}
            }
            }
        }
        else cachedLongPlan=null;
        chosenX=best==null?0:best.dx();chosenZ=best==null?0:best.dz();
        if(controlled!=null){controlled.dx=chosenX;controlled.dz=chosenZ;controlled.validUntilAge=c.player.age+4;}
        if(Math.hypot(chosenX,chosenZ)>.1)movingUpdates++;
        // Forecast the selected eight-tick path. A standstill control forecasts a stationary player.
        List<Enemy> predicted;
        if(best!=null){predicted=alive;for(int i=0;i<HORIZON;i++){
            MotionState state=best.path().get(i);predicted=advance(predicted,state.x(),state.z());}}
        else {predicted=alive;for(int i=0;i<HORIZON;i++)predicted=advance(predicted,px,pz);}
        if(!reused)for(Enemy e:predicted)forecasts.add(new Forecast(e.id(),tick,tick+HORIZON,e.x(),e.z()));
        Map<String,Object> report=new HashMap<>(Map.of("worldDirection",List.of(chosenX,chosenZ),"evading",evade,"candidateFound",best!=null,
                "predictedEnemyEndpoints",predicted.stream().map(e->Map.of("id",e.id().toString(),"pos",List.of(e.x(),e.z()))).toList(),
                "predictedPlayerPath",best==null?List.of():best.path().stream().map(s->List.of(s.x(),s.y(),s.z())).toList(),
                "horizonTicks",HORIZON,"score",best==null?0:best.score(),"rejectedCandidates",rejected,"lastSimulationFailure",simulationFailure));
        if(escape){report.put("escapeSearch",Map.of("blockedDirections",blocked,"constrained",constrained,"completedHorizon",best==null?0:best.path().size(),
                "expanded",searchExpanded,"planningMillis",searchMillis,"timeLimited",searchLimited,"cached",reused,"planSourceTick",lastSearchTick,"directionSequence",searchDirections));}
        report.put("continuationValidation",validation);
        report.put("continuation",Map.of("enabled",continuePlan,"segment",planSegment,"reason",replanReason,"deviation",planDeviation,
                "continuedTurns",continuedTurns,"interrupts",continuationInterrupts,"commitTicks",16));
        return report;
    }
    private static double sq(double x){return x*x;}
    private static String continuationReason(Candidate plan,List<Integer> directions,long age,double x,double y,double z,List<Enemy> enemies){
        if(plan==null)return "NO_PLAN";
        if(age<0 || age>=Math.min(16,plan.path().size()) || age/8>=directions.size())return "PLAN_PREFIX_COMPLETE";
        if(age>0){MotionState expected=plan.path().get((int)age-1);
            if(Math.sqrt(sq(x-expected.x())+sq(y-expected.y())+sq(z-expected.z()))>.85)return "DEVIATION";
        }
        if(enemies.stream().anyMatch(e->e.health()>0 && Math.hypot(e.x()-x,e.z()-z)<1.8))return "ENEMY_CLOSE";
        return "REUSED";
    }
    /** Re-simulates only the next few scheduled inputs, from the latest actual player state.
     * Enemy positions/velocities are freshly observed, never reused from the old plan. */
    private Map<String,Object> validateRemaining(MinecraftClient c,Candidate plan,List<Integer> directions,long age,List<Enemy> enemies){
        int horizon=Math.min(6,plan.path().size()-(int)age);
        try{return FusionWorld.in(c.world,Map.of(),()->{
            GroundAgent ghost=new GroundAgent(c,c.player);List<Enemy> predicted=enemies.stream().filter(e->e.health()>0).toList();
            double minimum=1000;List<MotionState> path=new ArrayList<>();
            for(int i=0;i<horizon;i++){
                int segment=((int)age+i)/8;
                if(segment>=directions.size())return validationResult(false,"NO_SCHEDULE",minimum,path);
                int direction=directions.get(segment);float yaw=direction<0?c.player.yaw:(float)(Math.toDegrees(direction*Math.PI/4)-90);
                ghost.setSprinting(false);((MotionClientAccess)(Object)ghost).fredobot$setSprintWindow(0);
                MotionState state=ghost.step(new MotionInput(direction>=0,false,false,false,false,false,false,yaw,0));path.add(state);
                if(Math.hypot(state.x()-.5,state.z()-.5)>8 || state.y()<3.9 || state.y()>(jumpExperiment?5.4:5))
                    return validationResult(false,"OUTSIDE_ARENA",minimum,path);
                predicted=advance(predicted,state.x(),state.z());double px=state.x(),pz=state.z();
                minimum=Math.min(minimum,predicted.stream().mapToDouble(e->Math.hypot(e.x()-px,e.z()-pz)).min().orElse(1000));
                if(minimum<2.4)return validationResult(false,"PREDICTED_CONTACT",minimum,path);
            }
            return validationResult(horizon>0,horizon>0?"SAFE":"NO_HORIZON",minimum,path);
        });}catch(IllegalArgumentException unsupported){
            return Map.of("checked",true,"safe",false,"reason","UNSUPPORTED_GEOMETRY","error",unsupported.toString(),"checkedTicks",0);
        }
    }
    private static Map<String,Object> validationResult(boolean safe,String reason,double minimum,List<MotionState> path){
        return Map.of("checked",true,"safe",safe,"reason",reason,"checkedTicks",path.size(),"minimumPredictedSeparation",minimum,
                "playerPath",path.stream().map(s->List.of(s.x(),s.y(),s.z())).toList());
    }
    private static int blockedDirections(MinecraftClient c,double x,double y,double z){
        int count=0;
        for(int i=0;i<8;i++){
            double a=i*Math.PI/4;boolean blocked=false;
            for(int height=0;height<=1;height++){
                BlockPos p=new BlockPos(x+2*Math.cos(a),y+height,z+2*Math.sin(a));
                if(!c.world.getBlockState(p).getCollisionShape(c.world,p).isEmpty())blocked=true;
            }
            if(blocked)count++;
        }
        return count;
    }
    private Candidate searchEscape(MinecraftClient c,List<Enemy> enemies,boolean constrained){
        long began=System.nanoTime(),deadline=began+40_000_000;searchExpanded=0;searchLimited=false;searchDirections=List.of();
        try{return FusionWorld.in(c.world,Map.of(),()->{
            List<Branch> beam=List.of(new Branch(new GroundAgent(c,c.player),enemies,List.of(),List.of(),Double.POSITIVE_INFINITY,0,0));
            Branch best=null;
            for(int depth=0;depth<3;depth++){
                List<Branch> next=new ArrayList<>();
                search:for(Branch parent:beam)for(int direction=-1;direction<8;direction++){
                    if(System.nanoTime()>deadline){searchLimited=true;break search;}
                    try{Branch child=extend(c,parent,direction,constrained);searchExpanded++;if(child!=null)next.add(child);}
                    catch(IllegalArgumentException unsupported){rejected++;simulationFailure=unsupported.toString();}
                }
                if(next.isEmpty())break;
                next.sort(Comparator.comparingDouble(Branch::score).reversed());best=next.get(0);
                beam=List.copyOf(next.subList(0,Math.min(6,next.size())));
                if(searchLimited)break;
            }
            if(best==null)return null;
            searchDirections=best.directions();
            int first=best.directions().get(0);double a=first*Math.PI/4;
            return new Candidate(first<0?0:Math.cos(a),first<0?0:Math.sin(a),best.score(),best.path(),best.enemies());
        });}finally{searchMillis=(System.nanoTime()-began)/1_000_000.0;}
    }
    private Branch extend(MinecraftClient c,Branch parent,int direction,boolean constrained){
        GroundAgent ghost=parent.agent().copy();List<MotionState> path=new ArrayList<>(parent.path());
        List<Integer> directions=new ArrayList<>(parent.directions());directions.add(direction);
        List<Enemy> predicted=parent.enemies();double minimum=parent.minimum(),risk=parent.risk(),endMinimum=0;
        float yaw=direction<0?c.player.yaw:(float)(Math.toDegrees(direction*Math.PI/4)-90);
        MotionInput input=new MotionInput(direction>=0,false,false,false,false,false,false,yaw,0);
        for(int i=0;i<8;i++){
            ghost.setSprinting(false);((MotionClientAccess)(Object)ghost).fredobot$setSprintWindow(0);
            MotionState state=ghost.step(input);path.add(state);
            if(Math.hypot(state.x()-.5,state.z()-.5)>8 || state.y()<3.9 || state.y()>(jumpExperiment?5.4:5))return null;
            predicted=advance(predicted,state.x(),state.z());
            endMinimum=predicted.stream().mapToDouble(e->Math.hypot(e.x()-state.x(),e.z()-state.z())).min().orElse(5);
            minimum=Math.min(minimum,endMinimum);risk+=Math.pow(Math.max(0,2.2-endMinimum),2);
        }
        MotionState end=path.get(path.size()-1);
        double openness=8-blockedDirections(c,end.x(),end.y(),end.z());
        double progress=Math.hypot(end.x()-c.player.getX(),end.z()-c.player.getZ());
        double score=1.5*minimum+2.5*endMinimum-risk+.35*openness-.06*Math.hypot(end.x()-.5,end.z()-.5);
        if(constrained)score+=.15*progress-(directions.get(0)<0?.75:0);
        return new Branch(ghost,predicted,List.copyOf(path),List.copyOf(directions),minimum,risk,score);
    }
    private Candidate simulate(MinecraftClient c,List<Enemy> enemies,double dx,double dz){
        return FusionWorld.in(c.world,Map.of(),()->{
            GroundAgent ghost=new GroundAgent(c,c.player);List<MotionState> path=new ArrayList<>();
            List<Enemy> predicted=enemies;double minimum=Double.POSITIVE_INFINITY,endMinimum=0,maxHome=0;
            boolean walking=Math.hypot(dx,dz)>.1;float yaw=walking?(float)(Math.toDegrees(Math.atan2(dz,dx))-90):c.player.yaw;
            MotionInput input=new MotionInput(walking,false,false,false,false,false,false,yaw,0);
            for(int i=0;i<HORIZON;i++){
                ghost.setSprinting(false);((MotionClientAccess)(Object)ghost).fredobot$setSprintWindow(0);
                MotionState state=ghost.step(input);path.add(state);
                double home=Math.hypot(state.x()-.5,state.z()-.5);if(home>8 || state.y()<3.9 || state.y()>(jumpExperiment?5.4:5))return null;
                maxHome=Math.max(maxHome,home);predicted=advance(predicted,state.x(),state.z());
                endMinimum=predicted.stream().mapToDouble(e->Math.hypot(e.x()-state.x(),e.z()-state.z())).min().orElse(5);
                minimum=Math.min(minimum,endMinimum);
            }
            double score=4*minimum+2*endMinimum-.12*maxHome-(walking?.25:0);
            if(walking && dx*chosenX+dz*chosenZ<-.2)score-=.15;
            return new Candidate(dx,dz,score,List.copyOf(path),predicted);
        });
    }
    public void requestJump(MinecraftClient client){
        acquire(client);controlled.jumpPulse=true;controlled.validUntilAge=client.player.age+4;
    }
    /** A short detached-player simulation checks the currently selected walking direction plus a jump.
     * Enemy pursuit remains an approximation. Unsupported geometry rejects the optional jump only. */
    public Map<String,Object> assessJump(MinecraftClient c,List<Enemy> enemies){
        try{
            return FusionWorld.in(c.world,Map.of(),()->{
                GroundAgent ghost=new GroundAgent(c,c.player);List<Enemy> predicted=enemies.stream().filter(e->e.health()>0).toList();
                double minimum=1000;boolean airborne=false,landed=false;List<MotionState> path=new ArrayList<>();
                boolean walking=Math.hypot(chosenX,chosenZ)>.1;
                float yaw=walking?(float)(Math.toDegrees(Math.atan2(chosenZ,chosenX))-90):c.player.yaw;
                for(int i=0;i<18;i++){
                    ghost.setSprinting(false);((MotionClientAccess)(Object)ghost).fredobot$setSprintWindow(0);
                    MotionState state=ghost.step(new MotionInput(walking,false,false,false,i==0,false,false,yaw,0));path.add(state);
                    if(state.y()>c.player.getY()+.1)airborne=true;
                    if(airborne && state.grounded())landed=true;
                    if(Math.hypot(state.x()-.5,state.z()-.5)>8 || state.y()<3.9 || state.y()>5.4)
                        return jumpAssessment(false,"OUTSIDE_ARENA",minimum,path);
                    predicted=advance(predicted,state.x(),state.z());
                    double px=state.x(),pz=state.z();minimum=Math.min(minimum,predicted.stream().mapToDouble(e->Math.hypot(e.x()-px,e.z()-pz)).min().orElse(1000));
                    if(landed)break;
                }
                boolean safe=airborne && landed && minimum>=2.8;
                return jumpAssessment(safe,!airborne?"NO_TAKEOFF":!landed?"NO_SAFE_LANDING":minimum<2.8?"ENEMY_TOO_CLOSE":"SAFE",minimum,path);
            });
        }catch(IllegalArgumentException unsupported){jumpRejected++;return Map.of("safe",false,"reason","UNSUPPORTED_GEOMETRY","error",unsupported.toString());}
    }
    private Map<String,Object> jumpAssessment(boolean safe,String reason,double minimum,List<MotionState> path){
        if(!safe)jumpRejected++;
        return Map.of("safe",safe,"reason",reason,"minimumPredictedSeparation",minimum,
                "playerPath",path.stream().map(s->List.of(s.x(),s.y(),s.z())).toList(),"horizonTicks",path.size());
    }
    private static List<Enemy> advance(List<Enemy> enemies,double px,double pz){
        List<Enemy> result=new ArrayList<>();
        for(Enemy e:enemies){
            double dx=px-e.x(),dz=pz-e.z(),d=Math.hypot(dx,dz),vx,vz;
            if(e.vx()*dx+e.vz()*dz<-.005){vx=e.vx()*.85;vz=e.vz()*.85;}
            else {vx=d>.01?dx/d*.115:0;vz=d>.01?dz/d*.115:0;}
            result.add(new Enemy(e.id(),e.x()+vx,e.z()+vz,vx,vz,e.health()));
        }
        return List.copyOf(result);
    }
    public Map<String,Object> summary(){Map<String,Object> out=new HashMap<>(Map.of("jumpRejected",jumpRejected,"measuredTravel",travel,"movingUpdates",movingUpdates,"constrainedUpdates",earlyUpdates,
            "enemyForecastSamples",errorCount,"enemyForecastMeanError",errorCount==0?0:errorSum/errorCount,
            "enemyForecastMaxError",errorMax,"forecastExcluded",excluded,"rejectedCandidates",rejected));
        out.put("validationChecks",validationChecks);out.put("validationRejects",validationRejects);
        out.put("continuedTurns",continuedTurns);out.put("continuationInterrupts",continuationInterrupts);return out;
    }
}
