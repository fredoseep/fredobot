package com.fredoseep.motion.sim;

import com.fredoseep.mixin.MotionClientAccess;
import com.fredoseep.mixin.MotionEntityAccess;
import com.fredoseep.mixin.MotionLivingAccess;
import com.fredoseep.mixin.MotionPlayerAccess;
import com.fredoseep.motion.MotionCoordinator;
import com.fredoseep.motion.MotionInput;
import com.fredoseep.motion.GroundRouteSearch;
import com.fredoseep.motion.GroundFallGuard;
import com.fredoseep.motion.MotionState;
import com.fredoseep.motion.PlannedInput;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.tag.FluidTags;

/** Detached 1.16.1 client player. Runs vanilla tickMovement, never ClientPlayerEntity.tick/network movement. */
public final class GroundAgent extends ClientPlayerEntity implements GroundRouteSearch.Agent {
    private final PlannedInput planned=new PlannedInput();
    private final ClientPlayerEntity source;
    private final MinecraftClient client;
    private final GroundFallGuard fallGuard;
    private final boolean combatPrediction;
    public static GroundAgent forCombatPrediction(MinecraftClient c,ClientPlayerEntity p){return new GroundAgent(c,p,true);}
    public GroundAgent(MinecraftClient client,ClientPlayerEntity source) {this(client,source,source instanceof GroundAgent ghost && ghost.combatPrediction);}
    private GroundAgent(MinecraftClient client,ClientPlayerEntity source,boolean combatPrediction) {
        super(client,client.world,source.networkHandler,source.getStatHandler(),source.getRecipeBook(),false,false);
        this.combatPrediction=combatPrediction;
        this.source=source instanceof GroundAgent ghost?ghost.source:source;
        this.client=client;fallGuard=source instanceof GroundAgent ghost?ghost.fallGuard.copy():new GroundFallGuard(source.getY());age=source.age;
        copyPositionAndRotation(source);setVelocity(source.getVelocity());setOnGround(source.isOnGround());
        horizontalCollision=source.horizontalCollision;verticalCollision=source.verticalCollision;
        setPose(source.getPose());setBoundingBox(source.getBoundingBox());stepHeight=source.stepHeight;
        if(FusionWorld.active()){
            touchingWater=source.isTouchingWater();submergedInWater=source.isSubmergedInWater();
            isSubmergedInWater=((MotionPlayerAccess)source).fredobot$getWaterSubmersion();
            field_25599=((MotionEntityAccess)source).fredobot$getSubmergedTag();setSwimming(source.isSwimming());setAir(source.getAir());
            fluidHeight.put(FluidTags.WATER,source.getFluidHeight(FluidTags.WATER));
            fluidHeight.put(FluidTags.LAVA,source.getFluidHeight(FluidTags.LAVA));
            for(int i=0;i<source.inventory.main.size();i++)inventory.main.set(i,source.inventory.main.get(i).copy());
            for(int i=0;i<source.inventory.armor.size();i++)inventory.armor.set(i,source.inventory.armor.get(i).copy());
            for(int i=0;i<source.inventory.offHand.size();i++)inventory.offHand.set(i,source.inventory.offHand.get(i).copy());
            inventory.selectedSlot=source.inventory.selectedSlot;
        }
        flyingSpeed=source.flyingSpeed;setMovementSpeed(source.getMovementSpeed());fallDistance=source.fallDistance;
        getAttributes().setFrom(source.getAttributes());setSprinting(source.isSprinting());
        abilities.creativeMode=source.abilities.creativeMode;abilities.invulnerable=source.abilities.invulnerable;
        abilities.allowFlying=source.abilities.allowFlying;abilities.flying=false;
        abilities.setWalkSpeed(source.abilities.getWalkSpeed());getHungerManager().setFoodLevel(source.getHungerManager().getFoodLevel());
        input=planned;planned.copyHistory(source.input);
        MotionClientAccess src=(MotionClientAccess)source,dst=(MotionClientAccess)(Object)this;
        dst.fredobot$setSprintWindow(src.fredobot$getSprintWindow());
        dst.fredobot$setAutoJumpTicks(src.fredobot$getAutoJumpTicks());dst.fredobot$setAutoJumpEnabled(false);
        dst.fredobot$setCrouching(src.fredobot$getCrouching());
        ((MotionLivingAccess)(Object)this).fredobot$setJumpCooldown(((MotionLivingAccess)source).fredobot$getJumpCooldown());
        ((MotionEntityAccess)(Object)this).fredobot$setMovementMultiplier(((MotionEntityAccess)source).fredobot$getMovementMultiplier());
        ((MotionPlayerAccess)(Object)this).fredobot$setFlightWindow(((MotionPlayerAccess)source).fredobot$getFlightWindow());
    }
    @Override public GroundAgent copy(){return new GroundAgent(client,this);}
    @Override public MotionState state(){return MotionCoordinator.capture(this);}
    @Override public float lookYaw(){return yaw;}
    @Override public boolean horizontalCollision(){return horizontalCollision;}
    @Override public MotionState step(MotionInput frame) {
        boolean fusion=FusionWorld.active();
        if(combatPrediction)FusionSafety.staticArea(world,source,getBoundingBox().stretch(getVelocity()));
        else if(fusion)FusionSafety.area(world,source,getBoundingBox().stretch(getVelocity()));
        else GroundSafety.requireArea(world,source,getBoundingBox().stretch(getVelocity()));
        fallGuard.beforeTick(getY(),isOnGround());
        MinecraftClient client=MinecraftClient.getInstance();
        boolean sprint=client.options.keySprint.isPressed();
        try {
            client.options.keySprint.setPressed(frame.sprint());yaw=frame.yaw();pitch=frame.pitch();planned.setFrame(frame);
            if(fusion){
                // Player.tick refreshes these before tickMovement; the detached simulator
                // must do the same, without invoking the networking/player tick itself.
                updateWaterSubmersionState();updateWaterState();
                ((MotionEntityAccess)(Object)this).fredobot$updateSubmergedState();updateSwimming();
            }
            super.tickMovement();updateSize();age++;
            if(fusion)setAir(isSubmergedIn(FluidTags.WATER)?getAir()-1:Math.min(getMaxAir(),getAir()+4));
        } finally {client.options.keySprint.setPressed(sprint);}
        if(combatPrediction)FusionSafety.staticArea(world,source,getBoundingBox());
        else if(fusion)FusionSafety.area(world,source,getBoundingBox());
        else GroundSafety.requireArea(world,source,getBoundingBox());
        if(abilities.flying || (!fusion && (getPose()!=EntityPose.STANDING || isTouchingWater())) || isInLava())
            throw new IllegalArgumentException("候选动作离开干燥站立姿态：pose="+getPose()+" flying="+abilities.flying);
        if(!fusion || !isTouchingWater())fallGuard.afterTick(getY(),isOnGround(),fallDistance);
        return MotionCoordinator.capture(this);
    }
    @Override public void tick(){throw new IllegalStateException("Detached agent must use step(), never network tick()");}
    @Override protected boolean isCamera(){return true;}
    @Override protected void autoJump(float dx,float dz) {}
    @Override protected void pushOutOfBlocks(double x,double y,double z) {}
    @Override protected void tickCramming() {}
    @Override public void pushAwayFrom(Entity other) {}
    @Override public void sendAbilitiesUpdate() {}
    @Override public void playSound(SoundEvent sound,float volume,float pitch) {}
    @Override public void playSound(SoundEvent sound,SoundCategory category,float volume,float pitch) {}
    @Override protected void playStepSound(BlockPos pos,BlockState state) {}
    @Override protected void onSwimmingStart() {}
    @Override protected void checkBlockCollision() {}
}
