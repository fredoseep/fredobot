package com.fredoseep.testing.combat;

import com.fredoseep.mixin.MotionProjectileAccess;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Read-only client-world lease. Server-world ticks and unrelated shooters are ignored. */
public final class ClientArrowTimingProbe implements AutoCloseable {
    public record State(ArrowTrajectory.Point position,ArrowTrajectory.Point velocity,boolean embedded,boolean wet,boolean gravity,int age){}
    public record Stamp(long ticks,long corrections,State tickStart,State tickEnd){}
    private static final class Entry {long ticks,corrections,startRevision;State start,end;}
    private static volatile ClientArrowTimingProbe active;
    private final World world;
    private final UUID shooter;
    private final Consumer<Map<String,Object>> log;
    private final Map<UUID,Entry> entries=new LinkedHashMap<>();
    private long sequence,tickPairs,freeChecks,stops,positionUpdates,velocityUpdates,logErrors,suppressed;
    private double positionError,velocityError,maxPositionError;
    private boolean closed;
    public ClientArrowTimingProbe(World world,UUID shooter,Consumer<Map<String,Object>> log){this.world=world;this.shooter=shooter;this.log=log;active=this;}
    private static ClientArrowTimingProbe owner(World world,PersistentProjectileEntity arrow){
        ClientArrowTimingProbe p=active;if(p==null || p.closed || p.world!=world)return null;
        Entity owner=arrow.getOwner();return owner!=null && p.shooter.equals(owner.getUuid())?p:null;
    }
    public static Stamp stamp(UUID id){
        ClientArrowTimingProbe p=active;if(p==null || p.closed)return null;
        Entry e=p.entries.computeIfAbsent(id,k->new Entry());return new Stamp(e.ticks,e.corrections,e.start,e.end);
    }
    public static void beforeTick(World world,PersistentProjectileEntity arrow){
        ClientArrowTimingProbe p=owner(world,arrow);if(p==null)return;
        Entry e=p.entries.computeIfAbsent(arrow.getUuid(),k->new Entry());e.start=state(arrow);e.startRevision=e.corrections;
    }
    public static void afterTick(World world,PersistentProjectileEntity arrow){
        ClientArrowTimingProbe p=owner(world,arrow);if(p==null)return;
        Entry e=p.entries.computeIfAbsent(arrow.getUuid(),k->new Entry());if(e.start==null)return;
        State before=e.start,after=state(arrow);e.end=after;e.ticks++;p.tickPairs++;
        String kind=before.embedded()?"ALREADY_EMBEDDED":after.embedded()?"COLLISION_STOP":before.wet()||after.wet()?"WET_UNSUPPORTED":e.startRevision!=e.corrections?"NETWORK_DURING_TICK":"FREE_FLIGHT";
        ArrowTrajectory.Flight predicted=new ArrowTrajectory.Flight(before.position(),before.velocity(),before.gravity()).next();
        double pe=distance(predicted.position(),after.position()),ve=distance(predicted.velocity(),after.velocity());
        if(kind.equals("FREE_FLIGHT")){p.freeChecks++;p.positionError+=pe;p.velocityError+=ve;p.maxPositionError=Math.max(p.maxPositionError,pe);}
        if(kind.equals("COLLISION_STOP"))p.stops++;
        if(!kind.equals("ALREADY_EMBEDDED")){
            Map<String,Object> row=new LinkedHashMap<>();row.put("kind","arrow_native_tick");row.put("id",arrow.getUuid().toString());row.put("worldTime",world.getTime());row.put("tickSerial",e.ticks);
            row.put("correctionRevision",e.corrections);row.put("classification",kind);row.put("before",values(before));row.put("after",values(after));
            row.put("positionError",pe);row.put("velocityError",ve);p.emit(row);
        }
    }
    /** Called before/after the native tracked-position or client-velocity setter. No setter is invoked here. */
    public static void network(World world,PersistentProjectileEntity arrow,String type,boolean before,List<?> packet){
        ClientArrowTimingProbe p=owner(world,arrow);if(p==null)return;
        Entry e=p.entries.computeIfAbsent(arrow.getUuid(),k->new Entry());
        if(before){e.corrections++;if(type.equals("POSITION"))p.positionUpdates++;else p.velocityUpdates++;}
        p.emit(Map.of("kind","arrow_network_update","id",arrow.getUuid().toString(),"worldTime",world.getTime(),"type",type,"phase",before?"HEAD":"TAIL","tickSerial",e.ticks,"correctionRevision",e.corrections,"packet",packet,"state",values(state(arrow))));
    }
    private void emit(Map<String,Object> input){
        if(sequence>=1024){suppressed++;return;}
        Map<String,Object> row=new LinkedHashMap<>(input);row.put("order",++sequence);
        try{log.accept(row);}catch(RuntimeException error){logErrors++;}
    }
    public Map<String,Object> summary(){
        Map<String,Object> row=new LinkedHashMap<>();row.put("tickPairs",tickPairs);row.put("freeFlightChecks",freeChecks);row.put("meanNativePositionError",freeChecks==0?0:positionError/freeChecks);
        row.put("maxNativePositionError",maxPositionError);row.put("meanNativeVelocityError",freeChecks==0?0:velocityError/freeChecks);row.put("collisionStops",stops);
        row.put("positionUpdates",positionUpdates);row.put("velocityUpdates",velocityUpdates);row.put("loggedEvents",sequence);row.put("suppressedEvents",suppressed);row.put("loggingErrors",logErrors);return row;
    }
    private static State state(PersistentProjectileEntity a){Vec3d v=a.getVelocity();return new State(new ArrowTrajectory.Point(a.getX(),a.getY(),a.getZ()),new ArrowTrajectory.Point(v.x,v.y,v.z),((MotionProjectileAccess)(Object)a).fredobot$isInGround(),a.isTouchingWater(),!a.hasNoGravity(),a.age);}
    private static Map<String,Object> values(State s){return Map.of("position",List.of(s.position().x(),s.position().y(),s.position().z()),"velocity",List.of(s.velocity().x(),s.velocity().y(),s.velocity().z()),"inGround",s.embedded(),"wet",s.wet(),"age",s.age());}
    static double distance(ArrowTrajectory.Point a,ArrowTrajectory.Point b){return Math.sqrt(sq(a.x()-b.x())+sq(a.y()-b.y())+sq(a.z()-b.z()));}
    private static double sq(double x){return x*x;}
    @Override public void close(){closed=true;if(active==this)active=null;}
}
