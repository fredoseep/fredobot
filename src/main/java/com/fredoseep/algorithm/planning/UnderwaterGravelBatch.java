package com.fredoseep.algorithm.planning;

/** Door gravel batch policy; dry gravel work keeps its own requirements. */
public final class UnderwaterGravelBatch {
    public static final int BLOCK_LIMIT = 3;
    public static final int RECOVERY_TICKS = 60;
    public static final int TARGET_TICKS = 30;
    public static final int QUIET_TICKS = 6;

    private UnderwaterGravelBatch() {}

    public static boolean complete(int confirmedBreaks, boolean flint) {
        return confirmedBreaks >= BLOCK_LIMIT || flint;
    }

    public static boolean expired(int start, int now, int air) {
        return start >= 0 && (now - start >= RECOVERY_TICKS || air <= 60);
    }

    public static boolean accepts(boolean gravel, boolean flint) {
        return gravel || flint;
    }
}
