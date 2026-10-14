package com.fredoseep.algorithm.planning;

import net.minecraft.util.math.BlockPos;
import java.util.function.Predicate;

/** Depth belongs to the whole current water column, not the remaining gap below moving feet. */
public final class SurfaceWaterDepth {
    private SurfaceWaterDepth() {}
    public static boolean canImmerse(double x,double y,double z,Predicate<BlockPos> water) {
        int minX=(int)Math.floor(x-.3),maxX=(int)Math.floor(x+.3-1e-7);
        int minZ=(int)Math.floor(z-.3),maxZ=(int)Math.floor(z+.3-1e-7);
        for(int bx=minX;bx<=maxX;bx++)for(int bz=minZ;bz<=maxZ;bz++) {
            int level=(int)Math.floor(y);
            if(!water.test(new BlockPos(bx,level,bz))) {
                level--;
                if(!water.test(new BlockPos(bx,level,bz)))return false;
            }
            // The top of this connected column stays fixed while the player immerses.
            for(int i=0;i<16 && water.test(new BlockPos(bx,level+1,bz));i++)level++;
            if(!water.test(new BlockPos(bx,level-1,bz)))return false;
        }
        return true;
    }
}
