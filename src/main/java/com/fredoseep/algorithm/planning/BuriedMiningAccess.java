package com.fredoseep.algorithm.planning;

import net.minecraft.util.math.BlockPos;
import java.util.function.Predicate;

/** A mining target remains intact while a dry, contiguous covering column is opened from above. */
public final class BuriedMiningAccess {
    public record Plan(BlockPos target,BlockPos entry) {}
    private BuriedMiningAccess(){}
    public static Plan find(BlockPos target,int maxCover,Predicate<BlockPos> clear,Predicate<BlockPos> safeCover){
        for(int count=0;count<=maxCover;count++){
            BlockPos p=target.up(count+1);
            if(clear.test(p) && clear.test(p.up()))return count==0?null:new Plan(target.toImmutable(),p);
            if(count==maxCover || !safeCover.test(p))return null;
        }
        return null;
    }
    public static boolean readyForCover(BlockPos cover,BlockPos feet,boolean grounded,double velocityY){
        return grounded && Math.abs(velocityY)<=.1 && cover.equals(feet.down());
    }
    public static float centerYaw(double playerMinusTargetX,double playerMinusTargetZ){
        return (float)Math.toDegrees(Math.atan2(playerMinusTargetX,-playerMinusTargetZ));
    }
    public static BlockPos nextCover(Plan plan,Predicate<BlockPos> clear){
        for(int y=plan.entry().getY()-1;y>plan.target().getY();y--){
            BlockPos p=new BlockPos(plan.target().getX(),y,plan.target().getZ());
            if(!clear.test(p))return p;
        }
        return null;
    }
}
