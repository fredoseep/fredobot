package com.fredoseep.algorithm.planning;

import com.fredoseep.behave.MovementController;
import com.fredoseep.logging.GlobalStateLogger;
import com.fredoseep.utils.player.InventoryHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.BoatItem;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import java.util.Map;

/** Confirmed boat break/pickup shared by Fusion shore arrivals and underwater surface handoffs. */
public final class UnderwaterBoatRecovery {
    public enum Result { ACTIVE,DONE,FAILED }
    private final BoatEntity boat;
    private final BlockPos goal;
    private int lastProgressAge,quietAge=-1;
    private String phase="下船";
    public UnderwaterBoatRecovery(BoatEntity boat,BlockPos goal,int age){
        this.boat=boat;this.goal=goal;lastProgressAge=age;
        event("underwater_boat_recovery_started","DISMOUNT");
    }
    public String status(){return phase;}
    private void event(String type,String reason){
        GlobalStateLogger.logCustomEvent(type,Map.of("goal",goal.toShortString(),"reason",reason,"boat_id",boat.getEntityId()));
    }
    @FunctionalInterface
    public interface Controls { void apply(boolean dismount,ItemEntity drop); }
    public Result tick(MinecraftClient client,PlayerEntity player,MovementController movement){
        return tick(client,player,(dismount,drop)->movement.controlUnderwaterBoatRecovery(client,player,dismount,drop));
    }
    /** Same confirmed recovery lifecycle, with inputs owned by the active Fusion lease when called there. */
    public Result tick(MinecraftClient client,PlayerEntity player,Controls controls){
        if(player.age-lastProgressAge>240){event("underwater_boat_recovery_failed","TIMEOUT_"+phase);return Result.FAILED;}
        if(player.hasVehicle()){
            if(player.getVehicle()!=boat){event("underwater_boat_recovery_failed","VEHICLE_CHANGED");return Result.FAILED;}
            controls.apply(true,null);
            return Result.ACTIVE;
        }
        if("下船".equals(phase)){
            phase="回收船";lastProgressAge=player.age;
            event("underwater_boat_dismounted","VEHICLE_CONFIRMED_ABSENT");
        }
        if(boat.isAlive()){
            if(player.squaredDistanceTo(boat)>36){event("underwater_boat_recovery_failed","BOAT_OUT_OF_REACH");return Result.FAILED;}
            controls.apply(false,null);
            if(InventoryHelper.putAxeToHotbar(player))player.inventory.selectedSlot=0;
            if(player.getAttackCooldownProgress(0)>=1){
                client.interactionManager.attackEntity(player,boat);player.swingHand(Hand.MAIN_HAND);
            }
            return Result.ACTIVE;
        }
        if(quietAge<0){quietAge=player.age;lastProgressAge=player.age;}
        ItemEntity drop=InventoryHelper.findNearestDroppedItem(player,6,BoatItem.class);
        if(drop!=null){
            phase="拾取船";quietAge=player.age;
            controls.apply(false,drop);
            return Result.ACTIVE;
        }
        controls.apply(false,null);
        // A spare boat in inventory is not proof that this boat's delayed drop was collected.
        if(player.age-quietAge<20)return Result.ACTIVE;
        event("underwater_boat_recovery_complete",InventoryHelper.hasBoat(player)?"BOAT_IN_INVENTORY":"NO_DROP_AFTER_GRACE");
        return Result.DONE;
    }
}
