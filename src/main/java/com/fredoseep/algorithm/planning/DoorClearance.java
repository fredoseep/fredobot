package com.fredoseep.algorithm.planning;

import net.minecraft.util.math.Box;
import java.util.List;

/** Local door panels are thin obstacles, not solid cubes. Sweeps use the standing player's width. */
public final class DoorClearance {
    private DoorClearance() {}
    public static boolean clear(List<Box> panels,double fromX,double fromY,double fromZ,double toX,double toY,double toZ) {
        int samples=Math.max(1,(int)Math.ceil(Math.max(Math.abs(toX-fromX),Math.max(Math.abs(toY-fromY),Math.abs(toZ-fromZ)))/.05));
        for(int i=0;i<=samples;i++) {
            double t=(double)i/samples,x=fromX+(toX-fromX)*t,y=fromY+(toY-fromY)*t,z=fromZ+(toZ-fromZ)*t;
            Box body=new Box(x-.3,y+.01,z-.3,x+.3,y+1.79,z+.3);
            for(Box panel:panels)if(body.intersects(panel))return false;
        }
        return true;
    }
}
