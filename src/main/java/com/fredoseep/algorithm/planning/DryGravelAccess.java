package com.fredoseep.algorithm.planning;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.IceBlock;
import net.minecraft.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/** Shared admission rules for dry gravel selection and confirmed top-down excavation. */
public final class DryGravelAccess {
    public static final int MAX_COVER=16;
    private DryGravelAccess(){}
    public static boolean clear(World world,BlockPos pos){
        return world.getChunkManager().isChunkLoaded(pos.getX()>>4,pos.getZ()>>4)
                && world.getBlockState(pos).getFluidState().isEmpty()
                && world.getBlockState(pos).getCollisionShape(world,pos).isEmpty();
    }
    public static boolean safeCover(World world,BlockPos pos){
        if(!world.getChunkManager().isChunkLoaded(pos.getX()>>4,pos.getZ()>>4))return false;
        BlockState state=world.getBlockState(pos);Block block=state.getBlock();
        boolean loose=block==Blocks.SAND || block==Blocks.RED_SAND || block==Blocks.GRAVEL;
        if(state.getHardness(world,pos)<0 || world.getBlockEntity(pos)!=null || !state.getFluidState().isEmpty()
                || block==Blocks.CRAFTING_TABLE || block instanceof FallingBlock && !loose
                || block instanceof IceBlock || block==Blocks.MAGMA_BLOCK || state.isIn(BlockTags.LOGS)
                || state.isIn(BlockTags.LEAVES) || !Block.isShapeFullCube(state.getCollisionShape(world,pos)))return false;
        for(Direction direction:Direction.values()){
            BlockPos neighbour=pos.offset(direction);
            if(!world.getChunkManager().isChunkLoaded(neighbour.getX()>>4,neighbour.getZ()>>4)
                    || !world.getBlockState(neighbour).getFluidState().isEmpty())return false;
        }
        return true;
    }
    public static boolean candidate(World world,BlockPos target){
        if(!world.getChunkManager().isChunkLoaded(target.getX()>>4,target.getZ()>>4))return false;
        BlockState state=world.getBlockState(target);
        if(state.getBlock()!=Blocks.GRAVEL || !state.getFluidState().isEmpty())return true;
        // Exposed and water-adjacent gravel retain their ordinary approach controllers.
        for(Direction direction:Direction.values()){
            BlockPos neighbour=target.offset(direction);
            if(!world.getChunkManager().isChunkLoaded(neighbour.getX()>>4,neighbour.getZ()>>4))continue;
            BlockState adjacent=world.getBlockState(neighbour);
            if(!adjacent.getFluidState().isEmpty() || adjacent.getCollisionShape(world,neighbour).isEmpty())return true;
        }
        return BuriedMiningAccess.find(target,MAX_COVER,p->clear(world,p),p->safeCover(world,p))!=null;
    }
}
