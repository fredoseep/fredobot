package com.fredoseep.testing;

import com.fredoseep.Fredobot;
import com.fredoseep.Test;
import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.GlobalExecutor;
import com.fredoseep.testing.nether.NetherTestScenario;
import com.fredoseep.testing.combat.PacketCombatScenario;
import com.fredoseep.testing.combat.PursuitCombatScenario;
import com.fredoseep.testing.combat.ThreatDefenseScenario;
import com.fredoseep.testing.combat.CriticalCombatScenario;
import com.fredoseep.testing.combat.WeaponYawScenario;
import com.fredoseep.testing.combat.TacticalKnockbackScenario;
import com.google.gson.Gson;
import me.voidxwalker.autoreset.Atum;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.resource.DataPackSettings;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.util.registry.RegistryTracker;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.level.storage.LevelStorage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Client-side reusable normal-creative-world batch template.
 * No Atum world creation or treasure seed filter. No automatic run at game startup.
 */
public final class CreativeWorldTestRunner {
    private static final CreativeWorldTestRunner INSTANCE = new CreativeWorldTestRunner();
    private static final Logger LOGGER = LogManager.getLogger("FredoBotTests");
    private static final Gson JSON = new Gson();
    private static final Map<String, Supplier<CreativeWorldScenario>> SCENARIOS = new LinkedHashMap<>();
    static {
        register("treasure", TreasurePredictionScenario::new);
        register("nether", NetherTestScenario::new);
        register("combat", PacketCombatScenario::new);
        register("knockback", PacketCombatScenario::knockback);
        register("pursuit", PursuitCombatScenario::new);
        register("defense", PursuitCombatScenario::continuousDefense);
        register("defense_ready", PursuitCombatScenario::cooldownDefense);
        register("threat_defense", ThreatDefenseScenario::new);
        register("predictive_defense", ThreatDefenseScenario::predictiveComparison);
        register("sidestep_defense", ThreatDefenseScenario::sidestepComparison);
        register("terrain_defense", ThreatDefenseScenario::terrainComparison);
        register("escape_defense", ThreatDefenseScenario::escapeComparison);
        register("critical", CriticalCombatScenario::new);
        register("critical_pursuit", ThreatDefenseScenario::criticalComparison);
        register("moving_critical", ThreatDefenseScenario::movingCriticalComparison);
        register("continuation_defense", ThreatDefenseScenario::continuationComparison);
        register("validated_defense", ThreatDefenseScenario::validatedContinuationComparison);
        register("weapon_yaw", WeaponYawScenario::new);
        register("tactical_knockback", TacticalKnockbackScenario::new);
        register("ranged_pursuit", TacticalKnockbackScenario::rangedPursuit);
        register("ranged_contact", TacticalKnockbackScenario::rangedContact);
        register("projectile_evasion", TacticalKnockbackScenario::projectileEvasion);
        register("contact_jump", TacticalKnockbackScenario::contactJump);
        register("contact_robustness", TacticalKnockbackScenario::contactRobustness);
        register("contact_impulse", TacticalKnockbackScenario::contactImpulse);
        register("contact_tracking", TacticalKnockbackScenario::contactTracking);
    }

    private enum Phase { PREPARE_WORLD, WAIT_WORLD, RUNNING, ROUND_FINISHED }
    private record SavedRound(String directory, TestBatch.Outcome outcome) {}
    private final List<SavedRound> ownedWorlds = new ArrayList<>();
    private boolean active, creating, previousTesting;
    private boolean flatCombatWorld;
    private Phase phase;
    private TestBatch batch;
    private Supplier<CreativeWorldScenario> factory;
    private CreativeWorldScenario scenario;
    private CreativeTestContext context;
    private ClientWorld expectedWorld;
    private String runId, worldName;
    private long requestedSeed, deadline, roundFinishedAt;
    private Long firstSeed;
    private Random seeds;
    private BufferedWriter report;
    private Path reportPath;
    private int consecutiveErrors;

    private CreativeWorldTestRunner() {}
    public static boolean isActive() { return INSTANCE.active; }
    public static void register(String name, Supplier<CreativeWorldScenario> scenario) { SCENARIOS.put(name, scenario); }

    public static void start(String name, int rounds, Long firstSeed) {
        INSTANCE.begin(MinecraftClient.getInstance(), name, rounds, firstSeed);
    }

    public static void tick(MinecraftClient client) { INSTANCE.pump(client); }
    public static void chat(Text message) {
        if (INSTANCE.active && INSTANCE.context != null && !INSTANCE.context.done()
                && INSTANCE.phase == Phase.RUNNING) INSTANCE.guard(() -> INSTANCE.scenario.onChat(INSTANCE.context, message));
    }
    public static void inventory(InventoryS2CPacket packet) {
        if (INSTANCE.active && INSTANCE.context != null && !INSTANCE.context.done()
                && INSTANCE.phase == Phase.RUNNING) INSTANCE.guard(() -> INSTANCE.scenario.onInventory(INSTANCE.context, packet));
    }

    /** Consumes only this local command; ordinary commands continue through vanilla. */
    public static boolean command(String message) {
        String[] args = message.trim().split("\\s+");
        if (args.length == 0 || !args[0].equalsIgnoreCase("/fredotest")) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        try {
            if (args.length == 2 && args[1].equalsIgnoreCase("stop")) INSTANCE.cancel("USER_STOP");
            else if (args.length == 2 && args[1].equalsIgnoreCase("status")) say(client, INSTANCE.status());
            else if (args.length >= 2 && args.length <= 4 && SCENARIOS.containsKey(args[1])) {
                int rounds = args.length >= 3 ? Integer.parseInt(args[2]) : (args[1].equals("nether") || args[1].equals("combat") || args[1].equals("knockback") || args[1].equals("pursuit") || args[1].equals("defense") || args[1].equals("defense_ready") || args[1].equals("threat_defense") || args[1].equals("predictive_defense") || args[1].equals("sidestep_defense") || args[1].equals("terrain_defense") || args[1].equals("escape_defense") || args[1].equals("projectile_evasion") || args[1].equals("contact_jump") || args[1].equals("contact_robustness") || args[1].equals("contact_impulse") || args[1].equals("contact_tracking") || args[1].equals("ranged_contact") || args[1].equals("ranged_pursuit") || args[1].equals("tactical_knockback") || args[1].equals("weapon_yaw") || args[1].equals("validated_defense") || args[1].equals("continuation_defense") || args[1].equals("moving_critical") || args[1].equals("critical_pursuit") || args[1].equals("critical")) ? 1 : 10;
                Long seed = args.length == 4 ? Long.valueOf(args[3]) : null;
                INSTANCE.begin(client, args[1], rounds, seed);
            } else say(client, "用法：/fredotest projectile_evasion [1] [seed] | contact_jump [1] [seed] | contact_robustness [1] [seed] | contact_impulse [1] [seed] | contact_tracking [1] [seed] | ranged_contact [1] [seed] | ranged_pursuit [1] [seed] | tactical_knockback [1] [seed] | weapon_yaw [1] [seed] | validated_defense [1] [seed] | continuation_defense [1] [seed] | moving_critical [1] [seed] | critical_pursuit [1] [seed] | critical [1] [seed] | escape_defense [1] [seed] | terrain_defense [1] [seed] | sidestep_defense [1] [seed] | predictive_defense [1] [seed] | threat_defense [1] [seed] | defense_ready [1] [seed] | defense [1] [seed] | pursuit [1] [seed] | knockback [1] [seed] | combat [1] [seed] | nether [1] [seed] | treasure [轮数1..1000] [seed] | stop | status");
        } catch (IllegalArgumentException exception) { say(client, "参数错误：" + exception.getMessage()); }
        return true;
    }

    private void begin(MinecraftClient client, String name, int rounds, Long firstSeed) {
        if (active) { say(client, "已有测试运行；/fredotest stop 可停止。"); return; }
        if (client.world != null && !client.isInSingleplayer()) { say(client, "测试只支持单人世界或标题界面。"); return; }
        Supplier<CreativeWorldScenario> selected = SCENARIOS.get(name);
        if (selected == null) throw new IllegalArgumentException("unknown test: " + name);
        if ((name.equals("combat") || name.equals("knockback") || name.equals("pursuit") || name.equals("defense") || name.equals("defense_ready") || name.equals("threat_defense") || name.equals("predictive_defense") || name.equals("sidestep_defense") || name.equals("terrain_defense") || name.equals("escape_defense") || name.equals("projectile_evasion") || name.equals("contact_jump") || name.equals("contact_robustness") || name.equals("contact_impulse") || name.equals("contact_tracking") || name.equals("ranged_contact") || name.equals("ranged_pursuit") || name.equals("tactical_knockback") || name.equals("weapon_yaw") || name.equals("validated_defense") || name.equals("continuation_defense") || name.equals("moving_critical") || name.equals("critical_pursuit") || name.equals("critical")) && rounds != 1) throw new IllegalArgumentException("攻击测试一次运行一个世界，内部自动遍历所有项目");
        if (name.equals("nether") && rounds != 1) throw new IllegalArgumentException("地狱测试交接给 Bot，一次只创建一个世界");
        TestBatch requested = new TestBatch(rounds);
        runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + UUID.randomUUID();
        reportPath = client.runDirectory.toPath().resolve("logs/fredobot-tests/" + runId + ".jsonl");
        try {
            Files.createDirectories(reportPath.getParent());
            pruneReports(reportPath.getParent());
            report = Files.newBufferedWriter(reportPath, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            LOGGER.error("Cannot create test report", exception);
            say(client, "无法创建测试报告，测试没有开始。");
            return;
        }
        previousTesting = Fredobot.isTesting;
        BotEngine.getInstance().stop();
        Test.reset();
        Atum.stopRunning(); // clears pending reset and seed futures; config is not changed/saved
        Fredobot.isTesting = true;
        batch = requested; factory = selected; this.firstSeed = firstSeed;
        flatCombatWorld = name.equals("combat") || name.equals("knockback") || name.equals("pursuit") || name.equals("defense") || name.equals("defense_ready") || name.equals("threat_defense") || name.equals("predictive_defense") || name.equals("sidestep_defense") || name.equals("terrain_defense") || name.equals("escape_defense") || name.equals("projectile_evasion") || name.equals("contact_jump") || name.equals("contact_robustness") || name.equals("contact_impulse") || name.equals("contact_tracking") || name.equals("ranged_contact") || name.equals("ranged_pursuit") || name.equals("tactical_knockback") || name.equals("weapon_yaw") || name.equals("validated_defense") || name.equals("continuation_defense") || name.equals("moving_critical") || name.equals("critical_pursuit") || name.equals("critical");
        seeds = firstSeed == null ? new Random() : new Random(firstSeed);
        ownedWorlds.clear(); context = null; expectedWorld = null; scenario = null;
        consecutiveErrors = 0;
        active = true; phase = Phase.PREPARE_WORLD;
        event("batch_start", Map.of("test", name, "rounds", rounds, "firstSeed", firstSeed == null ? "random" : firstSeed.toString(),
                "worldMode", flatCombatWorld ? "superflat creative setup; survival combat; structures disabled; cheats enabled"
                        : "normal creative; structures enabled; cheats enabled; vanilla datapacks"));
        say(client, "开始 " + name + " 自动测试，共 " + rounds + " 轮；当前世界会保存并退出。");
    }

    /** 1.16.1's vanilla dedicated-world property parser also provides the flat generator. */
    private static GeneratorOptions createTestOptions(long seed, boolean flatCombat) {
        if (!flatCombat) return new GeneratorOptions(seed, true, false, DimensionType.method_28517(seed));
        Properties properties = new Properties();
        properties.setProperty("level-seed", Long.toString(seed));
        properties.setProperty("level-type", "flat");
        properties.setProperty("generate-structures", "false");
        properties.setProperty("generator-settings", "{\"layers\":[{\"height\":1,\"block\":\"minecraft:bedrock\"},"
                + "{\"height\":2,\"block\":\"minecraft:dirt\"},{\"height\":1,\"block\":\"minecraft:grass_block\"}],"
                + "\"biome\":\"minecraft:plains\",\"structures\":{\"structures\":{}}}");
        GeneratorOptions options = GeneratorOptions.fromProperties(properties);
        if (!options.isFlatWorld()) throw new IllegalStateException("Combat test requires a flat generator");
        return options;
    }

    private void pump(MinecraftClient client) {
        if (!active || creating) return;
        guard(() -> {
            if (phase == Phase.PREPARE_WORLD) {
                if (client.world != null) {
                    client.world.disconnect();
                    client.disconnect();
                }
                client.openScreen(new TitleScreen());
                cleanupWorlds(client, null);
                requestedSeed = batch.completed() == 0 && firstSeed != null ? firstSeed : seeds.nextLong();
                worldName = "FredoTest-" + runId + "-r" + batch.round();
                LevelInfo info = new LevelInfo(worldName, GameMode.CREATIVE, false, Difficulty.NORMAL,
                        true, new GameRules(), DataPackSettings.SAFE_MODE);
                GeneratorOptions options = createTestOptions(requestedSeed, flatCombatWorld);
                phase = Phase.WAIT_WORLD;
                deadline = System.nanoTime() + 180_000_000_000L;
                event("create_world", Map.of("directory", worldName, "requestedSeed", requestedSeed));
                if (!active) return;
                creating = true;
                try { client.method_29607(worldName, info, RegistryTracker.create(), options); }
                finally { creating = false; }
                // createWorld's loading loop can render/tick: scenario entry waits for the next outer client tick.
            } else if (phase == Phase.WAIT_WORLD) {
                if (client.world != null && client.player != null && client.getNetworkHandler() != null
                        && client.player.abilities.creativeMode && client.player.abilities.allowFlying) {
                    expectedWorld = client.world;
                    context = new CreativeTestContext(this, client, batch.round());
                    scenario = factory.get();
                    phase = Phase.RUNNING;
                    client.openScreen(null);
                    scenario.start(context);
                } else if (System.nanoTime() > deadline) {
                    roundCompleted(TestBatch.Outcome.ERROR, "CREATE_WORLD_TIMEOUT", Map.of());
                }
            } else if (phase == Phase.RUNNING) {
                if (client.world != expectedWorld || client.player == null) {
                    cancel("WORLD_LEFT_OR_REPLACED");
                    return;
                }
                context.setPaused(client.isPaused());
                if (client.isPaused()) return;
                scenario.tick(context);
            } else if (phase == Phase.ROUND_FINISHED && System.nanoTime() - roundFinishedAt > 1_000_000_000L) {
                if (client.isPaused()) return;
                if (!batch.hasNext()) { finishBatch(); return; }
                if (context != null) scenario.close(context);
                context = null; expectedWorld = null; scenario = null;
                phase = Phase.PREPARE_WORLD;
            }
        });
    }

    /** Finish only the world-creation harness before enabling the bot's own state machine. */
    void handoffToNether(CreativeTestContext source) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!active || phase != Phase.RUNNING || context != source || client.world != expectedWorld) return;
        event("nether_handoff", Map.of("state", "ANALYZE_THE_BASTION", "worldDirectory", worldName));
        if (!active) return;
        source.complete(TestBatch.Outcome.PASS, "TEST_WORLD_READY", Map.of("scope", "world setup only; NetherAuto owns further behavior"));
        if (!active) return; // A report failure must not start a different task.
        finishBatch(); // Clears isActive(): BotEngine.start() deliberately rejects an active batch.
        Fredobot.isTesting = false; // GlobalController must tick during this test.
        BotEngine engine = BotEngine.getInstance();
        engine.start();
        engine.getModule(GlobalExecutor.class).startNetherTest();
        say(client, "地狱测试已启动：NetherAuto 准备装备并入地狱，随后执行 NetherAnalyzer。");
    }

    boolean seedMatchesCreatedWorld(long observedSeed) { return requestedSeed == observedSeed; }

    void roundCompleted(TestBatch.Outcome outcome, String reason, Map<String, ?> details) {
        if (!active || phase == Phase.ROUND_FINISHED) return;
        int round = batch.round();
        event("round_result", Map.of("outcome", outcome.name(), "reason", reason, "details", details,
                "worldDirectory", worldName, "requestedSeed", requestedSeed));
        if (!active) return;
        batch.record(outcome);
        ownedWorlds.add(new SavedRound(worldName, outcome));
        if (outcome == TestBatch.Outcome.ERROR) consecutiveErrors++; else consecutiveErrors = 0;
        if (consecutiveErrors >= 3) {
            batch.stop();
            event("abort", Map.of("reason", "THREE_CONSECUTIVE_INFRASTRUCTURE_ERRORS"));
        }
        phase = Phase.ROUND_FINISHED; roundFinishedAt = System.nanoTime();
        say(MinecraftClient.getInstance(), "第 " + round + "/" + batch.requested() + " 轮：" + outcome + " " + reason);
    }

    private void guard(Runnable action) {
        try { action.run(); }
        catch (Exception exception) {
            LOGGER.error("Automatic world test failed", exception);
            if (phase == Phase.ROUND_FINISHED) { batch.stop(); finishBatch(); }
            else roundCompleted(TestBatch.Outcome.ERROR, "EXCEPTION", Map.of("exception", exception.toString(), "phase", phase.name()));
        }
    }

    private void cancel(String reason) {
        if (!active) { say(MinecraftClient.getInstance(), "没有自动测试运行。"); return; }
        if (phase == Phase.RUNNING && context != null && !context.done()) {
            context.complete(TestBatch.Outcome.CANCELLED, reason, Map.of("phase", context.phase()));
        } else if (phase != Phase.ROUND_FINISHED) {
            roundCompleted(TestBatch.Outcome.CANCELLED, reason, Map.of());
        }
        batch.stop(); finishBatch();
    }

    private void finishBatch() {
        if (!active) return;
        if (scenario != null && context != null) scenario.close(context);
        active = false;
        Fredobot.isTesting = previousTesting;
        Atum.stopRunning();
        cleanupWorlds(MinecraftClient.getInstance(), worldName);
        event("batch_summary", Map.of("completed", batch.completed(), "requested", batch.requested(), "counts", batch.counts()));
        try { if (report != null) report.close(); } catch (IOException exception) { LOGGER.error("Closing test report failed", exception); }
        report = null;
        say(MinecraftClient.getInstance(), "测试结束：" + batch.counts() + "；报告：" + reportPath);
        // Leave final world/chest open for inspection. Never resume the bot automatically.
    }

    public static List<String> hudLines() {
        if (!INSTANCE.active) return List.of();
        return List.of("[Fredo 测试] 第 " + Math.min(INSTANCE.batch.round(), INSTANCE.batch.requested()) + "/" + INSTANCE.batch.requested() + " 轮",
                "测试状态：" + (INSTANCE.context == null ? INSTANCE.phase.name() : INSTANCE.context.phase()),
                "结果：" + INSTANCE.batch.counts());
    }

    private String status() {
        return active ? String.join(" | ", hudLines()) : "自动测试未运行" + (reportPath == null ? "" : "；上次报告：" + reportPath);
    }

    void event(String type, Map<String, ?> data) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("time", LocalDateTime.now().toString()); row.put("event", type);
        if (batch != null) row.put("round", batch.round());
        row.putAll(data);
        LOGGER.info("[WorldTest] {}", JSON.toJson(row));
        if (report != null) {
            try { report.write(JSON.toJson(row)); report.newLine(); report.flush(); }
            catch (IOException exception) {
                LOGGER.error("Writing test report failed; automatic test stopped", exception);
                active = false;
                if (batch != null) batch.stop();
                Fredobot.isTesting = previousTesting;
                Atum.stopRunning();
                try { report.close(); } catch (IOException closeFailure) { LOGGER.warn("Closing failed test report failed", closeFailure); }
                report = null;
                say(MinecraftClient.getInstance(), "报告写入失败，自动测试已停止：" + reportPath);
            }
        }
    }

    private static void say(MinecraftClient client, String message) {
        LOGGER.info("[WorldTest] {}", message);
        if (client.player != null) client.inGameHud.getChatHud().addMessage(new LiteralText("[Fredo 测试] " + message));
    }

    private static void pruneReports(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> reports = files.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jsonl"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
            for (int i = 0; i < reports.size() - 49; i++) Files.deleteIfExists(reports.get(i));
        }
    }

    private void cleanupWorlds(MinecraftClient client, String retainActive) {
        int failuresKept = 0;
        for (int i = ownedWorlds.size() - 1; i >= 0; i--) {
            SavedRound saved = ownedWorlds.get(i);
            boolean failed = saved.outcome == TestBatch.Outcome.MISMATCH || saved.outcome == TestBatch.Outcome.ERROR;
            if (failed && failuresKept++ < 2) continue;
            if (saved.directory == null || saved.directory.equals(retainActive)) continue;
            // Only exact names created and recorded by THIS batch are eligible.
            Path directory = client.getLevelStorage().getSavesDirectory().resolve(saved.directory);
            if (!Files.isDirectory(directory)) { ownedWorlds.remove(i); continue; }
            try (LevelStorage.Session session = client.getLevelStorage().createSession(saved.directory)) {
                session.deleteSessionLock();
                ownedWorlds.remove(i);
                event("remove_completed_test_world", Map.of("directory", saved.directory));
            } catch (IOException exception) { LOGGER.warn("Cannot clean completed test world {}", saved.directory, exception); }
        }
    }
}
