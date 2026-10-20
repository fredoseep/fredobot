package com.fredoseep.utils.player;

import net.minecraft.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Reject drowned/buried ship timbers while keeping exposed trunks, branches and 2x2 trees. */
public final class NaturalTreeLog {
    private NaturalTreeLog(){}
    public static boolean allowed(World world,BlockPos pos){
        if(!world.getBlockState(pos).isIn(BlockTags.LOGS))return true;
        BlockPos top=pos;
        for(int i=0;i<128;i++,top=top.up()){
            if(top.getY()>255 || !world.getChunkManager().isChunkLoaded(top.getX()>>4,top.getZ()>>4))return false;
            var state=world.getBlockState(top);
            if(!state.getFluidState().isEmpty())return false;
            if(state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.LEAVES))continue;
            if(!state.isAir() && !state.getMaterial().isReplaceable())return false;
            BlockPos root=pos;
            for(int down=0;down<128 && root.getY()>0 && world.getBlockState(root.down()).isIn(BlockTags.LOGS);down++)root=root.down();
            // Dry ship masts may reach above the sea, but are rooted in plank decks.
            var base=world.getBlockState(root.down());
            return !base.isIn(BlockTags.PLANKS) && base.getFluidState().isEmpty();
        }
        return false;
    }
}
