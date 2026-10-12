package com.fredoseep.algorithm.planning;

/** 1.16 survival progress math, using float addition just like the client, not a fractional hardness proxy. */
public final class MiningTime {
    private MiningTime() {}
    public static int ticks(float hardness, float speed, boolean harvestable) {
        if (hardness < 0 || speed <= 0) return Integer.MAX_VALUE;
        if (hardness == 0) return 1;
        float delta = speed / hardness / (harvestable ? 30f : 100f);
        if (delta >= 1f) return 1;
        // Simulating float accumulation matters near integral boundaries.
        float progress = 0;
        for (int n = 1; n <= 1_000_000; n++) {
            if ((n & 1023) == 0 && Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException("Mining precomputation interrupted");
            progress += delta;
            if (progress >= 1f) return n;
        }
        return Integer.MAX_VALUE;
    }
}
