package com.fredoseep.testing.combat;

import com.fredoseep.mixin.MotionClientAccess;
import com.fredoseep.mixin.MotionProjectileAccess;
import com.fredoseep.motion.MotionInput;
import com.fredoseep.motion.MotionState;
import com.fredoseep.motion.sim.FusionWorld;
import com.fredoseep.motion.sim.GroundAgent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RayTraceContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Experimental actual-arrow receding-horizon control. Client-thread only, dry arena only.
 * Dynamic mob pushing/future attacks are not simulated; ordinary vanilla ground/air movement is.
 * Reads client observations only, never server future state or a future spawn-probe event. */
public final class ArrowEvasionExperiment {
    public record Control(double x,double z,boolean jump){}
    private record Shot(Entity entity,ArrowTrajectory.Flight flight){}
    private record Forecast(ArrowTrajectory.Point position,boolean blockStop,ArrowTrajectory.Point blockPoint,ClientArrowTimingProbe.Stamp stamp,ArrowTrajectory.Point source){}
    private record Candidate(Control input,int hits,double firstHit,double score,List<MotionState> path){}
    private final UUID target;
    private int lastAge=-1,lastMeasured=-1,plans,rejected,overrides,matchedPlayer,matchedArrow,missingArrow,predictionErrors;
    private double playerError,arrowError;
    private int embeddedObservations,stoppedChecks,unexpectedStops,blockStillFlying,largeFlightResiduals;
    private final Map<UUID,String> arrowStates=new LinkedHashMap<>();
    private final Map<String,Integer> alignmentCounts=new LinkedHashMap<>();
    private MotionState expectedPlayer;
    private Map<UUID,Forecast> expectedArrows=Map.of();
    private Control current;
    public ArrowEvasionExperiment(UUID target){this.target=target;}
    public Control update(MinecraftClient c,double targetX,double targetZ,Consumer<Map<String,Object>> log){
        try{return calculate(c,targetX,targetZ,log);}
        catch(RuntimeException failure){predictionErrors++;lastAge=c.player.age;current=null;expectedPlayer=null;expectedArrows=Map.of();log.accept(Map.of("kind","prediction_error","age",lastAge,"error",failure.toString()));return null;}
    }
    private Control calculate(MinecraftClient c,double targetX,double targetZ,Consumer<Map<String,Object>> log){
        int age=c.player.age;
        if(age==lastAge)return current;
        measure(c,age,log);
        List<Shot> shots=new ArrayList<>();
        for(Entity e:c.world.getEntities(c.player,c.player.getBoundingBox().expand(20),entity->entity instanceof PersistentProjectileEntity)){
            PersistentProjectileEntity arrow=(PersistentProjectileEntity)e;Entity owner=arrow.getOwner();
            if(owner==null || !target.equals(owner.getUuid()))continue;
            Vec3d v=arrow.getVelocity();
            String state=!arrow.isAlive()?"REMOVED":embedded(arrow)?"EMBEDDED":arrow.isTouchingWater()?"WET_UNSUPPORTED":v.x*v.x+v.y*v.y+v.z*v.z<.001?"NO_MOTION":"FLYING";
            String previous=arrowStates.put(arrow.getUuid(),state);
            if(!state.equals(previous))log.accept(Map.of("kind","arrow_state","age",age,"id",arrow.getUuid().toString(),"state",state,"position",List.of(arrow.getX(),arrow.getY(),arrow.getZ()),"velocity",List.of(v.x,v.y,v.z)));
            if(state.equals("EMBEDDED"))embeddedObservations++;
            if(!state.equals("FLYING"))continue;
            shots.add(new Shot(arrow,new ArrowTrajectory.Flight(new ArrowTrajectory.Point(arrow.getX(),arrow.getY(),arrow.getZ()),new ArrowTrajectory.Point(v.x,v.y,v.z),!arrow.hasNoGravity())));
        }
        // Recheck native state before reusing the second input tick of an old plan.
        if(shots.isEmpty()){lastAge=age;current=null;expectedPlayer=null;expectedArrows=Map.of();return null;}
        if(lastAge>=0 && age-lastAge<2 && current!=null)return new Control(current.x(),current.z(),false);
        lastAge=age;current=null;expectedPlayer=null;expectedArrows=Map.of();
        // Never silently truncate a threat set and call it safe.
        if(shots.size()>8){log.accept(Map.of("kind","unsupported","reason","TOO_MANY_ARROWS","age",age));return null;}
        long began=System.nanoTime();List<Candidate> candidates=new ArrayList<>();List<String> failures=new ArrayList<>();
        double bearing=Math.atan2(targetZ-c.player.getZ(),targetX-c.player.getX());
        FusionWorld.in(c.world,Map.of(),()->{
            for(int direction=-1;direction<8;direction++)for(boolean jump:List.of(false,true)){
                if(jump && !c.player.isOnGround())continue;
                double angle=bearing+direction*Math.PI/4;
                Control input=direction<0?new Control(0,0,jump):new Control(Math.cos(angle),Math.sin(angle),jump);
                try{candidates.add(simulate(c,shots,input,targetX,targetZ));}
                catch(IllegalArgumentException unsupported){rejected++;if(failures.size()<3)failures.add(unsupported.toString());}
            }
            return null;
        });
        if(candidates.isEmpty()){log.accept(Map.of("kind","unsupported","age",age,"reason","NO_VALID_ROLLOUT","failures",failures));return null;}
        Candidate best=candidates.stream().min(Comparator.comparingInt(Candidate::hits)
                .thenComparing(Comparator.comparingDouble(Candidate::firstHit).reversed()).thenComparingDouble(Candidate::score)).orElseThrow();
        plans++;overrides++;current=best.input();expectedPlayer=best.path().get(0);
        Map<UUID,Forecast> next=new LinkedHashMap<>();for(Shot shot:shots)next.put(shot.entity().getUuid(),forecast(c,shot));expectedArrows=Map.copyOf(next);
        Map<String,Object> row=new LinkedHashMap<>();row.put("kind","plan");row.put("age",age);row.put("observedArrows",shots.size());row.put("candidates",candidates.size());row.put("rejected",rejected);
        row.put("predictedHits",best.hits());row.put("firstPredictedHitTick",Double.isFinite(best.firstHit())?best.firstHit():-1);
        row.put("allCandidatesRisky",candidates.stream().allMatch(a->a.hits()>0));
        row.put("input",List.of(current.x(),current.z(),current.jump()));row.put("elapsedMicros",(System.nanoTime()-began)/1000);
        row.put("candidateRisk",candidates.stream().map(a->Map.of("input",List.of(a.input().x(),a.input().z(),a.input().jump()),"hits",a.hits(),"firstHit",Double.isFinite(a.firstHit())?a.firstHit():-1)).toList());
        row.put("playerPath",best.path().stream().map(s->List.of(s.x(),s.y(),s.z())).toList());row.put("failures",failures);
        row.put("arrows",shots.stream().map(s->Map.of("id",s.entity().getUuid().toString(),"position",point(s.flight().position()),"velocity",point(s.flight().velocity()),"clock",clock(ClientArrowTimingProbe.stamp(s.entity().getUuid())))).toList());
        log.accept(row);return current;
    }
    private Candidate simulate(MinecraftClient c,List<Shot> shots,Control input,double tx,double tz){
        GroundAgent ghost=GroundAgent.forCombatPrediction(c,c.player);List<MotionState> path=new ArrayList<>();
        List<ArrowTrajectory.Flight> arrows=new ArrayList<>(shots.stream().map(Shot::flight).toList());boolean[] stopped=new boolean[shots.size()];
        int hits=0;double first=Double.POSITIVE_INFINITY;Box before=ghost.getBoundingBox();
        float yaw=(float)Math.toDegrees(Math.atan2(-input.x(),input.z()));boolean moving=Math.hypot(input.x(),input.z())>.1;
        for(int tick=0;tick<12;tick++){
            ghost.setSprinting(false);((MotionClientAccess)(Object)ghost).fredobot$setSprintWindow(0);
            MotionState s=ghost.step(new MotionInput(moving,false,false,false,input.jump() && tick==0,false,false,yaw,0));path.add(s);Box after=ghost.getBoundingBox();
            if(s.y()<3.9 || s.y()>5.5 || Math.abs(s.x())>11 || Math.abs(s.z())>11)throw new IllegalArgumentException("OUTSIDE_DRY_TEST_ARENA");
            for(int i=0;i<arrows.size();i++)if(!stopped[i]){
                ArrowTrajectory.Flight a=arrows.get(i),next=a.next();
                if(!c.world.getFluidState(new BlockPos(a.position().x(),a.position().y(),a.position().z())).isEmpty()
                        || !c.world.getFluidState(new BlockPos(next.position().x(),next.position().y(),next.position().z())).isEmpty())throw new IllegalArgumentException("WET_ARROW_PATH");
                Vec3d from=vec(a.position()),to=vec(next.position());
                HitResult wall=c.world.rayTrace(new RayTraceContext(from,to,RayTraceContext.ShapeType.COLLIDER,RayTraceContext.FluidHandling.NONE,shots.get(i).entity()));
                double length=Math.sqrt(sq(to.x-from.x)+sq(to.y-from.y)+sq(to.z-from.z));
                Vec3d at=wall.getPos();double wallFraction=wall.getType()==HitResult.Type.MISS?Double.POSITIVE_INFINITY:Math.sqrt(sq(at.x-from.x)+sq(at.y-from.y)+sq(at.z-from.z))/Math.max(1e-12,length);
                double hit=ArrowTrajectory.movingHit(a.position(),next.position(),body(before),body(after),.3);
                if(Double.isFinite(hit) && hit<=wallFraction){hits++;first=Math.min(first,tick+hit);stopped[i]=true;}
                else if(Double.isFinite(wallFraction))stopped[i]=true;
                arrows.set(i,next);
            }
            before=after;
        }
        MotionState end=path.get(path.size()-1);
        // Risk dominates. Among equal-risk candidates prefer approach and stable walking.
        double score=Math.hypot(end.x()-tx,end.z()-tz)+(input.jump()?.12:0);
        return new Candidate(input,hits,first,score,List.copyOf(path));
    }
    private void measure(MinecraftClient c,int age,Consumer<Map<String,Object>> log){
        if(age!=lastAge+1 || expectedPlayer==null || age==lastMeasured)return;lastMeasured=age;
        double e=Math.sqrt(sq(c.player.getX()-expectedPlayer.x())+sq(c.player.getY()-expectedPlayer.y())+sq(c.player.getZ()-expectedPlayer.z()));playerError+=e;matchedPlayer++;
        List<Entity> live=c.world.getEntities(c.player,c.player.getBoundingBox().expand(24),entity->expectedArrows.containsKey(entity.getUuid()));
        double maxArrow=0;int found=0,flightCount=0,stops=0,largeResiduals=0,blockMismatch=0;
        List<Map<String,Object>> details=new ArrayList<>();
        for(Entity entity:live){
            PersistentProjectileEntity arrow=(PersistentProjectileEntity)entity;Forecast forecast=expectedArrows.get(arrow.getUuid());found++;
            ClientArrowTimingProbe.Stamp now=ClientArrowTimingProbe.stamp(arrow.getUuid());
            String alignment=alignment(forecast,now);alignmentCounts.merge(alignment,1,Integer::sum);
            String state;
            double error=distance(arrow,forecast.position());
            if(embedded(arrow)){
                stoppedChecks++;stops++;state="COLLISION_STOP";
                if(!forecast.blockStop())unexpectedStops++;
            }else if(!alignment.equals("ALIGNED") && !alignment.equals("UNOBSERVED")){state=alignment;}
            else if(forecast.blockStop()){blockStillFlying++;blockMismatch++;state="PREDICTED_BLOCK_STILL_FLYING";}
            else {
                // Keep free-flight residuals separate from native collision transitions.
                state=alignment.equals("ALIGNED")?"FLYING":"TIMING_UNOBSERVED";
                if(alignment.equals("ALIGNED")){arrowError+=error;matchedArrow++;flightCount++;maxArrow=Math.max(maxArrow,error);
                    if(error>.1){largeFlightResiduals++;largeResiduals++;}}
            }
            Map<String,Object> detail=new LinkedHashMap<>();detail.put("id",arrow.getUuid().toString());detail.put("state",state);
            detail.put("alignment",alignment);detail.put("clockBefore",clock(forecast.stamp()));detail.put("clockNow",clock(now));
            detail.put("predictedBlockStop",forecast.blockStop());detail.put("rawFreeFlightError",error);
            detail.put("actualPosition",List.of(arrow.getX(),arrow.getY(),arrow.getZ()));detail.put("predictedPosition",point(forecast.position()));
            if(forecast.blockStop())detail.put("blockPoint",point(forecast.blockPoint()));details.add(detail);
        }
        missingArrow+=expectedArrows.size()-found;
        Map<String,Object> row=new LinkedHashMap<>();row.put("kind","prediction_check");row.put("age",age);row.put("playerPositionError",e);
        row.put("observedArrows",found);row.put("missingArrows",expectedArrows.size()-found);row.put("flyingArrowChecks",flightCount);
        row.put("collisionStops",stops);row.put("blockStillFlying",blockMismatch);row.put("largeFlightResiduals",largeResiduals);
        row.put("maxArrowPositionError",maxArrow);row.put("arrows",details);log.accept(row);
    }
    private Forecast forecast(MinecraftClient c,Shot shot){
        ArrowTrajectory.Point from=shot.flight().position(),to=shot.flight().next().position();
        HitResult wall=c.world.rayTrace(new RayTraceContext(vec(from),vec(to),RayTraceContext.ShapeType.COLLIDER,RayTraceContext.FluidHandling.NONE,shot.entity()));
        Vec3d p=wall.getPos();return new Forecast(to,wall.getType()!=HitResult.Type.MISS,new ArrowTrajectory.Point(p.x,p.y,p.z),ClientArrowTimingProbe.stamp(shot.entity().getUuid()),from);
    }
    public Map<String,Object> summary(){
        Map<String,Object> out=new LinkedHashMap<>();out.put("predictionErrors",predictionErrors);out.put("plans",plans);out.put("overrides",overrides);out.put("rejectedCandidates",rejected);
        out.put("playerChecks",matchedPlayer);out.put("meanPlayerError",matchedPlayer==0?0:playerError/matchedPlayer);
        out.put("arrowChecks",matchedArrow);out.put("meanArrowError",matchedArrow==0?0:arrowError/matchedArrow);out.put("missingArrows",missingArrow);
        out.put("embeddedObservations",embeddedObservations);out.put("collisionStops",stoppedChecks);out.put("unexpectedStops",unexpectedStops);
        out.put("predictedBlockStillFlying",blockStillFlying);out.put("largeFlightResiduals",largeFlightResiduals);out.put("alignmentCounts",Map.copyOf(alignmentCounts));return out;
    }
    private static String alignment(Forecast expected,ClientArrowTimingProbe.Stamp now){
        ClientArrowTimingProbe.Stamp before=expected.stamp();
        if(before==null || now==null)return "UNOBSERVED";
        if(now.corrections()!=before.corrections())return "NETWORK_CORRECTION";
        long ticks=now.ticks()-before.ticks();
        if(ticks==0)return "ARROW_NOT_ADVANCED";
        if(ticks!=1)return "ARROW_TICK_COUNT_MISMATCH";
        if(now.tickStart()==null || now.tickEnd()==null)return "INCOMPLETE_TICK_PAIR";
        if(ClientArrowTimingProbe.distance(now.tickStart().position(),expected.source())>.001)return "SOURCE_CHANGED_BEFORE_TICK";
        return "ALIGNED";
    }
    private static Map<String,Object> clock(ClientArrowTimingProbe.Stamp s){return s==null?Map.of("observed",false):Map.of("observed",true,"ticks",s.ticks(),"corrections",s.corrections());}
    private static boolean embedded(PersistentProjectileEntity arrow){return ((MotionProjectileAccess)(Object)arrow).fredobot$isInGround();}
    private static double distance(Entity arrow,ArrowTrajectory.Point p){return Math.sqrt(sq(arrow.getX()-p.x())+sq(arrow.getY()-p.y())+sq(arrow.getZ()-p.z()));}
    private static List<Double> point(ArrowTrajectory.Point p){return List.of(p.x(),p.y(),p.z());}
    private static Vec3d vec(ArrowTrajectory.Point p){return new Vec3d(p.x(),p.y(),p.z());}
    private static ArrowTrajectory.Body body(Box b){return new ArrowTrajectory.Body(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ);}
    private static double sq(double x){return x*x;}
}
