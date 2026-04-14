package com.fredoseep.algorithm;

import com.fredoseep.utils.InventoryHelper;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.*;

public class SimplePathfinder {

    private static final int MAX_NODES = 50000;
    private static final int ABUNDANT_BLOCK_THRESHOLD = 64;

    public enum MovementState {
        // 【新增】：剥离出独立的 DIVING（潜水）状态
        WALKING, FALLING, JUMPING_UP, JUMPING_AIR, BUILDING_BRIDGE, BUILDING_PILLAR, MINING, SWIMMING, DIVING
    }

    public static List<Node> findPath(World world, PlayerEntity player, BlockPos end) {
        BlockPos start = new BlockPos(player.getX(), player.getY() + 0.2, player.getZ());
        return findPath(world, player, start, end);
    }

    public static List<Node> findPath(World world, PlayerEntity player, BlockPos startPos, BlockPos end) {
        long startTime = System.currentTimeMillis();
        InventoryHelper.BlockCounts blockCounts = InventoryHelper.countAvailableBuildingBlocks(player);

        int margin = 100;
        int minX = Math.min(startPos.getX(), end.getX()) - margin;
        int maxX = Math.max(startPos.getX(), end.getX()) + margin;
        int minZ = Math.min(startPos.getZ(), end.getZ()) - margin;
        int maxZ = Math.max(startPos.getZ(), end.getZ()) + margin;

        PriorityQueue<Node> openSet = new PriorityQueue<>();
        Map<BlockPos, EnumMap<MovementState, Double>> costSoFar = new HashMap<>();

        Node startNode = new Node(startPos, null, null, 0, calculateHeuristic(startPos, end), MovementState.WALKING, 0);
        openSet.add(startNode);
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

            // 【白名单更新】：允许机器人在水面或水下安全截断
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

            for (Node neighbor : getNeighbors(world, current, end, blockCounts)) {
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
        return smoothPath(world, reconstructPath(closestNode == startNode ? null : closestNode));
    }

    private static double getDynamicCost(int blocksRemaining, double minCost, double maxCost) {
        if (blocksRemaining <= 0) return 9999.0;
        double scarcityRatio = 1.0 - ((double) Math.min(blocksRemaining, ABUNDANT_BLOCK_THRESHOLD) / ABUNDANT_BLOCK_THRESHOLD);
        return minCost + (maxCost - minCost) * scarcityRatio;
    }

    private static List<Node> getNeighbors(World world, Node current, BlockPos end, InventoryHelper.BlockCounts counts) {
        List<Node> neighbors = new ArrayList<>();
        Direction[] directions = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

        int remainingBridging = Math.max(0, counts.bridgingBlocks - current.blocksUsed);
        int remainingPillaring = Math.max(0, counts.pillaringBlocks - current.blocksUsed);

        boolean isCurrentWater = world.getBlockState(current.pos).getMaterial() == net.minecraft.block.Material.WATER;
        boolean isStandingOnWater = world.getBlockState(current.pos.down()).getMaterial() == net.minecraft.block.Material.WATER;

        boolean isSimulatedSolidBelow = isSolid(world, current.pos.down())
                || current.state == MovementState.BUILDING_PILLAR
                || current.state == MovementState.BUILDING_BRIDGE;

        boolean isCurrentMidAir = !isSimulatedSolidBelow && !isCurrentWater && !isStandingOnWater;

        if (isCurrentMidAir) {
            for (int drop = 1; drop <= 256; drop++) {
                BlockPos dropPos = current.pos.down(drop);
                if (!isPassable(world, dropPos) || !isPassable(world, dropPos.up())) break;

                boolean isWaterBelow = world.getBlockState(dropPos.down()).getMaterial() == net.minecraft.block.Material.WATER;
                if (isSolid(world, dropPos.down()) || isWaterBelow) {
                    if (!isWaterBelow && drop > 3) {
                        break;
                    }
                    neighbors.add(new Node(dropPos, null, current, current.costFromStart + (drop * 0.5), calculateHeuristic(dropPos, end), MovementState.FALLING, current.blocksUsed));
                    break;
                }
            }
            return neighbors;
        }

        // ==========================================
        // 【核心重构：水下 3D 动态代价引擎】
        // 彻底分离 SWIMMING 和 DIVING。根据与目标的 2D 距离智能规划潜水与上浮时机！
        // ==========================================
        if (isCurrentWater) {
            boolean isTargetWater = world.getBlockState(end).getMaterial() == net.minecraft.block.Material.WATER;
            double dist2D = Math.sqrt(Math.pow(current.pos.getX() - end.getX(), 2) + Math.pow(current.pos.getZ() - end.getZ(), 2));
            boolean isSurface = !world.getBlockState(current.pos.up()).getMaterial().equals(net.minecraft.block.Material.WATER);

            Direction[] allDirs = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN};

            for (Direction dir : allDirs) {
                BlockPos nextPos = current.pos.offset(dir);
                if (world.getBlockState(nextPos).getMaterial() == net.minecraft.block.Material.WATER) {
                    boolean nextIsSurface = !world.getBlockState(nextPos.up()).getMaterial().equals(net.minecraft.block.Material.WATER);
                    MovementState nextState = nextIsSurface ? MovementState.SWIMMING : MovementState.DIVING;
                    double moveCost = 1.2;

                    if (isSurface && !nextIsSurface) {
                        // 规则 1：水面 -> 水下 (尝试潜水)
                        if (!isTargetWater) moveCost = 999.0; // 目标在岸上，打死不潜水
                        else if (dist2D > 30) moveCost = 50.0; // 距离大于30，延迟潜水，强制水面赶路
                        else moveCost = 1.5; // 小于30，以稍微高于水平的代价倾斜潜水
                    }
                    else if (!isSurface && !nextIsSurface) {
                        // 规则 2：水下 -> 水下 (深海游动)
                        if (!isTargetWater || dist2D > 30) {
                            if (dir == Direction.UP) moveCost = 1.0; // 极速上浮逃逸
                            else moveCost = 10.0; // 惩罚深海水下乱游，逼迫其先上浮
                        } else {
                            if (dir == Direction.DOWN) moveCost = 1.5;
                            else moveCost = 1.2;
                        }
                    }
                    else if (!isSurface && nextIsSurface) {
                        // 水下 -> 水面 (破水而出)
                        moveCost = 1.0;
                    }

                    if (isPassable(world, nextPos) && isPassable(world, nextPos.up())) {
                        neighbors.add(new Node(nextPos, null, current, current.costFromStart + moveCost, calculateHeuristic(nextPos, end), nextState, current.blocksUsed));
                    }
                }
            }
        }

        BlockPos headPos = current.pos.up(2);
        double overheadMineCost = getMiningCost(world, headPos);

        if (remainingPillaring > 0 && overheadMineCost < 9999.0 && !isCurrentWater && !isStandingOnWater) {
            BlockPos pillarPos = current.pos.up();
            double dynamicPillarCost = getDynamicCost(remainingPillaring, 1.2, 3.5);
            double totalCost = current.costFromStart + dynamicPillarCost + overheadMineCost;
            neighbors.add(new Node(pillarPos, headPos, current, totalCost, calculateHeuristic(pillarPos, end), MovementState.BUILDING_PILLAR, current.blocksUsed + 1));
        }

        for (Direction dir : directions) {
            BlockPos adjacent = current.pos.offset(dir);
            BlockPos adjacentUp = adjacent.up();
            boolean isAdjacentWater = world.getBlockState(adjacent).getMaterial() == net.minecraft.block.Material.WATER;

            // 【重要拦截】：如果前面是水域，处理完边缘后直接 continue，防止与上面的全方位水域寻路冲突！
            if (isAdjacentWater) {
                if (!isCurrentWater) {
                    // 岸上 -> 扑通跳入水里
                    boolean nextIsSurface = !world.getBlockState(adjacent.up()).getMaterial().equals(net.minecraft.block.Material.WATER);
                    MovementState nextState = nextIsSurface ? MovementState.SWIMMING : MovementState.DIVING;
                    neighbors.add(new Node(adjacent, null, current, current.costFromStart + 1.2, calculateHeuristic(adjacent, end), nextState, current.blocksUsed));
                }
                continue;
            }

            if (!isPassable(world, adjacent) || !isPassable(world, adjacentUp)) {
                double mineCost = getMiningCost(world, adjacent) + getMiningCost(world, adjacentUp);
                if (mineCost < 9999.0) {
                    neighbors.add(new Node(adjacent, adjacentUp, current, current.costFromStart + 1.0 + mineCost, calculateHeuristic(adjacent, end), MovementState.MINING, current.blocksUsed));
                }

                if (isSolid(world, adjacent) && isPassable(world, adjacentUp) && isPassable(world, adjacent.up(2)) && isPassable(world, current.pos.up(2))) {
                    neighbors.add(new Node(adjacentUp, null, current, current.costFromStart + 1.5, calculateHeuristic(adjacentUp, end), MovementState.JUMPING_UP, current.blocksUsed));
                }
                continue;
            }

            if (isCurrentWater && isSolid(world, adjacent.down())) {
                neighbors.add(new Node(adjacent, null, current, current.costFromStart + 1.2, calculateHeuristic(adjacent, end), MovementState.WALKING, current.blocksUsed));
            } else if (isSolid(world, adjacent.down())) {
                neighbors.add(new Node(adjacent, null, current, current.costFromStart + 1.0, calculateHeuristic(adjacent, end), MovementState.WALKING, current.blocksUsed));
            } else {
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

                        if (world.getBlockState(dropPos).getMaterial() == net.minecraft.block.Material.WATER) {
                            neighbors.add(new Node(dropPos, null, current, current.costFromStart + 1.2, calculateHeuristic(dropPos, end), MovementState.SWIMMING, current.blocksUsed));
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

    private static boolean isSolid(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return false;
        if (state.getMaterial() == net.minecraft.block.Material.LAVA || state.getMaterial() == net.minecraft.block.Material.WATER) return false;
        if (state.getBlock() == net.minecraft.block.Blocks.LILY_PAD) return false;

        return !state.getCollisionShape(world, pos).isEmpty();
    }

    private static boolean isPassable(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return true;
        if (state.getMaterial() == net.minecraft.block.Material.WATER) return true;
        if (state.getMaterial() == net.minecraft.block.Material.LAVA) return false;
        if (state.getBlock() == net.minecraft.block.Blocks.LILY_PAD) return true;

        return state.getCollisionShape(world, pos).isEmpty();
    }

    private static double getMiningCost(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (isPassable(world, pos)) return 0.0;
        if (!state.getFluidState().isEmpty()) return 9999.0;

        float hardness = state.getHardness(world, pos);
        if (hardness < 0) return 9999.0;
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

        List<Node> smoothedPath = new ArrayList<>();
        smoothedPath.add(path.get(0));

        int currentIndex = 0;
        while (currentIndex < path.size() - 1) {
            int furthestWalkableIndex = currentIndex + 1;
            int lookAheadLimit = Math.min(currentIndex + 5, path.size());

            for (int i = currentIndex + 2; i < lookAheadLimit; i++) {
                if (canWalkStraight(world, path.get(currentIndex), path.get(i))) {
                    furthestWalkableIndex = i;
                } else {
                    break;
                }
            }

            smoothedPath.add(path.get(furthestWalkableIndex));
            currentIndex = furthestWalkableIndex;
        }

        return smoothedPath;
    }

    private static boolean canWalkStraight(World world, Node start, Node end) {
        // 【平滑扩容】：允许 SWIMMING 和 DIVING 在同一高度层面被拉直平滑
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
                if (start.state == MovementState.WALKING && !isSolid(world, checkPos.down())) {
                    return false;
                }
            }
        }
        return true;
    }
}