package com.fredoseep.utils;

import net.minecraft.block.BlockState;
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
        float bestSpeed = 1.0F; // 1.0 是空手或使用错误工具时的默认倍率
        int emptySlot = -1;     // 用于记录快捷栏中是否有一个空槽位

        // 遍历玩家快捷栏 (下标 0 到 8)
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.inventory.main.get(i);

            // 如果发现空手槽位，记录下它的下标作为第一备用退路
            if (stack.isEmpty()) {
                if (emptySlot == -1) {
                    emptySlot = i;
                }
                continue;
            }

            // 【新增条件】：如果参数设定不允许使用金镐子，并且当前物品正好是金镐子，直接无视它！
            if (!allowGoldPickaxe && stack.getItem() == Items.GOLDEN_PICKAXE) {
                continue;
            }

            // 调用原版底层方法获取该物品对目标方块的基础挖掘倍率
            float speed = stack.getMiningSpeedMultiplier(targetState);

            // 只有当 speed > 1.0 时，才说明这是“合适的工具”
            if (speed > 1.0F) {
                // 计算效率附魔的额外加成
                int effLevel = EnchantmentHelper.getLevel(Enchantments.EFFICIENCY, stack);
                if (effLevel > 0) {
                    speed += (float) (effLevel * effLevel + 1);
                }
            }

            // 如果当前工具的速度刷新了最高记录，更新最佳槽位
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }

        // ==========================================
        // 决策与切换逻辑
        // ==========================================
        if (bestSlot != -1) {
            // 情况 A：找到了最合适的工具
            if (player.inventory.selectedSlot != bestSlot) {
                player.inventory.selectedSlot = bestSlot;
            }
        } else {
            // 情况 B：实在找不到合适的工具（所有允许使用的工具速度都是 1.0）
            ItemStack currentHandStack = player.inventory.getMainHandStack();

            // 如果手里正拿着东西，尝试找空位
            if (!currentHandStack.isEmpty()) {
                if (emptySlot != -1) {
                    // 退路 1：有空位，切空手保护工具耐久
                    player.inventory.selectedSlot = emptySlot;
                } else {
                    // 退路 2：【没有合适工具，且 9 个格子全满了】
                    // 此时我们选择“保持 currentHandStack 不动”。
                    // 也就是机器人会直接挥舞着手里现有的那块泥土/木板去硬挖这个方块，
                    // 这就是最自然、不会引起快捷栏疯狂抽搐的“随便拿一个”。
                }
            }
        }
    }
}