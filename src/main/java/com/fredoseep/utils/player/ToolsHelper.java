package com.fredoseep.utils.player;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
import net.minecraft.item.SwordItem;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class ToolsHelper {

    /**
     * 智能选择快捷栏 (0-8) 中最适合挖掘目标方块的工具。
     *
     * @param player           当前玩家实体
     * @param blockPos         准备挖掘的方块坐标
     * @param allowGoldPickaxe 是否允许把金镐子作为最优工具选出来使用
     */
    public static void equipBestTool(PlayerEntity player, BlockPos blockPos, boolean allowGoldPickaxe) {
        World world = player.getEntityWorld();
        BlockState targetState = world.getBlockState(blockPos);

        int bestSlot = -1;
        int bestTicks = Integer.MAX_VALUE;
        int emptyHotbarSlot = -1;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.inventory.main.get(i);

            if (stack.isEmpty()) {
                if (i < 9 && emptyHotbarSlot == -1) {
                    emptyHotbarSlot = i;
                }
                continue;
            }

            if (!allowGoldPickaxe && stack.getItem() == Items.GOLDEN_PICKAXE) {
                continue;
            }

            int ticks = estimateBreakTicks(player, targetState, stack, blockPos);
            if (ticks < bestTicks) {
                bestTicks = ticks;
                bestSlot = i;
            }
        }

        if (emptyHotbarSlot != -1 && estimateBreakTicks(player, targetState, ItemStack.EMPTY, blockPos) < bestTicks)
            bestSlot = emptyHotbarSlot;

        if (bestSlot != -1) {
            if (bestSlot < 9) {
                if (player.inventory.selectedSlot != bestSlot) {
                    player.inventory.selectedSlot = bestSlot;
                }
            } else {
                int targetHotbar = (emptyHotbarSlot != -1) ? emptyHotbarSlot : player.inventory.selectedSlot;
                MinecraftClient.getInstance().interactionManager.clickSlot(
                        player.currentScreenHandler.syncId,
                        bestSlot,
                        targetHotbar,
                        net.minecraft.screen.slot.SlotActionType.SWAP,
                        player
                );

                player.inventory.selectedSlot = targetHotbar;
            }
        } else {
            ItemStack currentHandStack = player.inventory.getMainHandStack();
            if (!currentHandStack.isEmpty()) {
                if (emptyHotbarSlot != -1) {
                    player.inventory.selectedSlot = emptyHotbarSlot;
                }
            }
        }
    }
    public static boolean equipPlannedTool(PlayerEntity player, BlockPos pos, Item required) {
        BlockState state = player.getEntityWorld().getBlockState(pos);
        int best = -1, bestTicks = Integer.MAX_VALUE;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.inventory.main.get(i);
            if (stack.getItem() != required) continue;
            int wear = stack.getItem() instanceof SwordItem ? 2 : 1;
            if (stack.isDamageable() && stack.getMaxDamage() - stack.getDamage() <= wear) continue;
            int ticks = estimateBreakTicks(player, state, stack, pos);
            if (ticks < bestTicks) { best = i; bestTicks = ticks; }
        }
        if (best < 0) return false;
        if (best >= 9) {
            int hotbar = player.inventory.selectedSlot;
            MinecraftClient.getInstance().interactionManager.clickSlot(player.currentScreenHandler.syncId,
                    best, hotbar, net.minecraft.screen.slot.SlotActionType.SWAP, player);
        } else player.inventory.selectedSlot = best;
        return true;
    }

    public static int estimateBreakTicks(PlayerEntity player, BlockState state, ItemStack stack, BlockPos pos) {
        float speed = stack.getMiningSpeedMultiplier(state);
        int efficiency = EnchantmentHelper.getLevel(Enchantments.EFFICIENCY, stack);
        if (speed > 1 && efficiency > 0 && !stack.isEmpty()) speed += efficiency * efficiency + 1;
        int haste = -1;
        if (player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.HASTE))
            haste = player.getStatusEffect(net.minecraft.entity.effect.StatusEffects.HASTE).getAmplifier();
        if (player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.CONDUIT_POWER))
            haste = Math.max(haste, player.getStatusEffect(net.minecraft.entity.effect.StatusEffects.CONDUIT_POWER).getAmplifier());
        if (haste >= 0) speed *= 1 + .2f * (haste + 1);
        if (player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.MINING_FATIGUE)) {
            int level = player.getStatusEffect(net.minecraft.entity.effect.StatusEffects.MINING_FATIGUE).getAmplifier();
            speed *= level == 0 ? .3f : level == 1 ? .09f : level == 2 ? .0027f : .00081f;
        }
        if (player.isSubmergedIn(net.minecraft.tag.FluidTags.WATER) && !EnchantmentHelper.hasAquaAffinity(player)) speed /= 5;
        if (!player.isOnGround()) speed /= 5;
        return com.fredoseep.algorithm.planning.MiningTime.ticks(state.getHardness(player.getEntityWorld(),pos), speed,
                !state.isToolRequired() || stack.isEffectiveOn(state));
    }

}