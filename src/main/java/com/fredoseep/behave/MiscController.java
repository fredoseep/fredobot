package com.fredoseep.behave;

import com.fredoseep.algorithm.SimplePathfinder;
import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.GlobalExecutor;
import com.fredoseep.excutor.PathExecutor;
import com.fredoseep.utils.bt.BtStuff;
import com.fredoseep.utils.player.*;

import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.BoatEntity;

import net.minecraft.item.BoatItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Set;

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
    private final Set<Item> validDrops = new java.util.HashSet<>();
    private final Set<Entity> unreachableEntities = new java.util.HashSet<>();

    public enum MiscType {
        MINE_BLOCK_ABOVE_HEAD,
        BACK_FROM_SWIMMING,
        MINE_THE_BLOCK,
        MINE_THE_BLOCK_AND_COLLECT_THE_DROP;
    }
    public enum UwDoorPhase {
        NONE,               // 正常挖掘模式
        SINK_AND_CLEAR,     // 潜水触底并清理水草
        PLACE_DOOR,         // 放置呼吸门
        MINE_TARGETS,       // 躲在门里挖目标
        COLLECT_DROPS,      // 收集掉落物
        RETURN_AND_BREAK,   // 回到门里破坏地基回收门
        COLLECT_FINAL_DROPS // 收尾：捡起掉落的门和最后的地基方块
    }

    public UwDoorPhase uwPhase = UwDoorPhase.NONE;
    public BlockPos doorPos = null;
    public int uwWaitTicks = 0;

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
        if(currentTask==MiscType.MINE_THE_BLOCK_AND_COLLECT_THE_DROP)MiningHelper.currentTargetBlocks.clear();
        this.currentTask = null;
        this.targetPos = null;
        this.boatEntity = null;
        this.gotOffBoat = false;
        pickupCoolDownTick = 0;
        targetEntity = null;
        pathingFailTicks = 0;
        uwPhase = UwDoorPhase.NONE;
        doorPos = null;
        uwWaitTicks = 0;
        validDrops.clear();
        unreachableEntities.clear();
        resetKeys();
    }

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
        return 50;
    }

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

                if (client.world.getBlockState(targetPos).isAir()||client.world.getBlockState(targetPos).getMaterial().isLiquid()) {
                    System.out.println("FredoBot: 头顶方块清理完毕！");
                    stopTask();
                    pathExecutor.resume();
                    return;
                }

                prepareMineBlockAboveHead(player, targetPos);

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
                } else if (boatEntity != null && boatEntity.isAlive()) {
                    pressSneak = false;
                    pressForward = false;
                    float coolDown = player.getAttackCooldownProgress(0.0F);
                    if (coolDown >= 1.0F) {
                        MinecraftClient.getInstance().interactionManager.attackEntity(player, boatEntity);
                    }
                } else if (boatEntity != null && !boatEntity.isAlive()) {
                    pressAttack = false;
                    if (InventoryHelper.hasBoat(player)) {
                        System.out.println("FredoBot: 船只回收完毕，准备游向岸边！");
                        pathExecutor.resumeSuspendedGoal();
                        boatEntity = null;
                    } else {
                        ItemEntity droppedBoat = InventoryHelper.findNearestDroppedItem(player, 8.0, BoatItem.class);
                        if (droppedBoat != null) {
                            // 【核心修复 1】：等待船只落地或落水后再去捡
                            if (!droppedBoat.isTouchingWater() && !droppedBoat.isOnGround()) return;
                            walkTowardsEntity(player, droppedBoat);
                        } else {
                            System.out.println("FredoBot: 掉落的船丢失，放弃拾取！");
                            boatEntity = null;
                        }
                    }
                } else {
                    if(pathExecutor.getCurrentNode().state == SimplePathfinder.MovementState.DIVING){
                        System.out.println("FredoBot: start diving");
                        stopTask();
                        pathExecutor.resume();
                        return;
                    }
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
                if (pathExecutor.isBusy()) break;
                if (MiningHelper.blockToMine.isEmpty()) {
                    stopTask();
                    break;
                }
                pathExecutor.setGoal(MiningHelper.blockToMine.get(0),"block to mine pos");
                MiningHelper.blockToMine.remove(0);
                break;

            case MINE_THE_BLOCK_AND_COLLECT_THE_DROP:
                if (targetPos == null || client.world == null) {
                    stopTask();
                    break;
                }

                if (uwPhase == UwDoorPhase.NONE) {
                    boolean isUnderwater = client.world.getBlockState(targetPos).getBlock() == Blocks.WATER ||
                            client.world.getBlockState(targetPos.up()).getBlock() == Blocks.WATER;

                    int doorSlot = -1;
                    for (int i = 0; i < 36; i++) {
                        if (player.inventory.getStack(i).getItem().isIn(net.minecraft.tag.ItemTags.DOORS)) {
                            doorSlot = i;
                            break;
                        }
                    }


                    if (isUnderwater && doorSlot != -1) {
                        BlockPos bestSpot = null;
                        if (client.world.getBlockState(targetPos.up()).getMaterial().isLiquid() &&
                                isSafeDoorFoundation(client, targetPos)) {
                            bestSpot = targetPos.up();
                        } else {
                            for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
                                BlockPos p = targetPos.up().offset(d);
                                if (client.world.getBlockState(p).getMaterial().isLiquid() &&
                                        isSafeDoorFoundation(client, p.down()) &&
                                        !p.down().equals(targetPos)) {
                                    bestSpot = p;
                                    break;
                                }
                            }
                        }

                        if (bestSpot != null) {
                            doorPos = bestSpot;
                            uwPhase = UwDoorPhase.SINK_AND_CLEAR;
                            System.out.println("FredoBot [水下作战]: 发现目标处于水下，启用门呼吸策略！doorPos: " + doorPos.toShortString());
                        }
                    }
                }
                if (uwPhase != UwDoorPhase.NONE) {
                    switch (uwPhase) {
                        case SINK_AND_CLEAR:
                            if (player.squaredDistanceTo(Vec3d.ofBottomCenter(doorPos)) > 0.65 || !player.isOnGround()) {
                                if (!pathExecutor.isBusy()) pathExecutor.setGoal(doorPos,"door pos");
                                return;
                            }
                            if (pathExecutor.isBusy()) pathExecutor.stop();
                            Block blockAtDoor = client.world.getBlockState(doorPos).getBlock();
                            if (blockAtDoor == Blocks.SEAGRASS ||
                                    blockAtDoor == Blocks.TALL_SEAGRASS ||
                                    blockAtDoor == Blocks.KELP ||
                                    blockAtDoor == Blocks.KELP_PLANT) {
                                client.interactionManager.updateBlockBreakingProgress(doorPos, net.minecraft.util.math.Direction.UP);
                                player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                                return; // 等待下一帧水草消失
                            }

                            uwPhase = UwDoorPhase.PLACE_DOOR;
                            return;

                        case PLACE_DOOR:
                            int dSlot = -1;
                            for (int i = 0; i < 36; i++) {
                                if (player.inventory.getStack(i).getItem().isIn(net.minecraft.tag.ItemTags.DOORS)) {
                                    dSlot = i; break;
                                }
                            }
                            if (dSlot != -1) {
                                if (!RelevantDirectionHelper.placeDoorSmartly(client, player, doorPos, dSlot)) {
                                    return; // 视角未转正，强行 return 等待下一个 tick
                                }
                                uwWaitTicks = 5; // 放置成功，给服务器一点发包反应时间
                            }
                            uwPhase = UwDoorPhase.MINE_TARGETS;
                            return;
                        case MINE_TARGETS:
                            if (uwWaitTicks > 0) { uwWaitTicks--; return; }
                            if (player.squaredDistanceTo(Vec3d.ofBottomCenter(doorPos)) > 0.65 || !player.isOnGround()) {
                                if (!pathExecutor.isBusy()) {
                                    // 强制发起一次进门寻路！
                                    pathExecutor.setGoal(doorPos,"door Pos");
                                    System.out.println("FredoBot [水下作战]: 门已放置，正在进入氧气舱...");
                                }
                                return; // 没站进门里之前，坚决不准挥镐子！
                            }
                            if (pathExecutor.isBusy()) pathExecutor.stop();

                            if (player.squaredDistanceTo(Vec3d.ofCenter(targetPos)) > 25.0) {
                                uwPhase = UwDoorPhase.RETURN_AND_BREAK;
                                return;
                            }

                            if (targetPos.equals(doorPos) || targetPos.equals(doorPos.down()) || targetPos.equals(doorPos.up())) {
                                if (MiningHelper.blockToMine != null && !MiningHelper.blockToMine.isEmpty()) {
                                    targetPos = MiningHelper.blockToMine.remove(0); // 暂不挖地基，拿队列里的下一个去挖
                                } else {
                                    uwPhase = UwDoorPhase.COLLECT_DROPS;
                                    pickupCoolDownTick = 0;
                                }
                                return;
                            }

                            boolean isMined = client.world.getBlockState(targetPos).isAir() || client.world.getBlockState(targetPos).getMaterial().isLiquid();
                            if (!isMined) {
                                // 正常挖掘周边目标
                                registerValidDrop(client.world.getBlockState(targetPos).getBlock());

                                float[] angles = MiningHelper.getValidMiningAngle(player, targetPos);
                                yaw = angles[0];
                                pitch = angles[1];
                                MovementController.setLookDirection(player, yaw, pitch);
                                ToolsHelper.equipBestTool(player, targetPos, false);
                                client.interactionManager.updateBlockBreakingProgress(targetPos, net.minecraft.util.math.Direction.UP);
                                if (!player.handSwinging) player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                            } else {
                                // 当前周边方块挖完，切换下一个
                                if (MiningHelper.blockToMine != null && !MiningHelper.blockToMine.isEmpty()) {
                                    targetPos = MiningHelper.blockToMine.remove(0);
                                } else {
                                    uwPhase = UwDoorPhase.COLLECT_DROPS;
                                    pickupCoolDownTick = 0;
                                }
                            }
                            return;

                        case COLLECT_DROPS:
                            // 【核心修复】：防止疯狂发包重算！
                            if (pathExecutor.isBusy()) return;
                            pickupCoolDownTick++;

                            net.minecraft.util.math.Box searchBox = new net.minecraft.util.math.Box(doorPos).expand(15.0, 10.0, 15.0);
                            java.util.List<net.minecraft.entity.ItemEntity> drops = client.world.getEntities(
                                    net.minecraft.entity.ItemEntity.class,
                                    searchBox,
                                    // 【核心修复】：过滤掉在黑名单里的掉落物
                                    e -> (validDrops.isEmpty() || validDrops.contains(e.getStack().getItem())) && !unreachableEntities.contains(e)
                            );

                            if (!drops.isEmpty()) {
                                net.minecraft.entity.ItemEntity nearestDrop = drops.get(0);
                                double minDist = player.squaredDistanceTo(nearestDrop);
                                for (net.minecraft.entity.ItemEntity drop : drops) {
                                    double d = player.squaredDistanceTo(drop);
                                    if (d < minDist) { minDist = d; nearestDrop = drop; }
                                }
                                if (!nearestDrop.isTouchingWater() && !nearestDrop.isOnGround()) return;

                                // ==========================================
                                // 【黑名单判定】：如果刚刚去捡的就是它，但寻路停止了
                                // 距离远说明没路走，超时说明卡住了或背包满了，统统拉黑！
                                // ==========================================
                                if (targetEntity == nearestDrop) {
                                    if (player.squaredDistanceTo(nearestDrop) > 4.0 || pickupCoolDownTick > 60) {
                                        System.out.println("FredoBot [水下]: 掉落物不可达或超时，加入黑名单放弃！");
                                        unreachableEntities.add(nearestDrop);
                                        targetEntity = null;
                                        pickupCoolDownTick = 0;
                                    }
                                    return; // 等待下一 Tick 扫其他的
                                }

                                walkTowardsEntity(player, nearestDrop);
                                pickupCoolDownTick = 0;
                            } else {
                                if (pickupCoolDownTick < 40) {
                                    if (pickupCoolDownTick % 10 == 0) System.out.println("FredoBot [调试]: 等待周边掉落物刷新中... (" + pickupCoolDownTick + "/40)");
                                    return;
                                } else {
                                    uwPhase = UwDoorPhase.RETURN_AND_BREAK;
                                }
                            }
                            return;

                        case RETURN_AND_BREAK:
                            // 游回门里准备拆家
                            if (player.squaredDistanceTo(Vec3d.ofBottomCenter(doorPos)) > 3.0 || !player.isOnGround()) {
                                if (!pathExecutor.isBusy()) pathExecutor.setGoal(doorPos,"back to door pos");
                                return;
                            }
                            if (pathExecutor.isBusy()) pathExecutor.stop();

                            // ==========================================
                            // 【核心新增】：深呼吸！等待氧气完全回满再拆门
                            // ==========================================
                            if (player.getAir() < player.getMaxAir()) {
                                // 每 10 个 tick（0.5秒）打印一次，防止控制台刷屏
                                if (player.age % 10 == 0) {
                                    System.out.println("FredoBot [水下作战]: 正在氧气舱内大口呼吸 (" + player.getAir() + "/" + player.getMaxAir() + ")...");
                                }
                                return; // 挂起当前 Tick，什么都不做，就在门里站着回氧气
                            }

                            BlockPos foundation = doorPos.down();
                            boolean foundationMined = client.world.getBlockState(foundation).isAir() || client.world.getBlockState(foundation).getMaterial().isLiquid();

                            // ==========================================
                            // 【终极收尾】：将地基方块和所有的门加入白名单！
                            // 保证我们接下来爆出来的地基和门能被捡起来。
                            // ==========================================
                            registerValidDrop(client.world.getBlockState(foundation).getBlock());
                            validDrops.add(Items.OAK_DOOR);
                            validDrops.add(Items.SPRUCE_DOOR);
                            validDrops.add(Items.BIRCH_DOOR);
                            validDrops.add(Items.JUNGLE_DOOR);
                            validDrops.add(Items.ACACIA_DOOR);
                            validDrops.add(Items.DARK_OAK_DOOR);
                            validDrops.add(Items.IRON_DOOR);

                            // 对地基进行致命一击，挖掉地基，门自然爆裂！
                            if (!foundationMined && client.world.getBlockState(doorPos).getBlock() instanceof DoorBlock) {
                                float[] angs = MiningHelper.getValidMiningAngle(player, foundation);
                                MovementController.setLookDirection(player, angs[0], angs[1]);
                                ToolsHelper.equipBestTool(player, foundation, false);
                                client.interactionManager.updateBlockBreakingProgress(foundation, net.minecraft.util.math.Direction.UP);
                                if (!player.handSwinging) player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                            } else {
                                uwPhase = UwDoorPhase.COLLECT_FINAL_DROPS;
                                pickupCoolDownTick = 0;
                            }
                            return;

                        case COLLECT_FINAL_DROPS:
                            if (pathExecutor.isBusy()) return; // 防止疯狂发包
                            pickupCoolDownTick++;

                            net.minecraft.util.math.Box finalSearchBox = new net.minecraft.util.math.Box(doorPos).expand(8.0, 8.0, 8.0);
                            java.util.List<net.minecraft.entity.ItemEntity> finalDrops = client.world.getEntities(
                                    net.minecraft.entity.ItemEntity.class,
                                    finalSearchBox,
                                    e -> validDrops.contains(e.getStack().getItem()) && !unreachableEntities.contains(e)
                            );

                            if (!finalDrops.isEmpty()) {
                                net.minecraft.entity.ItemEntity nearestDrop = finalDrops.get(0);
                                double minDist = player.squaredDistanceTo(nearestDrop);
                                for (net.minecraft.entity.ItemEntity drop : finalDrops) {
                                    double d = player.squaredDistanceTo(drop);
                                    if (d < minDist) { minDist = d; nearestDrop = drop; }
                                }
                                if (!nearestDrop.isTouchingWater() && !nearestDrop.isOnGround()) return;

                                if (targetEntity == nearestDrop) {
                                    if (player.squaredDistanceTo(nearestDrop) > 4.0 || pickupCoolDownTick > 60) {
                                        unreachableEntities.add(nearestDrop);
                                        targetEntity = null;
                                        pickupCoolDownTick = 0;
                                    }
                                    return;
                                }

                                walkTowardsEntity(player, nearestDrop);
                                pickupCoolDownTick = 0;
                            } else {
                                if (pickupCoolDownTick < 40) {
                                    if (pickupCoolDownTick % 10 == 0) System.out.println("FredoBot [调试]: 门/地基已破坏，等待最终掉落物... (" + pickupCoolDownTick + "/40)");
                                    return;
                                } else {
                                    System.out.println("FredoBot: 水下地基和门已完全回收(或超时)，完美恢复正常状态！");
                                    uwPhase = UwDoorPhase.NONE;
                                    doorPos = null;
                                    pickupCoolDownTick = 0;
                                    pathingFailTicks = 0;
                                    targetEntity = null;
                                    pathExecutor.resumeSuspendedGoal();
                                    stopTask();
                                }
                            }
                            return;

                    }
                }

                // =================================================================
                // 3. 陆地普通挖掘逻辑 (即如果没满足水下条件，走原有的硬挖逻辑)
                // =================================================================
                boolean isBlockMined = client.world.getBlockState(targetPos).isAir() ||
                        client.world.getBlockState(targetPos).getMaterial().isLiquid();

                if (!isBlockMined) {
                    registerValidDrop(client.world.getBlockState(targetPos).getBlock());
                    Vec3d eyePos = new Vec3d(player.getX(), player.getEyeY(), player.getZ());
                    double distSq = eyePos.squaredDistanceTo(Vec3d.ofCenter(targetPos));

                    // 阶段 A：目标在物理范围之外 (> 20.0 也就是 > 4.47格)
                    if (distSq > 20.0) {
                        if (!pathExecutor.isBusy() && player.isOnGround()) {

                            BlockPos topPos = new BlockPos(targetPos.getX(), BtStuff.groundYNoLeavesOrTrees(targetPos), targetPos.getZ());
                            double horizDistSq = Math.pow(player.getX() - (targetPos.getX() + 0.5), 2) + Math.pow(player.getZ() - (targetPos.getZ() + 0.5), 2);

                            pathExecutor.setGoal(targetPos,"ground block to mine");
                            System.out.println("Fredodebug: distSq > 20, 开始寻路至目标");

                            if (targetPos.getY() < topPos.getY() - 3 && horizDistSq > 16.0) {
                                System.out.println("FredoBot [智能挖掘]: 目标深埋地下，启动降维打击！先前往正上方地表...");
                                System.out.println("Fredodebug: tempGoal is surface : " + topPos.toShortString());
                                pathExecutor.setTemporaryGoal(topPos, PathExecutor.TempMissionType.IDLE);
                            }

                            // 【机制大修】：每次发起新的寻路（意味着上一次失败并停止了），受挫值直接加 50 点
                            pathingFailTicks += 50;
                        }
                        // 删除之前那句坑爹的 else if (pathExecutor.isBusy()) { pathingFailTicks = 0; }
                        // 因为成功在路上走不代表永远不会失败，绝对不能清零！

                        if (pathingFailTicks > 300) {
                            System.out.println("FredoBot: 远距离寻路多次受阻(受挫值拉满)，果断跳过该节点！");
                            if (MiningHelper.blockToMine != null && !MiningHelper.blockToMine.isEmpty()) {
                                targetPos = MiningHelper.blockToMine.remove(0);
                                pathingFailTicks = 0;
                                if (pathExecutor.isBusy()) pathExecutor.stop();
                            } else {
                                targetPos = player.getBlockPos();
                                pickupCoolDownTick = 0;
                            }
                        }
                        return;
                    }

                    // 阶段 B：目标在物理范围之内 (distSq <= 20.0)
                    if (pathExecutor.isBusy() && player.isOnGround()) {
                        pathExecutor.stop();
                    }

                    float[] angles = MiningHelper.getValidMiningAngle(player, targetPos);
                    yaw = angles[0];
                    pitch = angles[1];
                    MovementController.setLookDirection(player, yaw, pitch);
                    ToolsHelper.equipBestTool(player, targetPos, false);

                    client.interactionManager.updateBlockBreakingProgress(targetPos, net.minecraft.util.math.Direction.UP);
                    if (!player.handSwinging) {
                        player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                    }

                    // 【机制大修】：如果在挥镐子，每一 Tick 受挫值 +1。
                    // 正常挖石头大约需要 30~50 Ticks，黑曜石需要 180 Ticks。
                    // 如果超过 300 Ticks（15秒）还没挖掉，说明它隔着墙瞎挥，或者方块不可破坏，强制跳过！
                    pathingFailTicks++;
                    if (pathingFailTicks > 300) {
                        System.out.println("FredoBot: 近战挖掘耗时过长(疑似隔着墙或处于死角)，受挫值拉满，放弃挖掘！");
                        if (MiningHelper.blockToMine != null && !MiningHelper.blockToMine.isEmpty()) {
                            targetPos = MiningHelper.blockToMine.remove(0);
                            pathingFailTicks = 0;
                        } else {
                            targetPos = player.getBlockPos();
                            pickupCoolDownTick = 0;
                        }
                    }

                } else {
                    // ==========================================
                    // 【全新战术：定点清扫与集中拾取】
                    // 目标已经挖掉，先不要急着乱跑捡东西！
                    // 看看站在原地不用动（触及范围内）还能不能挖到其他目标。
                    // ==========================================
                    BlockPos nextReachable = null;
                    if (MiningHelper.blockToMine != null && !MiningHelper.blockToMine.isEmpty()) {
                        Vec3d eyePos = new Vec3d(player.getX(), player.getEyeY(), player.getZ());

                        // 遍历剩余的目标队列
                        for (int i = 0; i < MiningHelper.blockToMine.size(); i++) {
                            BlockPos candidate = MiningHelper.blockToMine.get(i);
                            boolean candidateMined = client.world.getBlockState(candidate).isAir() ||
                                    client.world.getBlockState(candidate).getMaterial().isLiquid();

                            // 如果这块方块已经被意外破坏（比如树叶自己枯萎了），移出队列
                            if (candidateMined) {
                                MiningHelper.blockToMine.remove(i);
                                i--; // 删掉后索引回退
                                continue;
                            }

                            // 如果物理距离 <= 20.0 (约4.47格)，说明站在原地就能挥手挖到！
                            if (eyePos.squaredDistanceTo(Vec3d.ofCenter(candidate)) <= 20.0) {
                                nextReachable = candidate;
                                MiningHelper.blockToMine.remove(i);
                                break;
                            }
                        }
                    }

                    // 1. 如果周围还有够得着的目标，设为新目标原地继续抡镐子，绝对不走！
                    if (nextReachable != null) {
                        targetPos = nextReachable;
                        pickupCoolDownTick = 0;
                        pathingFailTicks = 0;
                        return;
                    }

                    // 2. 原地够得着的全挖完了（或配额已满），现在才开始集中捡掉落物！
                    if (pickupCoolDownTick <= 5) {
                        pickupCoolDownTick++;
                        return;
                    }

                    pressAttack = false;
                    pressForward = false;
                    pressJump = false;

                    if (pathExecutor.isBusy()) {
                        return;
                    }

                    pickupCoolDownTick++;
                    Box searchBox = new Box(player.getBlockPos()).expand(15.0, 10.0, 15.0);
                    List<ItemEntity> drops = client.world.getEntities(
                            ItemEntity.class,
                            searchBox,
                            // 加上黑名单过滤，避免树叶卡死
                            e -> (validDrops.isEmpty() || validDrops.contains(e.getStack().getItem())) && !unreachableEntities.contains(e)
                    );

                    if (!drops.isEmpty()) {
                        ItemEntity nearestDrop = null;
                        double minDist = Double.MAX_VALUE;
                        for (ItemEntity drop : drops) {
                            double d = player.squaredDistanceTo(drop);
                            if (d < minDist) {
                                minDist = d;
                                nearestDrop = drop;
                            }
                        }
                        if (nearestDrop != null) {
                            if (!nearestDrop.isTouchingWater() && !nearestDrop.isOnGround()) return;

                            if (targetEntity == nearestDrop) {
                                if (player.squaredDistanceTo(nearestDrop) > 4.0 || pickupCoolDownTick > 60) {
                                    System.out.println("FredoBot [陆地]: 掉落物不可达或超时，加入黑名单果断放弃！");
                                    unreachableEntities.add(nearestDrop);
                                    targetEntity = null;
                                    pickupCoolDownTick = 0;
                                }
                                return;
                            }

                            walkTowardsEntity(player, nearestDrop);
                            pickupCoolDownTick = 0;
                            return;
                        }
                    } else {
                        if (pickupCoolDownTick < 20) {
                            return; // 等待掉落物刷新
                        } else {
                            // 3. 当前区域打扫完毕！看看还有没有远处的下一批目标。
                            if (MiningHelper.blockToMine != null && !MiningHelper.blockToMine.isEmpty()) {
                                // 提取下一个需要跑路才能挖的远方目标
                                targetPos = MiningHelper.blockToMine.remove(0);
                                System.out.println("FredoBot [陆地]: 当前驻点已清空并打扫完毕，前往下一片目标区域！ -> " + targetPos.toShortString());
                                pickupCoolDownTick = 0;
                                pathingFailTicks = 0;
                                targetEntity = null;
                                return; // return 后，下一帧 distSq > 20.0，自然会开始寻路走过去
                            } else {
                                // 4. 队列彻底空了，意味着挖掘数量已达要求，完美收工！
                                System.out.println("FredoBot: 挖掘数量已达标，区域内方块及掉落物全部收集完毕！任务圆满结束！");
                                pickupCoolDownTick = 0;
                                pathingFailTicks = 0;
                                targetEntity = null;
                                pathExecutor.resumeSuspendedGoal();
                                stopTask();
                            }
                        }
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
        System.out.println("Fredodebug: tempGoal is Entity: "+ entity.toString());
        pathExecutor.setTemporaryGoal(new BlockPos(entityBlockPos.getX(), entityBlockPos.getY() + 0.5D, entityBlockPos.getZ()), PathExecutor.TempMissionType.GO_TO_ENTITY);
    }
    private void registerValidDrop(Block block) {
        if (block == Blocks.AIR || block == Blocks.WATER) return;
        Item baseItem = block.asItem();
        if (baseItem != Items.AIR) validDrops.add(baseItem);

        if (block.isIn(BlockTags.LEAVES)) {
            validDrops.add(Items.STICK);
            validDrops.add(Items.APPLE);
            validDrops.add(Items.OAK_SAPLING);
            validDrops.add(Items.SPRUCE_SAPLING);
            validDrops.add(Items.BIRCH_SAPLING);
            validDrops.add(Items.JUNGLE_SAPLING);
            validDrops.add(Items.ACACIA_SAPLING);
            validDrops.add(Items.DARK_OAK_SAPLING);
        } else if (block == Blocks.GRAVEL) {
            validDrops.add(Items.FLINT);
        } else if (block == Blocks.STONE) {
            validDrops.add(Items.COBBLESTONE);
        } else if (block == Blocks.COAL_ORE) {
            validDrops.add(Items.COAL);
        } else if (block == Blocks.IRON_ORE) {
            validDrops.add(Items.IRON_ORE);
        } else if (block == Blocks.GOLD_ORE) {
            validDrops.add(Items.GOLD_ORE);
        } else if (block == Blocks.DIAMOND_ORE) {
            validDrops.add(Items.DIAMOND);
        } else if (block == Blocks.GRASS_BLOCK || block == Blocks.GRASS_PATH) {
            validDrops.add(Items.DIRT);
        }
    }
    private boolean isSafeDoorFoundation(MinecraftClient client, BlockPos foundationPos) {
        BlockState foundationState = client.world.getBlockState(foundationPos);
        if (!foundationState.getMaterial().isSolid()) return false;
        if (foundationState.getBlock() instanceof FallingBlock) {
            BlockState stateBelow = client.world.getBlockState(foundationPos.down());
            if (!stateBelow.getMaterial().isSolid() || stateBelow.getMaterial().isLiquid() || stateBelow.getMaterial().isReplaceable()) {
                return false;
            }
        }

        return true;
    }
}