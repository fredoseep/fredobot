package com.fredoseep.utils.player;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.List;

/** Rays end just inside each outline box, including small non-cubic blocks. */
public final class MiningShapePoints {
    private MiningShapePoints(){}
    public static List<Vec3d> inside(Box box,BlockPos p){
        double x=(box.minX+box.maxX)*.5+p.getX(),y=(box.minY+box.maxY)*.5+p.getY(),z=(box.minZ+box.maxZ)*.5+p.getZ();
        double ex=Math.min(.001,(box.maxX-box.minX)*.1),ey=Math.min(.001,(box.maxY-box.minY)*.1),ez=Math.min(.001,(box.maxZ-box.minZ)*.1);
        return List.of(new Vec3d(x,y,z),new Vec3d(p.getX()+box.minX+ex,y,z),new Vec3d(p.getX()+box.maxX-ex,y,z),
                new Vec3d(x,p.getY()+box.minY+ey,z),new Vec3d(x,p.getY()+box.maxY-ey,z),
                new Vec3d(x,y,p.getZ()+box.minZ+ez),new Vec3d(x,y,p.getZ()+box.maxZ-ez));
    }
}
