package com.fredoseep.motion.sim;

import com.fredoseep.utils.player.InventoryHelper;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Same inventory/material policy for goal admission and simulated placement. */
public final class FusionMaterials {
    public record Choice(Item item,BlockState state,int count,int price,boolean gravity) {}
    private FusionMaterials(){}
    public static List<Choice> choices(ClientPlayerEntity player,World world,BlockPos pos){
        var counts=InventoryHelper.countAvailableBuildingBlocks(player);
        if(counts.pillaringBlocks<=0)return List.of();
        boolean solidBelow=Block.isShapeFullCube(world.getBlockState(pos.down()).getCollisionShape(world,pos.down()));
        List<Choice> result=new ArrayList<>();
        for(var material:InventoryHelper.PlaceableBlock.values()){
            if(material.getCost()==Integer.MAX_VALUE || !(material.getItem() instanceof BlockItem block))continue;
            if(material.isGravity()? !solidBelow : counts.bridgingBlocks<=0)continue;
            BlockState state=block.getBlock().getDefaultState();
            if(!state.getEntries().isEmpty() || !Block.isShapeFullCube(state.getCollisionShape(world,pos)))continue;
            int count=0;for(ItemStack stack:player.inventory.main)if(stack.getItem()==material.getItem())count+=stack.getCount();
            if(count>0)result.add(new Choice(material.getItem(),state,count,material.getCost(),material.isGravity()));
        }
        return List.copyOf(result);
    }
}
