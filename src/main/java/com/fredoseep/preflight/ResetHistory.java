package com.fredoseep.preflight;

import java.util.concurrent.atomic.AtomicReference;

/** Deliberately survives GlobalExecutor.resetState and changes of world. */
public final class ResetHistory {
    private static final AtomicReference<String> LAST = new AtomicReference<>("尚未 reset");
    private ResetHistory() {}
    public static void record(String reason) { LAST.set(reason == null || reason.isBlank() ? "未注明原因" : reason); }
    public static String lastReason() { return LAST.get(); }
}
