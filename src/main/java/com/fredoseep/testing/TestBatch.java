package com.fredoseep.testing;

import java.util.EnumMap;
import java.util.Map;

/** Pure lifecycle bookkeeping: missing fixtures and infrastructure errors never count as passes. */
public final class TestBatch {
    public enum Outcome { PASS, MISMATCH, SKIP, ERROR, CANCELLED }
    private final int requested;
    private int completed;
    private final EnumMap<Outcome, Integer> counts = new EnumMap<>(Outcome.class);
    private boolean stopped;

    public TestBatch(int requested) {
        if (requested < 1 || requested > 1000) throw new IllegalArgumentException("rounds must be 1..1000");
        this.requested = requested;
        for (Outcome outcome : Outcome.values()) counts.put(outcome, 0);
    }

    public void record(Outcome outcome) {
        if (stopped || completed >= requested) throw new IllegalStateException("batch already finished");
        counts.put(outcome, counts.get(outcome) + 1);
        completed++;
        if (outcome == Outcome.CANCELLED) stopped = true;
    }

    public void stop() { stopped = true; }
    public boolean hasNext() { return !stopped && completed < requested; }
    public int round() { return completed + 1; }
    public int requested() { return requested; }
    public int completed() { return completed; }
    public Map<Outcome, Integer> counts() { return Map.copyOf(counts); }
}
