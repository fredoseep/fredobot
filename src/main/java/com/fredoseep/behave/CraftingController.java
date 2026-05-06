package com.fredoseep.behave;

import com.fredoseep.utils.player.InventoryHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;

public class CraftingController implements IBotModule {

    // 【核心修复 1】：引入动态等待状态 WAITING_FOR_RESULT
    public enum CraftState { IDLE, SEND_PACKET, WAITING_FOR_RESULT, COLLECTING, DONE, FAILED }

    private CraftState state = CraftState.IDLE;
    private int waitTicks = 0;
    private Item currentTarget = null;
    private int currentTimes = 1;

    @Override
    public String getName() {
        return "Crafting_Controller";
    }

    @Override
    public int getPriority() {
        return 45;
    }

    @Override
    public boolean isBusy() {
        return state == CraftState.SEND_PACKET || state == CraftState.WAITING_FOR_RESULT || state == CraftState.COLLECTING;
    }

    public boolean isDone() { return state == CraftState.DONE; }
    public boolean isFailed() { return state == CraftState.FAILED; }

    public void startCrafting(Item item, int times) {
        this.currentTarget = item;
        this.currentTimes = times;
        this.state = CraftState.SEND_PACKET;
        this.waitTicks = 0;
        System.out.println("FredoBot [合成模块]: 收到合成任务 -> " + item.toString() + " x " + (times == 0 ? "全部" : times));
    }

    public void resetStatus() {
        this.state = CraftState.IDLE;
        this.currentTarget = null;
        this.waitTicks = 0;
    }

    @Override
    public void onEnable() { resetStatus(); }

    @Override
    public void onDisable() { resetStatus(); }

    @Override
    public void onTick(MinecraftClient client, PlayerEntity player) {
        if (state == CraftState.IDLE || state == CraftState.DONE || state == CraftState.FAILED) return;

        if (waitTicks > 0 && state != CraftState.WAITING_FOR_RESULT) {
            waitTicks--;
            return;
        }

        switch (state) {
            case SEND_PACKET:

                if (currentTarget instanceof net.minecraft.item.BoatItem) {
                    manualCraftBoat(client, player, currentTarget);
                    waitTicks = 10; // 给服务端一点时间计算并吐出产物
                    state = CraftState.WAITING_FOR_RESULT; // 完美衔接你之前的等待之眼状态
                    break;
                }

                // 常规物品依然走配方书极速发包 (门、镐子等)
                if (InventoryHelper.requestSearchCrafting(player, currentTarget, currentTimes)) {
                    waitTicks = 40;
                    state = CraftState.WAITING_FOR_RESULT;
                } else {
                    System.out.println("FredoBot [合成模块]: 失败！材料不足或未解锁配方 -> " + currentTarget.toString());
                    state = CraftState.FAILED;
                }
                break;

            case WAITING_FOR_RESULT:
                if (player.currentScreenHandler != null &&
                        !player.currentScreenHandler.slots.isEmpty() &&
                        player.currentScreenHandler.getSlot(0).hasStack()) {

                    state = CraftState.COLLECTING;
                    waitTicks = 2;
                } else {
                    waitTicks--;
                    if (waitTicks <= 0) {
                        System.out.println("FredoBot [合成模块]: 警告！等待产物出现超时 (服务器延迟过高)，强行尝试收割 -> " + currentTarget.toString());
                        state = CraftState.COLLECTING;
                        waitTicks = 2;
                    }
                }
                break;

            case COLLECTING:
                InventoryHelper.collectCraftingResult(player);
                state = CraftState.DONE;
                break;
        }
    }
    /**
     * 专属模拟物理合成：彻底无视配方书限制，强行摆放船的 5 个格子
     */
    private void manualCraftBoat(MinecraftClient client, PlayerEntity player, Item boatType) {
        if (!(player.currentScreenHandler instanceof net.minecraft.screen.CraftingScreenHandler)) return;

        System.out.println("FredoBot [合成模块]: 侦测到船的合成请求，为规避原版配方锁，启动物理级模拟摆放...");

        // 1. 根据船反推需要的木板
        Item plankType = net.minecraft.item.Items.OAK_PLANKS;
        if (boatType == net.minecraft.item.Items.SPRUCE_BOAT) plankType = net.minecraft.item.Items.SPRUCE_PLANKS;
        else if (boatType == net.minecraft.item.Items.BIRCH_BOAT) plankType = net.minecraft.item.Items.BIRCH_PLANKS;
        else if (boatType == net.minecraft.item.Items.JUNGLE_BOAT) plankType = net.minecraft.item.Items.JUNGLE_PLANKS;
        else if (boatType == net.minecraft.item.Items.ACACIA_BOAT) plankType = net.minecraft.item.Items.ACACIA_PLANKS;
        else if (boatType == net.minecraft.item.Items.DARK_OAK_BOAT) plankType = net.minecraft.item.Items.DARK_OAK_PLANKS;

        int syncId = player.currentScreenHandler.syncId;
        int plankSlotId = -1;

        // 2. 遍历工作台 GUI 的背包区 (槽位 10 到 45) 寻找至少 5 个木板
        for (int i = 10; i < 46; i++) {
            net.minecraft.item.ItemStack stack = player.currentScreenHandler.getSlot(i).getStack();
            if (stack.getItem() == plankType && stack.getCount() >= 5) {
                plankSlotId = i;
                break;
            }
        }

        if (plankSlotId == -1) {
            System.out.println("FredoBot [合成模块]: 物理合成失败，包里木板不足！");
            return;
        }

        // 3. 执行物理级动作：左键整组拿起 -> 依次右键发牌 -> 左键放回原处
        // 拿起整组木板
        client.interactionManager.clickSlot(syncId, plankSlotId, 0, net.minecraft.screen.slot.SlotActionType.PICKUP, player);

        // 船的 U 型摆放槽位：4(左中), 6(右中), 7(左下), 8(下中), 9(右下)
        int[] boatSlots = {4, 6, 7, 8, 9};
        for (int slot : boatSlots) {
            client.interactionManager.clickSlot(syncId, slot, 1, net.minecraft.screen.slot.SlotActionType.PICKUP, player); // 1 代表鼠标右键，仅放置 1 个
        }

        // 将剩余的木板放回原来的格子
        client.interactionManager.clickSlot(syncId, plankSlotId, 0, net.minecraft.screen.slot.SlotActionType.PICKUP, player);
    }
}