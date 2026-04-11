package com.fredoseep.utils;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

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
        // 1. 获取方块中心的绝对坐标 (加 0.5D 是为了精确到方块的几何中心)
        double blockX = pos.getX() + 0.5;
        double blockZ = pos.getZ() + 0.5;

        // 2. 计算从“方块”指向“玩家”的向量差 (终点 - 起点)
        double deltaX = player.getX() - blockX;
        double deltaZ = player.getZ() - blockZ;

        // 3. 将向量转换为 Minecraft 的 Yaw 角度
        // 注意：原版 MC 视角系中，需要对 X 轴取反来适配顺时针旋转的角度规则

        return Math.toDegrees(Math.atan2(-deltaX, deltaZ));
    }

    public static Direction getDirectionBetween(BlockPos from, BlockPos to) {
        // 1. 算出目标方块相对于起点方块的向量差
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();

        // 2. 让 Minecraft 引擎自己算出这个向量最贴合的方向
        return Direction.getFacing(dx, dy, dz);
    }

    public static float get3DPlayerBlockDistance(PlayerEntity player, BlockPos pos) {
        if (player == null) return 0;
        if (pos == null) return 0;
        float xDis = (float) (player.getX() - pos.getX() - 0.5);
        float yDis = (float) (player.getY() - pos.getY() - 0.5);
        float zDis = (float) (player.getZ() - pos.getZ() - 0.5);
        return (float) Math.sqrt(xDis * xDis + yDis * yDis + zDis * zDis);
    }
}
