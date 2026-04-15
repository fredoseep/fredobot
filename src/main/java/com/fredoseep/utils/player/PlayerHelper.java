package com.fredoseep.utils.player;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class PlayerHelper {
    public static boolean isDrivingBoat(PlayerEntity player) {
        if (player == null) return false;
        return player.hasVehicle() && player.getVehicle() instanceof BoatEntity;
    }
    public static float[] getLookAngles(PlayerEntity player, int x, int y, int z) {
        if (player == null) {
            return new float[]{0.0f, 0.0f};
        }

        double eyeX = player.getX();
        double eyeY = player.getY() + player.getStandingEyeHeight();
        double eyeZ = player.getZ();

        double targetX = x + 0.5D;
        double targetY = y + 0.5D;
        double targetZ = z + 0.5D;

        double dx = targetX - eyeX;
        double dy = targetY - eyeY;
        double dz = targetZ - eyeZ;

        double diffXZ = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));

        float pitch = (float) Math.toDegrees(Math.atan2(-dy, diffXZ));

        return new float[]{
                MathHelper.wrapDegrees(yaw),
                MathHelper.wrapDegrees(pitch)
        };
    }
    public static boolean isNear(PlayerEntity player, BlockPos targetPos, double radius) {
        return player.squaredDistanceTo(Vec3d.ofCenter(targetPos)) <= (radius * radius);
    }
}
