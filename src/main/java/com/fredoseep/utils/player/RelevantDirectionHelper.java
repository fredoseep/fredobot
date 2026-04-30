package com.fredoseep.utils.player;

import com.fredoseep.behave.MovementController;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class RelevantDirectionHelper {
    public enum RelevantDirection {
        EAST(135, 90), WEST(-45, -90), SOUTH(-135, 180), NORTH(45, 0);

        private final int speedbridgeYaw;
        private final int adjustPostureForSpeedbridgingYaw;

        RelevantDirection(int speedbridgeYaw, int adjustPostureForSpeedbridgingYaw) {
            this.speedbridgeYaw = speedbridgeYaw;
            this.adjustPostureForSpeedbridgingYaw = adjustPostureForSpeedbridgingYaw;
        }

        public int getSpeedbridgeYaw() {
            return speedbridgeYaw;
        }

        public int getAdjustPostureForSpeedbridgingYaw() {
            return adjustPostureForSpeedbridgingYaw;
        }
    }

    public static RelevantDirection getRelevantDirection(PlayerEntity player, BlockPos pos) {
        if (Math.abs(player.getX() - pos.getX() - 0.5) >= Math.abs(player.getZ() - pos.getZ() - 0.5)) {
            if (pos.getX() + 0.5 >= player.getX()) return RelevantDirection.EAST;
            else return RelevantDirection.WEST;
        } else {
            if (pos.getZ() + 0.5 >= player.getZ()) return RelevantDirection.SOUTH;
            else return RelevantDirection.NORTH;
        }
    }

    public static double getReversedYawFromBlock(PlayerEntity player, BlockPos pos) {
        double blockX = pos.getX() + 0.5;
        double blockZ = pos.getZ() + 0.5;
        double deltaX = player.getX() - blockX;
        double deltaZ = player.getZ() - blockZ;
        return Math.toDegrees(Math.atan2(-deltaX, deltaZ));
    }

    public static Direction getDirectionBetween(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        return Direction.getFacing(dx, dy, dz);
    }

    public static Direction[] getIrrelevantDirections(BlockPos from, BlockPos to){
        Direction relevantDirection = getDirectionBetween(from,to);
        if(relevantDirection == Direction.EAST || relevantDirection == Direction.WEST){
            return new Direction[]{Direction.SOUTH,Direction.NORTH};
        }
        else return new Direction[]{Direction.EAST,Direction.WEST};
    }

    public static float get3DPlayerBlockDistance(PlayerEntity player, BlockPos pos) {
        if (player == null) return 0;
        if (pos == null) return 0;
        float xDis = (float) (player.getX() - pos.getX() - 0.5);
        float yDis = (float) (player.getY() - pos.getY() - 0.5);
        float zDis = (float) (player.getZ() - pos.getZ() - 0.5);
        return (float) Math.sqrt(xDis * xDis + yDis * yDis + zDis * zDis);
    }
    /**
     * 智能防碰撞放门逻辑封装
     * @return boolean true表示放置成功，false表示视角尚未转正，需要等待下一个Tick
     */
    public static boolean placeDoorSmartly(MinecraftClient client, PlayerEntity player, BlockPos doorPlacePos, int doorInventorySlot) {
        double dx = player.getX() - (doorPlacePos.getX() + 0.5);
        double dz = player.getZ() - (doorPlacePos.getZ() + 0.5);
        float placeYaw = player.yaw;

        // 根据象限计算最空旷的一侧
        if (Math.abs(dx) > Math.abs(dz)) {
            if (dx > 0) placeYaw = -90f; // 玩家偏东，门放西侧
            else placeYaw = 90f;         // 玩家偏西，门放东侧
        } else {
            if (dz > 0) placeYaw = 0f;   // 玩家偏南，门放北侧
            else placeYaw = 180f;        // 玩家偏北，门放南侧
        }

        // 强行看向目标方向和正下方 (Pitch 90)
        MovementController.setLookDirection(player, placeYaw, 90f);

        // 如果视角还没转到位，先拦截
        if (Math.abs(MathHelper.wrapDegrees(player.yaw - placeYaw)) > 5.0f ||
                Math.abs(player.pitch - 90f) > 5.0f) {
            return false;
        }

        // 视角完美到位，切出门，强制放在快捷栏第8格并手持
        InventoryHelper.moveItemToHotbar(client, player, doorInventorySlot, 8);
        player.inventory.selectedSlot = 8;

        BlockPos foundation = doorPlacePos.down();
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(foundation), Direction.UP, foundation, false);
        client.interactionManager.interactBlock((net.minecraft.client.network.ClientPlayerEntity) player, client.world, net.minecraft.util.Hand.MAIN_HAND, hit);

        System.out.println("FredoBot [智能防碰撞放门]: 偏置 dx=" + String.format("%.2f", dx) + " dz=" + String.format("%.2f", dz));
        return true;
    }

    public static boolean isValidHitResult(PlayerEntity player, World world, BlockHitResult hitResult) {
        if (player == null || world == null || hitResult == null) {
            return false;
        }
        if (hitResult.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        BlockPos hitBlockPos = hitResult.getBlockPos();
        BlockState hitState = world.getBlockState(hitBlockPos);
        if (hitState.getMaterial().isReplaceable()) {
            return false;
        }
        Vec3d hitPoint = hitResult.getPos();
        Vec3d eyePos = player.getCameraPosVec(1.0F);
        return !(eyePos.squaredDistanceTo(hitPoint) > 20.25);
    }
}
