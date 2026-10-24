package com.fredoseep.utils.nether;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class NetherAnalyzer {
   private static World world;
   private static BlockPos playerEnterPos;

    public static void analyze(MinecraftClient client, PlayerEntity player) {
        initialize(client,player);
    }

    private static void initialize(MinecraftClient client, PlayerEntity player) {
    world = client.world;
    playerEnterPos = player.getBlockPos();
    }
}
