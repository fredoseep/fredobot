package com.fredoseep.mixin;

import com.fredoseep.motion.sim.BoatAgent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.util.math.Box;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Exclude only the real source boat from its detached copy's collision query. */
@Mixin(BoatEntity.class)
public class MixinFusionBoatCollision {
    @Inject(method="getCollisionBox",at=@At("HEAD"),cancellable=true)
    private void fredobot$sourceBox(CallbackInfoReturnable<Box> result){
        if(BoatAgent.ignored((Entity)(Object)this))result.setReturnValue(null);
    }
    @Inject(method="getHardCollisionBox",at=@At("HEAD"),cancellable=true)
    private void fredobot$sourceHardBox(Entity other,CallbackInfoReturnable<Box> result){
        if(BoatAgent.ignored((Entity)(Object)this) || BoatAgent.ignored(other))result.setReturnValue(null);
    }
}
