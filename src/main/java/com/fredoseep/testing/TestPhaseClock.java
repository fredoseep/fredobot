package com.fredoseep.testing;

import java.util.function.LongSupplier;

/** Phase deadlines exclude time spent in the user's vanilla pause menu. */
public final class TestPhaseClock {
    private final LongSupplier nanos;
    private long started, pausedAt, excluded;
    private boolean paused;
    public TestPhaseClock(LongSupplier nanos) { this.nanos = nanos; reset(); }
    public void reset() { started = nanos.getAsLong(); excluded = 0; if (paused) pausedAt = started; }
    public void setPaused(boolean value) {
        if (paused == value) return;
        long now = nanos.getAsLong();
        if (value) pausedAt = now; else excluded += now - pausedAt;
        paused = value;
    }
    public long millis() { return ((paused ? pausedAt : nanos.getAsLong()) - started - excluded) / 1_000_000L; }
}
