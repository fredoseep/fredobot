package com.fredoseep.algorithm;

import com.fredoseep.utils.player.InventoryHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.*;

public class SimplePathfinder {

    private static final int MAX_NODES = 50000;
    private static final int ABUNDANT_BLOCK_THRESHOLD = 64;

    public enum MovementState {
        WALKING, FALLING, JUMPING_UP, JUMPING_AIR, BUILDING_BRIDGE, BUILDING_PILLAR, MINING, SWIMMING, DIVING
    }

    public static List<Node> findPath(World world, PlayerEntity player, BlockPos end) {
        BlockPos start = new BlockPos(player.getX(), player.getY() + 0.2, player.getZ());
        return findPath(world, player, start, end, Collections.emptySet()); // 默认无黑名单
    }

    public static List<Node> findPath(World world, PlayerEntity player, BlockPos startPos, BlockPos end, Set<BlockPos> blacklist) {
        return findPath(world, player, startPos, end, blacklist, 0);
    }

    // 【新增】：带有黑名单参数的终极寻路入口
    public static List<Node> findPath(World world, PlayerEntity player, BlockPos startPos, BlockPos end, Set<BlockPos> blacklist, int initialBlocksUsed) {        long startTime = System.currentTimeMillis();
        InventoryHelper.BlockCounts blockCounts = InventoryHelper.countAvailableBuildingBlocks(player);
        System.out.println("Fredodebut: availableBlockCounts: " + blockCounts.pillaringBlocks);

        int margin = 100;
        int minX = Math.min(startPos.getX(), end.getX()) - margin;
        int maxX = Math.max(startPos.getX(), end.getX()) + margin;
        int minZ = Math.min(startPos.getZ(), end.getZ()) - margin;
        int maxZ = Math.max(startPos.getZ(), end.getZ()) + margin;

        PriorityQueue<Node> openSet = new PriorityQueue<>();
        Map<BlockPos, EnumMap<MovementState, Double>> costSoFar = new HashMap<>();

        Node startNode = new Node(startPos, null, null, 0, calculateHeuristic(startPos, end), MovementState.WALKING, initialBlocksUsed);        openSet.add(startNode);
        costSoFar.computeIfAbsent(startPos, k -> new EnumMap<>(MovementState.class)).put(MovementState.WALKING, 0.0);

        Node closestNode = startNode;
        double minEarlyExitScore = Math.abs(startPos.getX() - end.getX()) + Math.abs(startPos.getZ() - end.getZ());
        int nodesEvaluated = 0;

        while (!openSet.isEmpty() && nodesEvaluated < MAX_NODES) {
            Node current = openSet.poll();

            EnumMap<MovementState, Double> currentBestStates = costSoFar.get(current.pos);
            if (currentBestStates != null && current.costFromStart > currentBestStates.getOrDefault(current.state, Double.MAX_VALUE)) {
                continue;
            }

            nodesEvaluated++;

            if (current.state == MovementState.WALKING || current.state == MovementState.SWIMMING || current.state == MovementState.DIVING) {
                double horizontalDist = Math.abs(current.pos.getX() - end.getX()) + Math.abs(current.pos.getZ() - end.getZ());
                if (horizontalDist < minEarlyExitScore) {
                    minEarlyExitScore = horizontalDist;
                    closestNode = current;
                }
            }

            if (current.pos.equals(end)) {
                long endTime = System.currentTimeMillis();
                System.out.println("[SimplePathfinder] 寻路成功！耗时: " + (endTime - startTime) + " ms, 实际评估节点数: " + nodesEvaluated);
                return smoothPath(world, reconstructPath(current));
            }

            // 【注意】：将 blacklist 传递给 getNeighbors
            for (Node neighbor : getNeighbors(world, current, end, blockCounts, blacklist)) {
                if (neighbor.pos.getX() < minX || neighbor.pos.getX() > maxX ||
                        neighbor.pos.getZ() < minZ || neighbor.pos.getZ() > maxZ) {
                    continue;
                }

                EnumMap<MovementState, Double> neighborBestStates = costSoFar.computeIfAbsent(neighbor.pos, k -> new EnumMap<>(MovementState.class));
                double previousCost = neighborBestStates.getOrDefault(neighbor.state, Double.MAX_VALUE);

                if (neighbor.costFromStart < previousCost) {
                    neighborBestStates.put(neighbor.state, neighbor.costFromStart);
                    openSet.add(neighbor);
                }
            }
        }

        System.out.println("[SimplePathfinder] 触及算力上限！执行安全港截断。实际评估节点数: " + nodesEvaluated);
        if (startPos.getY() > end.getY() && Math.abs(startPos.getX() - end.getX()) <= 5 && Math.abs(startPos.getZ() - end.getZ()) <= 5) {
            System.out.println("FredoBot [保底机制]: 寻路算力超载！已切入【直下打桩机】模式！");
            return smoothPath(world, buildVerticalFallbackPath(world, startPos, end));
        }

        return smoothPath(world, reconstructPath(closestNode == startNode ? null : closestNode));
    }
    private static List<Node> buildVerticalFallbackPath(World world, BlockPos startPos, BlockPos end) {
        List<Node> path = new ArrayList<>();
        Node current = new Node(startPos, null, null, 0, 0, MovementState.WALKING, 0);
        path.add(current);

        BlockPos currPos = startPos;

        // 1. 水平微调对齐 (带防刮底智能上浮逻辑)
        while (currPos.getX() != end.getX() || currPos.getZ() != end.getZ()) {
            int stepX = Integer.compare(end.getX(), currPos.getX());
            int stepZ = Integer.compare(end.getZ(), currPos.getZ());
            BlockPos nextHoriz = currPos.add(stepX, 0, stepZ);

            // ==========================================
            // 【核心优化】：水下防刮底 / 坑底逃脱机制
            // 如果前方被挡住了，绝不急着平推挖过去！先看看头顶是不是通的（水或空气）。
            // 如果头顶是通的，优先“上浮/起跳”来越过障碍物！
            // ==========================================
            if (!isPassable(world, nextHoriz) || !isPassable(world, nextHoriz.up())) {
                BlockPos upPos = currPos.up();
                // 如果头顶通畅，且没超过世界高度限制，就往上浮！
                if (isPassable(world, upPos) && currPos.getY() < 255) {
                    currPos = upPos;
                    MovementState state = MovementState.JUMPING_UP; // 默认陆地起跳
                    if (isWaterBlock(world, currPos)) {
                        // 如果在水里，根据头顶是不是水面决定是游泳还是潜水
                        state = !isWaterBlock(world, currPos.up()) ? MovementState.SWIMMING : MovementState.DIVING;
                    }
                    Node next = new Node(currPos, null, current, current.costFromStart + 1, 0, state, 0);
                    path.add(next);
                    current = next;
                    continue; // 成功上浮一格，中断本次水平判断，下个循环重新判断前方是否畅通
                }
            }

            // 如果头顶也不通（比如在海底封闭洞穴里），或者前方本来就没被挡住
            // 那么正常进行水平移动（如果被挡住了状态会自动切成 MINING 硬挖）
            currPos = nextHoriz;
            MovementState state = MovementState.WALKING;
            if (isWaterBlock(world, currPos)) {
                state = !isWaterBlock(world, currPos.up()) ? MovementState.SWIMMING : MovementState.DIVING;
            } else if (!isPassable(world, currPos) || !isPassable(world, currPos.up())) {
                state = MovementState.MINING;
            }
            Node next = new Node(currPos, null, current, current.costFromStart + 1, 0, state, 0);
            path.add(next);
            current = next;
        }

        // 2. 暴力垂直下钻 (对齐后一路向下)
        while (currPos.getY() > end.getY()) {
            currPos = currPos.down();
            MovementState state = MovementState.FALLING;

            if (isWaterBlock(world, currPos)) {
                state = MovementState.DIVING;
            } else if (!isPassable(world, currPos)) {
                state = MovementState.MINING;
            }

            Node next = new Node(currPos, null, current, current.costFromStart + 1, 0, state, 0);
            path.add(next);
            current = next;
        }

        return path;
    }

    private static double getDynamicCost(int blocksRemaining, double minCost, double maxCost) {
        if (blocksRemaining <= 0) return 9999.0;
        double scarcityRatio = 1.0 - ((double) Math.min(blocksRemaining, ABUNDANT_BLOCK_THRESHOLD) / ABUNDANT_BLOCK_THRESHOLD);
        return minCost + (maxCost - minCost) * scarcityRatio;
    }

    private static List<Node> getNeighbors(World world, Node current, BlockPos end, InventoryHelper.BlockCounts counts, Set<BlockPos> blacklist) {        List<Node> neighbors = new ArrayList<>();
        Direction[] directions = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        int remainingBridging = counts.bridgingBlocks - current.blocksUsed;
        int remainingPillaring = counts.pillaringBlocks - current.blocksUsed;

        boolean isCurrentWater = isWaterBlock(world, current.pos);
        boolean isStandingOnWater = isWaterBlock(world, current.pos.down());

        boolean isSimulatedSolidBelow = isSolid(world, current.pos.down())
                || current.state == MovementState.BUILDING_PILLAR
                || current.state == MovementState.BUILDING_BRIDGE;

        boolean isCurrentMidAir = !isSimulatedSolidBelow && !isCurrentWater && !isStandingOnWater;

        if (isCurrentMidAir) {
            for (int drop = 1; drop <= 256; drop++) {
                BlockPos dropPos = current.pos.down(drop);
                if (!isPassable(world, dropPos) || !isPassable(world, dropPos.up())) break;

                if (isWaterBlock(world, dropPos)) {
                    // 【严格水域】: 空中掉进水里，落地状态必须是 SWIMMING 或 DIVING
                    boolean nextIsSurface = !isWaterBlock(world, dropPos.up());
                    MovementState fallState = nextIsSurface ? MovementState.SWIMMING : MovementState.DIVING;
                    neighbors.add(new Node(dropPos, null, current, current.costFromStart + (drop * 0.5), calculateHeuristic(dropPos, end), fallState, current.blocksUsed));
                    break;
                } else if (isSolid(world, dropPos.down())) {
                    if (drop > 3) break;
                    neighbors.add(new Node(dropPos, null, current, current.costFromStart + (drop * 0.5), calculateHeuristic(dropPos, end), MovementState.FALLING, current.blocksUsed));
                    break;
                }
            }
            return neighbors;
        }

        if (isCurrentWater) {
            double dist2D = Math.sqrt(Math.pow(current.pos.getX() - end.getX(), 2) + Math.pow(current.pos.getZ() - end.getZ(), 2));

            boolean isTargetWater = isWaterBlock(world, end) || isWaterBlock(world, end.up()) || (dist2D <= 5.0 && end.getY() < current.pos.getY());
            boolean isSurface = !isWaterBlock(world, current.pos.up());

            Direction[] allDirs = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN};
            double diveRadius = 5.0;

            for (Direction dir : allDirs) {
                BlockPos nextPos = current.pos.offset(dir);
                if (isWaterBlock(world, nextPos)) {
                    boolean nextIsSurface = !isWaterBlock(world, nextPos.up());
                    MovementState nextState = nextIsSurface ? MovementState.SWIMMING : MovementState.DIVING;
                    double moveCost;

                    boolean isHorizontal = (dir == Direction.NORTH || dir == Direction.SOUTH || dir == Direction.EAST || dir == Direction.WEST);

                    if (isSurface && nextIsSurface) {
                        moveCost = 1.2;
                    } else if (isSurface && !nextIsSurface) {
                        if (!isTargetWater) moveCost = 9999.0;
                        else if (dist2D > diveRadius) moveCost = 9999.0;
                        else moveCost = 1.2;
                    } else if (!isSurface && !nextIsSurface) {
                        if (!isTargetWater || dist2D > diveRadius) {
                            if (dir == Direction.UP) moveCost = 1.0;
                            else moveCost = 9999.0;
                        } else {
                            if (dir == Direction.DOWN) moveCost = 1.2;
                            else if (isHorizontal) moveCost = 4.0;
                            else moveCost = 1.5;
                        }
                    } else {
                        moveCost = 1.0;
                    }

                    if (moveCost < 9990.0 && isPassable(world, nextPos) && isPassable(world, nextPos.up())) {
                        neighbors.add(new Node(nextPos, null, current, current.costFromStart + moveCost, calculateHeuristic(nextPos, end), nextState, current.blocksUsed));
                    }
                } else if ((dir == Direction.DOWN || dir == Direction.UP) && !isPassable(world, nextPos)) {

                    double mineCost = getMiningCost(world, nextPos, blacklist);
                    if (mineCost < 9999.0) {
                        // ==========================================
                        // 【核心修复】：为水下垂直下挖也装备岩浆避险探针！
                        // ==========================================
                        if (dir == Direction.DOWN) {
                            BlockState dangerState = world.getBlockState(nextPos.down());
                            // 只有当即将挖开的方块下方不是岩浆时，才允许垂直往下挖
                            if (dangerState.getMaterial() != net.minecraft.block.Material.LAVA && !dangerState.getFluidState().isIn(net.minecraft.tag.FluidTags.LAVA)) {
                                neighbors.add(new Node(nextPos, null, current, current.costFromStart + 2.0 + mineCost, calculateHeuristic(nextPos, end), MovementState.MINING, current.blocksUsed));
                            }
                        } else {
                            neighbors.add(new Node(nextPos, null, current, current.costFromStart + 2.0 + mineCost, calculateHeuristic(nextPos, end), MovementState.MINING, current.blocksUsed));
                        }
                    }
                }
            }
        }
        BlockPos headPos = current.pos.up(2);
        double overheadMineCost = getMiningCost(world, headPos, blacklist);
        if (remainingPillaring > 0 && overheadMineCost < 9999.0 && !isCurrentWater && !isStandingOnWater) {
            BlockPos pillarPos = current.pos.up();
            double dynamicPillarCost = getDynamicCost(remainingPillaring, 1.2, 3.5);
            double totalCost = current.costFromStart + dynamicPillarCost + overheadMineCost;

            int netBlocksUsed = current.blocksUsed + 1;
            if (yieldsBuildingBlock(world, headPos)) {
                netBlocksUsed -= 1;
            }
            neighbors.add(new Node(pillarPos, headPos, current, totalCost, calculateHeuristic(pillarPos, end), MovementState.BUILDING_PILLAR, netBlocksUsed));
        }
        if (!isCurrentWater && !isCurrentMidAir) {
            BlockPos downPos = current.pos.down();
            if (!isPassable(world, downPos)) {
                double mineCostDown = getMiningCost(world, downPos, blacklist);
                if (mineCostDown < 9999.0) {
                    BlockState dangerState = world.getBlockState(downPos.down());
                    if (dangerState.getMaterial() != net.minecraft.block.Material.LAVA && !dangerState.getFluidState().isIn(net.minecraft.tag.FluidTags.LAVA)) {
                        int blocksGained = yieldsBuildingBlock(world, downPos) ? 1 : 0;
                        neighbors.add(new Node(downPos, null, current, current.costFromStart + 1.5 + mineCostDown, calculateHeuristic(downPos, end), MovementState.MINING, current.blocksUsed - blocksGained));
                    }
                }
            }
        }

        for (Direction dir : directions) {
            BlockPos adjacent = current.pos.offset(dir);
            BlockPos adjacentUp = adjacent.up();
            boolean isAdjacentWater = isWaterBlock(world, adjacent);

            if (isAdjacentWater) {
                if (!isCurrentWater) {
                    boolean nextIsSurface = !isWaterBlock(world, adjacent.up());
                    MovementState nextState = nextIsSurface ? MovementState.SWIMMING : MovementState.DIVING;
                    neighbors.add(new Node(adjacent, null, current, current.costFromStart + 1.2, calculateHeuristic(adjacent, end), nextState, current.blocksUsed));
                }
                continue;
            }

            BlockPos adjacentDown = adjacent.down();
            if (!isCurrentWater) {
                if (!isPassable(world, adjacentDown) || !isPassable(world, adjacent) || !isPassable(world, adjacentUp)) {
                    double stairMineCost = getMiningCost(world, adjacentDown, blacklist)
                            + getMiningCost(world, adjacent, blacklist)
                            + getMiningCost(world, adjacentUp, blacklist);

                    if (stairMineCost > 0 && stairMineCost < 9999.0 && isSolid(world, adjacentDown.down())) {
                        int blocksGained = 0;
                        if (yieldsBuildingBlock(world, adjacentDown)) blocksGained++;
                        if (yieldsBuildingBlock(world, adjacent)) blocksGained++;
                        if (yieldsBuildingBlock(world, adjacentUp)) blocksGained++;
                        neighbors.add(new Node(adjacentDown, adjacent, current, current.costFromStart + 1.5 + stairMineCost, calculateHeuristic(adjacentDown, end), MovementState.MINING, current.blocksUsed - blocksGained));
                    }
                }
            }

            boolean isBlocked = !isPassable(world, adjacent) || !isPassable(world, adjacentUp);
            double mineCost = 0.0;
            int blocksGained = 0;

            if (isBlocked) {
                mineCost = getMiningCost(world, adjacent, blacklist) + getMiningCost(world, adjacentUp, blacklist);
                if (mineCost < 9999.0) {
                    if (yieldsBuildingBlock(world, adjacent)) blocksGained++;
                    if (yieldsBuildingBlock(world, adjacentUp)) blocksGained++;
                }
            }

            if (isSolid(world, adjacent) && isPassable(world, adjacentUp) && isPassable(world, adjacent.up(2)) && isPassable(world, current.pos.up(2))) {
                neighbors.add(new Node(adjacentUp, null, current, current.costFromStart + 1.5, calculateHeuristic(adjacentUp, end), MovementState.JUMPING_UP, current.blocksUsed));
            }

            if (isSolid(world, adjacentDown) || isCurrentWater) {
                if (isBlocked) {
                    if (mineCost < 9999.0) {
                        neighbors.add(new Node(adjacent, adjacentUp, current, current.costFromStart + 1.0 + mineCost, calculateHeuristic(adjacent, end), MovementState.MINING, current.blocksUsed - blocksGained));
                    }
                } else {
                    double moveCost = isCurrentWater ? 1.2 : 1.0;
                    neighbors.add(new Node(adjacent, null, current, current.costFromStart + moveCost, calculateHeuristic(adjacent, end), MovementState.WALKING, current.blocksUsed));
                }
                continue;
            }

            if (isBlocked) {
                continue;
            }

            boolean jumpedOverGap = false;
            Node lastAirNode = current;

            for (int distance = 1; distance <= 4; distance++) {
                BlockPos forwardPos = current.pos.offset(dir, distance);
                if (!isPassable(world, forwardPos) || !isPassable(world, forwardPos.up())) break;

                if (isSolid(world, forwardPos.down())) {
                    neighbors.add(new Node(forwardPos, null, lastAirNode, lastAirNode.costFromStart + 1.2, calculateHeuristic(forwardPos, end), MovementState.WALKING, current.blocksUsed));
                    jumpedOverGap = true;
                    break;
                } else {
                    lastAirNode = new Node(forwardPos, null, lastAirNode, lastAirNode.costFromStart + 1.0, calculateHeuristic(forwardPos, end), MovementState.JUMPING_AIR, current.blocksUsed);
                }
            }

            if (!jumpedOverGap) {
                for (int drop = 1; drop <= 3; drop++) {
                    BlockPos dropPos = adjacent.down(drop);
                    if (!isPassable(world, dropPos) || !isPassable(world, dropPos.up())) break;

                    if (isWaterBlock(world, dropPos)) {
                        boolean nextIsSurface = !isWaterBlock(world, dropPos.up());
                        MovementState fallState = nextIsSurface ? MovementState.SWIMMING : MovementState.DIVING;
                        neighbors.add(new Node(dropPos, null, current, current.costFromStart + 1.2, calculateHeuristic(dropPos, end), fallState, current.blocksUsed));
                        break;
                    } else if (isSolid(world, dropPos.down())) {
                        neighbors.add(new Node(dropPos, null, current, current.costFromStart + 1.0 + (drop * 0.5), calculateHeuristic(dropPos, end), MovementState.FALLING, current.blocksUsed));
                        break;
                    }
                }
            }

            if (remainingBridging > 0) {
                BlockPos bridgePos = adjacent;
                double dynamicBridgeCost = getDynamicCost(remainingBridging, 5.0, 10.0);
                neighbors.add(new Node(bridgePos, null, current, current.costFromStart + dynamicBridgeCost, calculateHeuristic(bridgePos, end), MovementState.BUILDING_BRIDGE, current.blocksUsed + 1));
            }
        }
        return neighbors;
    }

    private static double calculateHeuristic(BlockPos a, BlockPos b) {
        double dx = Math.abs(a.getX() - b.getX());
        double dz = Math.abs(a.getZ() - b.getZ());
        double dy = Math.abs(a.getY() - b.getY());
        double horizontalDist = dx + dz;
        double yWeight;

        if (horizontalDist > 32) {
            yWeight = 0.1;
        } else if (horizontalDist > 8) {
            yWeight = 0.5;
        } else {
            yWeight = 1.2;
        }

        return horizontalDist + dy * yWeight;
    }
    private static List<Node> reconstructPath(Node node) {
        if (node == null) return new ArrayList<>();
        List<Node> path = new ArrayList<>();
        while (node != null) { path.add(node); node = node.parent; }
        Collections.reverse(path);
        return path;
    }

    private static boolean yieldsBuildingBlock(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.getMaterial().isLiquid() || state.getMaterial().isReplaceable()) return false;
        if (state.isIn(BlockTags.LEAVES)) return false;
        if(state.getBlock().is(Blocks.SNOW))return false;

        return state.getMaterial().isSolid();
    }

    private static boolean isSolid(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return false;
        if (state.getMaterial() == net.minecraft.block.Material.LAVA || isWaterBlock(world, pos)) return false;
        if (state.getBlock() == net.minecraft.block.Blocks.LILY_PAD) return false;

        // 【核心修复 1】：告诉寻路器门不是实心墙！
        if (state.getBlock() instanceof net.minecraft.block.DoorBlock) return false;

        return !state.getCollisionShape(world, pos).isEmpty();
    }

    private static boolean isPassable(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return true;
        if (isWaterBlock(world, pos)) return true;
        if (state.getMaterial() == net.minecraft.block.Material.LAVA) return false;
        if (state.getBlock() == net.minecraft.block.Blocks.LILY_PAD) return true;

        // 【核心修复 2】：告诉寻路器门是可以自由走进去的！
        if (state.getBlock() instanceof net.minecraft.block.DoorBlock) return true;

        return state.getCollisionShape(world, pos).isEmpty();
    }

    private static double getMiningCost(World world, BlockPos pos, Set<BlockPos> blacklist) {
        // 如果方块在黑名单中，将其挖掘代价设为无限大，寻路器将永远不会尝试去挖它！
        if (blacklist != null && blacklist.contains(pos)) return 9999.0;

        BlockState state = world.getBlockState(pos);
        if (isPassable(world, pos)) return 0.0;
        if (!state.getFluidState().isEmpty()) return 9999.0;

        float hardness = state.getHardness(world, pos);
        if (hardness < 0) return 9999.0; // 类似基岩不可破坏
        return hardness * 8.0;
    }

    public static class Node implements Comparable<Node> {
        public BlockPos pos;
        public BlockPos extraPos;
        public Node parent;
        public double costFromStart, estimatedCostToEnd, totalCost;
        public MovementState state;
        public int blocksUsed;

        public Node(BlockPos pos, BlockPos extraPos, Node parent, double costFromStart, double estimatedCostToEnd, MovementState state, int blocksUsed) {
            this.pos = pos;
            this.extraPos = extraPos;
            this.parent = parent;
            this.costFromStart = costFromStart;
            this.estimatedCostToEnd = estimatedCostToEnd;
            this.totalCost = costFromStart + estimatedCostToEnd;
            this.state = state;
            this.blocksUsed = blocksUsed;
        }
        @Override public String toString(){
            return "pos: " + pos.toShortString() + " state: " + state;
        }

        @Override public int compareTo(Node other) { return Double.compare(this.totalCost, other.totalCost); }
    }

    private static List<Node> smoothPath(World world, List<Node> path) {
        if (path == null || path.size() <= 2) return path;
        List<Node> verticallySmoothed = new ArrayList<>();
        verticallySmoothed.add(path.get(0));

        for (int i = 1; i < path.size() - 1; i++) {
            Node prev = verticallySmoothed.get(verticallySmoothed.size() - 1);
            Node curr = path.get(i);
            Node next = path.get(i + 1);

            if (prev.state == MovementState.DIVING && curr.state == MovementState.DIVING && next.state == MovementState.DIVING) {
                if (prev.pos.getX() == curr.pos.getX() && curr.pos.getX() == next.pos.getX() &&
                        prev.pos.getZ() == curr.pos.getZ() && curr.pos.getZ() == next.pos.getZ()) {
                    int dy1 = curr.pos.getY() - prev.pos.getY();
                    int dy2 = next.pos.getY() - curr.pos.getY();

                    if (dy1 == dy2) {
                        continue;
                    }
                }
            }
            verticallySmoothed.add(curr);
        }
        verticallySmoothed.add(path.get(path.size() - 1));
        List<Node> smoothedPath = new ArrayList<>();
        smoothedPath.add(verticallySmoothed.get(0));

        int currentIndex = 0;
        while (currentIndex < verticallySmoothed.size() - 1) {
            int furthestWalkableIndex = currentIndex + 1;
            int lookAheadLimit = Math.min(currentIndex + 5, verticallySmoothed.size());

            for (int i = currentIndex + 2; i < lookAheadLimit; i++) {
                if (canWalkStraight(world, verticallySmoothed.get(currentIndex), verticallySmoothed.get(i))) {
                    furthestWalkableIndex = i;
                } else {
                    break;
                }
            }

            smoothedPath.add(verticallySmoothed.get(furthestWalkableIndex));
            currentIndex = furthestWalkableIndex;
        }

        return smoothedPath;
    }
    private static boolean isWaterBlock(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getMaterial() == net.minecraft.block.Material.WATER ||
                state.getFluidState().isIn(net.minecraft.tag.FluidTags.WATER);
    }

    private static boolean canWalkStraight(World world, Node start, Node end) {
        if ((start.state != MovementState.WALKING && start.state != MovementState.SWIMMING && start.state != MovementState.DIVING) ||
                (end.state != MovementState.WALKING && end.state != MovementState.SWIMMING && end.state != MovementState.DIVING)) {
            return false;
        }
        if (start.pos.getY() != end.pos.getY()) {
            return false;
        }

        int minX = Math.min(start.pos.getX(), end.pos.getX());
        int maxX = Math.max(start.pos.getX(), end.pos.getX());
        int minZ = Math.min(start.pos.getZ(), end.pos.getZ());
        int maxZ = Math.max(start.pos.getZ(), end.pos.getZ());
        int y = start.pos.getY();

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos checkPos = new BlockPos(x, y, z);

                if (!isPassable(world, checkPos) || !isPassable(world, checkPos.up())) {
                    return false;
                }

                boolean isWater = isWaterBlock(world, checkPos);
                if (start.state == MovementState.SWIMMING || start.state == MovementState.DIVING) {
                    if (!isWater) return false;
                } else if (start.state == MovementState.WALKING) {
                    if (isWater) return false;
                    if (!isSolid(world, checkPos.down())) return false;
                }
            }
        }
        return true;
    }
}