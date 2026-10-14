package com.fredoseep.behave;

import com.fredoseep.algorithm.SimplePathfinder;
import com.fredoseep.algorithm.planning.SurfaceBoatRoute;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.BlockPos;
import net.minecraft.tag.FluidTags;
import net.minecraft.world.World;
import java.util.List;

/** Look ahead like the original boat controller, but sweep the whole hull before cutting a turn. */
public final class BoatPathFollower {
    public record Aim(Vec3d point,double stoppingDistance) {}
    private BoatPathFollower() {}
    public static Aim aim(World world,BoatEntity boat,List<SimplePathfinder.Node> path,int index) {
        Vec3d origin=boat.getPos(),best=origin;
        double stop=Double.POSITIVE_INFINITY;
        for(int i=index;i<Math.min(path.size(),index+32);i++) {
            var node=path.get(i);
            if(!SurfaceBoatRoute.allowed(world,node)) break;
            Vec3d point=new Vec3d(node.pos.getX()+.5,origin.y,node.pos.getZ()+.5);
            double dx=point.x-origin.x,dz=point.z-origin.z,distance=Math.hypot(dx,dz);
            boolean end=i==path.size()-1 || !SurfaceBoatRoute.allowed(world,path.get(i+1));
            if(end) stop=distance;
            if(distance>8) point=new Vec3d(origin.x+dx*8/distance,origin.y,origin.z+dz*8/distance);
            if(!clear(world,boat,point)) {
                stop=Math.min(stop,Math.hypot(best.x-origin.x,best.z-origin.z));break;
            }
            best=point;if(distance>8 || end) break;
        }
        return new Aim(best,stop);
    }
    private static boolean clear(World world,BoatEntity boat,Vec3d target) {
        Vec3d from=boat.getPos();double distance=Math.hypot(target.x-from.x,target.z-from.z);
        int samples=Math.max(1,(int)Math.ceil(distance/.25));
        var hull=boat.getBoundingBox().expand(.05,0,.05);
        for(int i=1;i<=samples;i++) {
            double fraction=(double)i/samples;
            double x=from.x+(target.x-from.x)*fraction,z=from.z+(target.z-from.z)*fraction;
            BlockPos water=new BlockPos(x,from.y,z);
            if(!world.getBlockState(water).getFluidState().isIn(FluidTags.WATER)
                    && !world.getBlockState(water.down()).getFluidState().isIn(FluidTags.WATER))return false;
            if(!world.doesNotCollide(boat,hull.offset(x-from.x,0,z-from.z)))return false;
        }
        return true;
    }
}
