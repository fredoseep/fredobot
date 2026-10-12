package com.fredoseep.algorithm.planning;

import java.util.*;
import java.util.function.BiPredicate;

/** Pure loading gate: all required chunks present continuously for a stable period, with a finite timeout. */
public final class TerrainReadiness {
    public static final class Check {
        public final int loaded,total;
        public final boolean ready,timedOut;
        public final List<String> missing;
        Check(int loaded,int total,boolean ready,boolean timedOut,List<String> missing) {
            this.loaded=loaded; this.total=total; this.ready=ready; this.timedOut=timedOut; this.missing=List.copyOf(missing);
        }
    }
    private final int minX,maxX,minZ,maxZ,total;
    private final long started,timeout,stableDuration;
    private long stableSince=-1;
    public TerrainReadiness(int startX,int startZ,int goalX,int goalZ,int margin,long started,long timeout,long stableDuration) {
        minX=Math.floorDiv(Math.min(startX,goalX)-margin,16); maxX=Math.floorDiv(Math.max(startX,goalX)+margin,16);
        minZ=Math.floorDiv(Math.min(startZ,goalZ)-margin,16); maxZ=Math.floorDiv(Math.max(startZ,goalZ)+margin,16);
        long count=(long)(maxX-minX+1)*(maxZ-minZ+1);
        if(count>16384) throw new IllegalArgumentException("Requested terrain exceeds 16384 chunks; use a nearer goal or smaller margin");
        total=(int)count; this.started=started; this.timeout=timeout; this.stableDuration=stableDuration;
    }
    public Check check(long now,BiPredicate<Integer,Integer> loadedChunk) {
        int loaded=0; List<String> missing=new ArrayList<>();
        for(int x=minX;x<=maxX;x++) for(int z=minZ;z<=maxZ;z++) {
            if(loadedChunk.test(x,z)) loaded++;
            else if(missing.size()<8) missing.add(x+","+z);
        }
        if(loaded==total) { if(stableSince<0) stableSince=now; } else stableSince=-1;
        boolean ready=stableSince>=0 && now-stableSince>=stableDuration;
        return new Check(loaded,total,ready,!ready && now-started>=timeout,missing);
    }
}
