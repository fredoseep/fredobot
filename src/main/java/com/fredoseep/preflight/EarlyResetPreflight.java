package com.fredoseep.preflight;

import com.fredoseep.utils.player.NaturalTreeLog;

import com.fredoseep.algorithm.planning.OceanGravelTargets;

import com.fredoseep.Fredobot;
import com.fredoseep.behave.MovementController;
import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.GlobalExecutor;
import com.fredoseep.testing.CreativeWorldTestRunner;
import me.voidxwalker.autoreset.Atum;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biomes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Client render/tick pump + server-thread read-only preflight while vanilla F3+Esc is active.
 * Seed/loot come ONLY from the client creation seed and matched seed-provider selection.
 * The server thread is used to generate/read terrain safely, never to query loot or world seed.
 */
public final class EarlyResetPreflight {
    public static final int MAGMA_RAVINE_RADIUS=200;
    public record Plan(BlockPos treasure, BlockPos treasureSurface, BlockPos tnt,
                       PreflightGeometry.Workspace workspace, RavineFinder.Candidate ravine, BlockPos gravel) {}
    private static final Logger LOGGER = LogManager.getLogger("FredoBotPreflight");
    private static volatile IntegratedServer pausedServer;
    private static SelectedTreasureSeed.Selection pendingSelection;
    private static long creationId, consumedCreation;
    private static String pendingDirectory;
    private static ClientWorld observed, owner, resetOwner;
    private static GameMenuScreen ownedPause;
    private static Job job;
    private static Plan plan;
    private static boolean waiting, applied;
    private static long started, heartbeat;
    private static String status = "";

    private EarlyResetPreflight() {}
    public static void creatingWorld(String directory, long seed) {
        creationId++;
        pendingDirectory = directory;
        // Matches the seed actually passed to vanilla creation, even if Atum prefetched other seeds.
        pendingSelection = CreativeWorldTestRunner.isActive() ? null : SelectedTreasureSeed.forSeed(seed);
    }
    public static void serverPause(IntegratedServer server, boolean paused) {
        if (paused) pausedServer = server;
        else if (pausedServer == server) pausedServer = null;
    }
    public static Plan plan(MinecraftClient client) { return client.world == owner ? plan : null; }
    public static boolean blocking(MinecraftClient client) {
        return client.world != null && (client.world == resetOwner || client.world == owner && (waiting || job != null && !applied));
    }
    public static void resetScheduled() {
        resetOwner = MinecraftClient.getInstance().world;
        if (job != null) job.cancelled = true;
        plan = null;
    }
    public static String status() { return status; }

    public static void pump(MinecraftClient client) {
        if (CreativeWorldTestRunner.isActive()) return;
        if (client.world != observed) {
            if (job != null) job.cancelled = true;
            pausedServer = null;
            observed = client.world; owner = null; resetOwner = null; job = null; plan = null;
            waiting = false; applied = false; ownedPause = null; status = "";
        }
        if (client.world == null || client.player == null) return;
        if (owner == null && pendingSelection != null && creationId > consumedCreation
                && !Fredobot.isTesting && (Atum.isRunning() || BotEngine.getInstance().isRunning())) {
            IntegratedServer server = client.getServer();
            if (server == null || server.isRemote()) return;
            consumedCreation = creationId; owner = client.world; waiting = true;
            started = System.nanoTime(); heartbeat = started;
            BotEngine.getInstance().getModule(MovementController.class).resetKeys();
            ownedPause = new GameMenuScreen(false);
            status = "前置判定：等待原版暂停";
            client.openScreen(ownedPause);
            LOGGER.info("[EarlyReset] WAIT_PAUSE directory={} seed={} treasure={},{}", pendingDirectory,
                    pendingSelection.seed, pendingSelection.treasureX, pendingSelection.treasureZ);
            return;
        }
        if (client.world != owner || !waiting && (job == null || applied)) return;
        if (client.currentScreen != ownedPause) {
            if (job != null) job.cancelled = true;
            waiting = false; applied = true; status = "前置判定已由用户中止";
            BotEngine.getInstance().stop();
            LOGGER.info("[EarlyReset] CANCELLED pause screen closed/replaced");
            return;
        }
        if (waiting) {
            IntegratedServer server = client.getServer();
            if (client.isPaused() && server != null && pausedServer == server) {
                job = new Job(pendingSelection); waiting = false;
                Job submitted = job;
                server.execute(() -> submitted.run(server.getOverworld()));
            }
        }
        if (job != null) status = "前置判定：" + job.phase + " " + job.detail;
        long now = System.nanoTime();
        if (now - heartbeat >= 2_000_000_000L) {
            heartbeat = now;
            LOGGER.info("[EarlyReset] heartbeat elapsed={}ms status={}", (now - started) / 1_000_000L, status);
        }
        if (job == null || !job.done) return;
        applied = true;
        if (job.error != null) {
            status = "前置判定异常：已停止机器人，保留暂停世界";
            LOGGER.error("[EarlyReset] ERROR; world was not rejected", job.error);
            BotEngine.getInstance().stop();
            return;
        }
        if (job.failure != null) {
            status = "前置判定淘汰：" + job.failure;
            closePause(client);
            BotEngine.getInstance().getModule(GlobalExecutor.class).resetWorld(job.failure);
        } else {
            plan = job.result;
            status = plan == null ? "前置判定作业点不确定：沿用原流程" : "前置判定全部通过";
            LOGGER.info("[EarlyReset] {} plan={}", status, plan);
            closePause(client);
        }
    }
    private static void closePause(MinecraftClient client) {
        if (client.currentScreen == ownedPause) client.openScreen(null);
        ownedPause = null;
    }

    private static final class Job {
        final SelectedTreasureSeed.Selection selection;
        final Set<Long> loaded = new HashSet<>();
        volatile boolean cancelled, done;
        volatile String phase = "等待地形任务", detail = "";
        volatile String failure;
        volatile Exception error;
        Plan result;
        ServerWorld world;
        BlockPos treasure, surface, tnt, gravel;
        PreflightGeometry.Workspace workspace;
        RavineFinder.Candidate ravine;
        boolean inconclusive;

        Job(SelectedTreasureSeed.Selection selection) { this.selection = selection; }
        void check() { if (cancelled) throw new CancellationException("preflight cancelled"); }
        void run(ServerWorld world) {
            this.world = world;
            try {
                boolean hasTnt = selection.loot != null && selection.loot.getOrDefault("minecraft:tnt", 0) > 0;
                PreflightOrder.Stage failed = PreflightOrder.firstFailure(hasTnt, this::stage);
                if (failed == null && !inconclusive) result = new Plan(treasure, surface, tnt, workspace, ravine, gravel);
            } catch (CancellationException ignored) { cancelled = true; }
            catch (Exception exception) { error = exception; }
            finally { done = true; }
        }
        boolean stage(PreflightOrder.Stage stage) {
            check();
            phase = stage.name(); detail = "";
            LOGGER.info("[EarlyReset] START {} seed={}", stage, selection.seed);
            if (stage == PreflightOrder.Stage.LOOT) {
                if (selection.loot == null) { inconclusive = true; return true; }
                if (!TreasureLootPredictor.resourcesSufficient(selection.loot)) {
                    int iron = selection.loot.getOrDefault("minecraft:iron_ingot", 0), diamond = selection.loot.getOrDefault("minecraft:diamond", 0);
                    failure = "前置：宝藏资源不达标（铁=" + iron + "，钻石=" + diamond + "，要求铁=" + (diamond >= 3 ? 4 : 7) + "）";
                    return false;
                }
                return true;
            }
            if (inconclusive) return true;
            if (surface == null) {
                BlockPos column = new BlockPos(selection.treasureX, 64, selection.treasureZ);
                load(column, 1);
                for (int y = 100; y >= 20; y--) {
                    BlockPos p = new BlockPos(column.getX(), y, column.getZ());
                    if (world.getBlockState(p).getBlock() == Blocks.CHEST) { treasure = p; break; }
                }
                if (treasure == null) { inconclusive = true; return true; }
                surface = new BlockPos(column.getX(), Math.max(41, PreflightGeometry.ground(world, column) + 1), column.getZ());
            }
            switch (stage) {
                case TNT -> {
                    load(surface, 53);
                    tnt = PreflightGeometry.tnt(world, treasure, this::check);
                    if (tnt == null) { failure = "前置：TNT 布置条件不足（宝藏附近 50 格无合适树木/位置）"; return false; }
                }
                case COLLECTION -> {
                    load(surface, 100);
                    BlockPos log = PreflightGeometry.find(world, surface, 100,
                            p -> world.getBlockState(p).isIn(BlockTags.LOGS) && NaturalTreeLog.allowed(world,p), this::check);
                    if (log == null) { failure = "前置：普通采集无目标（宝藏附近 100 格无原木）"; return false; }
                    BlockPos center = tnt != null ? tnt : log;
                    load(center, 34);
                    workspace = PreflightGeometry.workspace(world, center, tnt != null, this::check);
                    if (workspace == null) { inconclusive = true; return true; }
                    load(workspace.table(), 26);
                    // TNT may supply cobblestone drops: absence of nearby stone cannot reject that branch.
                    // 24 covers the runtime 15-block scan plus nearby workbench operating stances.
                    if (tnt == null && ResourceProjection.needsStone(selection.loot) && PreflightGeometry.find(world, workspace.table(), 24,
                            p -> world.getBlockState(p).getBlock() == Blocks.STONE, this::check) == null) {
                        failure = "前置：普通采集无目标（预计需要石工具，工作台操作范围无石头）"; return false;
                    }
                }
                case MAGMA -> {
                    load(workspace.table(), MAGMA_RAVINE_RADIUS+2);
                    ravine = RavineFinder.find(world, workspace.table(), MAGMA_RAVINE_RADIUS, this::check, true);
                    if (ravine == null) { failure = "前置：找不到 magma（预定工作台附近 "+MAGMA_RAVINE_RADIUS+" 格无可进入的 2×1 裂谷）"; return false; }
                }
                case GRAVEL -> {
                    BlockPos floor = PreflightGeometry.floor(world, ravine.magma());
                    load(floor, 42);
                    if(warmOcean()){
                        gravel = PreflightGeometry.find(world, floor, 40,
                                p -> world.getBlockState(p).getBlock() == Blocks.GRAVEL && PreflightGeometry.exposedGravel(world, p), this::check);
                        if(gravel==null){
                            load(workspace.table(),50);
                            gravel=PreflightGeometry.find(world,workspace.table(),50,
                                    p->world.getBlockState(p).getBlock()==Blocks.GRAVEL,this::check);
                        }
                    }else{
                        var selection=OceanGravelTargets.find(world,ravine.magma(),workspace.table(),40,1,this::check,p->true);
                        gravel=selection.targets().isEmpty()?null:selection.targets().get(0);
                        LOGGER.info("[EarlyReset] SEABED_GRAVEL referenceY={} marineColumns={} rejectedDeep={}",
                                selection.referenceY(),selection.marineColumns(),selection.rejectedDeepGravel());
                    }
                    if (gravel == null) { failure = warmOcean()?"前置：裂谷附近无可挖沙砾":"前置：裂谷附近无海床表层沙砾（排除谷底与覆盖目标）"; return false; }
                }
                default -> {}
            }
            detail = "通过";
            LOGGER.info("[EarlyReset] PASS {} origin={} workspace={} magma={} gravel={}", stage, treasure, workspace, ravine, gravel);
            return true;
        }
        boolean warmOcean() {
            var biome = world.getBiome(ravine.magma());
            return biome == Biomes.WARM_OCEAN || biome == Biomes.DEEP_WARM_OCEAN
                    || biome == Biomes.LUKEWARM_OCEAN || biome == Biomes.DEEP_LUKEWARM_OCEAN;
        }
        void load(BlockPos center, int radius) {
            int minX = (center.getX() - radius) >> 4, maxX = (center.getX() + radius) >> 4;
            int minZ = (center.getZ() - radius) >> 4, maxZ = (center.getZ() + radius) >> 4;
            int total = (maxX - minX + 1) * (maxZ - minZ + 1), count = 0;
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                check();
                long key = ((long) x << 32) ^ (z & 0xffffffffL);
                if (!loaded.contains(key)) { world.getChunk(x, z); loaded.add(key); }
                detail = "加载地形 " + (++count) + "/" + total + "（已准备 " + loaded.size() + " 个区块）";
            }
        }
    }
}
