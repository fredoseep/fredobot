package com.fredoseep.testing;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;

import java.util.Map;

/** Client-thread-only scenario context. The generation seed is intentionally not exposed. */
public final class CreativeTestContext {
    public final MinecraftClient client;
    public final ClientWorld world;
    public final int round;
    private final CreativeWorldTestRunner runner;
    private boolean done;
    private String phase = "START";
    private final TestPhaseClock phaseClock = new TestPhaseClock(System::nanoTime);

    CreativeTestContext(CreativeWorldTestRunner runner, MinecraftClient client, int round) {
        this.runner = runner;
        this.client = client;
        this.world = client.world;
        this.round = round;
    }

    public void command(String command) {
        if (done || client.world != world || client.player == null) return;
        runner.event("command", Map.of("command", command));
        client.player.sendChatMessage(command);
    }

    public void phase(String phase) {
        this.phase = phase;
        phaseClock.reset();
        runner.event("phase", Map.of("phase", phase));
    }

    public String phase() { return phase; }
    public long phaseMillis() { return phaseClock.millis(); }
    void setPaused(boolean paused) { phaseClock.setPaused(paused); }
    public boolean done() { return done; }
    public void event(String type, Map<String, ?> data) { runner.event(type, data); }

    public boolean seedMatchesCreatedWorld(long observedSeed) {
        return runner.seedMatchesCreatedWorld(observedSeed);
    }

    public void complete(TestBatch.Outcome outcome, String reason, Map<String, ?> details) {
        if (done) return;
        done = true;
        runner.roundCompleted(outcome, reason, details);
    }
}
