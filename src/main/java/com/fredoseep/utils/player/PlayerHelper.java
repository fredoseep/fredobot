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
    public static boolean isLineIntersectingBox(net.minecraft.util.math.Vec3d start, net.minecraft.util.math.Vec3d end, net.minecraft.util.math.Box box) {
        double dX = end.getX() - start.getX();
        double dY = end.getY() - start.getY();
        double dZ = end.getZ() - start.getZ();

        double tMin = 0.0;
        double tMax = 1.0;

        // X 轴
        if (Math.abs(dX) < 1.0E-7) {
            if (start.getX() < box.minX || start.getX() > box.maxX) return false;
        } else {
            double t1 = (box.minX - start.getX()) / dX;
            double t2 = (box.maxX - start.getX()) / dX;
            if (t1 > t2) { double temp = t1; t1 = t2; t2 = temp; }
            if (t1 > tMin) tMin = t1;
            if (t2 < tMax) tMax = t2;
            if (tMin > tMax) return false;
        }

        // Y 轴
        if (Math.abs(dY) < 1.0E-7) {
            if (start.getY() < box.minY || start.getY() > box.maxY) return false;
        } else {
            double t1 = (box.minY - start.getY()) / dY;
            double t2 = (box.maxY - start.getY()) / dY;
            if (t1 > t2) { double temp = t1; t1 = t2; t2 = temp; }
            if (t1 > tMin) tMin = t1;
            if (t2 < tMax) tMax = t2;
            if (tMin > tMax) return false;
        }

        // Z 轴
        if (Math.abs(dZ) < 1.0E-7) {
            if (start.getZ() < box.minZ || start.getZ() > box.maxZ) return false;
        } else {
            double t1 = (box.minZ - start.getZ()) / dZ;
            double t2 = (box.maxZ - start.getZ()) / dZ;
            if (t1 > t2) { double temp = t1; t1 = t2; t2 = temp; }
            if (t1 > tMin) tMin = t1;
            if (t2 < tMax) tMax = t2;
            if (tMin > tMax) return false;
        }

        return true;
    }
}
