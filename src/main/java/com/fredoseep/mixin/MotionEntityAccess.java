package com.fredoseep.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.tag.Tag;
import net.minecraft.fluid.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface MotionEntityAccess {
    @Invoker("updateSubmergedInWaterState") void fredobot$updateSubmergedState();
    @Accessor("field_25599") Tag<Fluid> fredobot$getSubmergedTag();
    @Accessor("movementMultiplier") Vec3d fredobot$getMovementMultiplier();
    @Accessor("movementMultiplier") void fredobot$setMovementMultiplier(Vec3d value);
}
