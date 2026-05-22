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

public class MiningHelper {
    public static List<BlockPos> blockToMine = new ArrayList<>();

    public static final Set<Block> currentTargetBlocks = new HashSet<Block>();
    // 新增标志位：标记当前是否处于批量挖掘阶段
    public static boolean isBatchMiningPhase = false;

    /**
     * 计算可行的挖掘视角 (保持原样)
     */
    public static float[] getValidMiningAngle(PlayerEntity player, BlockPos targetPos) {
        World world = player.getEntityWorld();
        Vec3d eyePos = player.getCameraPosVec(1.0F);

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

        float bestYaw = player.yaw;
        float bestPitch = player.pitch;
        double minScore = Double.MAX_VALUE;
        boolean foundVisiblePoint = false;

        for (Vec3d point : testPoints) {
            RayTraceContext context = new RayTraceContext(
                    eyePos, point,
                    RayTraceContext.ShapeType.COLLIDER,
                    RayTraceContext.FluidHandling.NONE,
                    player
            );
            BlockHitResult hitResult = world.rayTrace(context);

            if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK && hitResult.getBlockPos().equals(targetPos)) {
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
            double dx = (targetPos.getX() + 0.5D) - eyePos.x;
            double dy = (targetPos.getY() + 0.5D) - eyePos.y;
            double dz = (targetPos.getZ() + 0.5D) - eyePos.z;
            double distanceXZ = Math.sqrt(dx * dx + dz * dz);
            bestYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            bestPitch = (float) Math.toDegrees(Math.atan2(-dy, distanceXZ));
            System.out.println("Fredodebug: Cannot find a valid angle for " + targetPos.toShortString());
        }

        return new float[]{bestYaw, bestPitch};
    }

    /**
     * 计算可行的交互视角（严格限定只能看目标方块的指定面）
     *
     * @param player     玩家实体
     * @param targetPos  目标方块坐标
     * @param targetFace 必须击中的方块面 (如 Direction.UP, Direction.NORTH)
     * @return float[] {yaw, pitch}
     */
    public static float[] getValidMiningAngleForFace(PlayerEntity player, BlockPos targetPos, Direction targetFace) {
        World world = player.getEntityWorld();
        Vec3d eyePos = player.getCameraPosVec(1.0F);

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

                // =================================================================
                // 【物理穿透修复】：把网格点向方块内部强行推进 0.01 格！
                // 确保射线一定会和目标方块的碰撞箱产生物理交叠，绝不让引擎返回 MISS！
                // =================================================================
                double pushIn = 0.01D;
                x -= targetFace.getOffsetX() * pushIn;
                y -= targetFace.getOffsetY() * pushIn;
                z -= targetFace.getOffsetZ() * pushIn;

                testPoints.add(new Vec3d(x, y, z));
            }
        }

        float bestYaw = player.yaw;
        float bestPitch = player.pitch;
        double minScore = Double.MAX_VALUE;
        boolean foundVisiblePoint = false;

        for (Vec3d point : testPoints) {
            RayTraceContext context = new RayTraceContext(
                    eyePos, point,
                    RayTraceContext.ShapeType.COLLIDER,
                    RayTraceContext.FluidHandling.NONE,
                    player
            );
            BlockHitResult hitResult = world.rayTrace(context);

            // =================================================================
            // 【究极拦截锁】：必须是真实的方块碰撞 (Type.BLOCK)！
            // 彻底杜绝射空 (MISS) 导致的幽灵坐标飘移！
            // =================================================================
            if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK
                    && hitResult.getBlockPos().equals(targetPos)
                    && hitResult.getSide() == targetFace) {

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
            double fallbackX = targetPos.getX() + 0.5D + targetFace.getOffsetX() * 0.5D;
            double fallbackY = targetPos.getY() + 0.5D + targetFace.getOffsetY() * 0.5D;
            double fallbackZ = targetPos.getZ() + 0.5D + targetFace.getOffsetZ() * 0.5D;

            double dx = fallbackX - eyePos.x;
            double dy = fallbackY - eyePos.y;
            double dz = fallbackZ - eyePos.z;
            double distanceXZ = Math.sqrt(dx * dx + dz * dz);

            bestYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            bestPitch = (float) Math.toDegrees(Math.atan2(-dy, distanceXZ));
            System.out.println("Fredodebug: cannot find a valid angle to " + targetPos.toShortString() + " on face " + targetFace.toString());
        } else {
            System.out.println("Fredodebug: successfully find a valid angle to " + targetPos.toShortString() + " on face " + targetFace.toString());
        }

        return new float[]{bestYaw, bestPitch};
    }

    public static float[] getValidFluidAngle(PlayerEntity player, BlockPos targetPos) {
        World world = player.getEntityWorld();
        Vec3d eyePos = player.getCameraPosVec(1.0F);

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

        float bestYaw = player.yaw;
        float bestPitch = player.pitch;
        double minScore = Double.MAX_VALUE;
        boolean foundVisiblePoint = false;

        for (Vec3d point : testPoints) {
            RayTraceContext context = new RayTraceContext(
                    eyePos, point,
                    RayTraceContext.ShapeType.COLLIDER,
                    // 【核心改动】：允许射线击中流体源方块 (如岩浆源头/水源头)
                    RayTraceContext.FluidHandling.SOURCE_ONLY,
                    player
            );
            BlockHitResult hitResult = world.rayTrace(context);

            if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK && hitResult.getBlockPos().equals(targetPos)) {
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
            double dx = (targetPos.getX() + 0.5D) - eyePos.x;
            double dy = (targetPos.getY() + 0.5D) - eyePos.y;
            double dz = (targetPos.getZ() + 0.5D) - eyePos.z;
            double distanceXZ = Math.sqrt(dx * dx + dz * dz);
            bestYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            bestPitch = (float) Math.toDegrees(Math.atan2(-dy, distanceXZ));
            System.out.println("Fredodebug: Cannot find a valid angle for " + targetPos.toShortString());
        }

        return new float[]{bestYaw, bestPitch};
    }

    /**
     * 重构搜索逻辑：使用最近邻居算法，生成平滑的挖掘路径
     */
    public static List<BlockPos> findNearestBlocks(BlockPos blockPos, Map<Block, Integer> targetCounts, int maxRadius) {
        List<BlockPos> result = new ArrayList<>();
        if (targetCounts == null || targetCounts.isEmpty()) return result;
        Map<Block, Integer> remainingCounts = new HashMap<>(targetCounts);
        int totalNeeded = remainingCounts.values().stream().mapToInt(Integer::intValue).sum();
        if (totalNeeded <= 0) return result;

        List<BlockPos> rawBlocks = new ArrayList<>();

        // 1. 粗筛：找出范围内所有目标方块
        for (int x = -maxRadius; x <= maxRadius; x++) {
            for (int y = -maxRadius; y <= maxRadius; y++) {
                for (int z = -maxRadius; z <= maxRadius; z++) {
                    BlockPos checkPos = blockPos.add(x, y, z);
                    if (checkPos.getSquaredDistance(blockPos) > maxRadius * maxRadius) continue;
                    Block currentBlock = MinecraftClient.getInstance().world.getBlockState(checkPos).getBlock();
                    if (remainingCounts.containsKey(currentBlock)) {
                        rawBlocks.add(checkPos);
                    }
                }
            }
        }

        // 2. 路径优化 (Nearest Neighbor)：确保下一个挖的方块距离当前最近，防止乱跳
        BlockPos currentPos = blockPos; // 初始起点为玩家位置
        while (!rawBlocks.isEmpty() && totalNeeded > 0) {
            BlockPos finalCurrentPos = currentPos;
            BlockPos closest = rawBlocks.stream()
                    .min(Comparator.comparingDouble(p -> p.getSquaredDistance(finalCurrentPos)))
                    .orElse(null);

            if (closest != null) {
                Block block = MinecraftClient.getInstance().world.getBlockState(closest).getBlock();
                int needed = remainingCounts.getOrDefault(block, 0);

                if (needed > 0) {
                    result.add(closest);
                    remainingCounts.put(block, needed - 1);
                    totalNeeded--;
                }
                rawBlocks.remove(closest);
                currentPos = closest; // 移动模拟坐标到刚选中的方块
            } else {
                break;
            }
        }

        return result;
    }

    /**
     * 重构搜索逻辑：使用最近邻居算法，生成平滑的挖掘路径
     */
    public static List<BlockPos> findNearestBlocks(BlockPos blockPos, Set<Block> targetBlocks, int totalCount, int maxRadius) {
        List<BlockPos> result = new ArrayList<>();
        if (totalCount <= 0 || targetBlocks == null || targetBlocks.isEmpty()) return result;

        List<BlockPos> rawBlocks = new ArrayList<>();

        for (int x = -maxRadius; x <= maxRadius; x++) {
            for (int y = -maxRadius; y <= maxRadius; y++) {
                for (int z = -maxRadius; z <= maxRadius; z++) {
                    BlockPos checkPos = blockPos.add(x, y, z);
                    if (checkPos.getSquaredDistance(blockPos) > maxRadius * maxRadius) continue;
                    Block currentBlock = MinecraftClient.getInstance().world.getBlockState(checkPos).getBlock();
                    if (targetBlocks.contains(currentBlock)) {
                        rawBlocks.add(checkPos);
                    }
                }
            }
        }

        // 路径优化：始终寻找距离上一个方块最近的节点
        BlockPos currentPos = blockPos;
        while (!rawBlocks.isEmpty() && result.size() < totalCount) {
            BlockPos finalCurrentPos = currentPos;
            BlockPos closest = rawBlocks.stream()
                    .min(Comparator.comparingDouble(p -> p.getSquaredDistance(finalCurrentPos)))
                    .orElse(null);

            if (closest != null) {
                result.add(closest);
                rawBlocks.remove(closest);
                currentPos = closest;
            } else {
                break;
            }
        }

        return result;
    }

    public static List<BlockPos> findNearestBlocks(PlayerEntity player, Set<Block> targetBlocks, int totalCount, int maxRadius) {
        return findNearestBlocks(player.getBlockPos(), targetBlocks, totalCount, maxRadius);
    }

    public static List<BlockPos> findNearestBlocks(PlayerEntity player, Map<Block, Integer> targetCounts, int maxRadius) {
        return findNearestBlocks(player.getBlockPos(), targetCounts, maxRadius);
    }

    public static void mineAndCollect(PlayerEntity player, Set<Block> targetBlocks, int totalCount, int maxRadius) {
        System.out.println("FredoBot: 开始扫描并生成 [混合方块] 挖掘拾取队列...");
        MiningHelper.blockToMine.clear();

        MiningHelper.currentTargetBlocks.clear();
        MiningHelper.currentTargetBlocks.addAll(targetBlocks);

        MiningHelper.blockToMine.addAll(MiningHelper.findNearestBlocks(player, targetBlocks, totalCount, maxRadius));
        if (MiningHelper.blockToMine.isEmpty()) {
            System.out.println("Fredobot: reset because cant find enough blocks. Detail: " + targetBlocks.toString());
            MiningHelper.currentTargetBlocks.clear();
            BotEngine.getInstance().getModule(GlobalExecutor.class).resetWorld();
            return;
        }
        dispatchNextMineAndCollectTask();
    }

    public static void mineAndCollect(PlayerEntity player, java.util.Map<Block, Integer> targetCounts, int maxRadius) {
        System.out.println("FredoBot: 开始扫描并生成 [精确配额] 挖掘拾取队列...");
        MiningHelper.blockToMine.clear();

        MiningHelper.currentTargetBlocks.clear();
        MiningHelper.currentTargetBlocks.addAll(targetCounts.keySet());

        MiningHelper.blockToMine.addAll(MiningHelper.findNearestBlocks(player, targetCounts, maxRadius));
        if (MiningHelper.blockToMine.isEmpty()) {
            System.out.println("Fredobot: reset because cant find enough blocks");
            MiningHelper.currentTargetBlocks.clear();
            BotEngine.getInstance().getModule(GlobalExecutor.class).resetWorld();
            return;
        }
        dispatchNextMineAndCollectTask();
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
}