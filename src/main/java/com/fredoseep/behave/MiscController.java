package com.fredoseep.behave;

import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.GlobalExecutor;
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
        BACK_FROM_SWIMMING,
        MINE_THE_BLOCK,
        MINE_THE_BLOCK_AND_COLLECT_THE_DROP;
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
        PathExecutor pathExecutor = BotEngine.getInstance().getModule(PathExecutor.class);

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
                    pathExecutor.resume();
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
                            // 【优化】：直接调用提炼好的走向实体方法
                            walkTowardsEntity(player, droppedBoat);
                        } else {
                            System.out.println("FredoBot: 掉落的船丢失，放弃拾取！");
                            boatEntity = null;
                        }
                    }
                }
                else {
                    if (player.isTouchingWater()) {
                        float[] angles = PlayerHelper.getLookAngles(player, targetPos.getX(), targetPos.getY(), targetPos.getZ());
                        MovementController.setLookDirection(player, angles[0], angles[1]);

                        pressForward = true;
                        pressJump = true;
                        pressSprint = true;
                    } else {
                        System.out.println("FredoBot: 成功登陆目标海岸，恢复寻路！");
                        stopTask();
                        // 假设你的 PathExecutor 有 resume() 方法，没有的话删掉这行即可
                        // pathExecutor.resume();
                    }
                }
                break;
            case MINE_THE_BLOCK:
                if(pathExecutor.isBusy())break;
                if(MiningHelper.blockToMine.isEmpty()){
                    BotEngine.getInstance().getModule(MiscController.class).stopTask();
                    break;
                }
                pathExecutor.setGoal(MiningHelper.blockToMine.getFirst());
                MiningHelper.blockToMine.removeFirst();
                break;
            case MINE_THE_BLOCK_AND_COLLECT_THE_DROP:
                if (targetPos == null || client.world == null) {
                    stopTask();
                    break;
                }

                // 判断目标方块是否已经被挖掉（变成了空气/液体）
                boolean isBlockMined = client.world.getBlockState(targetPos).isAir() ||
                        client.world.getBlockState(targetPos).getMaterial().isLiquid();

                if (!isBlockMined) {
                    // --- 阶段 1：接近并挖掘 ---
                    double distSq = player.squaredDistanceTo(net.minecraft.util.math.Vec3d.ofCenter(targetPos));

                    // 如果距离超过 4 格（距离平方 > 16），需要先寻路过去
                    if (distSq > 16.0) {
                        if (!pathExecutor.isBusy()) {
                            pathExecutor.setGoal(targetPos);
                        }
                    } else {
                        // 足够接近了，停下脚步开始物理挖掘
                        if (pathExecutor.isBusy()) {
                            pathExecutor.stop();
                        }

                        // 计算最佳挖掘视角、切工具并锁定视角
                        float[] angles = MiningHelper.getValidMiningAngle(player, targetPos);
                        yaw = angles[0];
                        pitch = angles[1];
                        ToolsHelper.equipBestTool(player, targetPos, false);
                        MovementController.setLookDirection(player, yaw, pitch);

                        // 当准星对准方块时（容差 5 度），按住左键开挖！
                        if (Math.abs(MathHelper.wrapDegrees(player.yaw - yaw)) < 5.0f &&
                                Math.abs(player.pitch - pitch) < 5.0f) {
                            pressAttack = true;
                        }
                    }
                } else {
                    // --- 阶段 2：方块已碎，寻找并拾取掉落物 ---
                    pressAttack = false; // 赶紧松开左键
                    if (pathExecutor.isBusy()) {
                        pathExecutor.stop();
                    }

                    // 在目标方块原来的位置周围 4 格内扫描所有的掉落物实体
                    net.minecraft.util.math.Box searchBox = new net.minecraft.util.math.Box(targetPos).expand(4.0);
                    java.util.List<ItemEntity> drops = client.world.getEntities(ItemEntity.class, searchBox, e -> true);

                    if (!drops.isEmpty()) {
                        // 找出离玩家最近的那一个掉落物
                        ItemEntity nearestDrop = null;
                        double minDist = Double.MAX_VALUE;
                        for (ItemEntity drop : drops) {
                            double d = player.squaredDistanceTo(drop);
                            if (d < minDist) {
                                minDist = d;
                                nearestDrop = drop;
                            }
                        }

                        // 调用提炼好的公共方法走过去吃掉它！
                        if (nearestDrop != null) {
                            walkTowardsEntity(player, nearestDrop);
                        }
                    } else {
                        // 如果周围没有掉落物了，说明已经被我们捡进背包，或者掉进了岩浆被烧毁
                        System.out.println("FredoBot: 方块已挖掘并拾取完毕！");
                        stopTask();
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
    private void walkTowardsEntity(PlayerEntity player, net.minecraft.entity.Entity entity) {
        double dx = entity.getX() - player.getX();
        double dz = entity.getZ() - player.getZ();

        yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        // 视角对准目标，并稍微低头(30度)确保能看到地上的物品
        MovementController.setLookDirection(player, yaw, 30.0f);

        pressForward = true;

        // 如果前面有坎，自动跳跃
        if (player.horizontalCollision) {
            pressJump = true;
        }
    }
}