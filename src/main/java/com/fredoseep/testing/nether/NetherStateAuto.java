package com.fredoseep.testing.nether;

import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.GlobalExecutor;
import com.fredoseep.testing.utils.CommandUtils;
import com.fredoseep.utils.player.InventoryHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;

public class NetherStateAuto {
    private static final Map<Item,Integer> itemsToAsk = Map.of(Items.IRON_AXE,1,Items.IRON_PICKAXE,1,Items.IRON_SHOVEL,1,Items.BUCKET,1,Items.COOKED_SALMON,64,Items.DIRT,128,Items.ACACIA_BOAT,1,Items.FLINT_AND_STEEL,1,Items.ACACIA_PLANKS,30);
    public static void autoNether(){
        CommandUtils.enableCheats();
        CommandUtils.getItems(itemsToAsk);
        InventoryHelper.inventoryManagement();
        BotEngine.getInstance().getModule(GlobalExecutor.class).isAutoNetherFinished = true;
    }

}
