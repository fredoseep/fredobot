package com.fredoseep.motion.sim;

import com.fredoseep.motion.LocalGroundSearch;
import com.fredoseep.motion.MotionEnvironmentPolicy;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.EntityPose;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.World;

/** Scope guard for this first dry-ground simulator. Unmodelled environments are rejected explicitly. */
public final class GroundSafety {
    private GroundSafety() {}
    public static void requireStart(ClientPlayerEntity player) {
        if(!player.isOnGround() || !player.isAlive() || player.hasVehicle() || player.isTouchingWater()
                || player.isInLava() || player.isFallFlying() || player.isClimbing() || player.isUsingItem()
                || player.isSpectator() || player.abilities.flying || player.hasNoGravity()
                || player.getPose()!=EntityPose.STANDING || !player.getStatusEffects().isEmpty())
            throw new IllegalArgumentException("需要干燥地面、站立、无状态效果、无飞行/载具/物品使用的起点");
        requireArea(player.world,player,player.getBoundingBox());
    }
    public static void requireGoal(ClientPlayerEntity player,LocalGroundSearch.Goal goal) {
        requireGoal(player,goal,false);
    }
    public static void requireGoal(ClientPlayerEntity player,LocalGroundSearch.Goal goal,boolean allowVines) {
        Box body=new Box(goal.x()-.3,goal.y(),goal.z()-.3,goal.x()+.3,goal.y()+1.8,goal.z()+.3);
        requireArea(player.world,player,body,allowVines);
        BlockPos floor=new BlockPos(goal.x(),goal.y()-.001,goal.z());
        BlockState support=player.world.getBlockState(floor);
        if(!Block.isShapeFullCube(support.getCollisionShape(player.world,floor)))
            throw new IllegalArgumentException("目标支撑不是完整方块："+Registry.BLOCK.getId(support.getBlock())+" @ "+floor.toShortString());
        if(!player.world.doesNotCollide(player,body))
            throw new IllegalArgumentException("目标站位存在碰撞 @ "+new BlockPos(goal.x(),goal.y(),goal.z()).toShortString());
    }
    public static void requireArea(World world,ClientPlayerEntity realPlayer,Box box) {
        requireArea(world,realPlayer,box,false);
    }
    public static void requireArea(World world,ClientPlayerEntity realPlayer,Box box,boolean allowVines) {
        if(!world.getEntities(realPlayer,box.expand(.2),e -> e.isAlive() && !(e instanceof ItemEntity) && !(e instanceof ExperienceOrbEntity)).isEmpty())
            throw new IllegalArgumentException("轨迹附近有实体，第一批地面模拟暂不处理实体碰撞");
        for(int x=(int)Math.floor(box.minX-.01);x<=(int)Math.floor(box.maxX+.01);x++)
            for(int z=(int)Math.floor(box.minZ-.01);z<=(int)Math.floor(box.maxZ+.01);z++)
                for(int y=(int)Math.floor(box.minY-.08);y<=(int)Math.floor(box.maxY+.01);y++) {
                    if(!world.getChunkManager().isChunkLoaded(x>>4,z>>4))throw new IllegalArgumentException("轨迹区块尚未加载");
                    BlockPos p=new BlockPos(x,y,z);BlockState state=world.getBlockState(p);
                    String id=Registry.BLOCK.getId(state.getBlock()).toString();
                    if(MotionEnvironmentPolicy.reject(id,!state.getFluidState().isEmpty(),allowVines))
                        throw new IllegalArgumentException("暂不支持的轨迹环境："+id+" @ "+p.toShortString());
                }
    }
}
