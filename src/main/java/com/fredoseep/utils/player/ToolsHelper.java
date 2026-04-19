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
        float bestSpeed = 1.0F; // 1.0 是空手或使用错误工具时的默认倍率
        int emptyHotbarSlot = -1; // 仅记录【快捷栏】中的空位，用于退路或交换目标

        // 【核心修复】：将扫描范围从 9 扩大到 36！(0-8 是快捷栏，9-35 是主背包)
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.inventory.main.get(i);

            if (stack.isEmpty()) {
                // 只有快捷栏的空位才有意义，记下来备用
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
                bestSlot = i; // 可能是 0-8，也可能是 9-35
            }
        }

        // ==========================================
        // 决策与切换逻辑 (带自动发包拔取功能)
        // ==========================================
        if (bestSlot != -1) {
            // 情况 A：找到了最合适的工具
            if (bestSlot < 9) {
                // A1: 工具本来就在快捷栏，直接切过去就行
                if (player.inventory.selectedSlot != bestSlot) {
                    player.inventory.selectedSlot = bestSlot;
                }
            } else {
                // A2: 【神级修复】工具藏在背包深处 (9-35)！
                // 决定把它换到哪个快捷栏格子里：如果有空位就放空位，没空位就顶替当前手里拿的格子
                int targetHotbar = (emptyHotbarSlot != -1) ? emptyHotbarSlot : player.inventory.selectedSlot;

                // 向服务器发包，瞬间将背包里的神器与快捷栏的物品交换 (SWAP)
                MinecraftClient.getInstance().interactionManager.clickSlot(
                        player.currentScreenHandler.syncId,
                        bestSlot,
                        targetHotbar,
                        net.minecraft.screen.slot.SlotActionType.SWAP,
                        player
                );

                // 然后强行选中刚才换过来的那个快捷栏格子
                player.inventory.selectedSlot = targetHotbar;
            }
        } else {
            // 情况 B：全包扫遍了也找不到合适的工具
            ItemStack currentHandStack = player.inventory.getMainHandStack();
            if (!currentHandStack.isEmpty()) {
                if (emptyHotbarSlot != -1) {
                    player.inventory.selectedSlot = emptyHotbarSlot;
                }
                // 没空位就保持手里现有的东西硬挖
            }
        }
    }
}