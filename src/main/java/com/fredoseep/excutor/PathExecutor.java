package com.fredoseep.excutor;

import com.fredoseep.algorithm.SimplePathfinder;
import com.fredoseep.behave.IBotModule;
import com.fredoseep.behave.MovementController;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class PathExecutor implements IBotModule {

    public enum State {IDLE, CALCULATING, EXECUTING}

    private State currentState = State.IDLE;
    private List<SimplePathfinder.Node> currentPath = null;
    private int currentPathIndex = 0;
    private BlockPos finalDestination = null;

    // 废弃了 nextPath，因为我们将使用无缝拼接流！
    private boolean isCalculatingNext = false;
    private boolean isPaused = false;


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

    public void setGoal(BlockPos destination) {
        this.finalDestination = destination;
        this.isCalculatingNext = false;
        this.isPaused = false;

        MinecraftClient client = MinecraftClient.getInstance();
        client.player.sendMessage(new LiteralText("§e[Bot] 发起首次寻路计算..."), false);

        BlockPos startPos = new BlockPos(client.player.getX(), client.player.getY() + 0.2, client.player.getZ());
        recalculatePath(startPos);
    }

    public void stop() {
        this.currentState = State.IDLE;
        this.isCalculatingNext = false;
        this.isPaused = false;
        resetMovementKeys();
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
        if (currentPath == null || currentPath.isEmpty() || isCalculatingNext) return;

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

        net.minecraft.util.math.Box lowerBody;

        if (player.hasVehicle()) {
            lowerBody = player.getVehicle().getBoundingBox().expand(0.5, 0.0, 0.5);
        } else if (player.isSwimming() || player.isTouchingWater()) {
            net.minecraft.util.math.Box baseBox = player.getBoundingBox().expand(0.2, 0.2, 0.2);
            net.minecraft.util.math.Vec3d lookDir = player.getRotationVec(1.0F).normalize().multiply(1.2);
            net.minecraft.util.math.Box headBox = baseBox.offset(lookDir.x, lookDir.y, lookDir.z);
            lowerBody = baseBox.union(headBox);
        }else {
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
                isPhysicallyReached = (dx * dx + dz * dz <= 0.12) && (dy <= 1.0);
            }

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