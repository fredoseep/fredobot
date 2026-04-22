package com.fredoseep.utils.prenether;

import com.fredoseep.behave.MiscController;
import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.GlobalExecutor;
import com.fredoseep.excutor.PathExecutor;
import com.fredoseep.utils.bt.BtStuff;
import com.fredoseep.utils.player.InventoryHelper;
import com.fredoseep.utils.player.MiningHelper;
import com.fredoseep.utils.player.PlayerHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.Biomes;

import java.util.HashMap;
import java.util.Map;

public class PreNether {
    private static BlockPos magmaPos = null;
    private static BlockPos gravelPos = null;

    public static void reset() {
        magmaPos = null;
        gravelPos = null;
    }

    public static void preNetherStuff(PlayerEntity player) {
        GlobalExecutor globalExecutor = BotEngine.getInstance().getModule(GlobalExecutor.class);
        PathExecutor pathExecutor = BotEngine.getInstance().getModule(PathExecutor.class);

        if (magmaPos == null) {
            magmaPos = findOceanRavine(player, MinecraftClient.getInstance().world, 100);
            System.out.println("Fredodebug: magmaPos:"+ magmaPos.toShortString());
            if (magmaPos == null) {
                globalExecutor.resetWorld();
                System.out.println("Fredodebug: reset because no enterable ravine found");
            } else {
                if (InventoryHelper.countItem(player, Items.GRAVEL) >= 4 || InventoryHelper.countItem(player, Items.FLINT) >= 1 || InventoryHelper.countItem(player, Items.FLINT_AND_STEEL) == 1) {
                    System.out.println("Fredodebug: already have lighter");
                    return;
                }
                if (MinecraftClient.getInstance().world.getBiome(magmaPos) == Biomes.WARM_OCEAN||MinecraftClient.getInstance().world.getBiome(magmaPos) == Biomes.DEEP_WARM_OCEAN||MinecraftClient.getInstance().world.getBiome(magmaPos) == Biomes.LUKEWARM_OCEAN||MinecraftClient.getInstance().world.getBiome(magmaPos) == Biomes.DEEP_LUKEWARM_OCEAN) {
                    System.out.println("Fredodebug: magmaPos is in warm ocean . get lighter now");
                    MiningHelper.mineAndCollect(player, Map.of(Blocks.GRAVEL, 4), 50);
                    return;
                }
            }
            gravelPos = MiningHelper.findNearestBlocks(BtStuff.oceanFloorNoKelp(magmaPos), Map.of(Blocks.GRAVEL, 4), 40).getFirst();
            System.out.println("Fredodebug: ocean floor gravel: "+ gravelPos.toShortString());

        }
        if (pathExecutor.isBusy() || BotEngine.getInstance().getModule(MiscController.class).isBusy()) return;
        if(!PlayerHelper.isNear(player,magmaPos.up(),1)) {
            if (InventoryHelper.countItem(player, Items.GRAVEL) >= 4 || InventoryHelper.countItem(player, Items.FLINT) >= 1 || InventoryHelper.countItem(player, Items.FLINT_AND_STEEL) == 1) {
                System.out.println("Fredodebug: already have lighter goto magma spot");
                pathExecutor.setGoal(magmaPos.up());
                return;
            } else {
                if (gravelPos != null) {
                    if (!PlayerHelper.isNear(player, gravelPos, 1)) {
                        System.out.println("Fredodebug : set goal to the gravel Pos");
                        pathExecutor.setGoal(gravelPos);
                    }
                    else {
                        MiningHelper.mineAndCollect(player, Map.of(Blocks.GRAVEL, 4), 10);
                        System.out.println("Fredobug: already at gravel spot");

                    }
                }
            }
        }
        else {
            globalExecutor.currentState = GlobalExecutor.GlobalState.NEXT;
            System.out.println("Fredodebug: go Next");
        }
    }

    /**
     * 极速扫描海底峡谷 (针对 1.16.1 Y=10 岩浆块生成规律的特化版)
     * * @param player 当前玩家
     *
     * @param world  当前世界
     * @param radius 水平扫描半径 (例如 100 代表 200x200 的正方形范围)
     * @return 距离玩家最近的海底峡谷峡谷底坐标 (Y=10 的岩浆块)，如果没找到则返回 null
     */
    public static BlockPos findOceanRavine(PlayerEntity player, World world, int radius) {
        BlockPos playerPos = player.getBlockPos();
        int px = playerPos.getX();
        int pz = playerPos.getZ();

        BlockPos closestRavine = null;
        double minDistanceSq = Double.MAX_VALUE;
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        BlockPos.Mutable probeMutable = new BlockPos.Mutable();

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {

                mutable.set(px + x, 10, pz + z);

                if (world.getBlockState(mutable).getBlock() == Blocks.MAGMA_BLOCK) {
                    boolean hasLava = false;
                    boolean isTwoByOne = false;
                    probeMutable.set(px + x, 9, pz + z);
                    BlockState stateBelow = world.getBlockState(probeMutable);
                    if (stateBelow.getBlock() == Blocks.LAVA || (!stateBelow.getFluidState().isEmpty() && stateBelow.getFluidState().getFluid() == Fluids.LAVA)) {
                        hasLava = true;
                    }
                    for (Direction offset : new Direction[]{Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH}) {
                        BlockPos offsetPos = mutable.offset(offset);
                        if (world.getBlockState(offsetPos).getBlock() == Blocks.MAGMA_BLOCK && world.getBlockState(offsetPos.down()).getBlock() == Blocks.LAVA) {
                            isTwoByOne = true;
                            break;
                        }
                    }
                    if (hasLava && isTwoByOne) {
                        double distSq = playerPos.getSquaredDistance(mutable);
                        if (distSq < minDistanceSq) {
                            minDistanceSq = distSq;
                            closestRavine = mutable.toImmutable();
                        }
                    }
                }
            }
        }

        return closestRavine;
    }
}
