package com.fredoseep.utils.player;

import com.fredoseep.behave.MiscController;
import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.GlobalExecutor;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RayTraceContext;
import net.minecraft.world.World;

import java.util.*;
import java.util.function.Predicate;

public class MiningHelper {
    public static List<BlockPos> blockToMine = new ArrayList<>();
    public static final Set<Block> currentTargetBlocks = new HashSet<>();
    // 新增标志位：标记当前是否处于批量挖掘阶段
    public static boolean isBatchMiningPhase = false;

    // =================================================================
    // 视角计算部分 (Angle Calculations)
    // =================================================================

    public static float[] getValidMiningAngle(PlayerEntity player, BlockPos targetPos) {
        return calculateBestAngle(player, targetPos, generateStandardTestPoints(targetPos), RayTraceContext.FluidHandling.NONE, null);
    }

    public static float[] getValidMiningAngleForFace(PlayerEntity player, BlockPos targetPos, Direction targetFace) {
        List<Vec3d> testPoints = generateFaceTestPoints(targetPos, targetFace);
        return calculateBestAngle(player, targetPos, testPoints, RayTraceContext.FluidHandling.NONE, targetFace);
    }

    public static float[] getValidFluidAngle(PlayerEntity player, BlockPos targetPos) {
        return calculateBestAngle(player, targetPos, generateStandardTestPoints(targetPos), RayTraceContext.FluidHandling.SOURCE_ONLY, null);
    }

    // --- 视角计算私有辅助方法 ---

    /**
     * 提取通用的 3x3x3 表面网格测试点生成逻辑
     */
    private static List<Vec3d> generateStandardTestPoints(BlockPos targetPos) {
        List<Vec3d> testPoints = new ArrayList<>();
        double[] offsets = {0.1D, 0.5D, 0.9D};

        for (double x : offsets) {
            for (double z : offsets) {
                testPoints.add(new Vec3d(targetPos.getX() + x, targetPos.getY() + 1.0D, targetPos.getZ() + z));
                testPoints.add(new Vec3d(targetPos.getX() + x, targetPos.getY() + 0.0D, targetPos.getZ() + z));
            }
        }
        for (double x : offsets) {
            for (double y : offsets) {
                testPoints.add(new Vec3d(targetPos.getX() + x, targetPos.getY() + y, targetPos.getZ() + 0.0D));
                testPoints.add(new Vec3d(targetPos.getX() + x, targetPos.getY() + y, targetPos.getZ() + 1.0D));
            }
        }
        for (double y : offsets) {
            for (double z : offsets) {
                testPoints.add(new Vec3d(targetPos.getX() + 0.0D, targetPos.getY() + y, targetPos.getZ() + z));
                testPoints.add(new Vec3d(targetPos.getX() + 1.0D, targetPos.getY() + y, targetPos.getZ() + z));
            }
        }
        return testPoints;
    }

    /**
     * 提取面向特定表面的测试点生成逻辑
     */
    private static List<Vec3d> generateFaceTestPoints(BlockPos targetPos, Direction targetFace) {
        List<Vec3d> testPoints = new ArrayList<>();
        double[] offsets = {0.02D, 0.25D, 0.5D, 0.75D, 0.98D};

        for (double a : offsets) {
            for (double b : offsets) {
                double x = targetPos.getX();
                double y = targetPos.getY();
                double z = targetPos.getZ();

                switch (targetFace) {
                    case UP:    x += a; y += 1.0D; z += b; break;
                    case DOWN:  x += a; y += 0.0D; z += b; break;
                    case NORTH: x += a; y += b; z += 0.0D; break;
                    case SOUTH: x += a; y += b; z += 1.0D; break;
                    case WEST:  x += 0.0D; y += a; z += b; break;
                    case EAST:  x += 1.0D; y += a; z += b; break;
                }

                // 【物理穿透修复】：把网格点向方块内部强行推进 0.01 格
                double pushIn = 0.01D;
                x -= targetFace.getOffsetX() * pushIn;
                y -= targetFace.getOffsetY() * pushIn;
                z -= targetFace.getOffsetZ() * pushIn;

                testPoints.add(new Vec3d(x, y, z));
            }
        }
        return testPoints;
    }

    /**
     * 统一的射线检测与视角打分核心逻辑
     */
    private static float[] calculateBestAngle(PlayerEntity player, BlockPos targetPos, List<Vec3d> testPoints, RayTraceContext.FluidHandling fluidHandling, Direction targetFace) {
        World world = player.getEntityWorld();
        Vec3d eyePos = player.getCameraPosVec(1.0F);

        float bestYaw = player.yaw;
        float bestPitch = player.pitch;
        double minScore = Double.MAX_VALUE;
        boolean foundVisiblePoint = false;

        for (Vec3d point : testPoints) {
            RayTraceContext context = new RayTraceContext(
                    eyePos, point,
                    RayTraceContext.ShapeType.COLLIDER,
                    fluidHandling,
                    player
            );
            BlockHitResult hitResult = world.rayTrace(context);

            boolean isHitValid = hitResult != null
                    && hitResult.getType() == HitResult.Type.BLOCK
                    && hitResult.getBlockPos().equals(targetPos);

            // 【究极拦截锁】：如果指定了面，必须是真实的方块碰撞且击中正确的面
            if (targetFace != null) {
                isHitValid = isHitValid && hitResult.getSide() == targetFace;
            }

            if (isHitValid) {
                double dx = point.x - eyePos.x;
                double dy = point.y - eyePos.y;
                double dz = point.z - eyePos.z;
                double distanceXZ = Math.sqrt(dx * dx + dz * dz);

                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) Math.toDegrees(Math.atan2(-dy, distanceXZ));

                float deltaYaw = MathHelper.wrapDegrees(yaw - player.yaw);
                float deltaPitch = pitch - player.pitch;
                double score = (deltaYaw * deltaYaw) + (deltaPitch * deltaPitch);

                if (score < minScore) {
                    minScore = score;
                    bestYaw = yaw;
                    bestPitch = pitch;
                    foundVisiblePoint = true;
                }
            }
        }

        if (!foundVisiblePoint) {
            // Fallback 中心点逻辑，如果指定了面，则向对应面偏移
            double fallbackX = targetPos.getX() + 0.5D + (targetFace != null ? targetFace.getOffsetX() * 0.5D : 0);
            double fallbackY = targetPos.getY() + 0.5D + (targetFace != null ? targetFace.getOffsetY() * 0.5D : 0);
            double fallbackZ = targetPos.getZ() + 0.5D + (targetFace != null ? targetFace.getOffsetZ() * 0.5D : 0);

            double dx = fallbackX - eyePos.x;
            double dy = fallbackY - eyePos.y;
            double dz = fallbackZ - eyePos.z;
            double distanceXZ = Math.sqrt(dx * dx + dz * dz);

            bestYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            bestPitch = (float) Math.toDegrees(Math.atan2(-dy, distanceXZ));

            String faceMsg = targetFace != null ? " on face " + targetFace : "";
            System.out.println("Fredodebug: Cannot find a valid angle for " + targetPos.toShortString() + faceMsg);
        } else if (targetFace != null) {
            System.out.println("Fredodebug: successfully find a valid angle to " + targetPos.toShortString() + " on face " + targetFace);
        }

        return new float[]{bestYaw, bestPitch};
    }

    // =================================================================
    // 方块搜索与路径算法 (Block Searching & Pathfinding)
    // =================================================================

    public static List<BlockPos> findNearestBlocks(PlayerEntity player, Set<Block> targetBlocks, int totalCount, int maxRadius) {
        return findNearestBlocks(player.getBlockPos(), targetBlocks, totalCount, maxRadius);
    }

    public static List<BlockPos> findNearestBlocks(PlayerEntity player, Map<Block, Integer> targetCounts, int maxRadius) {
        return findNearestBlocks(player.getBlockPos(), targetCounts, maxRadius);
    }

    public static List<BlockPos> findNearestBlocks(BlockPos blockPos, Map<Block, Integer> targetCounts, int maxRadius) {
        List<BlockPos> result = new ArrayList<>();
        if (targetCounts == null || targetCounts.isEmpty()) return result;

        Map<Block, Integer> remainingCounts = new HashMap<>(targetCounts);
        int totalNeeded = remainingCounts.values().stream().mapToInt(Integer::intValue).sum();
        if (totalNeeded <= 0) return result;

        // 1. 粗筛：找出范围内所有目标方块
        List<BlockPos> rawBlocks = scanBlocksInRadius(blockPos, maxRadius, remainingCounts::containsKey);

        // 2. 路径优化 (Nearest Neighbor)
        BlockPos currentPos = blockPos;
        while (!rawBlocks.isEmpty() && totalNeeded > 0) {
            BlockPos closest = getClosestBlock(currentPos, rawBlocks);
            if (closest == null) break;

            Block block = MinecraftClient.getInstance().world.getBlockState(closest).getBlock();
            int needed = remainingCounts.getOrDefault(block, 0);

            if (needed > 0) {
                result.add(closest);
                remainingCounts.put(block, needed - 1);
                totalNeeded--;
            }
            rawBlocks.remove(closest);
            currentPos = closest;
        }

        return result;
    }

    public static List<BlockPos> findNearestBlocks(BlockPos blockPos, Set<Block> targetBlocks, int totalCount, int maxRadius) {
        List<BlockPos> result = new ArrayList<>();
        if (totalCount <= 0 || targetBlocks == null || targetBlocks.isEmpty()) return result;

        // 1. 粗筛：找出范围内所有目标方块
        List<BlockPos> rawBlocks = scanBlocksInRadius(blockPos, maxRadius, targetBlocks::contains);

        // 2. 路径优化：始终寻找距离上一个方块最近的节点
        BlockPos currentPos = blockPos;
        while (!rawBlocks.isEmpty() && result.size() < totalCount) {
            BlockPos closest = getClosestBlock(currentPos, rawBlocks);
            if (closest == null) break;

            result.add(closest);
            rawBlocks.remove(closest);
            currentPos = closest;
        }

        return result;
    }

    // --- 搜索与路径私有辅助方法 ---

    /**
     * 通用的球形区域扫描逻辑
     */
    private static List<BlockPos> scanBlocksInRadius(BlockPos center, int maxRadius, Predicate<Block> isTarget) {
        List<BlockPos> rawBlocks = new ArrayList<>();
        World world = MinecraftClient.getInstance().world;
        int maxRadiusSq = maxRadius * maxRadius;

        for (int x = -maxRadius; x <= maxRadius; x++) {
            for (int y = -maxRadius; y <= maxRadius; y++) {
                for (int z = -maxRadius; z <= maxRadius; z++) {
                    BlockPos checkPos = center.add(x, y, z);
                    if (checkPos.getSquaredDistance(center) > maxRadiusSq) continue;

                    Block currentBlock = world.getBlockState(checkPos).getBlock();
                    if (isTarget.test(currentBlock)) {
                        rawBlocks.add(checkPos);
                    }
                }
            }
        }
        return rawBlocks;
    }

    /**
     * 寻找距离当前坐标最近的方块
     */
    private static BlockPos getClosestBlock(BlockPos currentPos, List<BlockPos> blocks) {
        return blocks.stream()
                .min(Comparator.comparingDouble(p -> p.getSquaredDistance(currentPos)))
                .orElse(null);
    }

    // =================================================================
    // 挖掘收集与任务派发 (Mining & Collecting)
    // =================================================================

    public static void mineAndCollect(PlayerEntity player, Set<Block> targetBlocks, int totalCount, int maxRadius) {
        List<BlockPos> path = findNearestBlocks(player, targetBlocks, totalCount, maxRadius);
        setupAndDispatchTask(targetBlocks, path, "混合方块");
    }

    public static void mineAndCollect(PlayerEntity player, Map<Block, Integer> targetCounts, int maxRadius) {
        List<BlockPos> path = findNearestBlocks(player, targetCounts, maxRadius);
        setupAndDispatchTask(targetCounts.keySet(), path, "精确配额");
    }

    public static void dispatchNextMineAndCollectTask() {
        if (!MiningHelper.blockToMine.isEmpty()) {
            BlockPos nextTarget = MiningHelper.blockToMine.remove(0);
            BotEngine.getInstance().getModule(MiscController.class).startTask(
                    MiscController.MiscType.MINE_THE_BLOCK_AND_COLLECT_THE_DROP,
                    nextTarget
            );
        } else {
            MiningHelper.currentTargetBlocks.clear(); // 任务结束，释放黑名单
            System.out.println("FredoBot: 当前挖掘列表已全部执行完毕！");
        }
    }

    // --- 任务派发私有辅助方法 ---

    /**
     * 统一的队列装载与重置判断逻辑
     */
    private static void setupAndDispatchTask(Set<Block> targetTypes, List<BlockPos> blockPath, String logType) {
        System.out.println("FredoBot: 开始扫描并生成 [" + logType + "] 挖掘拾取队列...");

        MiningHelper.blockToMine.clear();
        MiningHelper.currentTargetBlocks.clear();

        MiningHelper.currentTargetBlocks.addAll(targetTypes);
        MiningHelper.blockToMine.addAll(blockPath);

        if (MiningHelper.blockToMine.isEmpty()) {
            System.out.println("Fredobot: reset because cant find enough blocks. Detail: " + targetTypes.toString());
            MiningHelper.currentTargetBlocks.clear();
            BotEngine.getInstance().getModule(GlobalExecutor.class).resetWorld();
            return;
        }
        dispatchNextMineAndCollectTask();
    }
}