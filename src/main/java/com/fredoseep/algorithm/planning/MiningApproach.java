package com.fredoseep.algorithm.planning;

import net.minecraft.util.math.BlockPos;
import java.util.function.Predicate;

/** An interaction target is not a feet destination. Callbacks are evaluated only on the client. */
public final class MiningApproach {
    private MiningApproach() {}
    public static BlockPos select(BlockPos target,BlockPos start,double eyeHeight,Predicate<BlockPos> standable,Predicate<BlockPos> visible) {
        BlockPos best=null;double score=Double.POSITIVE_INFINITY;
        for(int dx=-4;dx<=4;dx++)for(int dy=-5;dy<=1;dy++)for(int dz=-4;dz<=4;dz++) {
            BlockPos p=target.add(dx,dy,dz);
            double ey=target.getY()+.5-(p.getY()+eyeHeight);
            // Leave a margin below the controller's squared reach threshold of 20.
            if(dx*dx+dz*dz+ey*ey>16 || !standable.test(p) || !visible.test(p))continue;
            double x=p.getX()-start.getX(),y=p.getY()-start.getY(),z=p.getZ()-start.getZ();
            double distance=x*x+y*y+z*z;
            if(distance<score){score=distance;best=p;}
        }
        return best;
    }
}
