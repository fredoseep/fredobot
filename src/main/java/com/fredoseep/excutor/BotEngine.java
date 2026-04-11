package com.fredoseep.excutor;

import com.fredoseep.behave.IBotModule;
import com.fredoseep.behave.MiscController;
import com.fredoseep.behave.MovementController;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class BotEngine {

    // 单例模式，保证全局只有一个大脑
    private static final BotEngine INSTANCE = new BotEngine();

    public static BotEngine getInstance() {
        return INSTANCE;
    }

    private final List<IBotModule> modules = new ArrayList<>();
    private boolean isRunning = false;

    private BotEngine() {
        // 在这里注册你所有的 Controller
        // 注意：这里只是注册，它们会根据 getPriority() 自动排序
        registerModule(new PathExecutor());    // 也就是你之前的 PathExecutor
        registerModule(new MovementController());    // 底层移动模块
        registerModule(new MiscController());
        // 按照优先级排序 (Priority 越小越排在前面)
        modules.sort(Comparator.comparingInt(IBotModule::getPriority));

        // 挂载到 Fabric 的客户端 Tick 事件上
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    public boolean highPriorityIsBusy(int myPriority) {
        for (IBotModule iBotModule : this.modules) {
            if (iBotModule.getName().equals("Path_Executor")) continue;
            if (iBotModule.getPriority() < myPriority && iBotModule.isBusy()) return true;
        }
        return false;
    }

    private void registerModule(IBotModule module) {
        modules.add(module);
    }

    // 通过类名获取特定模块，方便模块之间互相通信！
    public <T extends IBotModule> T getModule(Class<T> clazz) {
        for (IBotModule module : modules) {
            if (clazz.isInstance(module)) {
                return clazz.cast(module);
            }
        }
        return null;
    }

    public void start() {
        if (isRunning) return;
        isRunning = true;
        for (IBotModule module : modules) {
            module.onEnable();
        }
    }

    public void stop() {
        if (!isRunning) return;
        isRunning = false;
        for (IBotModule module : modules) {
            module.onDisable();
        }
    }

    // 每 0.05 秒被 Fabric 自动调用
    private void onClientTick(MinecraftClient client) {
        if (!isRunning || client.player == null || client.world == null) return;

        // 严格按照优先级，让每个模块执行自己的逻辑
        for (IBotModule module : modules) {
            module.onTick(client, client.player);
        }
    }
}