package com.fredoseep.utils.player;

import net.minecraft.block.Block;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RayTraceContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

public class MiningHelper {
    /**
     * 计算可行的挖掘视角：避开遮挡，寻找肉眼可见的方块表面。
     * 优先选择最不需要大幅度转头的那个点，防止视角抽搐。
     *
     * @param player     玩家实体
     * @param targetPos  当前要挖掘的方块坐标
     * @return 返回一个 float[]，包含 [0]:目标Yaw, [1]:目标Pitch
     */
    public static float[] getValidMiningAngle(PlayerEntity player, BlockPos targetPos) {
        World world = player.getEntityWorld();
        Vec3d eyePos = player.getCameraPosVec(1.0F);

        // 1. 生成方块表面探测网格 (每个面 9 个点，6 个面共 54 个点)
        List<Vec3d> testPoints = new ArrayList<>();
        double[] offsets = {0.1D, 0.5D, 0.9D};

        // Y轴面 (上下)
        for (double x : offsets) {
            for (double z : offsets) {
                testPoints.add(new Vec3d(targetPos.getX() + x, targetPos.getY() + 1.0D, targetPos.getZ() + z));
                testPoints.add(new Vec3d(targetPos.getX() + x, targetPos.getY() + 0.0D, targetPos.getZ() + z));
            }
        }
        // Z轴面 (南北)
        for (double x : offsets) {
            for (double y : offsets) {
                testPoints.add(new Vec3d(targetPos.getX() + x, targetPos.getY() + y, targetPos.getZ() + 0.0D));
                testPoints.add(new Vec3d(targetPos.getX() + x, targetPos.getY() + y, targetPos.getZ() + 1.0D));
            }
        }
        // X轴面 (东西)
        for (double y : offsets) {
            for (double z : offsets) {
                testPoints.add(new Vec3d(targetPos.getX() + 0.0D, targetPos.getY() + y, targetPos.getZ() + z));
                testPoints.add(new Vec3d(targetPos.getX() + 1.0D, targetPos.getY() + y, targetPos.getZ() + z));
            }
        }

        float bestYaw = player.yaw;
        float bestPitch = player.pitch;
        double minScore = Double.MAX_VALUE; // 评分越低，甩头幅度越小
        boolean foundVisiblePoint = false;

        // 2. 遍历测试点，进行物理穿透检测 (无视杂草/水)
        for (Vec3d point : testPoints) {
            RayTraceContext context = new RayTraceContext(
                    eyePos, point,
                    RayTraceContext.ShapeType.COLLIDER,
                    RayTraceContext.FluidHandling.NONE,
                    player
            );
            BlockHitResult hitResult = world.rayTrace(context);

            // 如果打到了我们要挖的方块
            if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK && hitResult.getBlockPos().equals(targetPos)) {

                double dx = point.x - eyePos.x;
                double dy = point.y - eyePos.y;
                double dz = point.z - eyePos.z;
                double distanceXZ = Math.sqrt(dx * dx + dz * dz);

                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) Math.toDegrees(Math.atan2(-dy, distanceXZ));

                // 3. 评分逻辑：寻找距离玩家当前视角最近的点
                float deltaYaw = MathHelper.wrapDegrees(yaw - player.yaw);
                float deltaPitch = pitch - player.pitch;
                double score = (deltaYaw * deltaYaw) + (deltaPitch * deltaPitch);

                // 记录最小甩头幅度的视角
                if (score < minScore) {
                    minScore = score;
                    bestYaw = yaw;
                    bestPitch = pitch;
                    foundVisiblePoint = true;
                }
            }
        }

        // 4. 兜底机制：如果整块石头被完全包裹（没找到任何可视点），强行指正中心
        if (!foundVisiblePoint) {
            double dx = (targetPos.getX() + 0.5D) - eyePos.x;
            double dy = (targetPos.getY() + 0.5D) - eyePos.y;
            double dz = (targetPos.getZ() + 0.5D) - eyePos.z;
            double distanceXZ = Math.sqrt(dx * dx + dz * dz);
            bestYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            bestPitch = (float) Math.toDegrees(Math.atan2(-dy, distanceXZ));
        }

        return new float[]{bestYaw, bestPitch};
    }
    public static List<BlockPos> findNearestBlocks(PlayerEntity player, Block targetBlock, int count,int maxRadius) {
        List<BlockPos> result = new ArrayList<>();

        if (count <= 0) return result;

        World world = player.world;
        BlockPos playerPos = player.getBlockPos();

        PriorityQueue<BlockPos> queue = new PriorityQueue<>(
                Comparator.comparingDouble(pos -> pos.getSquaredDistance(playerPos))
        );

        for (int x = -maxRadius; x <= maxRadius; x++) {
            for (int y = -maxRadius; y <= maxRadius; y++) {
                for (int z = -maxRadius; z <= maxRadius; z++) {
                    BlockPos checkPos = playerPos.add(x, y, z);

                    // 剔除正方体边角，确保是一个完美的球形搜索范围
                    if (checkPos.getSquaredDistance(playerPos) > maxRadius * maxRadius) {
                        continue;
                    }

                    // 如果是目标方块，直接扔进优先队列
                    if (world.getBlockState(checkPos).getBlock() == targetBlock) {
                        queue.add(checkPos);
                    }
                }
            }
        }

        // 从优先队列中不断取出“最近的”方块，直到拿够所需数量，或者队列被拿空
        while (!queue.isEmpty() && result.size() < count) {
            result.add(queue.poll());
        }

        return result;
    }
}
