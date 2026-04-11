package com.fredoseep.excutor;

import com.fredoseep.algorithm.SimplePathfinder;
import com.fredoseep.behave.IBotModule;
import com.fredoseep.behave.MovementController;

import com.fredoseep.utils.RelevantDirectionHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;
import java.util.concurrent.CompletableFuture;

// 1. 实现 IBotModule 接口
public class PathExecutor implements IBotModule {

    public enum State {IDLE, CALCULATING, EXECUTING}

    private State currentState = State.IDLE;
    private List<SimplePathfinder.Node> currentPath = null;
    private int currentPathIndex = 0;
    private BlockPos finalDestination = null;

    private List<SimplePathfinder.Node> nextPath = null;
    private boolean isCalculatingNext = false;
    private boolean isPaused = false;

    // ==========================================
    // IBotModule 接口方法实现
    // ==========================================
    @Override
    public String getName() {
        return "Path_Executor";
    }

    @Override
    public int getPriority() {
        // 优先级 50，比 MovementController (100) 先执行
        // 这样可以确保在移动模块执行按键前，寻路器已经把目标节点塞给它了
        return 50;
    }

    @Override
    public boolean isBusy() {
        return !currentState.equals(State.IDLE);
    }

    @Override
    public void onEnable() {
        stop(); // 模块启用时，确保状态是干净的
    }

    @Override
    public void onDisable() {
        stop(); // 模块禁用时，停止寻路
    }

    public State getCurrentState(){
        return this.currentState;
    }
    // ==========================================
    // 辅助通信方法：通知移动控制器重置按键
    // ==========================================
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
            resetMovementKeys(); // 替换为实例调用
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

    public SimplePathfinder.Node getCurrentNode(){
        return this.currentPath.get(currentPathIndex);
    }


    public void setGoal(BlockPos destination) {
        this.finalDestination = destination;
        this.nextPath = null;
        this.isCalculatingNext = false;
        this.isPaused = false;

        MinecraftClient client = MinecraftClient.getInstance();
        client.player.sendMessage(new LiteralText("§e[Bot] 发起首次寻路计算..."), false);

        BlockPos startPos = new BlockPos(client.player.getX(), client.player.getY() + 0.2, client.player.getZ());
        recalculatePath(startPos);
    }

    public void stop() {
        this.currentState = State.IDLE;
        this.nextPath = null;
        this.isCalculatingNext = false;
        this.isPaused = false;
        resetMovementKeys(); // 替换为实例调用
    }

    private void recalculatePath(BlockPos startPos) {
        if (finalDestination == null) return;
        currentState = State.CALCULATING;
        MinecraftClient client = MinecraftClient.getInstance();

        CompletableFuture.supplyAsync(() -> {
            return SimplePathfinder.findPath(client.world, client.player, startPos, finalDestination);
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
        if (currentPath == null || currentPath.isEmpty() || isCalculatingNext || nextPath != null) return;

        BlockPos currentEnd = currentPath.get(currentPath.size() - 1).pos;

        double distX = currentEnd.getX() - finalDestination.getX();
        double distY = currentEnd.getY() - finalDestination.getY();
        double distZ = currentEnd.getZ() - finalDestination.getZ();

        if ((distX * distX + distY * distY + distZ * distZ) > 2.0) {
            isCalculatingNext = true;
            MinecraftClient client = MinecraftClient.getInstance();

            CompletableFuture.supplyAsync(() -> {
                return SimplePathfinder.findPath(client.world, client.player, currentEnd, finalDestination);
            }).thenAccept(path -> {
                client.execute(() -> {
                    isCalculatingNext = false;
                    if (!path.isEmpty()) {
                        nextPath = path;
                        if (currentState == State.CALCULATING && currentPathIndex >= currentPath.size()) {
                            currentPath = nextPath;
                            currentPathIndex = 0;
                            nextPath = null;
                            currentState = State.EXECUTING;
                            checkAndStartNextCalculation();
                        }
                    }
                });
            });
        }
    }

    // ==========================================
    // 核心 Tick 循环 (接管了原本的 tick() 方法)
    // ==========================================
    @Override
    public void onTick(MinecraftClient client, PlayerEntity player) {
        if (isPaused) return;
        if (currentState != State.EXECUTING || currentPath == null) return;

        updateProgress(player);

        // 2. 【新增】：实时检测是否异常偏离路线
        // 必须确保当前索引没越界，且确实偏离了路线
        if (currentPathIndex < currentPath.size() && isOffThePath(player)) {
            player.sendMessage(new LiteralText("§c[Bot] 偏离路线，正在重新计算..."), false);
            resetMovementKeys(); // 瞬间松开所有按键，防止乱跑

            // 发起重新寻路 (起点为玩家当前位置)
            recalculatePath(new BlockPos(player.getX(), player.getY() + 0.2, player.getZ()));
            return; // 这一帧直接结束，等待新的路径算出来
        }

        // 3. 终点判定与路径接力逻辑
        if (currentPathIndex >= currentPath.size()) {
            double distX = player.getX() - (finalDestination.getX() + 0.5);
            double distZ = player.getZ() - (finalDestination.getZ() + 0.5);
            double distY = player.getY() - finalDestination.getY();
            double distanceSq = distX * distX + distY * distY + distZ * distZ;

            if (distanceSq <= 2.0) {
                player.sendMessage(new LiteralText("§b[Bot] 已到达最终目的地！"), false);
                stop();
            } else {
                if (nextPath != null) {
                    currentPath = nextPath;
                    currentPathIndex = 0;
                    nextPath = null;

                    resetMovementKeys();
                    checkAndStartNextCalculation();
                } else {
                    if (isCalculatingNext) {
                        resetMovementKeys();
                        currentState = State.CALCULATING;
                    } else {
                        resetMovementKeys();
                        recalculatePath(new BlockPos(player.getX(), player.getY() + 0.2, player.getZ()));
                    }
                }
            }
            return;
        }

        // 4. 将当前节点派发给 MovementController 执行
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

        // 动态容差系统
        double hToleranceSq = inBoat ? 25.0 : 9.0;
        double vTolerance = 4.0;

        double targetX = currentNode.pos.getX() + 0.5;
        double targetY = currentNode.pos.getY();
        double targetZ = currentNode.pos.getZ() + 0.5;

        // ==========================================
        // 【核心修复】：重新定义“当前正在走的线段”起点
        // ==========================================
        double startX, startY, startZ;

        if (currentPathIndex == 0) {
            // 如果是路径的第一步，没有“上一个节点”，直接判定到目标点的绝对距离
            double dx = entityPos.x - targetX;
            double dy = entityPos.y - targetY;
            double dz = entityPos.z - targetZ;
            return (dx * dx + dz * dz) > hToleranceSq || Math.abs(dy) > vTolerance;
        } else {
            // 绝不能用 currentNode.parent！
            // 因为路径被 smoothPath (拉线算法) 优化过，原本的 parent 关系已经被打断了。
            // 必须用 currentPath 列表里，切切实实的“上一个途经点”来连线！
            SimplePathfinder.Node prevNode = currentPath.get(currentPathIndex - 1);
            startX = prevNode.pos.getX() + 0.5;
            startY = prevNode.pos.getY();
            startZ = prevNode.pos.getZ() + 0.5;
        }

        double px = entityPos.x;
        double pz = entityPos.z;

        // 计算当前行走线段的向量 AB
        double abx = targetX - startX;
        double aby = targetY - startY;
        double abz = targetZ - startZ;

        // 计算玩家偏离起点向量 AP
        double apx = px - startX;
        double apz = pz - startZ;

        double abSquared = abx * abx + abz * abz;

        double t = 0.0;
        if (abSquared > 0) {
            // 将玩家坐标投影到当前实际的行走线段上
            t = (apx * abx + apz * abz) / abSquared;
            t = Math.max(-1.0, Math.min(2.0, t)); // 允许两头有冲刺越界容差
        }

        // 计算线段上的最近投影点
        double closestX = startX + t * abx;
        double closestZ = startZ + t * abz;

        // 垂直判定
        double expectedY;
        if (abSquared == 0) {
            expectedY = (startY + targetY) / 2.0;
            vTolerance = Math.abs(targetY - startY) + 3.0;
        } else {
            expectedY = startY + t * aby;
        }

        // 最终偏离距离计算
        double dx = px - closestX;
        double dz = pz - closestZ;
        double distanceToPathSq = dx * dx + dz * dz;
        double dy = entityPos.y - expectedY;

        return distanceToPathSq > hToleranceSq || Math.abs(dy) > vTolerance;
    }

    private void updateProgress(PlayerEntity player) {
        World world = MinecraftClient.getInstance().world;
        if (world == null) return;

        net.minecraft.util.math.Box lowerBody;

        // ==========================================
        // 【核心修复 1】：多形态动态碰撞箱判定
        // ==========================================
        if (player.hasVehicle()) {
            lowerBody = player.getVehicle().getBoundingBox().expand(0.5, 0.0, 0.5);
        } else if (player.isSwimming()) {
            // 水中/游泳姿态：玩家的碰撞箱会变成水平的，且随波逐流。
            // 直接获取玩家此时真实的碰撞箱 (自动适配 0.6 的高度)，并整体膨胀，作为“水下大捕捉网”
            lowerBody = player.getBoundingBox().expand(0.2, 0.2, 0.2);
        } else {
            // 陆地正常行走：继续使用极其精准的下半身防误触碰撞箱
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

            // ==========================================
            // 【核心修复 2】：为游泳节点分配专属的极高宽容度
            // ==========================================
            if (node.state == SimplePathfinder.MovementState.SWIMMING) {
                // 水中游动不需要踩踏实地，漂浮误差极大，直接拉满验收框！
                expandXZ = 0.35;   // 水平放宽
                expandYDown = 0.5; // 允许从节点上方游过 (比如浮在水面)
                expandYUp = 0.5;   // 允许从节点下方潜水经过
            }
            else if (node.state == SimplePathfinder.MovementState.JUMPING_UP ||
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

            // 构建带动态宽容度的节点验收框
            net.minecraft.util.math.Box nodeBox = new net.minecraft.util.math.Box(
                    node.pos.getX() + 0.25 - expandXZ,
                    node.pos.getY() - expandYDown,
                    node.pos.getZ() + 0.25 - expandXZ,
                    node.pos.getX() + 0.75 + expandXZ,
                    node.pos.getY() + 1.0 + expandYUp,
                    node.pos.getZ() + 0.75 + expandXZ
            );

            boolean isPhysicallyReached = lowerBody.intersects(nodeBox);

            if (node.state == SimplePathfinder.MovementState.BUILDING_BRIDGE) {
                if (world.getBlockState(node.pos.down()).getMaterial().isReplaceable()) {
                    isPhysicallyReached = false;
                }
            }

            if (isPhysicallyReached) {
                furthestReachedIndex = i;
            }
        }

        if (furthestReachedIndex != -1) {
            currentPathIndex = furthestReachedIndex + 1;
        }
    }
}