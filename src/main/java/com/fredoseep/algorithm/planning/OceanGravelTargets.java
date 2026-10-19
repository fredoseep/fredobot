package com.fredoseep.algorithm.planning;

import net.minecraft.block.Blocks;
import net.minecraft.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Shared by preflight and live collection. A locally exposed ravine bottom is not a seabed target. */
public final class OceanGravelTargets {
    public static final int MIN_SEABED_Y=20;
    public static final int MAX_DROP_BELOW_RIM=6;
    public record Result(List<BlockPos> targets,int referenceY,int marineColumns,int rejectedDeepGravel) {}
    private record Column(BlockPos floor,int surface) {}
    private OceanGravelTargets(){}
    private static Column column(World world,int x,int z){
        if(!world.getChunkManager().isChunkLoaded(x>>4,z>>4))return null;
        int surface=-1;
        for(int y=100;y>=0;y--){
            BlockPos p=new BlockPos(x,y,z);var state=world.getBlockState(p);
            boolean clear=state.getCollisionShape(world,p).isEmpty();
            if(surface<0){
                if(!clear)return null; // Land/tree/roof above this water is not an open marine column.
                if(state.getFluidState().isIn(FluidTags.WATER)){
                    var head=world.getBlockState(p.up());var head2=world.getBlockState(p.up(2));
                    if(!head.getFluidState().isEmpty() || !head2.getFluidState().isEmpty()
                            || !head.getCollisionShape(world,p.up()).isEmpty() || !head2.getCollisionShape(world,p.up(2)).isEmpty())return null;
                    surface=y;
                } else if(!state.getFluidState().isEmpty())return null;
            }
            if(surface>=0){
                if(state.getFluidState().isIn(FluidTags.LAVA))return null;
                if(!clear)return surface-y>=2?new Column(p,surface):null;
                if(!state.getFluidState().isIn(FluidTags.WATER))return null;
            }
        }
        return null;
    }
    public static boolean eligible(World world,BlockPos gravel,int referenceY){
        if(gravel.getY()<Math.max(MIN_SEABED_Y,referenceY-MAX_DROP_BELOW_RIM)
                || world.getBlockState(gravel).getBlock()!=Blocks.GRAVEL)return false;
        Column c=column(world,gravel.getX(),gravel.getZ());
        return c!=null && c.floor().equals(gravel);
    }
    public static Result find(World world,BlockPos ravine,BlockPos playerSide,int radius,int count,
                              Runnable checkpoint,Predicate<BlockPos> allowed){
        List<Column> columns=new ArrayList<>();List<Integer> rimHeights=new ArrayList<>();
        for(int x=-radius;x<=radius;x++){
            checkpoint.run();
            for(int z=-radius;z<=radius;z++){
                if(x*x+z*z>radius*radius)continue;
                Column c=column(world,ravine.getX()+x,ravine.getZ()+z);
                if(c==null)continue;
                columns.add(c);
                if(c.floor().getY()>=MIN_SEABED_Y)rimHeights.add(c.floor().getY());
            }
        }
        if(rimHeights.isEmpty())return new Result(List.of(),MIN_SEABED_Y,columns.size(),
                (int)columns.stream().filter(c->world.getBlockState(c.floor()).getBlock()==Blocks.GRAVEL).count());
        rimHeights.sort(Integer::compare);
        int reference=rimHeights.get((rimHeights.size()-1)*3/4);
        List<BlockPos> candidates=new ArrayList<>();int deep=0;
        for(Column c:columns){
            BlockPos p=c.floor();
            if(world.getBlockState(p).getBlock()!=Blocks.GRAVEL || !allowed.test(p))continue;
            if(p.getY()<Math.max(MIN_SEABED_Y,reference-MAX_DROP_BELOW_RIM)){deep++;continue;}
            candidates.add(p);
        }
        // Both canyon sides compete by distance to the ravine, with a small preference for the player's side.
        candidates.sort(Comparator.comparingDouble((BlockPos p)->horizontal(p,ravine)+horizontal(p,playerSide)*.25)
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        return new Result(List.copyOf(candidates.subList(0,Math.min(Math.max(0,count),candidates.size()))),reference,columns.size(),deep);
    }
    private static double horizontal(BlockPos a,BlockPos b){return Math.hypot(a.getX()-b.getX(),a.getZ()-b.getZ());}
}
