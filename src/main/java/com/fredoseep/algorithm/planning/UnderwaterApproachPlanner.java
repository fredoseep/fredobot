package com.fredoseep.algorithm.planning;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import com.fredoseep.logging.GlobalStateLogger;

/** Loaded-terrain checks on the client, without a preliminary route search. */
public final class UnderwaterApproachPlanner {
    private UnderwaterApproachPlanner(){}
    public static UnderwaterApproach.Plan plan(MinecraftClient client,BlockPos goal,Set<BlockPos> blacklist){
        PlayerEntity player=client.player;World world=client.world;
        if(player==null || world==null)return null;
        Map<BlockPos,ColumnInspection> columns=new HashMap<>();
        var result=UnderwaterApproach.plan(player.getPos(),goal,player.isSubmergedIn(FluidTags.WATER),
                p->columns.computeIfAbsent(p.toImmutable(),key->inspect(world,key)).top(),blacklist::contains,
                (a,b)->descentClear(world,a,b,blacklist));
        ColumnInspection checked=columns.get(goal);
        if(result==null && checked!=null && checked.water()){
            double distanceSq=player.getPos().squaredDistanceTo(new Vec3d(goal.getX()+.5,goal.getY(),goal.getZ()+.5));
            boolean local=distanceSq<=UnderwaterApproach.LOCAL_UNDERWATER_RADIUS*UnderwaterApproach.LOCAL_UNDERWATER_RADIUS
                    && (player.isSubmergedIn(FluidTags.WATER) || checked.top()!=null && player.getY()<checked.top()-2);
            if(!local && (checked.top()==null || checked.top()-goal.getY()>=UnderwaterApproach.MIN_DEPTH)){
                GlobalStateLogger.logCustomEvent("underwater_approach_skipped",Map.of("goal",goal.toShortString(),
                        "reason",checked.top()==null?checked.reason():"NO_CLEAR_DESCENT_CORRIDOR",
                        "obstacle",checked.obstacle()==null?"NONE":checked.obstacle().toShortString(),
                        "block",checked.obstacle()==null?"NONE":world.getBlockState(checked.obstacle()).toString()));
            }
        }
        return result;
    }
    private record ColumnInspection(Integer top,String reason,BlockPos obstacle,boolean water){}
    private static boolean loaded(World world,BlockPos p){
        return world.getChunkManager().isChunkLoaded(p.getX()>>4,p.getZ()>>4);
    }
    /** Allow a few buried chest/cover blocks, then require a continuous open water column to open sky/air. */
    private static ColumnInspection inspect(World world,BlockPos goal){
        if(!loaded(world,goal))return new ColumnInspection(null,"UNLOADED_COLUMN",goal,false);
        int first=-1;
        for(int y=Math.max(0,goal.getY());y<=Math.min(253,goal.getY()+4);y++){
            BlockPos p=new BlockPos(goal.getX(),y,goal.getZ());var state=world.getBlockState(p);
            if(state.getFluidState().isIn(FluidTags.WATER) && state.getCollisionShape(world,p).isEmpty()){first=y;break;}
        }
        if(first<0)return new ColumnInspection(null,"NO_OPEN_WATER_NEAR_GOAL",goal,false);
        for(int y=first;y<=253;y++){
            BlockPos p=new BlockPos(goal.getX(),y,goal.getZ());var state=world.getBlockState(p);
            if(state.getFluidState().isIn(FluidTags.WATER) && state.getCollisionShape(world,p).isEmpty())continue;
            if(!state.getFluidState().isEmpty() || !state.getCollisionShape(world,p).isEmpty())
                return new ColumnInspection(null,"WATER_COLUMN_BLOCKED",p,true);
            BlockPos head=p.up();var above=world.getBlockState(head);
            if(!above.getFluidState().isEmpty() || !above.getCollisionShape(world,head).isEmpty())
                return new ColumnInspection(null,"SURFACE_HEADROOM_BLOCKED",head,true);
            return new ColumnInspection(y-1,"OPEN_SURFACE",null,true);
        }
        return new ColumnInspection(null,"WORLD_HEIGHT_LIMIT",goal,true);
    }
    private static boolean descentClear(World world,BlockPos surface,BlockPos goal,Set<BlockPos> blacklist){
        // Check the full standing body conservatively; the actual second-leg search handles final mining/contact.
        double endY=goal.getY()+2;
        double depth=surface.getY()-endY;
        int samples=Math.max(1,(int)Math.ceil(depth*2));
        for(int i=0;i<=samples;i++){
            double t=(double)i/samples;
            double x=surface.getX()+.5+(goal.getX()-surface.getX())*t;
            double z=surface.getZ()+.5+(goal.getZ()-surface.getZ())*t;
            double y=surface.getY()+(endY-surface.getY())*t;
            Box body=new Box(x-.3,y,z-.3,x+.3,y+1.8,z+.3);
            for(BlockPos p:BlockPos.iterate(new BlockPos(body.minX,body.minY,body.minZ),new BlockPos(body.maxX,body.maxY,body.maxZ))){
                if(!loaded(world,p) || blacklist.contains(p))return false;
                var state=world.getBlockState(p);
                if(state.getFluidState().isIn(FluidTags.LAVA))return false;
                var shape=state.getCollisionShape(world,p);
                if(!shape.isEmpty() && shape.getBoundingBox().offset(p.getX(),p.getY(),p.getZ()).intersects(body))return false;
            }
        }
        return true;
    }
}
