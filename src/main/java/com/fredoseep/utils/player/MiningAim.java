package com.fredoseep.utils.player;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RayTraceContext;
import net.minecraft.world.World;

/** A thin door may hide the block center while leaving another face point visible. */
public final class MiningAim {
    private MiningAim() {}
    public static BlockHitResult visibleFace(World world,PlayerEntity player,BlockPos target) {
        Vec3d eye=new Vec3d(player.getX(),player.getEyeY(),player.getZ());
        double[] samples={.5,.15,.85};
        for(int axis=0;axis<3;axis++)for(double face:new double[]{.01,.99})
            for(double a:samples)for(double b:samples) {
                double x=axis==0?face:a,y=axis==1?face:(axis==0?a:b),z=axis==2?face:b;
                Vec3d point=new Vec3d(target.getX()+x,target.getY()+y,target.getZ()+z);
                if(eye.squaredDistanceTo(point)>20)continue;
                BlockHitResult hit=world.rayTrace(new RayTraceContext(eye,point,RayTraceContext.ShapeType.OUTLINE,
                        RayTraceContext.FluidHandling.NONE,player));
                if(hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(target))return hit;
            }
        return null;
    }
}
