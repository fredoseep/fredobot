package com.fredoseep.preflight;

import java.util.function.Predicate;

/** Fixed short-circuit order: failed loot never triggers terrain capture/generation. */
public final class PreflightOrder {
    public enum Stage { LOOT, TNT, COLLECTION, MAGMA, GRAVEL }
    private PreflightOrder() {}
    public static Stage firstFailure(boolean hasTnt, Predicate<Stage> check) {
        for (Stage stage : Stage.values()) {
            if (stage == Stage.TNT && !hasTnt) continue;
            if (!check.test(stage)) return stage;
        }
        return null;
    }
}
