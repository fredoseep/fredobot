package com.fredoseep.utils.nether.bastion;

import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.GlobalExecutor;
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
        MinecraftClient client = MinecraftClient.getInstance();
        if (!WorldLoadingState.areAllChunksLoaded()) {
            return;
        }
        if (blockEntityChestList == null) {
            blockEntityChestList = client.world.blockEntities.stream().filter(blockEntity -> blockEntity.getType() == BlockEntityType.CHEST
            ).toList();
        }
        else{
            if(bastionChestList==null){
                bastionChestList = blockEntityChestList.stream().filter(blockEntity -> {
                    World world = client.world;
                    if(blockEntity.getCachedState().get(ChestBlock.CHEST_TYPE)!=ChestType.SINGLE)return true;
                    Direction chestFacingDirection = blockEntity.getCachedState().get(ChestBlock.FACING);
                    Direction secondOffsetDirection = chestFacingDirection.rotateYCounterclockwise();
                    BlockPos testingPos1 = blockEntity.getPos().offset(chestFacingDirection,2).offset(secondOffsetDirection,3).down();
                    BlockPos testingPos2 = blockEntity.getPos().offset(chestFacingDirection,2).offset(secondOffsetDirection,3);
                    return world.getBlockState(testingPos1).getBlock() == Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS||world.getBlockState(testingPos2).getBlock()==Blocks.BLACKSTONE_STAIRS;
                }).toList();
                final BlockPos[] closetBastionChestPos = new BlockPos[1];
                final double[] currentMinDis = {Double.MAX_VALUE};
                bastionChestList.forEach(blockEntity -> {
                    double candidateDistance =  blockEntity.getPos().getSquaredDistance(client.player.getPos(),true);
                    if(candidateDistance< currentMinDis[0]){
                        closetBastionChestPos[0] = blockEntity.getPos();
                        currentMinDis[0] = candidateDistance;
                    }
                });
                System.out.println("Closest Bastion Chest is "+ closetBastionChestPos[0].toShortString());
                BotEngine.getInstance().getModule(GlobalExecutor.class).dontBother = true;
            }
        }
    }

    public static void reset() {
        blockEntityChestList = null;
        bastionChestList = null;
    }
}
