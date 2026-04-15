package com.fredoseep.utils.bt;

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

public class BtStuff {
    public static boolean hasTNT = false;
    private static int MIN_IRON_NEEDED = 7;
    private static int ironIngotCount = 0;
    private static int goldIngotCount = 0;
    private static int diamondCount = 0;

    public static final List<Item> itemsToCraft= new ArrayList<>();

    public static BlockPos calTNTSetupPos(PlayerEntity player) {
        World world = player.world;
        BlockPos playerPos = player.getBlockPos();
        int pX = playerPos.getX();
        int pY = playerPos.getY();
        int pZ = playerPos.getZ();

        int maxY = Math.min(255, pY + 40);
        int minY = Math.max(0, pY - 15);

        for (int r = 0; r <= 200; r++) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (Math.abs(x) != r && Math.abs(z) != r) continue;

                    int worldX = pX + x;
                    int worldZ = pZ + z;

                    if (!world.getChunkManager().isChunkLoaded(worldX >> 4, worldZ >> 4)) {
                        continue;
                    }

                    boolean foundLog = false;

                    for (int y = maxY; y >= minY; y--) {
                        BlockPos checkPos = new BlockPos(worldX, y, worldZ);
                        BlockState state = world.getBlockState(checkPos);

                        if (state.isIn(BlockTags.LOGS)) {
                            foundLog = true;
                        }
                        else if (foundLog) {
                            if (state.getMaterial().isSolid() && !state.isIn(BlockTags.LEAVES)) {
                                int groundY = y;

                                BlockPos[] prioritizedCandidates = {
                                        new BlockPos(worldX, groundY + 1, worldZ),
                                        new BlockPos(worldX, groundY + 2, worldZ),
                                        new BlockPos(worldX, groundY + 3, worldZ)
                                };

                                for (int i = 0; i < prioritizedCandidates.length; i++) {
                                    BlockPos candidate = prioritizedCandidates[i];
                                    BlockState candidateState = world.getBlockState(candidate);

                                    // 1. 防哑炮/销毁保护：过滤水和岩浆
                                    if (candidateState.getMaterial().isLiquid()) {
                                        continue;
                                    }

                                    // ==========================================
                                    // 【新增：开局无工具防卡死保护】
                                    // 目标位置必须是空气、树叶，或者是可以被瞬间破坏/替换的植物！
                                    // getHardness == 0.0f 完美涵盖了小花、蘑菇、红石线等瞬间掉落的方块。
                                    // ==========================================
                                    boolean canPlaceWithoutTools =
                                            candidateState.isAir() ||
                                                    candidateState.isIn(BlockTags.LEAVES) ||
                                                    candidateState.getMaterial().isReplaceable() ||
                                                    candidateState.getHardness(world, candidate) == 0.0f;

                                    // 如果点位被原木、石头、泥土等占据，徒手挖太慢，直接放弃当前高度！
                                    if (!canPlaceWithoutTools) {
                                        continue;
                                    }

                                    // 只有满足秒放/秒挖条件，才进行产出评估
                                    int yield = evaluateTNTYield(world, candidate);

                                    if (yield >= 10) {
                                        System.out.printf("FredoBot: 锁定无障碍 TNT 点位 -> %s (高度顺位: %d, 预计产出: %d)\n",
                                                candidate.toShortString(), i, yield);
                                        return candidate;
                                    }
                                }
                                break;
                            }
                        }
                    }
                }
            }
        }
        return new BlockPos(-1, -1, -1);
    }

    /**
     * 辅助方法：模拟评估某个坐标点爆炸能摧毁多少木头
     */
    private static int evaluateTNTYield(World world, BlockPos tntPos) {
        int count = 0;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (dx * dx + dy * dy + dz * dz <= 11) {
                        BlockPos pos = tntPos.add(dx, dy, dz);
                        if (world.getBlockState(pos).isIn(BlockTags.LOGS)) {
                            count++;
                        }
                    }
                }
            }
        }
        return count;
    }


    public static boolean evaluateBt(PlayerEntity player) {
        for (int slot = 0; slot < 36 || slot == 40; slot++) {
            if (player.inventory.getStack(slot).getItem() == Items.IRON_INGOT) {
                ironIngotCount = player.inventory.getStack(slot).getCount();
            } else if (player.inventory.getStack(slot).getItem() == Items.GOLD_INGOT) {
                goldIngotCount = player.inventory.getStack(slot).getCount();
            } else if (player.inventory.getStack(slot).getItem() == Items.DIAMOND) {
                diamondCount = player.inventory.getStack(slot).getCount();
            }
            else if(player.inventory.getStack(slot).getItem()==Items.TNT){
                hasTNT = true;
            }
            if (ironIngotCount != 0 && goldIngotCount != 0 && diamondCount != 0) break;
            if (slot == 35) slot = 40;
        }
        if(diamondCount>=3){
            MIN_IRON_NEEDED = 4;
        }
        if(ironIngotCount<MIN_IRON_NEEDED)return false;
        int currentIronCount = ironIngotCount;
        int currentGoldCount = goldIngotCount;
        boolean pickaxeCrafted = false;
        boolean axeCrafted = false;
        boolean shovelCrafted = false;

        int currentDiamondCount = diamondCount;
        if(currentDiamondCount>=3){
            itemsToCraft.add(Items.DIAMOND_PICKAXE);
            pickaxeCrafted =true;
            currentDiamondCount-=3;
        }
        if(!pickaxeCrafted){
            itemsToCraft.add(Items.IRON_PICKAXE);
            pickaxeCrafted = true;
            currentIronCount-=3;
        }
        itemsToCraft.add(Items.BUCKET);
        currentIronCount-=3;
        if(currentDiamondCount==3){
            itemsToCraft.add(Items.DIAMOND_AXE);
            axeCrafted =true;
            currentDiamondCount-=3;
        }
        if(currentDiamondCount==2){
            itemsToCraft.add(Items.DIAMOND_SWORD);
            currentDiamondCount-=2;
        }
        if(currentDiamondCount==1){
            itemsToCraft.add(Items.DIAMOND_SHOVEL);
            shovelCrafted = true;
            currentDiamondCount--;
        }
        if(!axeCrafted&&currentIronCount>=4){
            itemsToCraft.add(Items.IRON_AXE);
            axeCrafted = true;
            currentIronCount-=3;
        }
        if(!shovelCrafted&&currentIronCount>=2){
            itemsToCraft.add(Items.IRON_SHOVEL);
            shovelCrafted = true;
            currentIronCount--;
        }
        if(!axeCrafted&&currentGoldCount>=3){
            itemsToCraft.add(Items.GOLDEN_AXE);
            axeCrafted = true;
            currentGoldCount-=3;
        }
        if(!shovelCrafted&&currentGoldCount>=1){
            itemsToCraft.add(Items.GOLDEN_SHOVEL);
            shovelCrafted = true;
            currentGoldCount--;
        }
        if(!axeCrafted){
            itemsToCraft.add(Items.STONE_AXE);
            axeCrafted = true;
        }
        if(!shovelCrafted){
            itemsToCraft.add(Items.STONE_SHOVEL);
            shovelCrafted = true;
        }
        if(hasTNT){
            if(currentIronCount>=3){
                itemsToCraft.add(Items.HEAVY_WEIGHTED_PRESSURE_PLATE);
                currentIronCount-=2;
            }
            else if(currentGoldCount>=2){
                itemsToCraft.add(Items.LIGHT_WEIGHTED_PRESSURE_PLATE);
                currentGoldCount-=2;
            }
            else{
                itemsToCraft.add(Items.STONE_BUTTON);
            }
        }
        else{
            if(currentIronCount>=3){
                itemsToCraft.add(Items.SHEARS);
                currentIronCount-=2;
            }
        }
        return true;
    }

    public static void reset() {
        hasTNT = false;
        ironIngotCount = 0;
        goldIngotCount = 0;
        diamondCount = 0;
        MIN_IRON_NEEDED = 7;
        itemsToCraft.clear();
    }

}
