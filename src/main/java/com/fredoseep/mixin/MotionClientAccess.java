package com.fredoseep.mixin;

import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientPlayerEntity.class)
public interface MotionClientAccess {
    @Accessor("ticksLeftToDoubleTapSprint") int fredobot$getSprintWindow();
    @Accessor("ticksLeftToDoubleTapSprint") void fredobot$setSprintWindow(int value);
    @Accessor("ticksToNextAutojump") int fredobot$getAutoJumpTicks();
    @Accessor("ticksToNextAutojump") void fredobot$setAutoJumpTicks(int value);
    @Accessor("autoJumpEnabled") void fredobot$setAutoJumpEnabled(boolean enabled);
    @Accessor("field_23093") boolean fredobot$getCrouching();
    @Accessor("field_23093") void fredobot$setCrouching(boolean value);
}
