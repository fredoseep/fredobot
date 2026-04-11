package com.fredoseep.algorithm;

import com.fredoseep.utils.InventoryHelper;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.*;

public class SimplePathfinder {

    private static final int MAX_NODES = 20000;
    private static final int ABUNDANT_BLOCK_THRESHOLD = 64;

    public enum MovementState {
        WALKING, FALLING, JUMPING_UP, JUMPING_AIR, BUILDING_BRIDGE, BUILDING_PILLAR, MINING, SWIMMING
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
        Set<BlockPos> closedSet = new HashSet<>();

        // 起点也没有附加方块，传入 null
        Node startNode = new Node(startPos, null, null, 0, calculateHeuristic(startPos, end), MovementState.WALKING, 0);
        openSet.add(startNode);

        Node closestNode = startNode;
        double minHeuristic = startNode.estimatedCostToEnd;
        int nodesEvaluated = 0;

        while (!openSet.isEmpty() && nodesEvaluated < MAX_NODES) {
            Node current = openSet.poll();

            if (current.estimatedCostToEnd < minHeuristic) {
                minHeuristic = current.estimatedCostToEnd;
                closestNode = current;
            }

            if (current.pos.equals(end)) {
                long endTime = System.currentTimeMillis();
                System.out.println("[SimplePathfinder] 寻路成功！耗时: " + (endTime - startTime) + " ms");
                return smoothPath(world, reconstructPath(current));
            }

            closedSet.add(current.pos);
            nodesEvaluated++;

            for (Node neighbor : getNeighbors(world, current, end, blockCounts)) {
                if (closedSet.contains(neighbor.pos)) continue;

                if (neighbor.pos.getX() < minX || neighbor.pos.getX() > maxX ||
                        neighbor.pos.getZ() < minZ || neighbor.pos.getZ() > maxZ) {
                    continue;
                }

                boolean inOpenSet = false;
                for (Node openNode : openSet) {
                    if (openNode.pos.equals(neighbor.pos)) {
                        inOpenSet = true;
                        if (neighbor.costFromStart < openNode.costFromStart) {
                            openNode.costFromStart = neighbor.costFromStart;
                            openNode.totalCost = openNode.costFromStart + openNode.estimatedCostToEnd;
                            openNode.parent = neighbor.parent;
                            openNode.state = neighbor.state;
                            openNode.blocksUsed = neighbor.blocksUsed;
                            openSet.remove(openNode);
                            openSet.add(openNode);
                        }
                        break;
                    }
                }

                if (!inOpenSet) openSet.add(neighbor);
            }
        }

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
        if (isCurrentWater) {
            BlockPos upPos = current.pos.up();
            // 【修复】：向上游的时候，必须确保头顶那格也是水！
            // 如果头顶是空气（说明已经到了水面），就不需要再往空气里生成游泳节点了，后续的边缘判断会接管出水逻辑
            if (world.getBlockState(upPos).getMaterial() == net.minecraft.block.Material.WATER && isPassable(world, upPos.up())) {
                neighbors.add(new Node(upPos, null, current, current.costFromStart + 1.2, calculateHeuristic(upPos, end), MovementState.SWIMMING, current.blocksUsed));
            }

            BlockPos downPos = current.pos.down();
            // 【修复】：向下潜水同理，必须确保下面也是水
            if (world.getBlockState(downPos).getMaterial() == net.minecraft.block.Material.WATER) {
                neighbors.add(new Node(downPos, null, current, current.costFromStart + 1.2, calculateHeuristic(downPos, end), MovementState.SWIMMING, current.blocksUsed));
            }
        }

        // ==========================================
        // 动作：原地垫高 / 向上破顶逃生
        // 【核心修复】：不再要求头顶必须是空气！只要头顶的方块能挖得动，就允许生成“先破顶再垫高”的节点
        // ==========================================
        BlockPos headPos = current.pos.up(2); // 头顶那格
        double overheadMineCost = getMiningCost(world, headPos);

        // 【新增水域检测】：获取玩家脚底下的方块材质
        boolean isStandingOnWater = world.getBlockState(current.pos.down()).getMaterial() == net.minecraft.block.Material.WATER;

        // 【核心拦截】：如果玩家当前泡在水里 (isCurrentWater)，或者脚底下踩的是水 (isStandingOnWater)，绝对不允许原地垫高！
        if (remainingPillaring > 0 && overheadMineCost < 9999.0 && !isCurrentWater && !isStandingOnWater) {

            BlockPos pillarPos = current.pos.up();
            double dynamicPillarCost = getDynamicCost(remainingPillaring, 1.2, 3.5);
            // 加上可能存在的破顶挖掘代价
            double totalCost = current.costFromStart + dynamicPillarCost + overheadMineCost;

            // 把头顶的方块设为 extraPos。执行器看到后，要先挖 extraPos，再往脚下放 pillarPos！
            neighbors.add(new Node(pillarPos, headPos, current, totalCost, calculateHeuristic(pillarPos, end), MovementState.BUILDING_PILLAR, current.blocksUsed + 1));
        }

        for (Direction dir : directions) {
            BlockPos adjacent = current.pos.offset(dir);
            BlockPos adjacentUp = adjacent.up();
            boolean isAdjacentWater = world.getBlockState(adjacent).getMaterial() == net.minecraft.block.Material.WATER;

            if (!isPassable(world, adjacent) || !isPassable(world, adjacentUp)) {
                double mineCost = getMiningCost(world, adjacent) + getMiningCost(world, adjacentUp);
                if (mineCost < 9999.0 && isSolid(world, adjacent.down())) {
                    // 【核心修复】：将目标上下两格都存入 Node，adjacent 是下半身，adjacentUp 是上半身
                    neighbors.add(new Node(adjacent, adjacentUp, current, current.costFromStart + 1.0 + mineCost, calculateHeuristic(adjacent, end), MovementState.MINING, current.blocksUsed));
                }
                if (isSolid(world, adjacent) && isPassable(world, adjacentUp) && isPassable(world, adjacent.up(2)) && isPassable(world, current.pos.up(2))) {
                    neighbors.add(new Node(adjacentUp, null, current, current.costFromStart + 1.5, calculateHeuristic(adjacentUp, end), MovementState.JUMPING_UP, current.blocksUsed));
                }
                continue;
            }

            if (isAdjacentWater) {
                neighbors.add(new Node(adjacent, null, current, current.costFromStart + 1.2, calculateHeuristic(adjacent, end), MovementState.SWIMMING, current.blocksUsed));
            } else if (isCurrentWater && isSolid(world, adjacent.down())) {
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

    private static boolean isSolid(World world, BlockPos pos) { return world.getBlockState(pos).getMaterial().isSolid(); }

    private static boolean isPassable(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.getMaterial() == net.minecraft.block.Material.LAVA) return false;
        if (state.getMaterial() == net.minecraft.block.Material.WATER) return true;
        return !state.getMaterial().isSolid();
    }

    private static double getMiningCost(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || !state.getMaterial().isSolid()) return 0.0;
        if (!state.getFluidState().isEmpty()) return 9999.0;
        float hardness = state.getHardness(world, pos);
        if (hardness < 0) return 9999.0;

        // 【核心优化】：成倍增加挖掘惩罚！
        // 将乘数 1.5 改为 8.0。这意味着挖开两块泥土的算法代价，相当于在平地上跑 16 格！
        // 通过加大挖掘代价，迫使算法在看到长距离地下目标时，优先选择向上破土寻找地表，而不是化身穿山甲硬挖。
        return hardness * 8.0;
    }

    public static class Node implements Comparable<Node> {
        public BlockPos pos;
        public BlockPos extraPos; // 【新增】：用于记录需要互动的附加方块（比如上半身或头顶）
        public Node parent;
        public double costFromStart, estimatedCostToEnd, totalCost;
        public MovementState state;
        public int blocksUsed;

        public Node(BlockPos pos, BlockPos extraPos, Node parent, double costFromStart, double estimatedCostToEnd, MovementState state, int blocksUsed) {
            this.pos = pos;
            this.extraPos = extraPos; // 记录附加方块
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
    // ==========================================
    // 路径平滑 (拉线算法) 核心逻辑
    // ==========================================
    private static List<Node> smoothPath(World world, List<Node> path) {
        if (path == null || path.size() <= 2) return path;

        List<Node> smoothedPath = new ArrayList<>();
        smoothedPath.add(path.get(0));

        int currentIndex = 0;
        while (currentIndex < path.size() - 1) {
            int furthestWalkableIndex = currentIndex + 1;

            // 往后看最多 5 个节点（避免长距离算力消耗太大，且应对复杂地形）
            int lookAheadLimit = Math.min(currentIndex + 5, path.size());

            for (int i = currentIndex + 2; i < lookAheadLimit; i++) {
                if (canWalkStraight(world, path.get(currentIndex), path.get(i))) {
                    furthestWalkableIndex = i; // 如果能直线走到 i，就更新最远可达点
                } else {
                    break; // 如果中间被挡住了，更后面的也别看了
                }
            }

            smoothedPath.add(path.get(furthestWalkableIndex));
            currentIndex = furthestWalkableIndex;
        }

        return smoothedPath;
    }

    private static boolean canWalkStraight(World world, Node start, Node end) {
        // 规则 1：只对普通的平地行走和水面游泳进行平滑。搭桥、挖掘、跳跃等复杂动作绝对不能平滑！
        if ((start.state != MovementState.WALKING && start.state != MovementState.SWIMMING) ||
                (end.state != MovementState.WALKING && end.state != MovementState.SWIMMING)) {
            return false;
        }

        // 规则 2：必须在同一高度。跨高度的平滑极易导致摔落或卡墙角
        if (start.pos.getY() != end.pos.getY()) {
            return false;
        }

        int minX = Math.min(start.pos.getX(), end.pos.getX());
        int maxX = Math.max(start.pos.getX(), end.pos.getX());
        int minZ = Math.min(start.pos.getZ(), end.pos.getZ());
        int maxZ = Math.max(start.pos.getZ(), end.pos.getZ());
        int y = start.pos.getY();

        // 规则 3：检查两点形成的矩形包围盒内的所有方块
        // 这是为了防止机器人被方块的边角卡住 (因为玩家碰撞箱有 0.6 宽)
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos checkPos = new BlockPos(x, y, z);

                // 身体和头顶必须畅通无阻
                if (!isPassable(world, checkPos) || !isPassable(world, checkPos.up())) {
                    return false;
                }

                // 如果是走路，脚底下的方块必须是实心的（防止直线走过去掉进坑里）
                if (start.state == MovementState.WALKING && !isSolid(world, checkPos.down())) {
                    return false;
                }
            }
        }
        return true;
    }
}