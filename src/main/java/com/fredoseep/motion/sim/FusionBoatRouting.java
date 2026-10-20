package com.fredoseep.motion.sim;

import com.fredoseep.algorithm.planning.BoatCrossingPreference;
import com.fredoseep.logging.GlobalStateLogger;
import java.util.Map;
import com.fredoseep.utils.player.InventoryHelper;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import java.util.ArrayList;
import java.util.List;

/** Geometric shore boundaries only: no preceding Fredobot or Fusion path search. */
public final class FusionBoatRouting {
    public record Leg(BlockPos goal,boolean boat) {}
    public static final int MAX_ENTRY_DROP=4;
    private FusionBoatRouting(){}
    public static List<Leg> split(ClientPlayerEntity player,BlockPos destination){
        if(!InventoryHelper.hasBoat(player) && !(player.getVehicle() instanceof BoatEntity))return List.of();
        double dx=destination.getX()+.5-player.getX(),dz=destination.getZ()+.5-player.getZ(),distance=Math.hypot(dx,dz);
        if(distance<8)return List.of();
        int samples=BoatCrossingPreference.samples(distance);
        int high=Math.min(253,(int)Math.ceil(Math.max(player.getY(),destination.getY()))+4);
        int low=Math.max(1,(int)Math.floor(Math.min(player.getY(),destination.getY()))-16);
        BlockPos[] water=new BlockPos[samples+1],dry=new BlockPos[samples+1];
        for(int i=0;i<=samples;i++){
            int x=(int)Math.floor(player.getX()+dx*i/samples),z=(int)Math.floor(player.getZ()+dz*i/samples);
            if(!player.world.getChunkManager().isChunkLoaded(x>>4,z>>4))continue;
            for(int y=high;y>=low;y--){
                BlockPos p=new BlockPos(x,y,z);
                var state=player.world.getBlockState(p);
                boolean clear=player.world.getBlockState(p.up()).getFluidState().isEmpty()
                        && player.world.getBlockState(p.up()).getCollisionShape(player.world,p.up()).isEmpty()
                        && player.world.getBlockState(p.up(2)).getCollisionShape(player.world,p.up(2)).isEmpty();
                if(!clear)continue;
                if(state.getFluidState().isIn(FluidTags.WATER)){water[i]=p;break;}
                if(state.isFullCube(player.world,p)){dry[i]=p.up();break;}
            }
        }
        List<Leg> legs=new ArrayList<>();
        for(int first=0;first<=samples;first++){
            if(water[first]==null)continue;
            int last=first;
            while(last+1<=samples && water[last+1]!=null && Math.abs(water[last+1].getY()-water[first].getY())<=1)last++;
            if((last-first)*distance/samples>=8){
                BlockPos entry=first>0?dry[first-1]:null;
                BlockPos exit=last<samples?dry[last+1]:null;
                // Launching now works from inside water. A two-block bank is not a reason
                // to discard the whole crossing: let Fusion reach the first water cell,
                // then let the boat controller place/board at that real water boundary.
                BlockPos bank=entry;boolean waterEntry=false;
                if(entry!=null){
                    int drop=entry.getY()-water[first].getY();
                    if(drop>1 && drop<=MAX_ENTRY_DROP){entry=water[first];waterEntry=true;}
                }
                boolean beginsInWater=first==0 && player.isTouchingWater();
                if(entry==null && beginsInWater && !(player.getVehicle() instanceof BoatEntity)
                        && player.getY()<water[first].getY()-1)entry=water[first];
                boolean deepWaterEnd=last==samples && underwaterDestination(player,destination,water[last]);
                boolean endsOnWater=last==samples && (Math.abs(destination.getY()-water[last].getY())<=1 || deepWaterEnd);
                if((entry!=null || beginsInWater) && (exit!=null || endsOnWater)
                        && (entry==null || Math.abs(entry.getY()-water[first].getY())<=1)
                        && (exit==null || Math.abs(exit.getY()-water[last].getY())<=1)){
                    if(waterEntry)GlobalStateLogger.logCustomEvent("boat_crossing_water_entry",Map.of("bank",bank.toShortString(),
                            "entry",entry.toShortString(),"crossing_blocks",(last-first)*distance/samples,"goal",destination.toShortString()));
                    if(entry!=null)legs.add(new Leg(entry,false));
                    legs.add(new Leg(water[last],true));
                    if(deepWaterEnd)GlobalStateLogger.logCustomEvent("boat_crossing_deep_goal",Map.of(
                            "surface",water[last].toShortString(),"goal",destination.toShortString(),
                            "crossing_blocks",(last-first)*distance/samples));
                    if(exit!=null)legs.add(new Leg(exit,false));
                }
            }
            first=last;
        }
        if(legs.isEmpty())return List.of();
        if(!legs.get(legs.size()-1).goal().equals(destination))legs.add(new Leg(destination,false));
        return List.copyOf(legs);
    }
    private static boolean underwaterDestination(ClientPlayerEntity player,BlockPos destination,BlockPos surface){
        if(destination.getY()>=surface.getY()-1)return false;
        // Do not send a boat above an underground dry target or through a roof.
        for(int y=destination.getY();y<=surface.getY();y++){
            BlockPos p=new BlockPos(destination.getX(),y,destination.getZ());
            var state=player.world.getBlockState(p);
            if(!state.getFluidState().isIn(FluidTags.WATER)
                    || !state.getCollisionShape(player.world,p).isEmpty())return false;
        }
        return true;
    }
}
