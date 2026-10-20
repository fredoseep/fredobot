package com.fredoseep.algorithm.planning;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class UnderwaterDoorAccess {
    private UnderwaterDoorAccess(){}
    public static boolean water(World world,BlockPos pos){
        return world.getBlockState(pos).getFluidState().isIn(FluidTags.WATER);
    }
    public static boolean doorSpace(World world,BlockPos pos){
        return water(world,pos) && world.getBlockState(pos).getCollisionShape(world,pos).isEmpty()
                && world.getBlockState(pos.up()).getCollisionShape(world,pos.up()).isEmpty();
    }
    public static boolean plant(Block block){
        return block==Blocks.KELP || block==Blocks.KELP_PLANT || block==Blocks.SEAGRASS || block==Blocks.TALL_SEAGRASS;
    }
    /** Clear the upper half first so it cannot hide the lower plant's outline ray. */
    public static BlockPos plantToClear(World world,BlockPos door){
        if(plant(world.getBlockState(door.up()).getBlock()))return door.up();
        return plant(world.getBlockState(door).getBlock())?door:null;
    }
}
