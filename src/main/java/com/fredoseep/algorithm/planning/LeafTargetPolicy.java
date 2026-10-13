package com.fredoseep.algorithm.planning;

import net.minecraft.util.math.BlockPos;
import java.util.*;
import java.util.function.Predicate;

/** Batch preflight, not a path search. Shared stance cache avoids re-reading a high canopy per leaf. */
public final class LeafTargetPolicy implements Predicate<BlockPos> {
    private final BlockPos start;
    private final double eyeHeight;
    private final Predicate<BlockPos> standing,direct;
    private final java.util.function.BiPredicate<BlockPos,BlockPos> visible;
    private final Map<BlockPos,Boolean> stanceCache=new HashMap<>(),targetCache=new HashMap<>();
    public LeafTargetPolicy(BlockPos start,double eyeHeight,Predicate<BlockPos> standing,
            Predicate<BlockPos> direct,java.util.function.BiPredicate<BlockPos,BlockPos> visible) {
        this.start=start;this.eyeHeight=eyeHeight;this.standing=standing;this.direct=direct;this.visible=visible;
    }
    @Override public boolean test(BlockPos target) {
        return targetCache.computeIfAbsent(target,p -> direct.test(p) || MiningApproach.select(p,start,eyeHeight,
                stance -> stanceCache.computeIfAbsent(stance,standing::test),stance -> visible.test(stance,p))!=null);
    }
}
