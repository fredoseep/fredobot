package com.fredoseep.utils.player;

import com.fredoseep.algorithm.planning.PickupTour;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.World;

/** Cache a collection batch until entities disappear, arrive or move substantially. */
public final class DropCollectionOrder {
    private World world;
    private List<PickupTour.Point> route=List.of();
    private Map<Integer,PickupTour.Point> snapshot=Map.of();
    public void reset(){world=null;route=List.of();snapshot=Map.of();}
    public ItemEntity next(PlayerEntity player,List<ItemEntity> drops){
        Map<Integer,ItemEntity> live=new HashMap<>();Map<Integer,PickupTour.Point> points=new HashMap<>();
        for(ItemEntity item:drops)if(item.isAlive() && !item.getStack().isEmpty()){
            live.put(item.getEntityId(),item);points.put(item.getEntityId(),new PickupTour.Point(item.getEntityId(),item.getX(),item.getY(),item.getZ()));
        }
        boolean changed=world!=player.world || !snapshot.keySet().equals(points.keySet());
        if(!changed)for(var point:points.values())if(PickupTour.distance(point,snapshot.get(point.id()))>.75){changed=true;break;}
        if(changed){world=player.world;snapshot=Map.copyOf(points);route=PickupTour.order(new PickupTour.Point(-1,player.getX(),player.getY(),player.getZ()),List.copyOf(points.values()));}
        for(var point:route)if(live.containsKey(point.id()))return live.get(point.id());return null;
    }
}
