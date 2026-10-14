package com.fredoseep.utils.player;

import com.fredoseep.behave.MovementController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.tag.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RayTraceContext;

/** Retains the original 1x1 underwater-shaft roof strategy for the new executors. */
public final class ShaftRoof {
    public enum Result { NOT_NEEDED, WAITING, NO_MATERIAL }
    private ShaftRoof() {}

    public static Result seal(MinecraftClient client, PlayerEntity player) {
        var world=client.world;
        BlockPos feet=player.getBlockPos();
        BlockPos roof=new BlockPos(feet.getX(), Math.max(feet.getY()+2,
                (int)Math.ceil(player.getBoundingBox().maxY)), feet.getZ());
        if(!world.getBlockState(roof).getFluidState().isIn(FluidTags.WATER)) return Result.NOT_NEEDED;
        for(Direction direction:new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST}) {
            if(!world.getBlockState(feet.offset(direction)).getMaterial().isSolid()) return Result.NOT_NEEDED;
        }
        boolean supportFound=false;
        for(Direction direction:new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST,Direction.UP}) {
            BlockPos support=roof.offset(direction);
            if(world.getBlockState(support).getMaterial().isReplaceable()
                    || world.getBlockState(support).getCollisionShape(world,support).isEmpty()) continue;
            supportFound=true;
            if(!InventoryHelper.selectBuildingBlock(player,false)) return Result.NO_MATERIAL;
            client.interactionManager.cancelBlockBreaking();
            Direction face=direction.getOpposite();
            Vec3d point=new Vec3d(support.getX()+.5+face.getOffsetX()*.5,
                    support.getY()+.5+face.getOffsetY()*.5,support.getZ()+.5+face.getOffsetZ()*.5);
            Vec3d eye=new Vec3d(player.getX(),player.getEyeY(),player.getZ());
            if(eye.squaredDistanceTo(point)>20.25) continue;
            var hit=world.rayTrace(new RayTraceContext(eye,point,RayTraceContext.ShapeType.OUTLINE,
                    RayTraceContext.FluidHandling.NONE,player));
            if(hit.getType()!=HitResult.Type.BLOCK || !hit.getBlockPos().equals(support)) continue;
            double dx=point.x-eye.x,dy=point.y-eye.y,dz=point.z-eye.z;
            MovementController.setLookDirection(player,(float)Math.toDegrees(Math.atan2(-dx,dz)),
                    (float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz))));
            client.interactionManager.interactBlock(client.player,world,Hand.MAIN_HAND,
                    new BlockHitResult(point,face,support,false));
            System.out.println("FredoBot: SHAFT_ROOF placing="+roof.toShortString());
            return Result.WAITING;
        }
        // At the first shaft layer the roof may still be in open water with no wall face.
        // Descend until a valid wall exists; do not wait forever for an impossible placement.
        return supportFound?Result.WAITING:Result.NOT_NEEDED;
    }
}
