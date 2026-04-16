package com.fredoseep.utils.player;

import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.RayTraceContext;
import net.minecraft.world.World;

import java.util.*;

public class MiningHelper {
    public static List<BlockPos> blockToMine = new ArrayList<>();

    /**
     * 计算可行的挖掘视角：避开遮挡，寻找肉眼可见的方块表面。
     * 优先选择最不需要大幅度转头的那个点，防止视角抽搐。
     *
     * @param player    玩家实体
     * @param targetPos 当前要挖掘的方块坐标
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

    public static List<BlockPos> findNearestBlocks(PlayerEntity player, Set<Block> targetBlocks, int totalCount,int maxRadius) {
        List<BlockPos> result = new ArrayList<>();
        if (totalCount <= 0 || targetBlocks == null || targetBlocks.isEmpty()) return result;

        World world = player.world;
        BlockPos playerPos = player.getBlockPos();
        PriorityQueue<BlockPos> queue = new PriorityQueue<>(
                Comparator.comparingDouble(pos -> pos.getSquaredDistance(playerPos))
        );


        for (int x = -maxRadius; x <= maxRadius; x++) {
            for (int y = -maxRadius; y <= maxRadius; y++) {
                for (int z = -maxRadius; z <= maxRadius; z++) {
                    BlockPos checkPos = playerPos.add(x, y, z);

                    if (checkPos.getSquaredDistance(playerPos) > maxRadius * maxRadius) continue;

                    // 【核心变化】：检查当前方块是否存在于我们的“目标池”中
                    Block currentBlock = world.getBlockState(checkPos).getBlock();
                    if (targetBlocks.contains(currentBlock)) {
                        queue.add(checkPos);
                    }
                }
            }
        }

        while (!queue.isEmpty() && result.size() < totalCount) {
            result.add(queue.poll());
        }

        return result;
    }
    public static List<BlockPos> findNearestBlocks(PlayerEntity player, Map<Block, Integer> targetCounts,int maxRadius) {
        List<BlockPos> result = new ArrayList<>();
        if (targetCounts == null || targetCounts.isEmpty()) return result;

        // 复制一份配额表（剩余需求表），防止修改外部传入的 Map
        Map<Block, Integer> remainingCounts = new HashMap<>(targetCounts);

        // 计算我们总共需要找多少个方块，如果总数是 0 直接结束
        int totalNeeded = remainingCounts.values().stream().mapToInt(Integer::intValue).sum();
        if (totalNeeded <= 0) return result;

        World world = player.world;
        BlockPos playerPos = player.getBlockPos();
        PriorityQueue<BlockPos> queue = new PriorityQueue<>(
                Comparator.comparingDouble(pos -> pos.getSquaredDistance(playerPos))
        );

        // 依然是单次雷达扫描
        for (int x = -maxRadius; x <= maxRadius; x++) {
            for (int y = -maxRadius; y <= maxRadius; y++) {
                for (int z = -maxRadius; z <= maxRadius; z++) {
                    BlockPos checkPos = playerPos.add(x, y, z);

                    if (checkPos.getSquaredDistance(playerPos) > maxRadius * maxRadius) continue;

                    Block currentBlock = world.getBlockState(checkPos).getBlock();
                    // 只有当这个方块在我们的清单里，我们才把它塞进队列
                    if (remainingCounts.containsKey(currentBlock)) {
                        queue.add(checkPos);
                    }
                }
            }
        }

        // ==========================================
        // 【核心分配逻辑】：按距离从小到大拿取，并检查各个方块的“配额”
        // ==========================================
        while (!queue.isEmpty() && totalNeeded > 0) {
            BlockPos pos = queue.poll();
            Block block = world.getBlockState(pos).getBlock();

            // 检查这个方块我们还需要多少个
            int needed = remainingCounts.getOrDefault(block, 0);

            if (needed > 0) {
                result.add(pos); // 确实还需要，加入结果！
                remainingCounts.put(block, needed - 1); // 这种方块的配额 -1
                totalNeeded--; // 总需求 -1
            }
            // 如果 needed 已经是 0 了，说明这种方块找够了，直接丢弃（循环进入下一次），继续找别的。
        }

        return result;
    }
    /**
     * 辅助方法：以玩家为中心，快速扫描四周，找到绝对意义上的“陆地表面”
     */
    public static BlockPos findNearestLand(PlayerEntity player) {
        net.minecraft.world.World world = MinecraftClient.getInstance().world;
        BlockPos pPos = player.getBlockPos();

        for (int r = 1; r <= 60; r++) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (Math.abs(x) != r && Math.abs(z) != r) continue;

                    int targetX = pPos.getX() + x;
                    int targetZ = pPos.getZ() + z;

                    if (!world.getChunkManager().isChunkLoaded(targetX >> 4, targetZ >> 4)) {
                        continue;
                    }

                    BlockPos checkPos = new BlockPos(targetX, 0, targetZ);

                    // 【极速优化】：直接读取原版的高度图（Heightmap），瞬间找到该坐标最顶层的方块
                    BlockPos topPos = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, checkPos).down();

                    // 只要这个群系最高处的方块不是水，那它就是露天的沙滩/草地/石头！
                    if (!world.getBlockState(topPos).getMaterial().isLiquid()) {
                        return topPos; // 找到陆地，返回目标坐标
                    }
                }
            }
        }
        return null;
    }
}
