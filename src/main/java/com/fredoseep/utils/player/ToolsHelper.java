package com.fredoseep.utils.player;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
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
        float bestSpeed = 1.0F;
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

            float speed = stack.getMiningSpeedMultiplier(targetState);

            if (speed > 1.0F) {
                int effLevel = net.minecraft.enchantment.EnchantmentHelper.getLevel(net.minecraft.enchantment.Enchantments.EFFICIENCY, stack);
                if (effLevel > 0) {
                    speed += (float) (effLevel * effLevel + 1);
                }
            }

            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }

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
}