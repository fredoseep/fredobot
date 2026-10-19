package com.fredoseep.motion.sim;

import com.fredoseep.mixin.MotionBoatAccess;
import com.fredoseep.motion.FusionSearch;
import com.fredoseep.motion.LocalGroundSearch;
import com.fredoseep.motion.MotionInput;
import com.fredoseep.motion.MotionState;
import com.fredoseep.motion.BoatControls;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.block.BlockState;
import net.minecraft.tag.FluidTags;
import net.minecraft.sound.SoundEvent;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import java.util.List;
import java.util.Map;

/** Detached vanilla boat physics. No boat tick, spawn, packet, passenger mutation, or block callbacks. */
public final class BoatAgent implements FusionSearch.Agent {
    private static final ThreadLocal<BoatEntity> SOURCE=new ThreadLocal<>();
    private final BoatEntity source;
    private final ClientPlayerEntity rider;
    private final Ghost boat;
    public BoatAgent(BoatEntity source,ClientPlayerEntity rider){this.source=source;this.rider=rider;boat=new Ghost(source,rider);}
    private BoatAgent(BoatAgent from){source=from.source;rider=from.rider;boat=new Ghost(from.boat,rider);}
    public static boolean ignored(Entity e){return e!=null && SOURCE.get()==e;}
    public static MotionState capture(BoatEntity b){var v=b.getVelocity();return new MotionState(b.getX(),b.getY(),b.getZ(),v.x,v.y,v.z,b.isOnGround());}
    public BoatAgent copy(){return new BoatAgent(this);}
    public MotionState state(){return capture(boat);}
    public boolean water(){return true;}
    public boolean checkpointSafe(){return false;}
    public boolean reachedGoal(LocalGroundSearch.Goal goal){
        MotionState s=state();return Math.hypot(goal.x()-s.x(),goal.z()-s.z())<=.45
                && Math.abs(goal.y()-s.y())<=.75 && Math.hypot(s.vx(),s.vz())<=.04;
    }
    public boolean horizontalCollision(){return boat.horizontalCollision;}
    public Object contextKey(){MotionBoatAccess a=(MotionBoatAccess)(Object)boat;return List.of(Math.round(MathHelper.wrapDegrees(boat.yaw)*2),Math.round(a.fredobot$getyawVelocity()*4),String.valueOf(a.fredobot$getlocation()),String.valueOf(a.fredobot$getlastLocation()));}
    public MotionInput control(MotionInput proposed,LocalGroundSearch.Goal goal){
        return BoatControls.choose(state(),boat.yaw,((MotionBoatAccess)(Object)boat).fredobot$getyawVelocity(),proposed.yaw(),goal.x(),goal.z());
    }
    public MotionState step(MotionInput input){
        BoatEntity previous=SOURCE.get();SOURCE.set(source);
        try{return FusionWorld.in(rider.world,Map.of(),()->{
            var swept=boat.getBoundingBox().stretch(boat.getVelocity()).expand(.1);
            for(Entity e:rider.world.getEntities(rider,swept,e->e.isAlive() && e!=source))
                if(!(e instanceof ItemEntity))throw new IllegalArgumentException("BOAT_ENTITY_OBSTRUCTION");
            MotionBoatAccess a=(MotionBoatAccess)(Object)boat;
            for(int x=(int)Math.floor(swept.minX);x<=Math.floor(swept.maxX);x++)
                for(int z=(int)Math.floor(swept.minZ);z<=Math.floor(swept.maxZ);z++){
                    if(!rider.world.getChunkManager().isChunkLoaded(x>>4,z>>4))throw new IllegalArgumentException("BOAT_UNLOADED_HULL");
                }
            a.fredobot$setlastLocation(a.fredobot$getlocation());a.fredobot$setlocation(a.fredobot$checkLocation());
            boat.refreshFluids();
            if(a.fredobot$getlocation()==BoatEntity.Location.UNDER_WATER || a.fredobot$getlocation()==BoatEntity.Location.UNDER_FLOWING_WATER)
                throw new IllegalArgumentException("BOAT_SUBMERGED");
            boat.setInputs(input.left(),input.right(),input.forward(),input.back());
            a.fredobot$updateVelocity();a.fredobot$updatePaddles();boat.move(MovementType.SELF,boat.getVelocity());boat.age++;
            BlockPos p=boat.getBlockPos();
            if(!rider.world.getChunkManager().isChunkLoaded(p.getX()>>4,p.getZ()>>4) || boat.isInLava())throw new IllegalArgumentException("BOAT_UNLOADED_OR_LAVA");
            if(boat.getY()<source.getY()-2)throw new IllegalArgumentException("BOAT_UNSAFE_DROP");
            return state();
        });}finally{if(previous==null)SOURCE.remove();else SOURCE.set(previous);}
    }
    public List<? extends FusionSearch.Interaction> interactions(){return List.of();}
    public BoatAgent apply(FusionSearch.Interaction edit){throw new IllegalArgumentException("boat has no terrain edit");}
    private static final class Ghost extends BoatEntity {
        private final ClientPlayerEntity rider;
        Ghost(BoatEntity from,ClientPlayerEntity rider){
            super(from.world,from.getX(),from.getY(),from.getZ());this.rider=rider;
            copyPositionAndRotation(from);setVelocity(from.getVelocity());setOnGround(from.isOnGround());
            age=from.age;touchingWater=from.isTouchingWater();
            fluidHeight.put(FluidTags.WATER,from.getFluidHeight(FluidTags.WATER));fluidHeight.put(FluidTags.LAVA,from.getFluidHeight(FluidTags.LAVA));
            horizontalCollision=from.horizontalCollision;verticalCollision=from.verticalCollision;setBoundingBox(from.getBoundingBox());setBoatType(from.getBoatType());
            MotionBoatAccess a=(MotionBoatAccess)from,b=(MotionBoatAccess)(Object)this;
            b.fredobot$setvelocityDecay(a.fredobot$getvelocityDecay());
            b.fredobot$setyawVelocity(a.fredobot$getyawVelocity());
            b.fredobot$setwaterLevel(a.fredobot$getwaterLevel());
            b.fredobot$setfield_7714(a.fredobot$getfield_7714());
            b.fredobot$setlocation(a.fredobot$getlocation());
            b.fredobot$setlastLocation(a.fredobot$getlastLocation());
            b.fredobot$setfallVelocity(a.fredobot$getfallVelocity());
            b.fredobot$setticksUnderwater(a.fredobot$getticksUnderwater());
        }
        void refreshFluids(){updateWaterState();}
        @Override protected void onSwimmingStart(){}
        @Override public void playSound(SoundEvent sound,float volume,float pitch){}
        @Override protected void playStepSound(BlockPos pos,BlockState state){}
        @Override public boolean hasPassengers(){return true;}
        @Override public Entity getPrimaryPassenger(){return rider;}
        @Override public Box getHardCollisionBox(Entity other){return other==rider || ignored(other)?null:super.getHardCollisionBox(other);}
        @Override protected void checkBlockCollision(){}
        @Override protected void fall(double height,boolean ground,BlockState state,BlockPos p){
            ((MotionBoatAccess)(Object)this).fredobot$setfallVelocity(getVelocity().y);
            if(ground)fallDistance=0;else if(height<0)fallDistance-=(float)height;
        }
        @Override public void tick(){throw new IllegalStateException("Use detached boat physics step, never tick/network");}
    }
}
