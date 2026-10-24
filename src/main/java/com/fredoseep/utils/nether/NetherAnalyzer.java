package com.fredoseep.utils.nether;

import com.fredoseep.utils.nether.bastion.BastionAnalyzer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class NetherAnalyzer {


    public static void analyzeBastion() {
        System.out.println("we are here 0");
        BastionAnalyzer.analyze();
    }


    public static void reset() {
        BastionAnalyzer.reset();
    }
}
