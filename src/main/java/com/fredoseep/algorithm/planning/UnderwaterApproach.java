package com.fredoseep.algorithm.planning;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.util.function.Function;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Geometry and handoff policy only; each leg still uses the active path planner. */
public final class UnderwaterApproach {
    public static final int MIN_DEPTH=4;
    public static final double MAX_SURFACE_SHIFT=4;
    public static final double SHIFT_PER_DEPTH=.15;
    public static final double LOCAL_UNDERWATER_RADIUS=10;
    public record Plan(BlockPos surface,BlockPos goal) {}
    public enum Phase { SURFACE, REFILL, DESCENT }
    public static final class Session {
        public final Plan plan;
        private Phase phase=Phase.SURFACE;
        public Session(Plan plan){this.plan=plan;}
        public Phase phase(){return phase;}
        public boolean reachedSurface(){if(phase!=Phase.SURFACE)return false;phase=Phase.REFILL;return true;}
        public boolean startDescent(boolean submerged,int air,int maxAir){
            if(phase!=Phase.REFILL || submerged || maxAir<=0 || air<maxAir)return false;
            phase=Phase.DESCENT;return true;
        }
    }
    private UnderwaterApproach(){}
    public static Plan plan(Vec3d player,BlockPos goal,boolean submerged,Function<BlockPos,Integer> waterTop,
                            Predicate<BlockPos> forbidden,BiPredicate<BlockPos,BlockPos> descentClear){
        double dx=player.x-goal.getX()-.5,dz=player.z-goal.getZ()-.5,distance=Math.hypot(dx,dz);
        Integer top=waterTop.apply(goal);
        if(top==null || top-goal.getY()<MIN_DEPTH)return null;
        // A demolished door leaves a brief dry breathing pocket: physical submersion is not
        // a reliable indication that a nearby underwater pickup/work target needs a surface detour.
        boolean alreadyBelowSurface=submerged || player.y<top-2;
        if(alreadyBelowSurface && Math.hypot(distance,player.y-goal.getY())<=LOCAL_UNDERWATER_RADIUS)return null;
        double shift=Math.min(MAX_SURFACE_SHIFT,Math.min((top-goal.getY())*SHIFT_PER_DEPTH,distance*.25));
        for(double offset=shift;offset>=-.001;offset-=.5){
            double scale=distance<.001?0:Math.max(0,offset)/distance;
            BlockPos column=new BlockPos(goal.getX()+.5+dx*scale,goal.getY(),goal.getZ()+.5+dz*scale);
            double actual=Math.hypot(column.getX()-goal.getX(),column.getZ()-goal.getZ());
            if(actual>shift+.001)continue;
            Integer y=waterTop.apply(column);
            if(y==null || y-goal.getY()<MIN_DEPTH)continue;
            BlockPos surface=new BlockPos(column.getX(),y,column.getZ());
            if(!forbidden.test(surface) && descentClear.test(surface,goal))return new Plan(surface,goal.toImmutable());
        }
        // Explicit vertical fallback also covers non-integral shifts that never sampled exactly zero.
        BlockPos surface=new BlockPos(goal.getX(),top,goal.getZ());
        return !forbidden.test(surface) && descentClear.test(surface,goal)?new Plan(surface,goal.toImmutable()):null;
    }
}
