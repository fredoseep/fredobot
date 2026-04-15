package com.fredoseep.behave;

import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.PathExecutor;
import com.fredoseep.utils.player.InventoryHelper;
import com.fredoseep.utils.player.MiningHelper;
import com.fredoseep.utils.player.PlayerHelper;
import com.fredoseep.utils.player.ToolsHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

public class MiscController implements IBotModule {

    private boolean pressForward = false;
    private boolean pressLeft = false;
    private boolean pressBack = false;
    private boolean pressRight = false;
    private boolean pressJump = false;
    private boolean pressSneak = false;
    private boolean pressAttack = false;
    private boolean pressUse = false;
    private boolean pressSprint = false;
    private float yaw;
    private float pitch;


    private BoatEntity boatEntity;
    private boolean gotOffBoat = false;

    public enum MiscType {
        MINE_BLOCK_ABOVE_HEAD,
        BACK_FROM_SWIMMING
        // 以后还可以加: EAT_FOOD, THROW_TRASH, etc.
    }

    // ==========================================
    // 核心状态管理变量
    // ==========================================
    private MiscType currentTask = null;
    private BlockPos targetPos = null;

    // ==========================================
    // 外部调用接口
    // ==========================================

    /**
     * 接收并开始一个新的杂项任务
     */
    public void startTask(MiscType type, BlockPos pos) {
        this.currentTask = type;
        this.targetPos = pos;
        System.out.println("FredoBot: 开始杂项任务 -> " + type.name());
    }

    /**
     * 判断当前是否有杂项任务正在进行中
     */
    public boolean isBusy() {
        return currentTask != null;
    }

    /**
     * 强制清空当前任务并释放按键
     */
    public void stopTask() {
        this.currentTask = null;
        this.targetPos = null;
        this.boatEntity = null;
        this.gotOffBoat = false;
        resetKeys();
    }

    // ==========================================
    // 生命周期与 Tick 循环
    // ==========================================

    @Override
    public void onEnable() {
        stopTask();
    }

    @Override
    public void onDisable() {
        stopTask();
    }

    @Override
    public String getName() {
        return "Misc_Controller";
    }

    @Override
    public int getPriority() {
        return 1; // 极高优先级，确保它能第一时间抢占控制权
    }

    @Override
    public void onTick(MinecraftClient client, PlayerEntity player) {
        // 如果当前没有任务，直接返回，不干扰其他控制器的按键
        if (currentTask == null) {
            return;
        }

        // 默认每帧重置攻击键，避免死锁

        switch (currentTask) {
            case MINE_BLOCK_ABOVE_HEAD:
                if (targetPos == null || client.world == null) {
                    stopTask();
                    break;
                }

                // 1. 检查完成条件：如果头顶的方块已经被挖成空气了，任务结束！
                if (client.world.getBlockState(targetPos).isAir()) {
                    System.out.println("FredoBot: 头顶方块清理完毕！");
                    stopTask();
                    BotEngine.getInstance().getModule(PathExecutor.class).resume();
                    return; // 结束这一帧
                }

                // 2. 持续执行动作：算视角、切工具、转头
                prepareMineBlockAboveHead(player, targetPos);

                // 3. 只有当准星精确对准了目标方块，才按下左键！(容差 2.0 度)
                if (Math.abs(MathHelper.wrapDegrees(player.yaw - yaw)) < 2.0f &&
                        Math.abs(player.pitch - pitch) < 2.0f) {
                    pressAttack = true;
                }
                break;
            case BACK_FROM_SWIMMING:
                if (PlayerHelper.isDrivingBoat(player)) {
                    boatEntity = (BoatEntity) player.getVehicle();
                    pressSneak = true;
                    InventoryHelper.putAxeToHotbar(player);
                    player.inventory.selectedSlot = 0;
                }
                else if (boatEntity != null && boatEntity.isAlive()) {
                    pressSneak = false;
                    pressForward = false;
                    float coolDown = player.getAttackCooldownProgress(0.0F);
                    if (coolDown >= 1.0F) {
                        MinecraftClient.getInstance().interactionManager.attackEntity(player, boatEntity);
                    }
                }
                else if (boatEntity != null && !boatEntity.isAlive()) {
                    pressAttack = false;
                    if (InventoryHelper.hasBoat(player)) {
                        System.out.println("FredoBot: 船只回收完毕，准备游向岸边！");
                        boatEntity = null;
                    } else {
                        ItemEntity droppedBoat = InventoryHelper.findNearestDroppedItem(player, 8.0, net.minecraft.item.BoatItem.class);
                        if (droppedBoat != null) {
                            double dx = droppedBoat.getX() - player.getX();
                            double dz = droppedBoat.getZ() - player.getZ();
                            yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                            MovementController.setLookDirection(player, yaw, 30.0f);
                            pressForward = true;
                            if (player.horizontalCollision) {
                                pressJump = true;
                            }
                        } else {
                            System.out.println("FredoBot: 掉落的船丢失，放弃拾取！");
                            boatEntity = null;
                        }
                    }
                }
                else {
                    if (player.isTouchingWater()) {
                        // 1. 还在水里 -> 看向目标节点，奋力游泳
                        float[] angles = PlayerHelper.getLookAngles(player, targetPos.getX(), targetPos.getY(), targetPos.getZ());
                        MovementController.setLookDirection(player, angles[0], angles[1]);

                        pressForward = true;
                        pressJump = true; // 保持浮在水面
                        pressSprint = true; // 【极其关键】在水里不按疾跑，速度会慢得像蜗牛
                    } else {
                        // 2. 脚踩实地 -> 完美收工！
                        System.out.println("FredoBot: 成功登陆目标海岸，恢复寻路！");
                        stopTask();
                        BotEngine.getInstance().getModule(PathExecutor.class).resume();
                    }
                }
                break;
            // case EAT_FOOD: ...
        }

        // 只有在有任务执行的时候，才真正应用按键
        applyKeys(client);
    }

    // ==========================================
    // 内部动作方法
    // ==========================================

    public void prepareMineBlockAboveHead(PlayerEntity player, BlockPos extraPos) {
        float[] angles = MiningHelper.getValidMiningAngle(player, extraPos);
        yaw = angles[0];
        pitch = angles[1];
        ToolsHelper.equipBestTool(player, extraPos, false);
        MovementController.setLookDirection(player, yaw, pitch);
    }

    private void applyKeys(MinecraftClient client) {
        if (client.options == null) return;
        KeyBinding.setKeyPressed(client.options.keyBack.getDefaultKey(), pressBack);
        KeyBinding.setKeyPressed(client.options.keyRight.getDefaultKey(), pressRight);
        KeyBinding.setKeyPressed(client.options.keyLeft.getDefaultKey(), pressLeft);
        KeyBinding.setKeyPressed(client.options.keyForward.getDefaultKey(), pressForward);
        KeyBinding.setKeyPressed(client.options.keyJump.getDefaultKey(), pressJump);
        KeyBinding.setKeyPressed(client.options.keyUse.getDefaultKey(), pressUse);
        KeyBinding.setKeyPressed(client.options.keySneak.getDefaultKey(), pressSneak);
        KeyBinding.setKeyPressed(client.options.keyAttack.getDefaultKey(), pressAttack);
        KeyBinding.setKeyPressed(client.options.keySprint.getDefaultKey(), pressSprint);
    }

    public void resetKeys() {
        this.pressBack = false;
        this.pressRight = false;
        this.pressLeft = false;
        this.pressForward = false;
        this.pressJump = false;
        this.pressUse = false;
        this.pressSneak = false;
        this.pressAttack = false;
        this.pressSprint = false;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.options != null) {
            KeyBinding.setKeyPressed(client.options.keyBack.getDefaultKey(), false);
            KeyBinding.setKeyPressed(client.options.keyRight.getDefaultKey(), false);
            KeyBinding.setKeyPressed(client.options.keyLeft.getDefaultKey(), false);
            KeyBinding.setKeyPressed(client.options.keyForward.getDefaultKey(), false);
            KeyBinding.setKeyPressed(client.options.keyJump.getDefaultKey(), false);
            KeyBinding.setKeyPressed(client.options.keyUse.getDefaultKey(), false);
            KeyBinding.setKeyPressed(client.options.keySneak.getDefaultKey(), false);
            KeyBinding.setKeyPressed(client.options.keyAttack.getDefaultKey(), false);
            KeyBinding.setKeyPressed(client.options.keySprint.getDefaultKey(), false);
        }
    }
}