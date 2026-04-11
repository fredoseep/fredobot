package com.fredoseep.behave;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;

public interface IBotModule {

    // 模块的初始化（引擎启动时调用）
    void onEnable();

    // 模块的关闭与清理（引擎停止时调用，用于松开按键、清空缓存）
    void onDisable();

    // 核心 Tick 逻辑（每 0.05 秒调用一次）
    void onTick(MinecraftClient client, PlayerEntity player);

    // 模块名字（用于调试输出）
    String getName();

    // 获取执行优先级（数字越小，越先执行。比如战斗模块必须在走路模块之前判断）
    int getPriority();
    boolean isBusy();
}