package com.fredoseep.mixin;

import com.fredoseep.motion.sim.FusionWorld;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla collision iteration reads chunks directly, bypassing World.getBlockState. */
@Mixin(WorldChunk.class)
public class MixinFusionChunk {
    @Shadow @Final private World world;
    @Inject(method="getBlockState",at=@At("HEAD"),cancellable=true)
    private void fredobot$fusionBlock(BlockPos pos,CallbackInfoReturnable<BlockState> result){
        BlockState value=FusionWorld.override(world,pos);if(value!=null)result.setReturnValue(value);
    }
    @Inject(method="getFluidState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/fluid/FluidState;",at=@At("HEAD"),cancellable=true)
    private void fredobot$fusionFluid(BlockPos pos,CallbackInfoReturnable<FluidState> result){
        BlockState value=FusionWorld.override(world,pos);if(value!=null)result.setReturnValue(value.getFluidState());
    }
    @Inject(method="getFluidState(III)Lnet/minecraft/fluid/FluidState;",at=@At("HEAD"),cancellable=true)
    private void fredobot$fusionFluidCoordinates(int x,int y,int z,CallbackInfoReturnable<FluidState> result){
        BlockState value=FusionWorld.override(world,new BlockPos(x,y,z));if(value!=null)result.setReturnValue(value.getFluidState());
    }
}
