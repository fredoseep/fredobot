package com.fredoseep.logging;

import com.fredoseep.algorithm.SimplePathfinder;
import com.fredoseep.algorithm.planning.PathReachBoxes;
import com.fredoseep.algorithm.planning.PlannerDebug;
import com.fredoseep.algorithm.planning.PlannerDiagnostics;
import com.fredoseep.algorithm.planning.StuckDump;
import com.fredoseep.behave.MovementController;
import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.PathExecutor;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FallingBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.tag.BlockTags;
import net.minecraft.tag.FluidTags;
import net.minecraft.text.LiteralText;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 暂停页面右下角 Snapshot 按钮的数据采集与导出器：
 * 1. 若当前存在路径任务：记录玩家当前位置到目标点形成的长方体范围的样子（含方块调色板、逐层可视化截面、非空气方块明细与完整 RLE 网格）以及完整路径节点列表。
 * 2. 若当前没有路径任务：Dump 玩家周围 5 区块（chunkX-5..chunkX+5, chunkZ-5..chunkZ+5，共 11x11=121 个区块）的内容。
 */
public final class WorldSnapshotDumper {
    private static final Logger LOG = LogManager.getLogger("FredoBotSnapshot");
    private static final DateTimeFormatter FILE_TIME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    public static final int DEFAULT_CHUNK_RADIUS = 5;

    private static final ExecutorService IO_WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fredobot-snapshot-writer");
        t.setDaemon(true);
        return t;
    });

    private WorldSnapshotDumper() {}

    public static void captureAndSave(MinecraftClient client, ButtonWidget button) {
        if (client == null || client.world == null || client.player == null) {
            if (button != null) {
                button.setMessage(new LiteralText("No World"));
            }
            return;
        }

        if (!client.isOnThread()) {
            client.execute(() -> captureAndSave(client, button));
            return;
        }

        try {
            if (button != null) {
                button.active = false;
                button.setMessage(new LiteralText("Dumping..."));
            }

            World world = client.world;
            PlayerEntity player = client.player;
            PathExecutor pathExecutor = BotEngine.getInstance().getModule(PathExecutor.class);
            MovementController moveController = BotEngine.getInstance().getModule(MovementController.class);

            BlockPos pathTarget = resolveActivePathTarget(pathExecutor);
            boolean hasPathTask = pathTarget != null;

            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("schema", "fredobot-pause-snapshot-v1");
            snapshot.put("captured_at", LocalDateTime.now().toString());
            snapshot.put("world", GlobalStateLogger.resolveWorldName(client));
            snapshot.put("player_age", player.age);
            snapshot.put("mode", hasPathTask ? "PATH_CUBOID" : "SURROUNDING_5_CHUNKS");

            Map<String, Object> playerInfo = new LinkedHashMap<>();
            playerInfo.put("pos", List.of(player.getX(), player.getY(), player.getZ()));
            playerInfo.put("block_pos", posList(player.getBlockPos()));
            playerInfo.put("chunk_pos", List.of(player.getBlockPos().getX() >> 4, player.getBlockPos().getZ() >> 4));
            playerInfo.put("pose", GlobalStateLogger.describePose(client, player, pathExecutor, moveController));
            playerInfo.put("look", GlobalStateLogger.describeLook(world, player));
            snapshot.put("player", playerInfo);

            if (hasPathTask) {
                capturePathCuboidAndNodes(world, player, pathExecutor, pathTarget, snapshot);
            } else {
                captureSurroundingChunks(world, player, DEFAULT_CHUNK_RADIUS, snapshot);
            }

            String stamp = LocalDateTime.now().format(FILE_TIME_FMT);
            String suffix = hasPathTask ? "path-cuboid" : "chunks-5r";
            Path snapshotsDir = client.runDirectory.toPath().resolve("logs/fredobot-snapshots");
            Path archiveFile = snapshotsDir.resolve("snapshot-" + stamp + "-" + suffix + ".json");
            Path latestFile = client.runDirectory.toPath().resolve("logs/fredobot-snapshot-latest.json");

            GlobalStateLogger.logCustomEvent("snapshot_captured", Map.of(
                    "mode", hasPathTask ? "PATH_CUBOID" : "SURROUNDING_5_CHUNKS",
                    "player_pos", posList(player.getBlockPos()),
                    "target_pos", hasPathTask ? posList(pathTarget) : "NONE",
                    "file", archiveFile.toString(),
                    "latest_file", latestFile.toString()
            ));

            IO_WRITER.submit(() -> {
                try {
                    Files.createDirectories(snapshotsDir);
                    String encoded = PlannerDiagnostics.json(snapshot);
                    Files.writeString(archiveFile, encoded, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                    Path tempLatest = latestFile.resolveSibling(latestFile.getFileName() + ".part");
                    Files.writeString(tempLatest, encoded, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                    Files.move(tempLatest, latestFile, StandardCopyOption.REPLACE_EXISTING);

                    LOG.info("[Snapshot] Saved {} ({}) -> {}", suffix, encoded.length() + " chars", archiveFile);
                    client.execute(() -> {
                        if (button != null) {
                            button.active = true;
                            button.setMessage(new LiteralText("Snapshot ✓"));
                        }
                        if (client.player != null) {
                            client.player.sendMessage(new LiteralText(
                                    "§a[Bot] Snapshot 已导出 (" + (hasPathTask ? "路径长方体+节点列表" : "周围5区块") + "): §f"
                                            + snapshotsDir.getFileName() + "/" + archiveFile.getFileName()
                            ), false);
                        }
                    });
                } catch (Exception e) {
                    LOG.error("Failed writing pause snapshot", e);
                    client.execute(() -> {
                        if (button != null) {
                            button.active = true;
                            button.setMessage(new LiteralText("Snapshot !"));
                        }
                    });
                }
            });
        } catch (RuntimeException ex) {
            LOG.error("Failed capturing pause snapshot on client thread", ex);
            if (button != null) {
                button.active = true;
                button.setMessage(new LiteralText("Snapshot !"));
            }
        }
    }

    /** 判断当前是否有路径任务并返回目标点；若无路径任务则返回 null。 */
    private static BlockPos resolveActivePathTarget(PathExecutor pathExecutor) {
        if (pathExecutor == null) return null;
        boolean active = pathExecutor.isBusy() || pathExecutor.hasSuspendedGoal();
        if (!active) return null;

        if (pathExecutor.getFinalDestination() != null) {
            return pathExecutor.getFinalDestination();
        }
        List<SimplePathfinder.Node> currentPath = pathExecutor.getCurrentPath();
        if (currentPath != null && !currentPath.isEmpty()) {
            return currentPath.get(currentPath.size() - 1).pos;
        }
        if (!pathExecutor.suspendedDestinations.isEmpty()) {
            return pathExecutor.suspendedDestinations.peek();
        }
        return null;
    }

    /**
     * 有路径任务时：记录当前位置到目标点形成的长方体范围的样子和节点列表。
     */
    private static void capturePathCuboidAndNodes(
            World world,
            PlayerEntity player,
            PathExecutor pathExecutor,
            BlockPos targetPos,
            Map<String, Object> out
    ) {
        BlockPos startPos = player.getBlockPos();
        List<SimplePathfinder.Node> path = pathExecutor.getCurrentPath();
        int currentIndex = pathExecutor.getCurrentPathIndex();
        boolean strictReach = pathExecutor.getConsecutiveStuckCount() >= 2;

        Set<BlockPos> pathPositions = new HashSet<>();
        if (path != null) {
            for (SimplePathfinder.Node node : path) {
                if (node != null && node.pos != null) {
                    pathPositions.add(node.pos);
                }
            }
        }

        int minX = Math.min(startPos.getX(), targetPos.getX());
        int maxX = Math.max(startPos.getX(), targetPos.getX());
        int minY = Math.max(0, Math.min(startPos.getY(), targetPos.getY()));
        int maxY = Math.min(255, Math.max(startPos.getY(), targetPos.getY()));
        int minZ = Math.min(startPos.getZ(), targetPos.getZ());
        int maxZ = Math.max(startPos.getZ(), targetPos.getZ());

        long sizeX = maxX - minX + 1L;
        long sizeY = maxY - minY + 1L;
        long sizeZ = maxZ - minZ + 1L;
        long volume = sizeX * sizeY * sizeZ;

        Map<String, Object> cuboid = new LinkedHashMap<>();
        cuboid.put("from_pos", posList(startPos));
        cuboid.put("to_pos", posList(targetPos));
        cuboid.put("min", List.of(minX, minY, minZ));
        cuboid.put("max", List.of(maxX, maxY, maxZ));
        cuboid.put("dimensions", List.of(sizeX, sizeY, sizeZ));
        cuboid.put("total_blocks", volume);
        cuboid.put("legend", Map.of(
                "S", "当前位置(Start)",
                "T", "目标点(Target)",
                "@", "路径节点(PathNode)",
                ".", "空气(Air)",
                "~", "水(Water)",
                "!", "岩浆(Lava)",
                "G", "沙砾(Gravel)",
                "^", "树叶/原木(Tree)",
                "/", "门(Door)",
                "#", "实体方块(Solid)"
        ));

        IdentityHashMap<BlockState, Integer> stateToId = new IdentityHashMap<>();
        List<Map<String, Object>> palette = new ArrayList<>();
        BlockState airState = Blocks.AIR.getDefaultState();
        stateToId.put(airState, 0);
        palette.add(describeBlockState(world, new BlockPos(0, 0, 0), airState, 0));

        List<Map<String, Object>> visualLayers = new ArrayList<>();
        List<List<Integer>> nonAirBlocks = new ArrayList<>();
        List<Map<String, Object>> detailedCells = volume <= 4096 ? new ArrayList<>() : null;
        List<Integer> rle = new ArrayList<>();
        int rleCurrentId = -1;
        int rleCount = 0;

        boolean buildAsciiSlices = (sizeX <= 160 && sizeZ <= 160 && volume <= 500_000);
        BlockPos.Mutable mutable = new BlockPos.Mutable();

        for (int y = minY; y <= maxY; y++) {
            List<String> zRows = buildAsciiSlices ? new ArrayList<>((int) sizeZ) : null;
            for (int z = minZ; z <= maxZ; z++) {
                StringBuilder rowBuilder = buildAsciiSlices ? new StringBuilder((int) sizeX) : null;
                for (int x = minX; x <= maxX; x++) {
                    mutable.set(x, y, z);
                    boolean chunkLoaded = world.getChunkManager().isChunkLoaded(x >> 4, z >> 4);
                    BlockState state = chunkLoaded ? world.getBlockState(mutable) : airState;

                    Integer pid = stateToId.get(state);
                    if (pid == null) {
                        pid = palette.size();
                        stateToId.put(state, pid);
                        palette.add(describeBlockState(world, mutable, state, pid));
                    }

                    if (pid == rleCurrentId) {
                        rleCount++;
                    } else {
                        if (rleCount > 0) {
                            rle.add(rleCurrentId);
                            rle.add(rleCount);
                        }
                        rleCurrentId = pid;
                        rleCount = 1;
                    }

                    if (!state.isAir()) {
                        nonAirBlocks.add(List.of(x, y, z, pid));
                    }
                    if (detailedCells != null) {
                        detailedCells.add(captureCellDetail(world, mutable.toImmutable(), chunkLoaded, state));
                    }

                    if (rowBuilder != null) {
                        if (x == startPos.getX() && y == startPos.getY() && z == startPos.getZ()) {
                            rowBuilder.append('S');
                        } else if (x == targetPos.getX() && y == targetPos.getY() && z == targetPos.getZ()) {
                            rowBuilder.append('T');
                        } else if (pathPositions.contains(mutable)) {
                            rowBuilder.append('@');
                        } else if (!chunkLoaded) {
                            rowBuilder.append('?');
                        } else {
                            rowBuilder.append(symbolForState(state));
                        }
                    }
                }
                if (zRows != null) {
                    zRows.add(rowBuilder.toString());
                }
            }
            if (zRows != null) {
                Map<String, Object> slice = new LinkedHashMap<>();
                slice.put("y", y);
                slice.put("rows_z", zRows);
                visualLayers.add(slice);
            }
        }
        if (rleCount > 0) {
            rle.add(rleCurrentId);
            rle.add(rleCount);
        }

        cuboid.put("palette", palette);
        if (buildAsciiSlices) {
            cuboid.put("visual_layers_y", visualLayers);
        }
        cuboid.put("non_air_blocks_xyzi", nonAirBlocks);
        cuboid.put("rle_yzx_palette_counts", rle);
        if (detailedCells != null) {
            cuboid.put("cells_detail", detailedCells);
        }
        out.put("cuboid", cuboid);

        // 节点列表 (nodes)
        Map<String, Object> pathSection = new LinkedHashMap<>();
        pathSection.put("state", pathExecutor.getCurrentState().name());
        pathSection.put("paused", pathExecutor.isPaused());
        pathSection.put("uses_action_planner", pathExecutor.isUsingActionPlanner());
        pathSection.put("current_path_index", currentIndex);
        pathSection.put("final_destination", posList(targetPos));

        List<List<Integer>> suspended = new ArrayList<>();
        for (BlockPos sp : pathExecutor.suspendedDestinations) {
            if (sp != null) suspended.add(posList(sp));
        }
        pathSection.put("suspended_destinations", suspended);

        List<Map<String, Object>> nodeList = new ArrayList<>();
        if (path != null) {
            for (int i = 0; i < path.size(); i++) {
                SimplePathfinder.Node n = path.get(i);
                Map<String, Object> nodeMap = new LinkedHashMap<>();
                nodeMap.put("index", i);
                nodeMap.put("pos", posList(n.pos));
                nodeMap.put("extra_pos", n.extraPos == null ? null : posList(n.extraPos));
                nodeMap.put("state", n.state.name());
                nodeMap.put("parent", n.parent == null ? null : posList(n.parent.pos));
                nodeMap.put("is_current", i == currentIndex);
                nodeMap.put("is_reached", i < currentIndex);
                nodeMap.put("is_final", i == path.size() - 1);
                nodeMap.put("cost_from_start", n.costFromStart);
                nodeMap.put("estimated_cost_to_end", n.estimatedCostToEnd);
                nodeMap.put("total_cost", n.totalCost);
                nodeMap.put("blocks_used", n.blocksUsed);
                nodeMap.put("action_ticks", n.actionTicks);
                nodeMap.put("material_penalty", n.materialPenalty);
                nodeMap.put("reach_box", StuckDump.box(PathReachBoxes.node(path, i, strictReach)));
                if (n.plannedAction != null) {
                    var a = n.plannedAction;
                    Map<String, Object> actionMap = new LinkedHashMap<>();
                    actionMap.put("kind", a.kind);
                    actionMap.put("movement", a.movement.name());
                    actionMap.put("breaks", a.breaks.stream().map(WorldSnapshotDumper::posList).toList());
                    actionMap.put("tools", a.tools.stream().map(Object::toString).toList());
                    actionMap.put("material", a.material == null ? null : a.material.toString());
                    nodeMap.put("action", actionMap);
                } else {
                    nodeMap.put("action", null);
                }
                nodeList.add(nodeMap);
            }
        }
        pathSection.put("node_count", nodeList.size());
        pathSection.put("nodes", nodeList);

        PlannerDebug.View searchView = pathExecutor.getSearchView();
        if (searchView != null) {
            Map<String, Object> viewMap = new LinkedHashMap<>();
            viewMap.put("phase", searchView.phase);
            viewMap.put("detail", searchView.detail);
            viewMap.put("elapsed_ms", searchView.elapsedMillis);
            viewMap.put("expanded", searchView.expanded);
            viewMap.put("open", searchView.open);
            viewMap.put("known", searchView.known);
            viewMap.put("current_search_path", searchView.currentPath.stream().map(WorldSnapshotDumper::posList).toList());
            viewMap.put("closest_search_path", searchView.closestPath.stream().map(WorldSnapshotDumper::posList).toList());
            pathSection.put("active_search_preview", viewMap);
        }

        out.put("node_list", nodeList);
        out.put("path_task", pathSection);
    }

    /**
     * 没有路径任务时：Dump 玩家周围 5 区块（chunkX-5..chunkX+5, chunkZ-5..chunkZ+5）的内容。
     */
    private static void captureSurroundingChunks(
            World world,
            PlayerEntity player,
            int chunkRadius,
            Map<String, Object> out
    ) {
        BlockPos playerPos = player.getBlockPos();
        int centerChunkX = playerPos.getX() >> 4;
        int centerChunkZ = playerPos.getZ() >> 4;
        int minChunkX = centerChunkX - chunkRadius;
        int maxChunkX = centerChunkX + chunkRadius;
        int minChunkZ = centerChunkZ - chunkRadius;
        int maxChunkZ = centerChunkZ + chunkRadius;

        int minBlockX = minChunkX << 4;
        int maxBlockX = (maxChunkX << 4) + 15;
        int minBlockZ = minChunkZ << 4;
        int maxBlockZ = (maxChunkZ << 4) + 15;

        Map<String, Object> region = new LinkedHashMap<>();
        region.put("center_chunk", List.of(centerChunkX, centerChunkZ));
        region.put("chunk_radius", chunkRadius);
        region.put("chunk_min", List.of(minChunkX, minChunkZ));
        region.put("chunk_max", List.of(maxChunkX, maxChunkZ));
        region.put("block_min", List.of(minBlockX, 0, minBlockZ));
        region.put("block_max", List.of(maxBlockX, 255, maxBlockZ));
        region.put("total_chunks", (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1));

        IdentityHashMap<BlockState, Integer> stateToId = new IdentityHashMap<>();
        List<Map<String, Object>> palette = new ArrayList<>();
        BlockState airState = Blocks.AIR.getDefaultState();
        stateToId.put(airState, 0);
        palette.add(describeBlockState(world, new BlockPos(0, 0, 0), airState, 0));

        List<Map<String, Object>> chunkDumps = new ArrayList<>();
        BlockPos.Mutable mutable = new BlockPos.Mutable();

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                boolean loaded = world.getChunkManager().isChunkLoaded(cx, cz);
                Map<String, Object> chunkData = new LinkedHashMap<>();
                chunkData.put("chunk_pos", List.of(cx, cz));
                chunkData.put("block_origin", List.of(cx << 4, 0, cz << 4));
                chunkData.put("loaded", loaded);

                if (loaded) {
                    int baseX = cx << 4;
                    int baseZ = cz << 4;

                    Map<String, Integer> blockCounts = new HashMap<>();
                    List<Integer> rle = new ArrayList<>();
                    int rleCurrentId = -1;
                    int rleCount = 0;
                    int maxNonAirY = 0;

                    for (int y = 0; y <= 255; y++) {
                        for (int dz = 0; dz < 16; dz++) {
                            int wz = baseZ + dz;
                            for (int dx = 0; dx < 16; dx++) {
                                mutable.set(baseX + dx, y, wz);
                                BlockState state = world.getBlockState(mutable);
                                Integer pid = stateToId.get(state);
                                if (pid == null) {
                                    pid = palette.size();
                                    stateToId.put(state, pid);
                                    palette.add(describeBlockState(world, mutable, state, pid));
                                }
                                if (pid == rleCurrentId) {
                                    rleCount++;
                                } else {
                                    if (rleCount > 0) {
                                        rle.add(rleCurrentId);
                                        rle.add(rleCount);
                                    }
                                    rleCurrentId = pid;
                                    rleCount = 1;
                                }
                                if (!state.isAir()) {
                                    if (y > maxNonAirY) maxNonAirY = y;
                                    blockCounts.merge(state.getBlock().toString(), 1, Integer::sum);
                                }
                            }
                        }
                    }
                    if (rleCount > 0) {
                        rle.add(rleCurrentId);
                        rle.add(rleCount);
                    }
                    chunkData.put("max_non_air_y", maxNonAirY);
                    chunkData.put("non_air_block_counts", blockCounts);
                    chunkData.put("rle_yzx_palette_counts", rle);
                }
                chunkDumps.add(chunkData);
            }
        }

        Box entityBox = new Box(minBlockX, 0, minBlockZ, maxBlockX + 1, 256, maxBlockZ + 1);
        List<Entity> entities = world.getEntities(Entity.class, entityBox, e -> e != null && e.isAlive());
        List<Map<String, Object>> entityList = new ArrayList<>();
        for (Entity e : entities) {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("entity", e.toString());
            em.put("pos", List.of(e.getX(), e.getY(), e.getZ()));
            em.put("block_pos", posList(e.getBlockPos()));
            if (e instanceof ItemEntity itemEntity && !itemEntity.getStack().isEmpty()) {
                em.put("item", itemEntity.getStack().getItem().toString());
                em.put("count", itemEntity.getStack().getCount());
            }
            entityList.add(em);
        }

        region.put("palette", palette);
        region.put("chunks", chunkDumps);
        region.put("entities", entityList);
        out.put("surrounding_5_chunks", region);
    }

    private static Map<String, Object> describeBlockState(World world, BlockPos samplePos, BlockState state, int id) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", id);
        info.put("symbol", String.valueOf(symbolForState(state)));
        info.put("block_state", state.toString());
        info.put("block", state.getBlock().toString());
        info.put("air", state.isAir());
        info.put("water", state.getFluidState().isIn(FluidTags.WATER));
        info.put("lava", state.getFluidState().isIn(FluidTags.LAVA));
        info.put("replaceable", state.getMaterial().isReplaceable());
        info.put("gravity", state.getBlock() instanceof FallingBlock);
        var shape = state.getCollisionShape(world, samplePos);
        info.put("passable", shape.isEmpty());
        info.put("full_support", Block.isShapeFullCube(shape));
        return info;
    }

    private static Map<String, Object> captureCellDetail(World world, BlockPos p, boolean loaded, BlockState state) {
        Map<String, Object> cell = new LinkedHashMap<>();
        cell.put("pos", posList(p));
        if (!loaded) {
            cell.put("availability", "UNLOADED");
            return cell;
        }
        cell.put("availability", "LOADED");
        cell.put("block_state", state.toString());
        cell.put("water", state.getFluidState().isIn(FluidTags.WATER));
        cell.put("lava", state.getFluidState().isIn(FluidTags.LAVA));
        cell.put("replaceable", state.getMaterial().isReplaceable());
        List<Object> boxes = new ArrayList<>();
        for (Box b : state.getCollisionShape(world, p).getBoundingBoxes()) {
            boxes.add(StuckDump.box(b));
        }
        cell.put("collision_boxes_local", boxes);
        return cell;
    }

    private static char symbolForState(BlockState state) {
        if (state.isAir()) return '.';
        if (state.getFluidState().isIn(FluidTags.LAVA)) return '!';
        if (state.getFluidState().isIn(FluidTags.WATER)) return '~';
        Block block = state.getBlock();
        if (block == Blocks.GRAVEL) return 'G';
        if (block instanceof DoorBlock) return '/';
        if (state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.LOGS)) return '^';
        if (state.getMaterial().isReplaceable()) return '_';
        return '#';
    }

    private static List<Integer> posList(BlockPos p) {
        return List.of(p.getX(), p.getY(), p.getZ());
    }
}
