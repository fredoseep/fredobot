package com.fredoseep.utils.player;

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

    public static float get3DPlayerBlockDistance(PlayerEntity player, BlockPos pos) {
        if (player == null) return 0;
        if (pos == null) return 0;
        float xDis = (float) (player.getX() - pos.getX() - 0.5);
        float yDis = (float) (player.getY() - pos.getY() - 0.5);
        float zDis = (float) (player.getZ() - pos.getZ() - 0.5);
        return (float) Math.sqrt(xDis * xDis + yDis * yDis + zDis * zDis);
    }
}
