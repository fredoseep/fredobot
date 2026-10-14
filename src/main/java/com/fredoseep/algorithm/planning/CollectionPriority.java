package com.fredoseep.algorithm.planning;

import java.util.function.*;

/** Preferred interaction targets first, then actual interaction distance; keeps a nearest fallback. */
public final class CollectionPriority {
    private CollectionPriority() {}
    public static <T> T nearest(Iterable<T> candidates,Predicate<T> preferred,ToDoubleFunction<T> distance) {
        T best=null;boolean bestPreferred=false;double bestDistance=Double.POSITIVE_INFINITY;
        for(T candidate:candidates) {
            boolean priority=preferred.test(candidate);double cost=distance.applyAsDouble(candidate);
            if(best==null || (priority && !bestPreferred) || (priority==bestPreferred && cost<bestDistance)) {
                best=candidate;bestPreferred=priority;bestDistance=cost;
            }
        }
        return best;
    }
}
