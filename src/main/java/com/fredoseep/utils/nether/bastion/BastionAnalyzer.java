package com.fredoseep.utils.nether.bastion;

import com.fredoseep.utils.render.WorldLoadingState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BastionAnalyzer {
    private static List<BlockEntity> blockEntityChestList;
    private static List<BlockEntity> bastionChestList;

    public static void analyze() {
        initialize();
    }

    private static void initialize() {
        System.out.println("we are here -1");
        MinecraftClient client = MinecraftClient.getInstance();
        if (!WorldLoadingState.areAllChunksLoaded()) {
            System.out.println("Waiting for the chunks loaded...");
            return;
        }
        System.out.println("we are here 1");
        if (blockEntityChestList == null) {
            System.out.println("we are here 2");
            blockEntityChestList = client.world.blockEntities.stream().filter(blockEntity -> blockEntity.getType() == BlockEntityType.CHEST
            ).toList();
        }
        else{
            System.out.println("we are here 3");
            if(bastionChestList==null){
                System.out.println("we are here 4");
                bastionChestList = blockEntityChestList.stream().filter(blockEntity -> {
                    World world = client.world;
                    if(blockEntity.getCachedState().get(ChestBlock.CHEST_TYPE)!=ChestType.SINGLE)return true;
                    Direction chestFacingDirection = blockEntity.getCachedState().get(ChestBlock.FACING);
                    Direction secondOffsetDirection = chestFacingDirection.rotateYCounterclockwise();
                    BlockPos testingPos = blockEntity.getPos().offset(chestFacingDirection,2).offset(secondOffsetDirection,3).down();
                    return world.getBlockState(testingPos).getBlock() == Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS;
                }).toList();
                bastionChestList.forEach(blockEntity -> {
                    System.out.println("candidate: "+ blockEntity.getPos());
                });
            }
        }
    }

    public static void reset() {
        blockEntityChestList = null;
    }
}
