package com.fredoseep.logging;

import com.fredoseep.algorithm.SimplePathfinder;
import com.fredoseep.algorithm.planning.PlannerDiagnostics;
import com.fredoseep.algorithm.planning.UnderwaterGravelWork;
import com.fredoseep.behave.CraftingController;
import com.fredoseep.behave.MiscController;
import com.fredoseep.behave.MovementController;
import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.GlobalExecutor;
import com.fredoseep.excutor.PathExecutor;
import com.fredoseep.utils.bt.BtStuff;
import com.fredoseep.utils.player.InventoryHelper;
import com.fredoseep.utils.player.MiningHelper;
import com.fredoseep.utils.prenether.NetherPortalBuilding;
import com.fredoseep.utils.prenether.PreNether;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.tag.FluidTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RayTraceContext;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 全局状态日志记录器：
 * 1. 一个存档对应一个全局日志文件 (logs/fredobot-global.log)，新开存档自动覆盖之前的日志。
 * 2. 严格过滤冗余状态：仅当核心状态签名（阶段、子任务、方块坐标、姿态、视线目标、挖掘目标、剩余挖掘数量、路径节点等）发生变化时才写入。
 * 3. 详细记录玩家当前位置、姿态、视线、目标方块（例如目标沙砾）以及剩余要挖几个。
 */
public final class GlobalStateLogger {
    private static final Logger LOG = LogManager.getLogger("FredoBotGlobalLog");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    public static final String LOG_RELATIVE_PATH = "logs/fredobot-global.log";

    private record WriteCommand(boolean truncate, String line) {}

    private static final LinkedBlockingQueue<WriteCommand> QUEUE = new LinkedBlockingQueue<>(4096);
    private static volatile Path logFilePath;
    private static volatile boolean writerStarted = false;
    private static final Object INIT_LOCK = new Object();

    private static volatile boolean pendingNewSave = true;
    private static volatile String pendingServerWorldName = null;
    private static final SaveLogSession SESSION=new SaveLogSession();
    private static String activeDimension="unknown";
    private static String activeWorldLabel = "unknown";
    private static String lastStateSignature = null;

    private GlobalStateLogger() {}

    private static void ensureWriterStarted(Path runDir) {
        if (logFilePath == null && runDir != null) {
            logFilePath = runDir.resolve(LOG_RELATIVE_PATH);
        }
        if (writerStarted) return;
        synchronized (INIT_LOCK) {
            if (writerStarted) return;
            Thread writer = new Thread(GlobalStateLogger::writeLoop, "fredobot-global-state-logger");
            writer.setDaemon(true);
            writer.start();
            writerStarted = true;
        }
    }

    /** 由 ExampleMixin 在 MinecraftServer.loadWorld 时调用，标记新开/新载入存档。 */
    public static void onServerLoadWorld(MinecraftServer server) {
        if (server != null) {
            pendingServerWorldName = extractServerWorldName(server);
        }
        pendingNewSave = true;
        lastStateSignature = null;
    }

    /** 由 GlobalExecutor.resetWorld 调用，记录重置原因并准备下一个存档覆盖。 */
    public static void onWorldReset(String reason, GlobalExecutor.GlobalState stage) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("time", LocalTime.now().format(TIME_FMT));
        entry.put("event", "world_reset");
        entry.put("world", activeWorldLabel);
        entry.put("dimension",activeDimension);
        entry.put("global_state", stage == null ? "UNKNOWN" : stage.name());
        entry.put("reason", reason);
        enqueue(false, PlannerDiagnostics.json(entry));
        lastStateSignature = null;
    }

    /** 记录外部事件（如手动点击 Snapshot 按钮）。 */
    public static void logCustomEvent(String eventType, Map<String, Object> payload) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.runDirectory != null) {
            ensureWriterStarted(client.runDirectory.toPath());
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("time", LocalTime.now().format(TIME_FMT));
        entry.put("event", eventType);
        entry.put("world", activeWorldLabel);
        entry.put("dimension",activeDimension);
        if (payload != null) {
            entry.putAll(payload);
        }
        enqueue(false, PlannerDiagnostics.json(entry));
    }

    /** 每个客户端 Tick 调用，检测存档切换并按需去重记录当前状态。 */
    public static void tick(MinecraftClient client) {
        if (client == null || client.runDirectory == null) return;
        ensureWriterStarted(client.runDirectory.toPath());

        if (client.world == null || client.player == null) {
            lastStateSignature = null;
            return;
        }

        String currentWorldName = resolveWorldName(client);
        String dimension=client.world.getRegistryKey().getValue().toString();
        boolean fresh=SESSION.startsNew(pendingNewSave,client.getServer(),currentWorldName);
        pendingNewSave=false;
        if(fresh){
            activeWorldLabel=currentWorldName;activeDimension=dimension;
            lastStateSignature=null;
            startNewSaveLog(client,currentWorldName);
        }else if(!dimension.equals(activeDimension)){
            String previous=activeDimension;activeDimension=dimension;lastStateSignature=null;
            logCustomEvent("dimension_changed",Map.of("from",previous,"to",dimension,"player_age",client.player.age));
        }

        captureAndLogIfChanged(client, client.world, client.player);
    }

    public static String resolveWorldName(MinecraftClient client) {
        if (client != null) {
            IntegratedServer server = client.getServer();
            if (server != null) {
                String name = extractServerWorldName(server);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        }
        if (pendingServerWorldName != null && !pendingServerWorldName.isEmpty()) {
            return pendingServerWorldName;
        }
        return client != null && client.world != null
                ? "world@" + Integer.toHexString(System.identityHashCode(client.world))
                : "unknown";
    }

    private static String extractServerWorldName(MinecraftServer server) {
        try {
            if (server.getSaveProperties() != null) {
                String name = server.getSaveProperties().getLevelName();
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Throwable ignored) {
        }
        return server.toString();
    }

    private static void startNewSaveLog(MinecraftClient client, String worldName) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("time", LocalDateTime.now().format(DATE_TIME_FMT));
        header.put("event", "save_session_start");
        header.put("world", worldName);
        header.put("dimension",activeDimension);
        header.put("player_age", client.player.age);
        header.put("spawn_pos", posList(client.player.getBlockPos()));
        enqueue(true, PlannerDiagnostics.json(header));
    }

    private static void captureAndLogIfChanged(MinecraftClient client, World world, PlayerEntity player) {
        BotEngine engine = BotEngine.getInstance();
        GlobalExecutor global = engine.getModule(GlobalExecutor.class);
        PathExecutor path = engine.getModule(PathExecutor.class);
        MiscController misc = engine.getModule(MiscController.class);
        CraftingController craft = engine.getModule(CraftingController.class);
        MovementController move = engine.getModule(MovementController.class);

        String globalStateName = global != null ? global.currentState.name() : "UNKNOWN";
        String taskSummary = buildTaskSummary(global, misc, craft, path);
        String pathStateName = path == null ? "IDLE" : (path.getCurrentState().name() + (path.isPaused() ? "_PAUSED" : ""));

        BlockPos playerBlockPos = player.getBlockPos();
        Map<String, Object> poseInfo = describePose(client, player, path, move);
        String postureSummary = String.valueOf(poseInfo.get("posture"));

        LookSnapshot lookSnap = captureLook(world, player);
        TargetAndQuota targetQuota = captureTargetAndQuota(world, player, global, misc, craft, path);

        BlockPos pathGoal = path != null ? path.getFinalDestination() : null;
        int pathIndex = path != null && path.getCurrentPath() != null ? path.getCurrentPathIndex() : -1;
        int pathSize = path != null && path.getCurrentPath() != null ? path.getCurrentPath().size() : 0;
        BlockPos currentNodePos = (path != null && path.getCurrentPath() != null && pathIndex >= 0 && pathIndex < pathSize)
                ? path.getCurrentPath().get(pathIndex).pos : null;

        // 构造去重签名：若方块位置、姿态、视线目标、任务状态、目标方块（如目标沙砾）、要挖几个、路径节点均未改变，则忽略冗余状态
        String signature = String.join("|",
                String.valueOf(engine.isRunning()),
                globalStateName,
                taskSummary,
                pathStateName,
                posKey(playerBlockPos),
                postureSummary,
                lookSnap.dedupKey,
                targetQuota.dedupKey,
                posKey(pathGoal),
                pathIndex + "/" + pathSize,
                posKey(currentNodePos)
        );

        if (Objects.equals(signature, lastStateSignature)) {
            return;
        }
        lastStateSignature = signature;

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("time", LocalTime.now().format(TIME_FMT));
        record.put("player_age", player.age);
        record.put("world", activeWorldLabel);
        record.put("dimension",activeDimension);
        record.put("bot_running", engine.isRunning());
        record.put("global_state", globalStateName);
        record.put("task_state", taskSummary);
        record.put("path_state", pathStateName);

        // 1. 当前位置 (pos & block_pos)
        record.put("pos", List.of(round2(player.getX()), round2(player.getY()), round2(player.getZ())));
        record.put("block_pos", posList(playerBlockPos));

        // 2. 姿态 (pose)
        record.put("pose", poseInfo);

        // 3. 视线 (look)
        record.put("look", lookSnap.data);

        // 4. 目标（如目标沙砾坐标、方块类型、队列中剩余目标）
        record.put("target", targetQuota.targetData);

        // 5. 要挖几个 (remaining_count)
        record.put("remaining_count", targetQuota.remainingToMine);

        if (path != null && (path.isBusy() || pathGoal != null)) {
            Map<String, Object> pathInfo = new LinkedHashMap<>();
            pathInfo.put("goal", pathGoal == null ? null : posList(pathGoal));
            pathInfo.put("node_index", pathIndex);
            pathInfo.put("node_count", pathSize);
            pathInfo.put("current_node", currentNodePos == null ? null : posList(currentNodePos));
            record.put("path", pathInfo);
        }

        enqueue(false, PlannerDiagnostics.json(record));
    }

    public static Map<String, Object> describePose(MinecraftClient client, PlayerEntity player, PathExecutor path, MovementController move) {
        String entityPose = player.getPose() != null ? player.getPose().name() : "STANDING";
        boolean onGround = player.isOnGround();
        boolean sneaking = "CROUCHING".equals(entityPose)
                || (client != null && client.options != null && client.options.keySneak.isPressed());
        boolean sprinting = (client != null && client.options != null && client.options.keySprint.isPressed())
                || player.isSwimming();
        boolean swimming = player.isSwimming();
        boolean touchingWater = player.isTouchingWater();
        boolean submergedWater = player.isSubmergedIn(FluidTags.WATER);
        boolean inVehicle = player.hasVehicle();
        String moveMode = (path != null && path.isBusy() && move != null && move.getLastState() != null)
                ? move.getLastState().name() : "NONE";

        List<String> tags = new ArrayList<>();
        if (inVehicle) tags.add("in_boat");
        if (sneaking) tags.add("sneaking");
        if (sprinting) tags.add("sprinting");
        if (swimming) tags.add("swimming");
        if (submergedWater) tags.add("submerged");
        else if (touchingWater) tags.add("in_water");
        tags.add(onGround ? "on_ground" : "airborne");
        if (!"NONE".equals(moveMode)) tags.add("mode=" + moveMode);

        String posture = entityPose + "(" + String.join(",", tags) + ")";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("posture", posture);
        out.put("entity_pose", entityPose);
        out.put("on_ground", onGround);
        out.put("sneaking", sneaking);
        out.put("sprinting", sprinting);
        out.put("swimming", swimming);
        out.put("touching_water", touchingWater);
        out.put("submerged_water", submergedWater);
        out.put("in_vehicle", inVehicle);
        out.put("air_supply", player.getAir() + "/" + player.getMaxAir());
        out.put("movement_mode", moveMode);
        return out;
    }

    private record LookSnapshot(Map<String, Object> data, String dedupKey) {}

    public static Map<String, Object> describeLook(World world, PlayerEntity player) {
        return captureLook(world, player).data;
    }

    private static String cardinalFacing(float wrappedYaw) {
        if (wrappedYaw >= -45.0F && wrappedYaw < 45.0F) return "SOUTH";
        if (wrappedYaw >= 45.0F && wrappedYaw < 135.0F) return "WEST";
        if (wrappedYaw >= -135.0F && wrappedYaw < -45.0F) return "EAST";
        return "NORTH";
    }

    private static LookSnapshot captureLook(World world, PlayerEntity player) {
        float wrappedYaw = MathHelper.wrapDegrees(player.yaw);
        float pitch = player.pitch;
        Vec3d lookVec = player.getRotationVec(1.0F);
        String facing = cardinalFacing(wrappedYaw);

        Vec3d eye = player.getCameraPosVec(1.0F);
        Vec3d reachEnd = new Vec3d(eye.x + lookVec.x * 5.0D, eye.y + lookVec.y * 5.0D, eye.z + lookVec.z * 5.0D);
        BlockHitResult hit = world.rayTrace(new RayTraceContext(
                eye, reachEnd,
                RayTraceContext.ShapeType.OUTLINE,
                RayTraceContext.FluidHandling.NONE,
                player
        ));

        Map<String, Object> look = new LinkedHashMap<>();
        look.put("yaw", round1(wrappedYaw));
        look.put("pitch", round1(pitch));
        look.put("facing", facing);
        look.put("look_vec", List.of(round3(lookVec.x), round3(lookVec.y), round3(lookVec.z)));

        String dedup;
        if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos hitPos = hit.getBlockPos();
            BlockState hitState = world.getBlockState(hitPos);
            String blockName = hitState.getBlock().toString();
            String side = hit.getSide().toString();
            Map<String, Object> hitInfo = new LinkedHashMap<>();
            hitInfo.put("block", blockName);
            hitInfo.put("pos", posList(hitPos));
            hitInfo.put("face", side);
            look.put("looking_at", hitInfo);
            dedup = "HIT:" + blockName + "@" + posKey(hitPos) + ":" + side;
        } else {
            look.put("looking_at", "MISS");
            int qYaw = Math.round(wrappedYaw / 15.0F) * 15;
            int qPitch = Math.round(pitch / 15.0F) * 15;
            dedup = "AIM:" + qYaw + ":" + qPitch;
        }
        return new LookSnapshot(look, dedup);
    }

    private record TargetAndQuota(Map<String, Object> targetData, int remainingToMine, String dedupKey) {}

    private static TargetAndQuota captureTargetAndQuota(
            World world,
            PlayerEntity player,
            GlobalExecutor global,
            MiscController misc,
            CraftingController craft,
            PathExecutor path
    ) {
        Map<String, Object> target = new LinkedHashMap<>();
        int remainingToMine = 0;

        MiscController.MiscType miscTask = misc != null ? misc.getCurrentTask() : null;
        BlockPos miscTargetPos = misc != null ? misc.getTargetPos() : null;

        boolean isGravelContext = false;
        BlockPos primaryTargetPos = null;
        String primaryTargetType = "NONE";
        List<List<Integer>> queuedPositions = new ArrayList<>();

        if (miscTask == MiscController.MiscType.MINE_THE_BLOCK
                || miscTask == MiscController.MiscType.MINE_THE_BLOCK_AND_COLLECT_THE_DROP
                || miscTask == MiscController.MiscType.MINE_BLOCK_ABOVE_HEAD) {
            primaryTargetPos = miscTargetPos;
            boolean currentTargetUnmined = false;
            if (primaryTargetPos != null) {
                BlockState state = world.getBlockState(primaryTargetPos);
                currentTargetUnmined = !state.isAir() && !state.getMaterial().isLiquid();
                primaryTargetType = state.getBlock().toString();
                if (state.getBlock() == Blocks.GRAVEL) {
                    isGravelContext = true;
                }
            }
            if (misc != null && misc.isUwGravelMission()) {
                isGravelContext = true;
                if (primaryTargetType.contains("air") || primaryTargetType.contains("water")) {
                    primaryTargetType = Blocks.GRAVEL.toString();
                }
            }
            if (MiningHelper.currentTargetBlocks.contains(Blocks.GRAVEL)) {
                isGravelContext = true;
            }

            int unminedInQueue = 0;
            if (MiningHelper.blockToMine != null) {
                for (BlockPos qp : MiningHelper.blockToMine) {
                    if (qp == null) continue;
                    BlockState qs = world.getBlockState(qp);
                    if (!qs.isAir() && !qs.getMaterial().isLiquid()) {
                        unminedInQueue++;
                        if (queuedPositions.size() < 16) {
                            queuedPositions.add(posList(qp));
                        }
                    }
                }
            }

            int queueTotal = (currentTargetUnmined ? 1 : 0) + unminedInQueue;
            if (misc != null && misc.isUwGravelMission() && misc.getUwGravelWork() != null) {
                UnderwaterGravelWork uwWork = misc.getUwGravelWork();
                boolean hasFlint = uwWork.hasFlint() || InventoryHelper.countItem(player, Items.FLINT) > 0;
                remainingToMine = hasFlint ? 0 : Math.max(0, 4 - uwWork.count());
                target.put("uw_door_phase", misc.uwPhase.name());
                target.put("uw_door_pos", misc.doorPos == null ? null : posList(misc.doorPos));
                target.put("uw_confirmed_mined", uwWork.count());
                target.put("uw_has_flint", hasFlint);
            } else if (isGravelContext) {
                boolean hasFlintOrLighter = InventoryHelper.countItem(player, Items.FLINT) > 0
                        || InventoryHelper.countItem(player, Items.FLINT_AND_STEEL) > 0;
                int gravelDeficit = hasFlintOrLighter ? 0 : Math.max(0, 4 - InventoryHelper.countItem(player, Items.GRAVEL));
                remainingToMine = queueTotal > 0 ? queueTotal : gravelDeficit;
            } else {
                remainingToMine = queueTotal;
            }
        } else if (global != null && global.currentState == GlobalExecutor.GlobalState.PRE_NETHER) {
            BlockPos gravelSpot = PreNether.getGravelPos();
            boolean hasLighterMaterials = InventoryHelper.countItem(player, Items.GRAVEL) >= 4
                    || InventoryHelper.countItem(player, Items.FLINT) >= 1
                    || InventoryHelper.countItem(player, Items.FLINT_AND_STEEL) >= 1;
            if (!hasLighterMaterials && gravelSpot != null) {
                isGravelContext = true;
                primaryTargetPos = gravelSpot;
                primaryTargetType = Blocks.GRAVEL.toString();
                remainingToMine = Math.max(0, 4 - InventoryHelper.countItem(player, Items.GRAVEL));
            } else if (PreNether.magmaPos != null) {
                primaryTargetPos = PreNether.magmaPos;
                primaryTargetType = "MAGMA_RAVINE:" + world.getBlockState(PreNether.magmaPos).getBlock().toString();
                remainingToMine = 0;
            }
        } else if (global != null && global.currentState == GlobalExecutor.GlobalState.BUILDING_TWO_BY_ONE_NETHER_PORTAL) {
            if (NetherPortalBuilding.currentTBOState == NetherPortalBuilding.TwoByOneBuildState.FORCING_FLINT
                    && PreNether.alignedMagmaPos != null) {
                isGravelContext = true;
                primaryTargetPos = PreNether.alignedMagmaPos.down();
                primaryTargetType = Blocks.GRAVEL.toString();
                boolean hasFlint = InventoryHelper.findItemSlot(player, Items.FLINT) != -1;
                remainingToMine = hasFlint ? 0 : 1;
            } else if (PreNether.magmaPos != null) {
                primaryTargetPos = PreNether.magmaPos;
                primaryTargetType = "NETHER_PORTAL_SITE";
            }
        } else if (global != null && (global.currentState == GlobalExecutor.GlobalState.GOING_TO_BT_STANDING_PLACE
                || global.currentState == GlobalExecutor.GlobalState.DIGGING_BT
                || global.currentState == GlobalExecutor.GlobalState.LOOTING_BT)) {
            primaryTargetPos = BtStuff.btPos != null ? BtStuff.btPos : BtStuff.btStandingPos;
            primaryTargetType = "BURIED_TREASURE_CHEST";
            remainingToMine = (global.currentState == GlobalExecutor.GlobalState.DIGGING_BT && BtStuff.btPos != null
                    && !world.getBlockState(BtStuff.btPos.up()).isAir()) ? 1 : 0;
        } else if (path != null && path.getFinalDestination() != null && path.isBusy()) {
            primaryTargetPos = path.getFinalDestination();
            primaryTargetType = "PATH_DESTINATION";
        }

        target.put("type", primaryTargetType);
        target.put("pos", primaryTargetPos == null ? null : posList(primaryTargetPos));
        if (!queuedPositions.isEmpty()) {
            target.put("queued_targets", queuedPositions);
        }
        if (isGravelContext) {
            target.put("target_gravel", primaryTargetPos == null ? null : posList(primaryTargetPos));
            target.put("inventory_gravel", InventoryHelper.countItem(player, Items.GRAVEL));
            target.put("inventory_flint", InventoryHelper.countItem(player, Items.FLINT));
        }
        if (misc != null && misc.targetEntity != null) {
            target.put("target_entity", misc.targetEntity.toString() + "@" + posList(misc.targetEntity.getBlockPos()));
        }
        if (craft != null && craft.isBusy() && craft.getCurrentTarget() != null) {
            target.put("craft_item", craft.getCurrentTarget().toString() + "x" + craft.getCurrentTimes());
        }

        String dedup = primaryTargetType + "@" + posKey(primaryTargetPos)
                + "#rem=" + remainingToMine
                + "#q=" + queuedPositions.size()
                + (misc != null && misc.targetEntity != null ? "#ent=" + System.identityHashCode(misc.targetEntity) : "");
        return new TargetAndQuota(target, remainingToMine, dedup);
    }

    private static String buildTaskSummary(
            GlobalExecutor global,
            MiscController misc,
            CraftingController craft,
            PathExecutor path
    ) {
        List<String> parts = new ArrayList<>();
        if (global != null) {
            parts.add("global=" + global.currentState.name());
            if (global.currentState == GlobalExecutor.GlobalState.CRAFTING) {
                parts.add("btCraft=" + BtStuff.btCraftPhase.name());
            } else if (global.currentState == GlobalExecutor.GlobalState.LOOKING_FOR_TREES && BtStuff.hasTNT) {
                parts.add("tnt=" + BtStuff.tntState.name());
            } else if (global.currentState == GlobalExecutor.GlobalState.BUILDING_TWO_BY_ONE_NETHER_PORTAL) {
                parts.add("portal=" + NetherPortalBuilding.currentTBOState.name());
            }
        }
        if (misc != null && misc.isBusy() && misc.getCurrentTask() != null) {
            parts.add("misc=" + misc.getCurrentTask().name());
            if (misc.uwPhase != MiscController.UwDoorPhase.NONE) {
                parts.add("uw=" + misc.uwPhase.name());
            }
        }
        if (craft != null && craft.isBusy()) {
            parts.add("craft=" + craft.getState().name());
        }
        if (path != null && path.isBusy()) {
            parts.add("path=" + path.getCurrentState().name());
        }
        return parts.isEmpty() ? "IDLE" : String.join(";", parts);
    }

    private static List<Integer> posList(BlockPos pos) {
        return List.of(pos.getX(), pos.getY(), pos.getZ());
    }

    private static String posKey(BlockPos pos) {
        return pos == null ? "null" : (pos.getX() + "," + pos.getY() + "," + pos.getZ());
    }

    private static double round1(double v) {
        return Math.round(v * 10.0D) / 10.0D;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0D) / 100.0D;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0D) / 1000.0D;
    }

    private static void enqueue(boolean truncate, String line) {
        if (truncate) {
            QUEUE.clear();
        }
        if (!QUEUE.offer(new WriteCommand(truncate, line))) {
            QUEUE.poll();
            QUEUE.offer(new WriteCommand(truncate, line));
        }
    }

    private static void writeLoop() {
        BufferedWriter writer = null;
        Path openedPath = null;
        try {
            while (true) {
                WriteCommand cmd = QUEUE.take();
                Path target = logFilePath;
                if (target == null) continue;

                try {
                    if (cmd.truncate() || writer == null || !target.equals(openedPath)) {
                        if (writer != null) {
                            try {
                                writer.close();
                            } catch (IOException ignored) {
                            }
                        }
                        Files.createDirectories(target.getParent());
                        writer = Files.newBufferedWriter(
                                target,
                                StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE,
                                cmd.truncate() ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND,
                                StandardOpenOption.WRITE
                        );
                        openedPath = target;
                    }
                    writer.write(cmd.line());
                    writer.newLine();

                    WriteCommand next;
                    int batch = 0;
                    while (batch++ < 64 && (next = QUEUE.poll()) != null) {
                        if (next.truncate()) {
                            writer.flush();
                            writer.close();
                            writer = Files.newBufferedWriter(
                                    target,
                                    StandardCharsets.UTF_8,
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.TRUNCATE_EXISTING,
                                    StandardOpenOption.WRITE
                            );
                        }
                        writer.write(next.line());
                        writer.newLine();
                    }
                    writer.flush();
                } catch (IOException ioEx) {
                    LOG.error("Failed writing global state log to {}", String.valueOf(target), ioEx);
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
