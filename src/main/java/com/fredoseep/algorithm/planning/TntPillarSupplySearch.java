package com.fredoseep.algorithm.planning;

import net.minecraft.util.math.BlockPos;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** Search around the TNT foundation, protecting its entire vertical column. */
public final class TntPillarSupplySearch {
    public record Plan(BlockPos target,BlockPos stand) {}
    private TntPillarSupplySearch() {}

    public static Plan find(BlockPos center,BlockPos tnt,Set<BlockPos> rejected,
                            Predicate<BlockPos> material,Function<BlockPos,BlockPos> stance) {
        for(int radius=1;radius<=16;radius++)
            for(int dy=0;dy<=8;dy++)for(int sign:dy==0?new int[]{1}:new int[]{-1,1})
                for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++){
                    if(Math.max(Math.abs(x),Math.abs(z))!=radius)continue;
                    BlockPos target=center.add(x,dy*sign,z);
                    if(target.getY()<1 || target.getY()>253 || rejected.contains(target)
                            || target.getX()==tnt.getX() && target.getZ()==tnt.getZ() || !material.test(target))continue;
                    BlockPos stand=stance.apply(target);
                    if(stand!=null && !(stand.getX()==tnt.getX() && stand.getZ()==tnt.getZ()))
                        return new Plan(target.toImmutable(),stand.toImmutable());
                }
        return null;
    }
}
