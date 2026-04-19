package com.fredoseep.behave;

import com.fredoseep.utils.player.InventoryHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;

public class CraftingController implements IBotModule {

    public enum CraftState { IDLE, SEND_PACKET, WAITING, COLLECTING, DONE, FAILED }

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
        return 45; // 优先级排在寻路和战斗之间
    }

    @Override
    public boolean isBusy() {
        // 只要不是空闲、完成或失败状态，就说明正在合成中
        return state == CraftState.SEND_PACKET || state == CraftState.WAITING || state == CraftState.COLLECTING;
    }

    public boolean isDone() { return state == CraftState.DONE; }
    public boolean isFailed() { return state == CraftState.FAILED; }

    /**
     * 外部调用的唯一入口
     */
    public void startCrafting(Item item, int times) {
        this.currentTarget = item;
        this.currentTimes = times;
        this.state = CraftState.SEND_PACKET;
        this.waitTicks = 0;
        System.out.println("FredoBot [合成模块]: 收到合成任务 -> " + item.toString() + " x " + (times == 0 ? "全部" : times));
    }

    /**
     * 外部在收到 Done 或 Failed 后，必须调用此方法重置模块
     */
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

        // 处理底层时序延迟
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }

        switch (state) {
            case SEND_PACKET:
                // 发送配方霰弹枪包
                if (InventoryHelper.requestSearchCrafting(player, currentTarget, currentTimes)) {
                    waitTicks = 2; // 强制给服务器 2 Tick 时间摆放材料
                    state = CraftState.COLLECTING;
                } else {
                    System.out.println("FredoBot [合成模块]: 失败！材料不足或未解锁配方 -> " + currentTarget.toString());
                    state = CraftState.FAILED;
                }
                break;

            case COLLECTING:
                // 收割产物
                InventoryHelper.collectCraftingResult(player);
                state = CraftState.DONE;
                break;
        }
    }
}