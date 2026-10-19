package com.fredoseep.behave;

import com.fredoseep.algorithm.planning.SurfaceBoatRoute;
import com.fredoseep.algorithm.planning.ShoreLaunchPolicy;
import com.fredoseep.algorithm.SimplePathfinder;
import com.fredoseep.logging.GlobalStateLogger;
import com.fredoseep.excutor.PathExecutor;
import com.fredoseep.utils.player.InventoryHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.BoatItem;
import net.minecraft.tag.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Native item placement followed by observed entity boarding, with a non-sprinting, upright water approach. */
public final class BoatLaunch {
    public record Input(boolean active,boolean forward,boolean jump,boolean sneak) {
        public Input(boolean active,boolean forward,boolean jump){this(active,forward,jump,false);}
    }
    private static final Input IDLE=new Input(false,false,false);
    private static final Input FLOAT_FORWARD=new Input(true,true,true);
    private World owner;
    private Vec3d candidate;
    private BoatEntity boat;
    private final Set<Integer> previousBoats=new HashSet<>();
    private int aimTicks,approachTicks,requestAge=-1,nextAttemptAge,boardTicks,failures;
    private boolean session,abandoned;
    private List<SimplePathfinder.Node> route;
    private Vec3d requestedPoint;
    private float launchYaw,launchPitch;
    private String lastRay="none";
    private final List<String> attempts=new ArrayList<>();

    public void reset(){
        owner=null;candidate=null;boat=null;requestedPoint=null;route=null;requestAge=-1;
        previousBoats.clear();attempts.clear();session=abandoned=false;
        aimTicks=approachTicks=boardTicks=nextAttemptAge=failures=0;
    }
    public boolean boarded(PlayerEntity player){return boat!=null && player.getVehicle()==boat;}
    public boolean active(){return session;}
    public boolean abandoned(){return abandoned;}

    public Input tick(MinecraftClient client,PlayerEntity player,PathExecutor path) {
        if(owner!=client.world || route!=path.getCurrentPath()){
            reset();owner=client.world;route=path.getCurrentPath();
        }
        if(player.hasVehicle()){reset();return IDLE;}
        if(abandoned)return IDLE;
        // Keep observing an accepted placement even during retry cooldown, before checking inventory.
        if(boat==null && requestedPoint!=null) {
            for(BoatEntity seen:client.world.getEntities(BoatEntity.class,box(requestedPoint).expand(2,2,2),
                    b -> b.isAlive() && !b.hasPassengers())) {
                if(!previousBoats.contains(seen.getEntityId())) {
                    boat=seen;requestAge=-1;boardTicks=0;session=true;break;
                }
            }
        }
        if(boat!=null) {
            if(!boat.isAlive() || ++boardTicks>80){
                retry(client,player,path,"boarding timed out",true);return IDLE;
            }
            Vec3d point=new Vec3d(boat.getX(),boat.getY()+.3,boat.getZ());
            look(player,point);
            double distance=player.getPos().squaredDistanceTo(point);
            if(distance<=9) client.interactionManager.interactEntity(client.player,boat,Hand.MAIN_HAND);
            return new Input(true,distance>6.25,player.isTouchingWater() && !player.isSubmergedIn(FluidTags.WATER));
        }
        if(requestAge>=0) {
            if(player.age-requestAge>40){
                retry(client,player,path,"no boat entity after placement",!InventoryHelper.hasBoat(player));
            }
            return session?FLOAT_FORWARD:IDLE;
        }
        // Retain launch ownership during cooldown so movement cannot activate water nodes.
        if(session && player.age<nextAttemptAge)return FLOAT_FORWARD;
        if(!InventoryHelper.hasBoat(player)){
            if(session)retry(client,player,path,"boat unavailable",true);
            return IDLE;
        }
        if(candidate==null) {
            candidate=choose(client,player,path);
            if(candidate==null){
                if(session)retry(client,player,path,"no reachable collision-free launch point",true);
                return IDLE;
            }
            session=true;aimTicks=approachTicks=0;launchPitch=ShoreLaunchPolicy.FIRST_PITCH;attempts.clear();
            // Lock the heading toward the selected route node for the entire downward pitch sweep.
            launchYaw=(float)Math.toDegrees(Math.atan2(-(candidate.x-player.getX()),candidate.z-player.getZ()));
        }
        // Walk into the water upright; never creep or sprint into the launch.
        if(!player.isTouchingWater()) {
            player.yaw=launchYaw;player.pitch=ShoreLaunchPolicy.FIRST_PITCH;
            if(++approachTicks>160){retry(client,player,path,"water approach timed out",true);return IDLE;}
            return FLOAT_FORWARD;
        }
        player.yaw=launchYaw;player.pitch=launchPitch;
        if(++aimTicks>120){retry(client,player,path,"aim did not settle",false);return session?FLOAT_FORWARD:IDLE;}
        float yawError=(player.yaw-launchYaw)%360;
        if(yawError>180)yawError-=360;else if(yawError<-180)yawError+=360;
        if(Math.abs(yawError)>1 || Math.abs(player.pitch-launchPitch)>1)return FLOAT_FORWARD;
        if(!(player.getMainHandStack().getItem() instanceof BoatItem)) {
            if(!InventoryHelper.putBoatToHotBar(player)){
                retry(client,player,path,"boat unavailable",true);return IDLE;
            }
            player.inventory.selectedSlot=7;return FLOAT_FORWARD;
        }
        Vec3d nearby=choose(client,player,path);
        if(nearby!=null)candidate=nearby;
        HitResult ray=player.rayTrace(5,1,true);
        lastRay=ray==null?"null":ray.getType().toString();
        if(ray instanceof BlockHitResult hit)lastRay+=" pos="+hit.getPos()+" block="+hit.getBlockPos();
        boolean valid=ray instanceof BlockHitResult hit && hit.getType()==HitResult.Type.BLOCK
                && client.world.getBlockState(hit.getBlockPos()).getFluidState().isIn(FluidTags.WATER)
                && !client.world.getBlockState(hit.getBlockPos().up()).getFluidState().isIn(FluidTags.WATER)
                && hit.getPos().squaredDistanceTo(candidate)<=4 && free(client,player,hit.getPos());
        attempts.add("pitch="+launchPitch+" ray="+lastRay+" valid="+valid);
        if(!valid){
            launchPitch+=ShoreLaunchPolicy.PITCH_STEP;
            if(launchPitch>ShoreLaunchPolicy.MAX_PITCH)retry(client,player,path,"downward pitch sweep found no safe water hit",false);
            return session?FLOAT_FORWARD:IDLE;
        }
        Vec3d actual=((BlockHitResult)ray).getPos();
        previousBoats.clear();
        for(BoatEntity old:client.world.getEntities(BoatEntity.class,box(actual).expand(2,2,2),b -> true))
            previousBoats.add(old.getEntityId());
        if(client.interactionManager.interactItem(client.player,client.world,Hand.MAIN_HAND).isAccepted()) {
            player.swingHand(Hand.MAIN_HAND);requestAge=player.age;requestedPoint=actual;
        } else {
            // Continue the same locked heading at the next lower angle before retrying the session.
            launchPitch+=ShoreLaunchPolicy.PITCH_STEP;
            if(launchPitch>ShoreLaunchPolicy.MAX_PITCH)retry(client,player,path,"native boat use rejected throughout pitch sweep",false);
        }
        return session?FLOAT_FORWARD:IDLE;
    }
    private static double[] shoreFootprint(MinecraftClient client,PlayerEntity player){
        Box body=player.getBoundingBox();int water=0,land=0;
        for(int x=0;x<5;x++)for(int z=0;z<5;z++){
            BlockPos p=new BlockPos(body.minX+(body.maxX-body.minX)*(x+.5)/5,body.minY-.05,body.minZ+(body.maxZ-body.minZ)*(z+.5)/5);
            var state=client.world.getBlockState(p);
            if(state.getFluidState().isIn(FluidTags.WATER))water++;
            else if(!state.getCollisionShape(client.world,p).isEmpty())land++;
        }
        return new double[]{water/25.0,land/25.0};
    }
    private static Vec3d choose(MinecraftClient client,PlayerEntity player,PathExecutor path) {
        if(player.hasStatusEffect(StatusEffects.DOLPHINS_GRACE) && player.isSwimming())return null;
        var nodes=path.getCurrentPath();
        int first=SurfaceBoatRoute.launchEntry(client.world,nodes,path.getCurrentPathIndex());
        if(first<0 || player.getHungerManager().getFoodLevel()>6
                && SurfaceBoatRoute.distance(client.world,nodes,first,player.getX(),player.getZ())<=7) return null;
        Vec3d eye=new Vec3d(player.getX(),player.getEyeY(),player.getZ());
        for(int i=first;i<Math.min(nodes.size(),first+3);i++) {
            var node=nodes.get(i);if(!SurfaceBoatRoute.allowed(client.world,node)) break;
            BlockPos p=node.pos;
            Vec3d point=new Vec3d(p.getX()+.5,p.getY()+client.world.getBlockState(p).getFluidState().getHeight(client.world,p)-.01,p.getZ()+.5);
            if(eye.squaredDistanceTo(point)<=20.25 && free(client,player,point))return point;
            // A smoothed node may be far offshore: sample nearer water along its heading.
            double dx=point.x-player.getX(),dz=point.z-player.getZ(),length=Math.hypot(dx,dz);
            if(length<.001)continue;
            for(double distance:new double[]{1.25,1.75,2.25,2.75,3.25}){
                if(distance>length)break;
                BlockPos near=new BlockPos(player.getX()+dx/length*distance,p.getY(),player.getZ()+dz/length*distance);
                var fluid=client.world.getBlockState(near).getFluidState();
                if(!fluid.isIn(FluidTags.WATER) || client.world.getBlockState(near.up()).getFluidState().isIn(FluidTags.WATER))continue;
                Vec3d local=new Vec3d(player.getX()+dx/length*distance,near.getY()+fluid.getHeight(client.world,near)-.01,player.getZ()+dz/length*distance);
                if(eye.squaredDistanceTo(local)<=20.25 && free(client,player,local))return local;
            }
        }
        return null;
    }
    private static Box box(Vec3d point) {
        // Vanilla 1.16.1 boat dimensions, with a small clearance margin.
        return new Box(point.x-.7375,point.y+.05,point.z-.7375,point.x+.7375,point.y+.5625,point.z+.7375);
    }
    private static boolean free(MinecraftClient client,PlayerEntity player,Vec3d point) {
        Box hull=box(point);
        return !hull.intersects(player.getBoundingBox().expand(.1,.1,.1)) && client.world.doesNotCollide(null,hull);
    }
    private static void look(PlayerEntity player,Vec3d point) {
        double dx=point.x-player.getX(),dy=point.y-player.getEyeY(),dz=point.z-player.getZ();
        float yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));
        float pitch=(float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz)));
        MovementController.setLookDirection(player,yaw,pitch);
    }
    private void retry(MinecraftClient client,PlayerEntity player,PathExecutor path,String reason,boolean terminal) {
        failures++;
        Map<String,Object> dump=new LinkedHashMap<>();
        dump.put("reason",reason);dump.put("attempt",failures);
        dump.put("fallback_to_swimming",terminal || failures>=3);
        dump.put("player_age",player.age);dump.put("pos",List.of(player.getX(),player.getY(),player.getZ()));
        dump.put("player_box",player.getBoundingBox().toString());
        double[] footprint=shoreFootprint(client,player);dump.put("footprint_water_fraction",footprint[0]);dump.put("footprint_land_fraction",footprint[1]);dump.put("shore_approach_ticks",approachTicks);
        dump.put("pose",player.getPose().name());dump.put("velocity",player.getVelocity().toString());
        dump.put("swimming",player.isSwimming());dump.put("touching_water",player.isTouchingWater());
        dump.put("submerged",player.isSubmergedIn(FluidTags.WATER));dump.put("on_ground",player.isOnGround());
        dump.put("vehicle",player.getVehicle()==null?null:player.getVehicle().toString());
        dump.put("yaw",player.yaw);dump.put("pitch",player.pitch);
        dump.put("launch_policy","WATER_FORWARD_SPACE_NO_SPRINT");dump.put("pitch_direction","UP_TO_DOWN");
        dump.put("locked_yaw",launchYaw);dump.put("sweep_pitch",launchPitch);
        dump.put("candidate",candidate==null?null:candidate.toString());
        dump.put("candidate_free",candidate!=null && free(client,player,candidate));
        dump.put("candidate_hull",candidate==null?null:box(candidate).toString());
        dump.put("requested_point",requestedPoint==null?null:requestedPoint.toString());
        dump.put("request_age",requestAge);dump.put("has_boat_item",InventoryHelper.hasBoat(player));
        dump.put("tracked_boat",boat==null?null:boat.getEntityId());
        dump.put("last_ray",lastRay);dump.put("pitch_attempts",List.copyOf(attempts));
        dump.put("node_index",path.getCurrentPathIndex());
        List<String> nodes=new ArrayList<>();
        for(int i=Math.max(0,path.getCurrentPathIndex()-1);i<Math.min(route.size(),path.getCurrentPathIndex()+6);i++)
            nodes.add(i+":"+route.get(i).state+"@"+route.get(i).pos);
        dump.put("nearby_nodes",nodes);dump.put("node_count",route.size());
        List<String> boats=new ArrayList<>();
        for(BoatEntity nearby:client.world.getEntities(BoatEntity.class,player.getBoundingBox().expand(6,3,6),b -> true)){
            if(boats.size()>=16)break;
            boats.add("id="+nearby.getEntityId()+" pos="+nearby.getPos()+" alive="+nearby.isAlive()+" passengers="+nearby.hasPassengers());
        }
        dump.put("nearby_boats",boats);
        GlobalStateLogger.logCustomEvent("boat_launch_failed_dump",dump);
        System.out.println("FredoBot: BOAT_LAUNCH failed reason="+reason+" attempt="+failures);
        candidate=null;boat=null;requestAge=-1;nextAttemptAge=player.age+40;
        abandoned=terminal || failures>=3;session=!abandoned;
    }
}
