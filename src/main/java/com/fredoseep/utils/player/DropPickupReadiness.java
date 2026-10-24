package com.fredoseep.utils.player;

import net.minecraft.entity.ItemEntity;
import net.minecraft.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

/** Blast items must settle onto collision support before freezing their position for planning. */
public final class DropPickupReadiness {
    private DropPickupReadiness(){}
    public static boolean ready(World world,ItemEntity item){
        if(!item.isAlive())return false;
        // Water items can float or sink without ever touching a solid floor.
        if(item.isTouchingWater() || world.getFluidState(item.getBlockPos()).isIn(FluidTags.WATER))return true;
        if(Math.abs(item.getVelocity().y)>.08)return false;
        Box body=item.getBoundingBox();
        Box feet=new Box(body.minX,body.minY-.06,body.minZ,body.maxX,body.minY+.001,body.maxZ);
        BlockPos origin=item.getBlockPos();
        for(int x=-1;x<=1;x++)for(int y=-2;y<=0;y++)for(int z=-1;z<=1;z++){
            BlockPos p=origin.add(x,y,z);
            if(!world.getChunkManager().isChunkLoaded(p.getX()>>4,p.getZ()>>4))continue;
            for(Box support:world.getBlockState(p).getCollisionShape(world,p).getBoundingBoxes())
                if(feet.intersects(support.offset(p.getX(),p.getY(),p.getZ())))return true;
        }
        return false;
    }
}
