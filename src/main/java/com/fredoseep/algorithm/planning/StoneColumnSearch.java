package com.fredoseep.algorithm.planning;

import net.minecraft.util.math.BlockPos;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** First stone directly below the player, then its fixed 3x3 column layer by layer. */
public final class StoneColumnSearch {
    private StoneColumnSearch() {}
    private static final int[][] NEIGHBORS={
            {0,-1},{1,0},{0,1},{-1,0},{-1,-1},{1,-1},{1,1},{-1,1}
    };

    public static List<BlockPos> find(BlockPos origin,int count,int depth,Predicate<BlockPos> stone){
        List<BlockPos> result=new ArrayList<>();
        if(count<=0 || depth<=0)return result;
        boolean foundFirst=false;
        for(int down=1;down<=depth && origin.getY()-down>=1;down++){
            BlockPos center=origin.add(0,-down,0);
            if(!foundFirst){
                if(!stone.test(center))continue;
                foundFirst=true;
            }
            if(stone.test(center))result.add(center.toImmutable());
            if(result.size()>=count)return result;
            for(int[] offset:NEIGHBORS){
                BlockPos candidate=center.add(offset[0],0,offset[1]);
                if(stone.test(candidate))result.add(candidate.toImmutable());
                if(result.size()>=count)return result;
            }
        }
        return result;
    }
}
