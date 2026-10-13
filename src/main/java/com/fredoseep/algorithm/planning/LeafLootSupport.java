package com.fredoseep.algorithm.planning;

import net.minecraft.util.math.BlockPos;
import java.util.function.Predicate;

/** Tests the item's whole contact footprint, including items straddling a leaf edge. */
public final class LeafLootSupport {
    private LeafLootSupport() {}
    public static BlockPos find(double minX,double maxX,double minY,double minZ,double maxZ,Predicate<BlockPos> leaves) {
        int y=(int)Math.floor(minY-.01);
        for(int x=(int)Math.floor(minX);x<=(int)Math.floor(maxX-1e-7);x++)
            for(int z=(int)Math.floor(minZ);z<=(int)Math.floor(maxZ-1e-7);z++) {
                BlockPos p=new BlockPos(x,y,z);if(leaves.test(p))return p;
            }
        return null;
    }
}
