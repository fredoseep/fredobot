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

        Node startNode = new Node(startPos, null, null, 0, calculateHeuristic(startPos, end), MovementState.WALKING, 0);
        openSet.add(startNode);

        Node closestNode = startNode;
        double minEarlyExitScore = Math.abs(startPos.getX() - end.getX()) + Math.abs(startPos.getZ() - end.getZ());        int nodesEvaluated = 0;

        while (!openSet.isEmpty() && nodesEvaluated < MAX_NODES) {
            Node current = openSet.poll();

            double dx = Math.abs(current.pos.getX() - end.getX());
            double dz = Math.abs(current.pos.getZ() - end.getZ());
            double horizontalDist = dx + dz;

            double statePenalty = 0.0;
            if (current.state == MovementState.BUILDING_BRIDGE || current.state == MovementState.BUILDING_PILLAR) {
                statePenalty = 15.0;
            } else if (current.state == MovementState.MINING || current.state == MovementState.FALLING || current.state == MovementState.JUMPING_AIR) {
                statePenalty = 5.0;
            }

            double earlyExitScore = horizontalDist + statePenalty;

            if (earlyExitScore < minEarlyExitScore) {
                minEarlyExitScore = earlyExitScore;
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

        if (isCurrentWater) {
            BlockPos upPos = current.pos.up();
            if (world.getBlockState(upPos).getMaterial() == net.minecraft.block.Material.WATER && isPassable(world, upPos.up())) {
                neighbors.add(new Node(upPos, null, current, current.costFromStart + 1.2, calculateHeuristic(upPos, end), MovementState.SWIMMING, current.blocksUsed));
            }

            BlockPos downPos = current.pos.down();
            if (world.getBlockState(downPos).getMaterial() == net.minecraft.block.Material.WATER) {
                neighbors.add(new Node(downPos, null, current, current.costFromStart + 1.2, calculateHeuristic(downPos, end), MovementState.SWIMMING, current.blocksUsed));
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

    private static boolean isSolid(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return false;
        if (state.getMaterial() == net.minecraft.block.Material.LAVA || state.getMaterial() == net.minecraft.block.Material.WATER) return false;
        return !state.getCollisionShape(world, pos).isEmpty();
    }
    private static boolean isPassable(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return true;
        if (state.getMaterial() == net.minecraft.block.Material.WATER) return true; // 水可以游过去
        if (state.getMaterial() == net.minecraft.block.Material.LAVA) return false; // 岩浆绝对不能碰
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
        if ((start.state != MovementState.WALKING && start.state != MovementState.SWIMMING) ||
                (end.state != MovementState.WALKING && end.state != MovementState.SWIMMING)) {
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