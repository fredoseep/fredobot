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
import net.minecraft.entity.Entity;
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
    private int pickupCoolDownTick = 0;
    private int pathingFailTicks = 0;
    private boolean gotOffBoat = false;

    public enum MiscType {
        MINE_BLOCK_ABOVE_HEAD,
        BACK_FROM_SWIMMING,
        MINE_THE_BLOCK,
        MINE_THE_BLOCK_AND_COLLECT_THE_DROP;
    }

    private MiscType currentTask = null;
    private BlockPos targetPos = null;
    public Entity targetEntity = null;

    public void startTask(MiscType type, BlockPos pos) {
        this.currentTask = type;
        this.targetPos = pos;
        System.out.println("FredoBot: 开始杂项任务 -> " + type.name());
    }

    public boolean isBusy() {
        return currentTask != null;
    }

    public void stopTask() {
        this.currentTask = null;
        this.targetPos = null;
        this.boatEntity = null;
        this.gotOffBoat = false;
        pickupCoolDownTick = 0;
        targetEntity = null;
        pathingFailTicks = 0;
        resetKeys();
    }

    @Override
    public void onEnable() { stopTask(); }

    @Override
    public void onDisable() { stopTask(); }

    @Override
    public String getName() { return "Misc_Controller"; }

    @Override
    public int getPriority() { return 50; }

    @Override
    public void onTick(MinecraftClient client, PlayerEntity player) {
        if (currentTask == null) return;

        PathExecutor pathExecutor = BotEngine.getInstance().getModule(PathExecutor.class);

        switch (currentTask) {
            case MINE_BLOCK_ABOVE_HEAD:
                if (targetPos == null || client.world == null) {
                    stopTask();
                    break;
                }

                if (client.world.getBlockState(targetPos).isAir()) {
                    System.out.println("FredoBot: 头顶方块清理完毕！");
                    stopTask();
                    pathExecutor.resume();
                    return;
                }

                prepareMineBlockAboveHead(player, targetPos);

                // 【已修改】：直接发包挖掘头顶方块，无视准星精准度
                client.interactionManager.updateBlockBreakingProgress(targetPos, net.minecraft.util.math.Direction.DOWN);
                if (!player.handSwinging) {
                    player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
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
                        pathExecutor.resumeSuspendedGoal();
                        boatEntity = null;
                    } else {
                        ItemEntity droppedBoat = InventoryHelper.findNearestDroppedItem(player, 8.0, net.minecraft.item.BoatItem.class);
                        if (droppedBoat != null) {
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
                        pathExecutor.resume();
                    }
                }
                break;

            case MINE_THE_BLOCK:
                if(pathExecutor.isBusy()) break;
                if(MiningHelper.blockToMine.isEmpty()){
                    stopTask();
                    break;
                }
                pathExecutor.setGoal(MiningHelper.blockToMine.get(0));
                MiningHelper.blockToMine.remove(0);
                break;

            case MINE_THE_BLOCK_AND_COLLECT_THE_DROP:
                if (targetPos == null || client.world == null) {
                    stopTask();
                    break;
                }

                boolean isBlockMined = client.world.getBlockState(targetPos).isAir() ||
                        client.world.getBlockState(targetPos).getMaterial().isLiquid();

                if (!isBlockMined) {
                    // 1. 【挖掘阶段】：修复距离判断，使用眼睛坐标（上半身）
                    net.minecraft.util.math.Vec3d eyePos = new net.minecraft.util.math.Vec3d(player.getX(), player.getEyeY(), player.getZ());
                    double distSq = eyePos.squaredDistanceTo(net.minecraft.util.math.Vec3d.ofCenter(targetPos));

                    // 原版生存模式最远挖掘距离是 4.5 格（平方约 20.25），这里使用 20.0 作为安全阈值
                    if (distSq > 20.0) {
                        // 距离不够，必须赶路
                        if (!pathExecutor.isBusy() && player.isOnGround()) {
                            pathExecutor.setGoal(targetPos);
                            pathingFailTicks++; // 一直尝试 setGoal 却没走起来，说明寻路瞬间失败或受阻
                        } else if (pathExecutor.isBusy()) {
                            pathingFailTicks = 0; // 正在正常赶路上，重置失败计数
                        }

                        // 【核心修复】：如果重复受阻超过 20 tick (1秒)，说明该方块当前摸不到（如太高且没垫脚石）
                        if (pathingFailTicks > 20) {
                            System.out.println("FredoBot: 寻路到目标方块失败，跳过该节点，继续下一个！");
                            if (MiningHelper.blockToMine != null && !MiningHelper.blockToMine.isEmpty()) {
                                targetPos = MiningHelper.blockToMine.remove(0); // 放弃当前，直接接手下一个新节点
                                pathingFailTicks = 0;
                                if (pathExecutor.isBusy()) pathExecutor.stop();
                            } else {
                                // 队列空了但当前方块挖不到，伪装成挖完，强制进入收集掉落物阶段
                                targetPos = player.getBlockPos();
                                pickupCoolDownTick = 0;
                            }
                        }
                        return; // 距离大于设定值，直接 return 结束本帧，绝对不隔空挥镐！
                    }

                    // ==========================================
                    // 距离足够近（distSq <= 20.0），进入真实挖掘动作
                    // ==========================================
                    pathingFailTicks = 0; // 成功靠近，重置失败计数

                    if (pathExecutor.isBusy()) {
                        pathExecutor.stop(); // 到了面前，停下脚步
                    }

                    float[] angles = MiningHelper.getValidMiningAngle(player, targetPos);
                    yaw = angles[0];
                    pitch = angles[1];
                    MovementController.setLookDirection(player, yaw, pitch);
                    ToolsHelper.equipBestTool(player, targetPos, false);

                    // 底层发包破坏方块
                    client.interactionManager.updateBlockBreakingProgress(targetPos, net.minecraft.util.math.Direction.UP);
                    if (!player.handSwinging) {
                        player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                    }

                } else {
                    // 2. 【检查队列】：当前方块挖完了，检查是否还有需要连根拔起的方块
                    if (MiningHelper.blockToMine != null && !MiningHelper.blockToMine.isEmpty()) {
                        targetPos = MiningHelper.blockToMine.remove(0); // 拿出下一个方块
                        pickupCoolDownTick = 0; // 重置掉落冷却
                        pathingFailTicks = 0;
                        return; // 直接 return 进入下一帧去挖新的方块
                    }

                    // 3. 【等待掉落】：所有方块都挖完了，稍微等一会儿让物品全部生成/落地
                    if (pickupCoolDownTick <= 18) {
                        pickupCoolDownTick++;
                        return;
                    }

                    pressAttack = false;
                    pressForward = false;
                    pressJump = false;

                    if (pathExecutor.isBusy()) {
                        return; // 如果正在走向某个掉落物，等走到再说
                    }

                    // 4. 【统一收集】：扩大搜索范围至玩家周围 10 格，扫描所有掉落物
                    net.minecraft.util.math.Box searchBox = new net.minecraft.util.math.Box(player.getBlockPos()).expand(10.0);
                    java.util.List<ItemEntity> drops = client.world.getEntities(ItemEntity.class, searchBox, e -> true);

                    if (!drops.isEmpty()) {
                        ItemEntity nearestDrop = null;
                        double minDist = Double.MAX_VALUE;
                        // 寻找离玩家最近的掉落物去捡
                        for (ItemEntity drop : drops) {
                            double d = player.squaredDistanceTo(drop);
                            if (d < minDist) {
                                minDist = d;
                                nearestDrop = drop;
                            }
                        }
                        if (nearestDrop != null) {
                            // 委派给 PathExecutor，并立刻结束本帧
                            walkTowardsEntity(player, nearestDrop);
                            return;
                        }
                    } else {
                        // 5. 【任务完成】：所有物品都捡干净了
                        System.out.println("FredoBot: 区域内方块已全部挖掘，掉落物收集完毕！");
                        pickupCoolDownTick = 0;
                        pathingFailTicks = 0;
                        targetEntity = null;
                        pathExecutor.resumeSuspendedGoal();
                        stopTask();
                    }
                }
                break;
        }

        applyKeys(client);
    }

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

    private void walkTowardsEntity(PlayerEntity player, Entity entity) {
        PathExecutor pathExecutor = BotEngine.getInstance().getModule(PathExecutor.class);
        targetEntity = entity;
        BlockPos entityBlockPos = entity.getBlockPos();
        pathExecutor.setTemporaryGoal(new BlockPos(entityBlockPos.getX(),entityBlockPos.getY()+0.5D,entityBlockPos.getZ()), PathExecutor.TempMissionType.GO_TO_ENTITY);
    }
}