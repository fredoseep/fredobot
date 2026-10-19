package com.fredoseep.mixin;

import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.class)
public interface MotionLivingAccess {
    @Accessor("jumpingCooldown") int fredobot$getJumpCooldown();
    @Accessor("jumpingCooldown") void fredobot$setJumpCooldown(int value);
}
