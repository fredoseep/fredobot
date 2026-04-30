package com.fredoseep;


import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class Test {
    public static void testBlockPlace(){
        BlockPos hitPos = new BlockPos(0,5,0);
        BlockHitResult blockHitResult = new BlockHitResult(Vec3d.ofCenter(hitPos), Direction.NORTH,hitPos,false);
        System.out.println("Fredobotdebug: hitResult: " + MinecraftClient.getInstance().interactionManager.interactBlock(MinecraftClient.getInstance().player, MinecraftClient.getInstance().world, Hand.MAIN_HAND,blockHitResult));
    }
}
