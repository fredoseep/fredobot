package com.fredoseep.mixin;

import net.minecraft.entity.projectile.PersistentProjectileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only native arrow state; nonzero velocity does not imply an embedded arrow is flying. */
@Mixin(PersistentProjectileEntity.class)
public interface MotionProjectileAccess {
    @Accessor("inGround") boolean fredobot$isInGround();
}
