package com.fredoseep.utils.bt;

import com.fredoseep.algorithm.SimplePathfinder;
import net.minecraft.util.math.BlockPos;
import java.util.*;
import java.util.function.*;

/** Deterministic micro-command construction and read-only loot selection. */
public final class TntActions {
    private TntActions() {}
    public static List<SimplePathfinder.Node> coverPath(BlockPos cover) {
        List<SimplePathfinder.Node> result=new ArrayList<>();
        SimplePathfinder.Node parent=null;
        for(int depth=0;depth<=2;depth++) {
            parent=new SimplePathfinder.Node(cover.down(depth),null,parent,0,0,SimplePathfinder.MovementState.MINING,0);
            result.add(parent);
        }
        return result;
    }
    public static <T> T nearest(Iterable<T> candidates,Predicate<T> eligible,ToDoubleFunction<T> distance) {
        T best=null;double closest=Double.POSITIVE_INFINITY;
        for(T candidate:candidates) {
            if(!eligible.test(candidate))continue;
            double d=distance.applyAsDouble(candidate);
            if(d<closest){best=candidate;closest=d;}
        }
        return best;
    }
}
