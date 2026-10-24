package com.fredoseep.algorithm.planning;

import net.minecraft.util.math.BlockPos;
import java.util.HashMap;
import java.util.Map;

/** Prediction is provisional: require a stable update window, and throttle failed placement retries. */
public final class DoorPlacementGuard {
    public static final int CONFIRM_TICKS=10;
    private final Map<BlockPos,Integer> goodSince=new HashMap<>(),lastIssue=new HashMap<>();
    public boolean observe(BlockPos door,int tick,boolean matches){
        if(!matches){goodSince.remove(door);return false;}
        int since=goodSince.computeIfAbsent(door.toImmutable(),p->tick);
        return tick-since>=CONFIRM_TICKS;
    }
    public boolean tryIssue(BlockPos door,int tick){
        Integer last=lastIssue.get(door);
        if(last!=null && tick-last<CONFIRM_TICKS)return false;
        lastIssue.put(door.toImmutable(),tick);return true;
    }
    public void clear(){goodSince.clear();lastIssue.clear();}
}
