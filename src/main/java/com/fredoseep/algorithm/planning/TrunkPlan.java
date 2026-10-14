package com.fredoseep.algorithm.planning;

import net.minecraft.util.math.BlockPos;
import java.util.*;
import java.util.function.Predicate;

/** Vertical trunk columns only; supports ordinary and 2x2 trunks without following branches. */
public final class TrunkPlan {
    private TrunkPlan() {}
    public static List<BlockPos> find(BlockPos seed,Predicate<BlockPos> log) {
        BlockPos root=seed;
        for(int i=0;i<64 && log.test(root.down());i++)root=root.down();
        List<BlockPos> bases=new ArrayList<>();bases.add(root);
        int minX=root.getX(),maxX=minX,minZ=root.getZ(),maxZ=minZ;
        for(int index=0;index<bases.size();index++)for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
            BlockPos p=bases.get(index).add(d[0],0,d[1]);
            if(bases.contains(p) || !log.test(p))continue;
            int x0=Math.min(minX,p.getX()),x1=Math.max(maxX,p.getX()),z0=Math.min(minZ,p.getZ()),z1=Math.max(maxZ,p.getZ());
            if(x1-x0>1 || z1-z0>1)continue;
            bases.add(p);minX=x0;maxX=x1;minZ=z0;maxZ=z1;
        }
        List<BlockPos> result=new ArrayList<>();
        for(int y=0;y<64;y++)for(BlockPos base:bases) {
            BlockPos p=base.up(y);
            boolean continuous=y==0 || result.contains(p.down());
            if(continuous && log.test(p))result.add(p);
        }
        return result;
    }
}
