package com.fredoseep.excutor;

import com.fredoseep.algorithm.SimplePathfinder;
import com.fredoseep.behave.IBotModule;
import com.fredoseep.behave.MiscController;
import com.fredoseep.behave.MovementController;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.CompletableFuture;

public class PathExecutor implements IBotModule {

    public enum State {IDLE, CALCULATING, EXECUTING}

    private State currentState = State.IDLE;
    private TempMissionType tempMissionType = TempMissionType.IDLE;
    private List<SimplePathfinder.Node> currentPath = null;
    private int currentPathIndex = 0;
    private BlockPos finalDestination = null;
    public Stack<BlockPos> suspendedDestinations = new Stack<>();
    public Set<BlockPos> currentBlacklist = Collections.emptySet();

    // 废弃了 nextPath，因为我们将使用无缝拼接流！
    private boolean isCalculatingNext = false;
    private boolean isPaused = false;
    private int stuckCheckTicks = 0;
    private int consecutiveStuckCount = 0; // 【新增】：记录连续卡死的次数
    private Vec3d lastStuckCheckPos = null;


    @Override
    public String getName() {
        return "Path_Executor";
    }

    @Override
    public int getPriority() {
        return 40;
    }

    @Override
    public boolean isBusy() {
        return !currentState.equals(State.IDLE);
    }

    @Override
    public void onEnable() {
        stop();
    }

    @Override
    public void onDisable() {
        stop();
        this.suspendedDestinations.clear();
        this.currentBlacklist.clear();
        this.finalDestination = null;
    }

    public State getCurrentState() {
        return this.currentState;
    }

    private void resetMovementKeys() {
        MovementController mc = BotEngine.getInstance().getModule(MovementController.class);
        if (mc != null) {
            mc.resetKeys();
        }
    }

    public List<SimplePathfinder.Node> getCurrentPath() {
        return this.currentPath;
    }

    public int getCurrentPathIndex() {
        return this.currentPathIndex;
    }

    public boolean isPaused() {
        return this.isPaused;
    }

    public void pause() {
        if (this.currentState == State.EXECUTING || this.currentState == State.CALCULATING) {
            this.isPaused = true;
            resetMovementKeys();
            MinecraftClient.getInstance().player.sendMessage(new LiteralText("§e[Bot] 寻路已暂停。"), false);
        }
    }

    public void resume() {
        if (this.isPaused) {
            this.isPaused = false;
            MinecraftClient.getInstance().player.sendMessage(new LiteralText("§a[Bot] 寻路已继续。"), false);
        }
    }

    public void togglePause() {
        if (this.isPaused) {
            resume();
        } else {
            pause();
        }
    }

    public SimplePathfinder.Node getCurrentNode() {
        return this.currentPath.get(currentPathIndex);
    }

    public void setGoal(BlockPos destination, String targetType) {
        setGoal(destination, Collections.emptySet(), targetType); // 兼容旧接口
    }

    // 支持黑名单的 setGoal
    public void setGoal(BlockPos destination, Set<BlockPos> blacklist, String targetType) {
        this.suspendedDestinations.clear();
        this.currentBlacklist = blacklist != null ? blacklist : Collections.emptySet();
        startNewPath(destination, targetType);
    }

    // 兼容 MiscController 的无参数临时任务
    public void setTemporaryGoal(BlockPos tempDestination) {
        setTemporaryGoal(tempDestination, TempMissionType.IDLE, this.currentBlacklist);
    }

    public void setTemporaryGoal(BlockPos tempDestination, TempMissionType type) {
        setTemporaryGoal(tempDestination, type, this.currentBlacklist);
    }

    // 支持黑名单的 setTemporaryGoal
    public void setTemporaryGoal(BlockPos tempDestination, TempMissionType type, Set<BlockPos> blacklist) {
        tempMissionType = type;
        if (this.finalDestination != null) {
            this.suspendedDestinations.push(this.finalDestination);
            System.out.println("FredoBot: 挂起原目标 -> " + this.finalDestination.toShortString() + "，前往临时目标 -> " + tempDestination.toShortString());
        }
        this.currentBlacklist = blacklist != null ? blacklist : Collections.emptySet();
        startNewPath(tempDestination, "temp target");
    }

    public void resumeSuspendedGoal() {
        tempMissionType = TempMissionType.IDLE;
        if (!this.suspendedDestinations.isEmpty()) {
            BlockPos target = this.suspendedDestinations.pop();
            System.out.println("FredoBot: 临时任务结束，恢复原目标 -> " + target.toShortString());
            startNewPath(target, "resume original target");
        } else {
            System.out.println("FredoBot: 没有被挂起的任务。");
        }
    }

    private void startNewPath(BlockPos dest, String targetType) {
        this.finalDestination = dest;
        this.isCalculatingNext = false;
        this.isPaused = false;
        this.stuckCheckTicks = 0;
        this.lastStuckCheckPos = null;
        this.consecutiveStuckCount = 0;


        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(new LiteralText("§e[Bot] 发起寻路计算... -> " + dest.toShortString()), false);
            System.out.println("Fredodebug: setGoalType: " + targetType);
            BlockPos startPos = new BlockPos(client.player.getX(), client.player.getY() + 0.2, client.player.getZ());
            recalculatePath(startPos);
        }
    }

    public boolean hasSuspendedGoal() {
        return !this.suspendedDestinations.isEmpty();
    }

    public void stop() {
        this.currentState = State.IDLE;
        this.isCalculatingNext = false;
        this.isPaused = false;
        this.stuckCheckTicks = 0;           // 【新增】
        this.lastStuckCheckPos = null;
        this.consecutiveStuckCount = 0; // 【新增】：开始新路径时重置
        tempMissionType = TempMissionType.IDLE;
        resetMovementKeys();
    }

    private void recalculatePath(BlockPos startPos) {
        if (finalDestination == null) return;
        currentState = State.CALCULATING;
        MinecraftClient client = MinecraftClient.getInstance();

        CompletableFuture.supplyAsync(() -> {
            return SimplePathfinder.findPath(client.world, client.player, startPos, finalDestination, currentBlacklist, 0);
        }).thenAccept(path -> {
            client.execute(() -> {
                if (path.isEmpty()) {
                    stop();
                    client.player.sendMessage(new LiteralText("§c[Bot] 寻路失败，前方无路可走！"), false);
                } else {
                    this.currentPath = path;
                    this.currentPathIndex = 0;
                    this.currentState = State.EXECUTING;
                    checkAndStartNextCalculation();
                }
            });
        });
    }

    private void checkAndStartNextCalculation() {
        if (currentPath == null || currentPath.isEmpty() || isCalculatingNext) return;

        BlockPos currentEnd = currentPath.get(currentPath.size() - 1).pos;
        int blocksUsedSoFar = currentPath.get(currentPath.size() - 1).blocksUsed;

        double distX = currentEnd.getX() - finalDestination.getX();
        double distY = currentEnd.getY() - finalDestination.getY();
        double distZ = currentEnd.getZ() - finalDestination.getZ();

        if ((distX * distX + distY * distY + distZ * distZ) > 2.0) {
            isCalculatingNext = true;
            MinecraftClient client = MinecraftClient.getInstance();

            CompletableFuture.supplyAsync(() -> {
                return SimplePathfinder.findPath(client.world, client.player, currentEnd, finalDestination, currentBlacklist, blocksUsedSoFar);
            }).thenAccept(path -> {
                client.execute(() -> {
                    isCalculatingNext = false;
                    if (!path.isEmpty()) {
                        if (!currentPath.isEmpty() && path.get(0).pos.equals(currentPath.get(currentPath.size() - 1).pos)) {
                            path.remove(0);
                        }
                        currentPath.addAll(path);

                        checkAndStartNextCalculation();
                    }
                });
            });
        }
    }

    @Override
    public void onTick(MinecraftClient client, PlayerEntity player) {
        if (isPaused) return;
        if (currentState != State.EXECUTING || currentPath == null) return;
        boolean isMining = false;
        if (lastStuckCheckPos == null) {
            lastStuckCheckPos = player.getPos();
            stuckCheckTicks = 0;
        } else {
            if (currentPathIndex < currentPath.size()) {
                if (currentPath.get(currentPathIndex).state == SimplePathfinder.MovementState.MINING) {
                    isMining = true;
                }
            }
        }
        if (isMining) {
            stuckCheckTicks = 0;
            lastStuckCheckPos = player.getPos();
        } else {
            stuckCheckTicks++;
            if (stuckCheckTicks >= 60) { // 3秒 = 60 Tick
                double dx = player.getX() - lastStuckCheckPos.x;
                double dz = player.getZ() - lastStuckCheckPos.z;
                double dy = Math.abs(player.getY() - lastStuckCheckPos.y);
                double horizontalDistSq = dx * dx + dz * dz;

                if (horizontalDistSq < 1.0 && dy < 1.0) {
                    consecutiveStuckCount++;
                    player.sendMessage(new net.minecraft.text.LiteralText("§c[Bot] 检测到被困！尝试重新寻路... (连续卡死: " + consecutiveStuckCount + " 次)"), false);
                    System.out.println("FredoBot: 触发被困保护，连续次数: " + consecutiveStuckCount + "，正在重新计算！");

                    // 【核心修复 1】：连续卡死 3 次直接放弃治疗！切断无限安全港刷屏。
                    // 彻底停止寻路，让外层的 MiscController 接手并增加受挫值
                    if (consecutiveStuckCount >= 3) {
                        System.out.println("FredoBot: 彻底被困，放弃当前寻路路线！");
                        stop();
                        return;
                    }

                    resetMovementKeys();
                    stuckCheckTicks = 0;
                    lastStuckCheckPos = player.getPos();

                    recalculatePath(new BlockPos(player.getX(), player.getY() + 0.2, player.getZ()));
                    return;
                }
                consecutiveStuckCount = 0;
                lastStuckCheckPos = player.getPos();
                stuckCheckTicks = 0;
            }
        }
        updateProgress(player);
        if (currentPathIndex > 500) {
            currentPath.subList(0, currentPathIndex - 1).clear();
            currentPathIndex = 1;
        }

        if (currentPathIndex < currentPath.size() && isOffThePath(player)) {
            player.sendMessage(new LiteralText("§c[Bot] 偏离路线，正在重新计算..."), false);
            resetMovementKeys();
            recalculatePath(new BlockPos(player.getX(), player.getY() + 0.2, player.getZ()));
            return;
        }

        if (currentPathIndex >= currentPath.size()) {
            double distX = player.getX() - (finalDestination.getX() + 0.5);
            double distZ = player.getZ() - (finalDestination.getZ() + 0.5);
            double distY = player.getY() - finalDestination.getY();
            double distanceSq = distX * distX + distY * distY + distZ * distZ;

            if (distanceSq <= 2.0) {
                player.sendMessage(new LiteralText("§b[Bot] 已到达最终目的地！"), false);
                stop();
            } else {
                if (isCalculatingNext) {
                    resetMovementKeys();
                    currentState = State.CALCULATING;
                } else {
                    resetMovementKeys();
                    recalculatePath(new BlockPos(player.getX(), player.getY() + 0.2, player.getZ()));
                }
            }
            return;
        }
        if (tempMissionType == TempMissionType.GO_TO_ENTITY && BotEngine.getInstance().getModule(MiscController.class).targetEntity == null) {
            player.sendMessage(new LiteralText("§b[Bot] Entity has gone"), false);
            stop();
        }

        SimplePathfinder.Node targetNode = currentPath.get(currentPathIndex);
        MovementController mc = BotEngine.getInstance().getModule(MovementController.class);
        if (mc != null) {
            mc.executeMovementNode(client, player, targetNode);
        }
    }

    private boolean isOffThePath(PlayerEntity player) {
        if (currentPath == null || currentPathIndex >= currentPath.size()) {
            return false;
        }

        SimplePathfinder.Node currentNode = currentPath.get(currentPathIndex);
        if (currentNode == null) return false;

        boolean inBoat = player.hasVehicle();
        Vec3d entityPos = inBoat ? player.getVehicle().getPos() : player.getPos();

        double hToleranceSq = inBoat ? 25.0 : 9.0;
        double vTolerance = 4.0;
        if (!player.isOnGround() && !player.isTouchingWater() && !player.isSwimming() && !player.hasVehicle()) {
            vTolerance = 25.0;
        }

        double targetX = currentNode.pos.getX() + 0.5;
        double targetY = currentNode.pos.getY();
        double targetZ = currentNode.pos.getZ() + 0.5;

        double startX, startY, startZ;

        if (currentPathIndex == 0) {
            double dx = entityPos.x - targetX;
            double dy = entityPos.y - targetY;
            double dz = entityPos.z - targetZ;
            return (dx * dx + dz * dz) > hToleranceSq || Math.abs(dy) > vTolerance;
        } else {
            SimplePathfinder.Node prevNode = currentPath.get(currentPathIndex - 1);
            startX = prevNode.pos.getX() + 0.5;
            startY = prevNode.pos.getY();
            startZ = prevNode.pos.getZ() + 0.5;
        }

        double px = entityPos.x;
        double pz = entityPos.z;
        double abx = targetX - startX;
        double aby = targetY - startY;
        double abz = targetZ - startZ;
        double apx = px - startX;
        double apz = pz - startZ;
        double abSquared = abx * abx + abz * abz;

        double t = 0.0;
        if (abSquared > 0) {
            t = (apx * abx + apz * abz) / abSquared;
            t = Math.max(-1.0, Math.min(2.0, t));
        }

        double closestX = startX + t * abx;
        double closestZ = startZ + t * abz;

        double expectedY;
        if (abSquared == 0) {
            expectedY = (startY + targetY) / 2.0;
            vTolerance = Math.abs(targetY - startY) + 3.0;
        } else {
            expectedY = startY + t * aby;
        }

        double dx = px - closestX;
        double dz = pz - closestZ;
        double distanceToPathSq = dx * dx + dz * dz;
        double dy = entityPos.y - expectedY;

        return distanceToPathSq > hToleranceSq || Math.abs(dy) > vTolerance;
    }


    private void updateProgress(PlayerEntity player) {
        World world = MinecraftClient.getInstance().world;
        if (world == null) return;

        boolean isStrictMode = consecutiveStuckCount >= 2;

        net.minecraft.util.math.Box lowerBody;

        if (isStrictMode) {
            lowerBody = new net.minecraft.util.math.Box(
                    player.getX() - 0.3, player.getY(), player.getZ() - 0.3,
                    player.getX() + 0.3, player.getY() + 0.5, player.getZ() + 0.3
            );
        } else if (player.hasVehicle()) {
            lowerBody = player.getVehicle().getBoundingBox().expand(0.5, 0.0, 0.5);
        } else if (player.isSwimming() || player.isTouchingWater()) {
            net.minecraft.util.math.Box baseBox = player.getBoundingBox().expand(0.2, 0.2, 0.2);
            net.minecraft.util.math.Vec3d lookDir = player.getRotationVec(1.0F).normalize().multiply(1.2);
            net.minecraft.util.math.Box headBox = baseBox.offset(lookDir.x, lookDir.y, lookDir.z);
            lowerBody = baseBox.union(headBox);
        } else {
            lowerBody = new net.minecraft.util.math.Box(
                    player.getX() - 0.3, player.getY(), player.getZ() - 0.3,
                    player.getX() + 0.3, player.getY() + 0.5, player.getZ() + 0.3
            );
        }

        int lookAheadLimit = Math.min(currentPathIndex + 5, currentPath.size());
        int furthestReachedIndex = -1;

        for (int i = currentPathIndex; i < lookAheadLimit; i++) {
            SimplePathfinder.Node node = currentPath.get(i);

            double expandXZ = 0.0;
            double expandYDown = 0.0;
            double expandYUp = 0.0;

            if (!isStrictMode) {
                if (node.state == SimplePathfinder.MovementState.SWIMMING) {
                    expandXZ = 0.35;
                    expandYDown = 0.5;
                    expandYUp = 0.5;
                } else if (node.state == SimplePathfinder.MovementState.DIVING) {
                    expandXZ = 0.5;
                    expandYDown = 1.0;
                    expandYUp = 1.0;
                } else if (node.state == SimplePathfinder.MovementState.JUMPING_UP ||
                        node.state == SimplePathfinder.MovementState.JUMPING_AIR ||
                        node.state == SimplePathfinder.MovementState.FALLING) {
                    expandXZ = 0.25;
                    expandYDown = 1.0;
                    expandYUp = 1.5;
                }

                if (node.state == SimplePathfinder.MovementState.WALKING && (i + 1 < currentPath.size())) {
                    SimplePathfinder.Node nextNode = currentPath.get(i + 1);
                    if (nextNode.state == SimplePathfinder.MovementState.JUMPING_UP) {
                        expandXZ = 0.35;
                    }
                }
            }

            net.minecraft.util.math.Box nodeBox = new net.minecraft.util.math.Box(
                    node.pos.getX() + 0.25 - expandXZ,
                    node.pos.getY() - expandYDown,
                    node.pos.getZ() + 0.25 - expandXZ,
                    node.pos.getX() + 0.75 + expandXZ,
                    node.pos.getY() + 1.0 + expandYUp,
                    node.pos.getZ() + 0.75 + expandXZ
            );

            boolean isPhysicallyReached = lowerBody.intersects(nodeBox);

            if (i == currentPath.size() - 1) {
                double dx = player.getX() - (node.pos.getX() + 0.5);
                double dz = player.getZ() - (node.pos.getZ() + 0.5);
                double dy = Math.abs(player.getY() - node.pos.getY());

                boolean isWater = node.state == SimplePathfinder.MovementState.SWIMMING || node.state == SimplePathfinder.MovementState.DIVING;
                double allowedDistSq = isWater ? 0.12 : 0.03;

                if (isStrictMode) {
                    allowedDistSq = 0.03;
                }

                isPhysicallyReached = (dx * dx + dz * dz <= allowedDistSq) && (dy <= 0.5);
            }

            boolean isPathBlocked = false;

            if (node.state == SimplePathfinder.MovementState.BUILDING_BRIDGE || node.state == SimplePathfinder.MovementState.BUILDING_PILLAR) {
                if (world.getBlockState(node.pos.down()).getMaterial().isReplaceable()) {
                    isPhysicallyReached = false;
                    isPathBlocked = true;
                }
            }
            if (node.state == SimplePathfinder.MovementState.MINING) {
                if (world.getBlockState(node.pos).getMaterial().isSolid()) {
                    isPhysicallyReached = false;
                    isPathBlocked = true; // 坚决阻断后续判定
                }
            }

            if (isPhysicallyReached) {
                furthestReachedIndex = i;
            }
            if (isPathBlocked) {
                break;
            }
        }

        if (furthestReachedIndex != -1) {
            currentPathIndex = furthestReachedIndex + 1;
        } else {
            boolean inAir = !player.isOnGround() && !player.isTouchingWater() && !player.isSwimming() && !player.hasVehicle();

            if (inAir && player.getVelocity().y < 0) {
                while (currentPathIndex < currentPath.size()) {
                    SimplePathfinder.Node node = currentPath.get(currentPathIndex);
                    if (player.getY() < node.pos.getY() - 0.5) {
                        if (node.state == SimplePathfinder.MovementState.WALKING ||
                                node.state == SimplePathfinder.MovementState.FALLING ||
                                node.state == SimplePathfinder.MovementState.JUMPING_AIR) {
                            currentPathIndex++;
                        } else {
                            break;
                        }
                    } else {
                        break;
                    }
                }
            }
        }
    }

    /**
     * 强制接管寻路：直接输入自定义的节点列表供机器人执行。
     * 非常适合用于硬编码的微操、短距离精确移动或规避特殊地形。
     * * @param customPath 你手动组装的 Node 列表
     */
    public void executeCustomPath(List<SimplePathfinder.Node> customPath) {
        if (customPath == null || customPath.isEmpty()) {
            stop();
            return;
        }

        this.isCalculatingNext = false;
        this.isPaused = false;
        this.stuckCheckTicks = 0;
        this.lastStuckCheckPos = null;
        this.consecutiveStuckCount = 0;
        resetMovementKeys();

        this.currentPath = customPath;
        this.currentPathIndex = 0;

        this.finalDestination = customPath.get(customPath.size() - 1).pos;

        this.currentState = State.EXECUTING;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(new LiteralText("§d[Bot] 收到微操指令，已接管自定义路线！"), false);
        }
    }

    public enum TempMissionType {
        IDLE, GO_TO_ENTITY;
    }

}