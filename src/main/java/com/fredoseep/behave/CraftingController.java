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
                        player.currentScreenHandler.slots.size() > 0 &&
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
}