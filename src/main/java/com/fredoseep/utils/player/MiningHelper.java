package com.fredoseep.utils.player;

import com.fredoseep.behave.MiscController;
import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.GlobalExecutor;
import com.fredoseep.utils.bt.BtStuff;
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

    public static List<BlockPos> findNearestBlocks(BlockPos blockPos, Map<Block, Integer> targetCounts, int maxRadius) {
        List<BlockPos> result = new ArrayList<>();
        if (targetCounts == null || targetCounts.isEmpty()) return result;
        Map<Block, Integer> remainingCounts = new HashMap<>(targetCounts);
        int totalNeeded = remainingCounts.values().stream().mapToInt(Integer::intValue).sum();
        if (totalNeeded <= 0) return result;

        PriorityQueue<BlockPos> queue = new PriorityQueue<>(
                Comparator.comparingDouble(pos -> pos.getSquaredDistance(blockPos))
        );

        for (int x = -maxRadius; x <= maxRadius; x++) {
            for (int y = -maxRadius; y <= maxRadius; y++) {
                for (int z = -maxRadius; z <= maxRadius; z++) {
                    BlockPos checkPos = blockPos.add(x, y, z);

                    if (checkPos.getSquaredDistance(blockPos) > maxRadius * maxRadius) continue;

                    Block currentBlock = MinecraftClient.getInstance().world.getBlockState(checkPos).getBlock();
                    if (remainingCounts.containsKey(currentBlock)) {
                        queue.add(checkPos);
                    }
                }
            }
        }

        while (!queue.isEmpty() && totalNeeded > 0) {
            BlockPos pos = queue.poll();
            Block block = MinecraftClient.getInstance().world.getBlockState(pos).getBlock();

            int needed = remainingCounts.getOrDefault(block, 0);

            if (needed > 0) {
                result.add(pos);
                remainingCounts.put(block, needed - 1);
                totalNeeded--;
            }
        }

        return result;
    }

    public static List<BlockPos> findNearestBlocks(BlockPos blockPos, Set<Block> targetBlocks, int totalCount, int maxRadius) {
        List<BlockPos> result = new ArrayList<>();
        if (totalCount <= 0 || targetBlocks == null || targetBlocks.isEmpty()) return result;
        PriorityQueue<BlockPos> queue = new PriorityQueue<>(
                Comparator.comparingDouble(pos -> pos.getSquaredDistance(blockPos))
        );


        for (int x = -maxRadius; x <= maxRadius; x++) {
            for (int y = -maxRadius; y <= maxRadius; y++) {
                for (int z = -maxRadius; z <= maxRadius; z++) {
                    BlockPos checkPos = blockPos.add(x, y, z);

                    if (checkPos.getSquaredDistance(blockPos) > maxRadius * maxRadius) continue;
                    Block currentBlock = MinecraftClient.getInstance().world.getBlockState(checkPos).getBlock();
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

    public static List<BlockPos> findNearestBlocks(PlayerEntity player, Set<Block> targetBlocks, int totalCount, int maxRadius) {
        return findNearestBlocks(player.getBlockPos(), targetBlocks, totalCount, maxRadius);
    }

    public static List<BlockPos> findNearestBlocks(PlayerEntity player, Map<Block, Integer> targetCounts, int maxRadius) {
        return findNearestBlocks(player.getBlockPos(), targetCounts, maxRadius);
    }


    public static void mineAndCollect(PlayerEntity player, Set<Block> targetBlocks, int totalCount, int maxRadius) {
        System.out.println("FredoBot: 开始扫描并生成 [混合方块] 挖掘拾取队列...");
        MiningHelper.blockToMine.clear();
        MiningHelper.blockToMine.addAll(MiningHelper.findNearestBlocks(player, targetBlocks, totalCount, maxRadius));
        if (MiningHelper.blockToMine.isEmpty()) {
            System.out.println("Fredobot: reset because cant find enough blocks. Detail: " + targetBlocks.toString());
            BotEngine.getInstance().getModule(GlobalExecutor.class).resetWorld();
            return;
        }
        dispatchNextMineAndCollectTask(); // 启动第一个任务
    }

    /**
     * 重载 2：扫描精确配额的方块
     */
    public static void mineAndCollect(PlayerEntity player, java.util.Map<Block, Integer> targetCounts, int maxRadius) {
        System.out.println("FredoBot: 开始扫描并生成 [精确配额] 挖掘拾取队列...");
        MiningHelper.blockToMine.clear();
        MiningHelper.blockToMine.addAll(MiningHelper.findNearestBlocks(player, targetCounts, maxRadius));
        if (MiningHelper.blockToMine.isEmpty()) {
            System.out.println("Fredobot: reset because cant find enough blocks");
            BotEngine.getInstance().getModule(GlobalExecutor.class).resetWorld();
            return;
        }
        dispatchNextMineAndCollectTask();
    }

    /**
     * 核心派发器：从列表中取出一个方块，丢给 MiscController 执行
     */
    public static void dispatchNextMineAndCollectTask() {
        if (!MiningHelper.blockToMine.isEmpty()) {
            BlockPos nextTarget = MiningHelper.blockToMine.remove(0);
            BotEngine.getInstance().getModule(MiscController.class).startTask(
                    MiscController.MiscType.MINE_THE_BLOCK_AND_COLLECT_THE_DROP,
                    nextTarget
            );
        } else {
            System.out.println("FredoBot: 当前挖掘列表已全部执行完毕！");
        }
    }

}
