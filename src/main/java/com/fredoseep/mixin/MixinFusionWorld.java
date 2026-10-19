package com.fredoseep.mixin;

import com.fredoseep.motion.sim.FusionWorld;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
public class MixinFusionWorld {
    @Inject(method="getBlockState",at=@At("HEAD"),cancellable=true)
    private void fredobot$fusionBlock(BlockPos pos,CallbackInfoReturnable<BlockState> result){
        BlockState value=FusionWorld.override((World)(Object)this,pos);if(value!=null)result.setReturnValue(value);
    }
    @Inject(method="getFluidState",at=@At("HEAD"),cancellable=true)
    private void fredobot$fusionFluid(BlockPos pos,CallbackInfoReturnable<FluidState> result){
        BlockState value=FusionWorld.override((World)(Object)this,pos);if(value!=null)result.setReturnValue(value.getFluidState());
    }
}
