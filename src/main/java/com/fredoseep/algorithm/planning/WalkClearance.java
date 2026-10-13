package com.fredoseep.algorithm.planning;

import net.minecraft.util.math.BlockPos;
import java.util.function.*;
import java.util.concurrent.CancellationException;

/** Conservative swept standing footprint: body clearance and full support along a level segment. */
public final class WalkClearance {
    private WalkClearance() {}
    public static boolean clear(double fromX,double fromZ,double toX,double toZ,int feetY,
                                Predicate<BlockPos> clearCell,Predicate<BlockPos> support,BooleanSupplier cancelled) {
        int samples=Math.max(1,(int)Math.ceil(Math.hypot(toX-fromX,toZ-fromZ)/.1));
        // .30 player half-width + .06 margin exceeds the half-sample spacing.
        for(int i=0;i<=samples;i++) {
            if((i&63)==0 && cancelled.getAsBoolean())throw new CancellationException();
            double t=(double)i/samples,x=fromX+(toX-fromX)*t,z=fromZ+(toZ-fromZ)*t;
            for(int bx=(int)Math.floor(x-.36);bx<=(int)Math.floor(x+.36);bx++)
                for(int bz=(int)Math.floor(z-.36);bz<=(int)Math.floor(z+.36);bz++) {
                    BlockPos p=new BlockPos(bx,feetY,bz);
                    if(!clearCell.test(p) || !clearCell.test(p.up()) || !support.test(p.down()))return false;
                }
        }
        return true;
    }
}
