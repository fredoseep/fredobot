package com.fredoseep.mixin;

import com.fredoseep.testing.combat.CriticalCombatScenario;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Read-only test observer; the scenario's UUID filter makes normal gameplay a no-op. */
@Mixin(ServerPlayerEntity.class)
public abstract class MixinCombatCritProbe {
    @Inject(method="attack",at=@At("HEAD"),require=0)
    private void fredobot$observeAttack(Entity target,CallbackInfo ci){CriticalCombatScenario.observe((ServerPlayerEntity)(Object)this,target,false);}
    @Inject(method="attack",at=@At("RETURN"),require=0)
    private void fredobot$observeAttackReturn(Entity target,CallbackInfo ci){CriticalCombatScenario.observeReturn((ServerPlayerEntity)(Object)this,target);}
    @Inject(method="addCritParticles",at=@At("HEAD"),require=0)
    private void fredobot$observeCrit(Entity target,CallbackInfo ci){CriticalCombatScenario.observe((ServerPlayerEntity)(Object)this,target,true);}
}
