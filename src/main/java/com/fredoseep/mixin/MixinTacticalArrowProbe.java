package com.fredoseep.mixin;

import com.fredoseep.testing.combat.RangedArrowProbe;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerWorld.class)
public abstract class MixinTacticalArrowProbe {
    @Inject(method="spawnEntity",at=@At("RETURN"),require=0)
    private void fredobot$observeArrowSpawn(Entity entity,CallbackInfoReturnable<Boolean> cir){
        if(Boolean.TRUE.equals(cir.getReturnValue()))RangedArrowProbe.spawned((ServerWorld)(Object)this,entity);
    }
}
