package com.fredoseep.behave;

import com.fredoseep.algorithm.planning.SurfaceBoatRoute;
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

/** Native item placement followed by observed entity boarding, including launch from the bank. */
public final class BoatLaunch {
    public record Input(boolean active,boolean forward,boolean jump) {}
    private static final Input IDLE=new Input(false,false,false), WAIT=new Input(true,false,false);
    private World owner;
    private Vec3d candidate;
    private BoatEntity boat;
    private final Set<Integer> previousBoats=new HashSet<>();
    private int aimTicks,requestAge=-1,nextAttemptAge,boardTicks;
    public void reset(){owner=null;candidate=null;boat=null;requestAge=-1;previousBoats.clear();aimTicks=boardTicks=nextAttemptAge=0;}
    public boolean boarded(PlayerEntity player){return boat!=null && player.getVehicle()==boat;}
    public boolean active(){return candidate!=null || requestAge>=0 || boat!=null;}

    public Input tick(MinecraftClient client,PlayerEntity player,PathExecutor path) {
        if(owner!=client.world){reset();owner=client.world;}
        if(player.hasVehicle()){reset();return IDLE;}
        if(boat!=null) {
            if(!boat.isAlive() || ++boardTicks>80){retry(player,"boarding timed out");return IDLE;}
            Vec3d point=new Vec3d(boat.getX(),boat.getY()+.3,boat.getZ());
            look(player,point);
            double distance=player.getPos().squaredDistanceTo(point);
            if(distance<=9) client.interactionManager.interactEntity(client.player,boat,Hand.MAIN_HAND);
            return new Input(true,distance>6.25,player.isTouchingWater() && !player.isSubmergedIn(FluidTags.WATER));
        }
        if(requestAge>=0) {
            for(BoatEntity seen:client.world.getEntities(BoatEntity.class,box(candidate).expand(2,2,2),
                    b -> b.isAlive() && !b.hasPassengers())) {
                if(!previousBoats.contains(seen.getEntityId())) {
                    boat=seen;requestAge=-1;boardTicks=0;
                    System.out.println("FredoBot: BOAT_LAUNCH observed entity="+boat.getEntityId());return WAIT;
                }
            }
            if(player.age-requestAge>40){retry(player,"no boat entity after placement");return IDLE;}
            return WAIT;
        }
        if(player.age<nextAttemptAge || !InventoryHelper.hasBoat(player)) return IDLE;
        if(candidate==null) {
            candidate=choose(client,player,path);
            if(candidate==null)return IDLE;
            aimTicks=0;
            System.out.println("FredoBot: BOAT_LAUNCH aiming at "+candidate+" from="+player.getPos());
        }
        look(player,candidate);
        if(++aimTicks>30){retry(player,"no visible collision-free water hit");return IDLE;}
        if(!(player.getMainHandStack().getItem() instanceof BoatItem)) {
            if(!InventoryHelper.putBoatToHotBar(player)){retry(player,"boat unavailable");return IDLE;}
            player.inventory.selectedSlot=7;return WAIT;
        }
        HitResult ray=player.rayTrace(5,1,true);
        if(!(ray instanceof BlockHitResult hit) || hit.getType()!=HitResult.Type.BLOCK
                || !client.world.getBlockState(hit.getBlockPos()).getFluidState().isIn(FluidTags.WATER)
                || hit.getPos().squaredDistanceTo(candidate)>1) return WAIT;
        Vec3d actual=hit.getPos();
        if(!free(client,player,actual)) {retry(player,"actual hit obstructed by player or hull collision");return IDLE;}
        previousBoats.clear();
        for(BoatEntity old:client.world.getEntities(BoatEntity.class,box(actual).expand(2,2,2),b -> true))
            previousBoats.add(old.getEntityId());
        candidate=actual;
        if(client.interactionManager.interactItem(client.player,client.world,Hand.MAIN_HAND).isAccepted()) {
            player.swingHand(Hand.MAIN_HAND);requestAge=player.age;
            System.out.println("FredoBot: BOAT_LAUNCH placement requested at="+actual);
        } else retry(player,"native boat use rejected");
        return WAIT;
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
            if(eye.squaredDistanceTo(point)>20.25 || !free(client,player,point)) continue;
            return point;
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
    private void retry(PlayerEntity player,String reason) {
        System.out.println("FredoBot: BOAT_LAUNCH retry reason="+reason);
        candidate=null;boat=null;requestAge=-1;nextAttemptAge=player.age+40;
    }
}
