package com.fredoseep.algorithm.planning;

import net.minecraft.util.math.BlockPos;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.concurrent.CancellationException;

/** Conservative 3D swept body, including turns and vertical movement. */
public final class DiveClearance {
    private DiveClearance() {}
    public static boolean clear(double x0,double y0,double z0,double x1,double y1,double z1,
                                Predicate<BlockPos> clearWater,BooleanSupplier cancelled) {
        return clear(x0,y0,z0,x1,y1,z1,1.8,clearWater,cancelled);
    }
    public static boolean clear(double x0,double y0,double z0,double x1,double y1,double z1,double height,
                                Predicate<BlockPos> clearWater,BooleanSupplier cancelled) {
        return clear(x0,y0,z0,x1,y1,z1,height,.36,clearWater,cancelled);
    }
    /** Runtime uses the actual body radius; planning may retain its extra safety margin. */
    public static boolean clear(double x0,double y0,double z0,double x1,double y1,double z1,double height,double radius,
                                Predicate<BlockPos> clearWater,BooleanSupplier cancelled) {
        if(!Double.isFinite(height) || height<=0 || !Double.isFinite(radius) || radius<=0)return false;
        double length=Math.hypot(Math.hypot(x1-x0,z1-z0),y1-y0);
        int samples=Math.max(1,(int)Math.ceil(length/.1));
        for(int i=0;i<=samples;i++) {
            if((i&63)==0 && cancelled.getAsBoolean())throw new CancellationException();
            double t=(double)i/samples,x=x0+(x1-x0)*t,y=y0+(y1-y0)*t,z=z0+(z1-z0)*t;
            for(int bx=(int)Math.floor(x-radius+1e-7);bx<=(int)Math.floor(x+radius-1e-7);bx++)
                for(int bz=(int)Math.floor(z-radius+1e-7);bz<=(int)Math.floor(z+radius-1e-7);bz++)
                    for(int by=(int)Math.floor(y);by<=(int)Math.floor(y+height-1e-7);by++)
                        if(!clearWater.test(new BlockPos(bx,by,bz)))return false;
        }
        return true;
    }
}
