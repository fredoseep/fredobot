package com.fredoseep.utils.player;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RayTraceContext;
import net.minecraft.world.World;
import java.util.function.Predicate;

/** A thin door may hide the block center while leaving another face point visible. */
public final class MiningAim {
    private MiningAim() {}
    public static BlockHitResult visibleFace(World world,PlayerEntity player,BlockPos target) {
        return face(world,player,target,RayTraceContext.ShapeType.OUTLINE);
    }
    /** Collision rays pass through aquatic plants but still stop at doors and solid cover. */
    public static BlockHitResult gravelFace(World world,PlayerEntity player,BlockPos target){
        return face(world,player,target,RayTraceContext.ShapeType.COLLIDER);
    }
    /** Inspect actual first collisions, so a covered target is not automatically blamed on the door. */
    public static BlockHitResult blockingFace(World world,PlayerEntity player,BlockPos target,Predicate<BlockPos> accepts){
        Vec3d eye=new Vec3d(player.getX(),player.getEyeY(),player.getZ());
        double[] samples={.5,.15,.85};
        for(int axis=0;axis<3;axis++)for(double face:new double[]{.01,.99})
            for(double a:samples)for(double b:samples){
                double x=axis==0?face:a,y=axis==1?face:(axis==0?a:b),z=axis==2?face:b;
                Vec3d point=new Vec3d(target.getX()+x,target.getY()+y,target.getZ()+z);
                if(eye.squaredDistanceTo(point)>20)continue;
                BlockHitResult hit=world.rayTrace(new RayTraceContext(eye,point,RayTraceContext.ShapeType.COLLIDER,
                        RayTraceContext.FluidHandling.NONE,player));
                if(hit.getType()==HitResult.Type.BLOCK && !hit.getBlockPos().equals(target)
                        && eye.squaredDistanceTo(hit.getPos())<=20 && accepts.test(hit.getBlockPos()))return hit;
            }
        return null;
    }
    private static BlockHitResult face(World world,PlayerEntity player,BlockPos target,RayTraceContext.ShapeType shape){
        Vec3d eye=new Vec3d(player.getX(),player.getEyeY(),player.getZ());
        double[] samples={.5,.15,.85};
        for(int axis=0;axis<3;axis++)for(double face:new double[]{.01,.99})
            for(double a:samples)for(double b:samples) {
                double x=axis==0?face:a,y=axis==1?face:(axis==0?a:b),z=axis==2?face:b;
                Vec3d point=new Vec3d(target.getX()+x,target.getY()+y,target.getZ()+z);
                if(eye.squaredDistanceTo(point)>20)continue;
                BlockHitResult hit=world.rayTrace(new RayTraceContext(eye,point,shape,
                        RayTraceContext.FluidHandling.NONE,player));
                if(hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(target))return hit;
            }
        return null;
    }
}
