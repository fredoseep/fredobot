package com.fredoseep.excutor;

import com.fredoseep.algorithm.SimplePathfinder;
import com.fredoseep.algorithm.planning.AsyncPlanner;
import com.fredoseep.algorithm.planning.PlannerDebug;
import com.fredoseep.algorithm.planning.PlannerSettings;
import com.fredoseep.algorithm.planning.RetryBackoff;
import com.fredoseep.algorithm.planning.PathReachBoxes;
import com.fredoseep.algorithm.planning.StuckDump;
import com.fredoseep.algorithm.planning.UnderwaterGravelWork;
import com.fredoseep.algorithm.planning.SurfaceBoatRoute;
import net.minecraft.tag.FluidTags;
import com.fredoseep.algorithm.planning.PlannerDiagnostics;

import com.fredoseep.behave.IBotModule;
import com.fredoseep.behave.MiscController;
import com.fredoseep.behave.MovementController;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import java.util.Map;

public class PathExecutor implements IBotModule {

    public enum State {IDLE, CALCULATING, EXECUTING}

    public static boolean USE_ACTION_PLANNER = true;
    private volatile long calculationGeneration = 0;
    private AsyncPlanner plannerJob;
    private PlannerDebug plannerDebug;
    private record RequestKey(World world,BlockPos start,BlockPos goal,Set<BlockPos> blacklist) {}
    private final RetryBackoff<RequestKey> retryBackoff=new RetryBackoff<>();
    private boolean breathingDoorGoal;
    private long searchFailureSerial;
    private World lastSearchFailureWorld;
    private BlockPos lastSearchFailureGoal;
    public boolean searchFailedFor(World world,BlockPos goal,long sinceSerial) {
        return searchFailureSerial>sinceSerial && lastSearchFailureWorld==world
                && goal!=null && goal.equals(lastSearchFailureGoal);
    }
    /** A confirmed bank launch has already traversed the dry entry; retain only the safe water segment. */
    public void enterBoatSurfaceRoute() {
        World world=MinecraftClient.getInstance().world;
        if(currentPath==null || world==null)return;
        int entry=SurfaceBoatRoute.launchEntry(world,currentPath,currentPathIndex);
        if(entry>=0) {
            STUCK_LOG.info("BOAT_ROUTE_ENTRY old_index={} water_index={}",currentPathIndex,entry);
            currentPathIndex=entry;
        }
    }

    public boolean isBreathingDoorGoal(){return breathingDoorGoal;}
    public boolean isFinalNode(){return currentPath!=null && currentPathIndex==currentPath.size()-1;}
    public long getSearchFailureSerial(){return searchFailureSerial;}
    public void recordMiningTarget(BlockPos block,BlockPos feetGoal) {
        if(plannerDebug!=null && plannerDebug.diagnostics!=null)
            plannerDebug.diagnostics.event("mining_target",Map.of("block",block.toShortString(),"feet_goal",feetGoal.toShortString()));
    }
    public boolean trySetGoal(BlockPos destination,String targetType) {
        MinecraftClient client=MinecraftClient.getInstance();
        if(!client.isOnThread() || client.player==null || client.world==null || isBusy()
                || !requestAllowed(destination,Collections.emptySet()))return false;
        long before=calculationGeneration;
        setGoal(destination,targetType);
        return calculationGeneration!=before && currentState==State.CALCULATING;
    }
    private RequestKey requestKey(MinecraftClient client,BlockPos goal,Set<BlockPos> blacklist) {
        return new RequestKey(client.world,new BlockPos(client.player.getX(),client.player.getY()+.2,client.player.getZ()),
                goal.toImmutable(),Set.copyOf(blacklist));
    }
    private boolean requestAllowed(BlockPos goal,Set<BlockPos> blacklist) {
        MinecraftClient client=MinecraftClient.getInstance();
        return client.player==null || goal==null || retryBackoff.allowed(requestKey(client,goal,blacklist),System.nanoTime()/1_000_000);
    }
    private boolean reportFailure(MinecraftClient client) {
        searchFailureSerial++;
        lastSearchFailureWorld=client.world;
        lastSearchFailureGoal=finalDestination==null?null:finalDestination.toImmutable();
        return client.player==null || finalDestination==null || retryBackoff.fail(requestKey(client,finalDestination,currentBlacklist),System.nanoTime()/1_000_000)==1;
    }
    public PlannerDebug.View getSearchView() {
        return currentState == State.CALCULATING && plannerDebug != null ? plannerDebug.view() : null;
    }
    /** Called once per rendered frame, including vanilla F3+Esc pause frames. */
    public void pumpPlanner(MinecraftClient client) {
        AsyncPlanner job=plannerJob;
        if(job==null) return;
        job.pump(client);
        if(job.ready()) {
            plannerJob=null;
            List<SimplePathfinder.Node> path=job.result();
            if(path.isEmpty()) {
                boolean notify=reportFailure(client);
                stop();
                if(notify && client.player!=null) client.player.sendMessage(new LiteralText("§c[Bot] 寻路失败，相同起点和目标将退避重试，详情见 [ActionPlanner] 日志。"),false);
            } else {
                retryBackoff.clear();
                currentPath=path;currentPathUsesActionPlanner=true; currentPathIndex=0; currentState=State.EXECUTING;
                if(breathingDoorGoal) STUCK_LOG.info("BREATHING_DOOR_ROUTE player={} swimming={} nodes={}",client.player.getPos(),
                        client.player.isSwimming(),path.stream().map(n -> n.pos.toShortString()+":"+n.state).toList());
            }
        } else if(job.failed() || job.cancelled()) {
            boolean notify=!job.failed() || reportFailure(client);
            plannerJob=null;
            stop();
            if(notify && client.player!=null) client.player.sendMessage(new LiteralText(job.failed()
                    ? "§c[Bot] 寻路出错，详情见 [ActionPlanner] 日志。" : "§e[Bot] 寻路已取消。"),false);
        }
    }
    private State currentState = State.IDLE;
    private TempMissionType tempMissionType = TempMissionType.IDLE;
    private List<SimplePathfinder.Node> currentPath = null;
    private boolean currentPathUsesActionPlanner;
    private int currentPathIndex = 0;
    private BlockPos finalDestination = null;
    public Stack<BlockPos> suspendedDestinations = new Stack<>();
    public Set<BlockPos> currentBlacklist = Collections.emptySet();

    // 废弃了 nextPath，因为我们将使用无缝拼接流！
    private boolean isCalculatingNext = false;
    private boolean isPaused = false;
    private static final Logger STUCK_LOG =
            LogManager.getLogger("FredoBotPathfinder");
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
        retryBackoff.clear();
        stop();
    }

    @Override
    public void onDisable() {
        retryBackoff.clear();
        stop();
        this.suspendedDestinations.clear();
        this.currentBlacklist = Collections.emptySet();
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

    public BlockPos getFinalDestination() {
        return this.finalDestination;
    }

    public boolean isUsingActionPlanner() {
        return this.currentPathUsesActionPlanner;
    }

    public int getConsecutiveStuckCount() {
        return this.consecutiveStuckCount;
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
        MinecraftClient client=MinecraftClient.getInstance();
        if(!client.isOnThread()) {
            BlockPos immutable=destination.toImmutable();
            Set<BlockPos> copy=blacklist==null?Collections.emptySet():Set.copyOf(blacklist);
            client.execute(() -> setGoal(immutable,copy,targetType)); return;
        }
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
        if(!requestAllowed(tempDestination,blacklist==null?Collections.emptySet():blacklist))return;
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
        MinecraftClient dispatchClient=MinecraftClient.getInstance();
        if(!dispatchClient.isOnThread()) { dispatchClient.execute(() -> startNewPath(dest,targetType)); return; }
        if(!requestAllowed(dest,currentBlacklist))return;
        this.breathingDoorGoal="breathing door".equals(targetType);
        this.finalDestination = dest;
        this.isCalculatingNext = false;
        this.isPaused = false;
        this.stuckCheckTicks = 0;
        this.lastStuckCheckPos = null;
        this.consecutiveStuckCount = 0;


        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            if(retryBackoff.failures(requestKey(client,dest,currentBlacklist))==0)
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
        MinecraftClient client=MinecraftClient.getInstance();
        if(!client.isOnThread()) { client.execute(this::stop); return; }
        calculationGeneration++;
        if(plannerJob!=null) { plannerJob.cancel(client,"executor stopped"); plannerJob=null; }
        this.currentState = State.IDLE;
        this.isCalculatingNext = false;
        this.isPaused = false;
        this.stuckCheckTicks = 0;           // 【新增】
        this.lastStuckCheckPos = null;
        this.consecutiveStuckCount = 0; // 【新增】：开始新路径时重置
        tempMissionType = TempMissionType.IDLE;
        resetMovementKeys();
    }

    public void replanFromPlayer() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && finalDestination != null)
            recalculatePath(new BlockPos(client.player.getX(), client.player.getY() + 0.2, client.player.getZ()));
    }

    private void recalculatePath(BlockPos startPos) {
        MinecraftClient client=MinecraftClient.getInstance();
        if(!client.isOnThread()) { client.execute(() -> recalculatePath(startPos)); return; }
        if(finalDestination==null || client.world==null || client.player==null) return;
        if(!requestAllowed(finalDestination,currentBlacklist)){stop();return;}
        if(plannerJob!=null) { plannerJob.cancel(client,"superseded by new goal/replan"); plannerJob=null; }
        currentState=State.CALCULATING; currentPath=null; currentPathIndex=0; resetMovementKeys();
        final long generation=++calculationGeneration;
        final BlockPos destination=finalDestination.toImmutable();
        final Set<BlockPos> blacklist=Set.copyOf(currentBlacklist);
        if(USE_ACTION_PLANNER) {
            try {
                PlannerSettings settings=PlannerSettings.DEFAULT.copy();
                if(breathingDoorGoal) {
                    settings.preferVerticalSurfacing=false;settings.allowWorldEdits=false;
                    settings.minimumRouteY=Math.min(startPos.getY(),destination.getY());
                }
                plannerJob=new AsyncPlanner(client,destination,blacklist,settings,generation);
                plannerDebug=plannerJob.debug;
                if(plannerDebug.diagnostics!=null) {
                    MiscController misc=BotEngine.getInstance().getModule(MiscController.class);
                    plannerDebug.diagnostics.event("movement_request",Map.of(
                            "goal_kind",breathingDoorGoal?"BREATHING_DOOR":"GENERAL",
                            "underwater_phase",misc==null?"NONE":misc.uwPhase.name(),
                            "minimum_route_y",settings.minimumRouteY,"allow_world_edits",settings.allowWorldEdits));
                }
            } catch(RuntimeException error) {
                boolean notify=reportFailure(client);
                error.printStackTrace(); stop();
                if(notify)client.player.sendMessage(new LiteralText("§c[Bot] 无法启动寻路，详情见日志。"),false);
            }
            return;
        }
        final World capturedWorld=client.world;
        final PlayerEntity capturedPlayer=client.player;
        CompletableFuture.supplyAsync(() -> SimplePathfinder.findPath(capturedWorld,capturedPlayer,startPos,destination,blacklist,0))
                .thenAccept(path -> client.execute(() -> {
                    if(generation!=calculationGeneration || client.world!=capturedWorld || client.player!=capturedPlayer) return;
                    if(path.isEmpty()) stop();
                    else { currentPath=path;currentPathUsesActionPlanner=false; currentPathIndex=0; currentState=State.EXECUTING; checkAndStartNextCalculation(); }
                })).exceptionally(error -> {
                    client.execute(() -> { if(generation==calculationGeneration) { error.printStackTrace(); stop(); } }); return null;
                });
    }

    private void checkAndStartNextCalculation() {
        // A partial route is replanned after execution, with actual inventory and actual edits.
        // Searching ahead against an unchanged world would double-spend blocks and forget excavations.
        if (USE_ACTION_PLANNER) return;
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

    private void dumpStuck(MinecraftClient client,PlayerEntity player,double dx,double dy,double dz,double minMovement) {
        if(!PlannerSettings.DEFAULT.diagnosticsEnabled)return;
        PlannerDiagnostics diagnostic=null;
        try {
            var keys=Map.ofEntries(
                    Map.entry("forward",client.options.keyForward.isPressed()),
                    Map.entry("back",client.options.keyBack.isPressed()),
                    Map.entry("left",client.options.keyLeft.isPressed()),
                    Map.entry("right",client.options.keyRight.isPressed()),
                    Map.entry("jump",client.options.keyJump.isPressed()),
                    Map.entry("sprint",client.options.keySprint.isPressed()),
                    Map.entry("sneak",client.options.keySneak.isPressed()),
                    Map.entry("attack",client.options.keyAttack.isPressed()),
                    Map.entry("use",client.options.keyUse.isPressed()));
            var state=StuckDump.capture(client.world,player,currentPath,currentPathIndex,finalDestination,
                    currentPathUsesActionPlanner,consecutiveStuckCount,stuckCheckTicks,dx,dy,dz,minMovement,keys);
            state.put("min_vertical_movement_blocks",PlannerSettings.DEFAULT.stuckMinVerticalMovement);
            MovementController movement=BotEngine.getInstance().getModule(MovementController.class);
            if(movement!=null && currentPathIndex<currentPath.size())state.put("jump_control",movement.jumpDiagnostic(currentPath.get(currentPathIndex)));
            if(movement!=null)state.put("surface_swim_phase",movement.swimPhase());
            diagnostic=new PlannerDiagnostics(client.runDirectory.toPath(),calculationGeneration,
                    Thread.currentThread(),error -> STUCK_LOG.error("STUCK_DUMP_WRITE_FAILED",error),
                    PlannerSettings.DEFAULT.keepSuccessfulDiagnostics);
            diagnostic.phase("STUCK","captured before releasing movement keys");
            diagnostic.stuckDump(state);diagnostic.close("failed");
            STUCK_LOG.info("[ActionPlanner #{}] STUCK_DUMP index={} count={} ticks={} archive={} (async finalization)",
                    calculationGeneration,currentPathIndex,consecutiveStuckCount+1,stuckCheckTicks,diagnostic.archive());
        } catch(RuntimeException error) {
            if(diagnostic!=null){diagnostic.error(error);diagnostic.close("failed");}
            STUCK_LOG.error("STUCK_DUMP_CAPTURE_FAILED",error);
        }
    }

    public void failCurrentJump(PlayerEntity player) {
        if(currentState!=State.EXECUTING || currentPath==null)return;
        Vec3d before=lastStuckCheckPos==null?player.getPos():lastStuckCheckPos;
        dumpStuck(MinecraftClient.getInstance(),player,player.getX()-before.x,player.getY()-before.y,
                player.getZ()-before.z,PlannerSettings.DEFAULT.stuckMinMovement);
        consecutiveStuckCount++;
        STUCK_LOG.info("JUMP_RETRY_LIMIT count={} node={}",consecutiveStuckCount,currentPathIndex);
        if(consecutiveStuckCount>=3){stop();return;}
        stuckCheckTicks=0;lastStuckCheckPos=player.getPos();replanFromPlayer();
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
                SimplePathfinder.Node stuckNode = currentPath.get(currentPathIndex);
                if ((stuckNode.plannedAction == null && stuckNode.state == SimplePathfinder.MovementState.MINING
                            && !MovementController.getObstaclesInTunnel(player,client.world,stuckNode.pos).isEmpty())
                        || (stuckNode.plannedAction != null && stuckNode.plannedAction.breaks.stream().anyMatch(
                                p -> !client.world.getBlockState(p).getCollisionShape(client.world, p).isEmpty()))) {
                    isMining = true;
                }
            }
        }
        MovementController movement=BotEngine.getInstance().getModule(MovementController.class);
        if(movement!=null)movement.alignNewBoatRoute(this,player);
        boolean turningJump=movement!=null && currentPathIndex<currentPath.size()
                && movement.jumpTurningProgress(currentPath.get(currentPathIndex));
        boolean startingSwim=movement!=null && movement.swimStarting();
        boolean startingBoat=movement!=null && movement.boatLaunching();
        if (isMining || turningJump || startingSwim || startingBoat) {
            stuckCheckTicks = 0;
            lastStuckCheckPos = player.getPos();
        } else {
            stuckCheckTicks++;
            if (stuckCheckTicks >= Math.max(1,PlannerSettings.DEFAULT.stuckWindowTicks)) {
                double dx = player.getX() - lastStuckCheckPos.x;
                double dz = player.getZ() - lastStuckCheckPos.z;
                double dy = Math.abs(player.getY() - lastStuckCheckPos.y);

                double minMovement = PlannerSettings.DEFAULT.stuckMinMovement;
                if (!Double.isFinite(minMovement) || minMovement <= 0) minMovement = .3;
                if (PlannerSettings.DEFAULT.isStuckDisplacement(dx,dy,dz)) {
                    dumpStuck(client,player,dx,player.getY()-lastStuckCheckPos.y,dz,minMovement);
                    consecutiveStuckCount++;
                    player.sendMessage(new LiteralText("§c[Bot] 检测到被困！尝试重新寻路... (连续卡死: " + consecutiveStuckCount + " 次)"), false);
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

        Box lowerBody = PathReachBoxes.player(player,isStrictMode);

        int lookAheadLimit = Math.min(currentPathIndex + 5, currentPath.size());
        if (player.hasVehicle() && SurfaceBoatRoute.allowed(world,currentPath.get(currentPathIndex)))
            lookAheadLimit=Math.min(currentPathIndex+32,currentPath.size());
        else if (currentPathUsesActionPlanner) lookAheadLimit = Math.min(currentPathIndex + 1, currentPath.size());
        int furthestReachedIndex = -1;

        for (int i = currentPathIndex; i < lookAheadLimit; i++) {
            SimplePathfinder.Node node = currentPath.get(i);
            if(player.hasVehicle() && i>currentPathIndex && !SurfaceBoatRoute.allowed(world,node)) break;

            Box nodeBox = PathReachBoxes.node(currentPath,i,isStrictMode);

            boolean isPhysicallyReached = lowerBody.intersects(nodeBox);
            // A sprint-swimming body's forward projection must not skip a rise or a turn.
            if(PathReachBoxes.plannedWater(node) && !player.hasVehicle())
                isPhysicallyReached &= PathReachBoxes.waterPositionReached(player.getX(),player.getY(),player.getZ(),node);
            if (node.plannedAction != null) {
                // A node cannot be skipped while any explicit prerequisite remains unfinished.
                for (BlockPos obstacle : node.plannedAction.breaks) {
                    if (!world.getBlockState(obstacle).getCollisionShape(world, obstacle).isEmpty()) isPhysicallyReached = false;
                }
                if (node.plannedAction.material != null && world.getBlockState(node.pos.down()).getMaterial().isReplaceable())
                    isPhysicallyReached = false;
                // Loose fall tolerances must not mark an unlanded jump/fall as complete.
                if (node.state == SimplePathfinder.MovementState.FALLING || node.state == SimplePathfinder.MovementState.JUMPING_AIR
                        || node.state == SimplePathfinder.MovementState.JUMPING_UP)
                    isPhysicallyReached &= Math.abs(player.getY() - node.pos.getY()) < 0.3 && (player.isOnGround() || player.isTouchingWater());
                if(node.state==SimplePathfinder.MovementState.JUMPING_UP) {
                    double landingX=player.getX()-node.pos.getX()-.5,landingZ=player.getZ()-node.pos.getZ()-.5;
                    isPhysicallyReached &= landingX*landingX+landingZ*landingZ<=.12;
                }
            }

            if(node.plannedAction!=null && node.state==SimplePathfinder.MovementState.WALKING
                    && i+1<currentPath.size()) {
                SimplePathfinder.Node next=currentPath.get(i+1);
                if(next.state==SimplePathfinder.MovementState.JUMPING_UP && next.plannedAction!=null
                        && !next.plannedAction.isShoreExit()) {
                    double approachX=player.getX()-node.pos.getX()-.5,approachZ=player.getZ()-node.pos.getZ()-.5;
                    Vec3d v=player.getVelocity();
                    isPhysicallyReached &= approachX*approachX+approachZ*approachZ<=.06
                            && Math.abs(player.getY()-node.pos.getY())<.3 && player.isOnGround()
                            && v.x*v.x+v.z*v.z<=.01;
                }
            }

            if (node.plannedAction != null && node.plannedAction.isShoreExit()) {
                isPhysicallyReached &= player.isOnGround() && Math.abs(player.getY() - node.pos.getY()) < 0.3;
            }
            if(node.plannedAction!=null && node.plannedAction.isSurfaceRise()) {
                isPhysicallyReached &= player.getY()>=node.pos.getY()-.25
                        && !player.isSubmergedIn(FluidTags.WATER);
            }

            if (i == currentPath.size() - 1) {
                double dx = player.getX() - (node.pos.getX() + 0.5);
                double dz = player.getZ() - (node.pos.getZ() + 0.5);
                double dy = Math.abs(player.getY() - node.pos.getY());

                double allowedDistSq = PathReachBoxes.finalHorizontalLimit(node,isStrictMode);

                // Keep planned break/place/landing prerequisites even at the final mission node.
                double finalVertical=PathReachBoxes.plannedWater(node)?PathReachBoxes.waterUpperLimit(node):.5;
                isPhysicallyReached &= (dx * dx + dz * dz <= allowedDistSq) && (dy <= finalVertical);
                if(breathingDoorGoal) {
                    isPhysicallyReached &= UnderwaterGravelWork.inside(dx,player.getY()-node.pos.getY(),dz,
                            player.isOnGround(),player.isSubmergedIn(FluidTags.WATER));
                    if(!isPhysicallyReached && dx*dx+dz*dz<1 && player.age%20==0)
                        STUCK_LOG.info("BREATHING_DOOR_SETTLE index={} nodes={} pos={} goal={} grounded={} submerged={} dy={}",
                                i,currentPath.size(),player.getPos(),node.pos.toShortString(),player.isOnGround(),
                                player.isSubmergedIn(FluidTags.WATER),player.getY()-node.pos.getY());
                }
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
                    isPathBlocked = true;
                }
            }

            if (node.state == SimplePathfinder.MovementState.JUMPING_UP) {
                if (player.getY() < node.pos.getY() - 0.2) {
                    isPhysicallyReached = false;
                    isPathBlocked = true;
                }

                if (player.isSwimming() || player.isTouchingWater()) {
                    boolean isShoreJump = false;
                    if (i > 0) {
                        SimplePathfinder.MovementState prevState = currentPath.get(i - 1).state;
                        if (prevState == SimplePathfinder.MovementState.SWIMMING || prevState == SimplePathfinder.MovementState.DIVING) {
                            isShoreJump = true;
                        }
                    } else {
                        isShoreJump = true;
                    }

                    if (isShoreJump) {
                        double dx = player.getX() - (node.pos.getX() + 0.5);
                        double dz = player.getZ() - (node.pos.getZ() + 0.5);
                        double horizDistSq = dx * dx + dz * dz;
                        if (horizDistSq > 1.0) {
                            isPhysicallyReached = false;
                            isPathBlocked = true;
                        }
                    }
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
            if(breathingDoorGoal) STUCK_LOG.info("BREATHING_DOOR_PROGRESS {} -> {} player={} swimming={} grounded={} submerged={}",
                    currentPathIndex,furthestReachedIndex+1,player.getPos(),player.isSwimming(),player.isOnGround(),player.isSubmergedIn(FluidTags.WATER));
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
        MinecraftClient client=MinecraftClient.getInstance();
        if(plannerJob!=null){plannerJob.cancel(client,"custom micro-command takes over");plannerJob=null;}
        calculationGeneration++;
        currentPathUsesActionPlanner=false;

        this.isCalculatingNext = false;
        this.isPaused = false;
        this.stuckCheckTicks = 0;
        this.lastStuckCheckPos = null;
        this.consecutiveStuckCount = 0;
        resetMovementKeys();

        this.currentPath = new ArrayList<>(customPath);
        this.currentPathIndex = 0;

        this.finalDestination = customPath.get(customPath.size() - 1).pos;

        this.currentState = State.EXECUTING;

        if (client.player != null) {
            client.player.sendMessage(new LiteralText("§d[Bot] 收到微操指令，已接管自定义路线！"), false);
        }
    }

    public enum TempMissionType {
        IDLE, GO_TO_ENTITY;
    }

}
