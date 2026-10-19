package com.fredoseep.motion.sim;

import com.fredoseep.motion.GroundRouteSearch;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Box;
import java.util.HashMap;
import java.util.Map;

/** Cached geometry hints only: every proposed edge still needs vanilla simulation. */
public final class GroundTerrain implements GroundRouteSearch.Terrain {
    private final ClientPlayerEntity player;
    private final Map<GroundRouteSearch.Cell,Boolean> standing=new HashMap<>();
    public GroundTerrain(ClientPlayerEntity player){this.player=player;}
    @Override public boolean stand(GroundRouteSearch.Cell cell){
        return standing.computeIfAbsent(cell,p->{
            try {GroundSafety.requireGoal(player,p.goal());return true;}
            catch(IllegalArgumentException unsupported){return false;}
        });
    }
    @Override public boolean walkClear(GroundRouteSearch.Cell from,GroundRouteSearch.Cell to){
        if(!stand(to))return false;
        // Swept axis-aligned volume also guards diagonal corner clipping. Conservative by design.
        Box sweep=new Box(Math.min(from.x(),to.x())+.2,from.y()+.001,Math.min(from.z(),to.z())+.2,
                Math.max(from.x(),to.x())+.8,from.y()+1.8,Math.max(from.z(),to.z())+.8);
        try {GroundSafety.requireArea(player.world,player,sweep);return player.world.doesNotCollide(player,sweep);}
        catch(IllegalArgumentException unsupported){return false;}
    }
}
