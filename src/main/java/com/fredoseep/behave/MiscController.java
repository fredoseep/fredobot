package com.fredoseep.behave;

import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.GlobalExecutor;
import com.fredoseep.excutor.PathExecutor;
import com.fredoseep.utils.player.InventoryHelper;
import com.fredoseep.utils.player.MiningHelper;
import com.fredoseep.utils.player.PlayerHelper;
import com.fredoseep.utils.player.ToolsHelper;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
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
        RETURN_AND_BREAK    // 回到门里破坏地基回收门
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

                if (client.world.getBlockState(targetPos).isAir()) {
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
                            walkTowardsEntity(player, droppedBoat);
                        } else {
                            System.out.println("FredoBot: 掉落的船丢失，放弃拾取！");
                            boatEntity = null;
                        }
                    }
                } else {
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
                pathExecutor.setGoal(MiningHelper.blockToMine.get(0));
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

                    // 如果在水下且有门，寻找一个合适的放门点（紧挨着目标，但不在目标上方或目标本身）
                    if (isUnderwater && doorSlot != -1) {
                        BlockPos bestSpot = null;
                        for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
                            BlockPos p = targetPos.offset(d);
                            // 确保放门的位置是水，下方是实体方块，并且门的地基不是我们要挖的目标！
                            if (client.world.getBlockState(p).getMaterial().isLiquid() &&
                                    client.world.getBlockState(p.down()).getMaterial().isSolid() &&
                                    !p.down().equals(targetPos)) {
                                bestSpot = p;
                                break;
                            }
                        }
                        if (bestSpot != null) {
                            doorPos = bestSpot;
                            uwPhase = UwDoorPhase.SINK_AND_CLEAR;
                            System.out.println("FredoBot [水下作战]: 发现目标处于水下，启用门呼吸策略！");
                        }
                    }
                }

                // =================================================================
                // 2. 水下门呼吸状态机执行
                // =================================================================
                if (uwPhase != UwDoorPhase.NONE) {
                    switch (uwPhase) {
                        case SINK_AND_CLEAR:
                            // 让玩家游向门的预定位置，并等待触底
                            double dToDoor = player.squaredDistanceTo(Vec3d.ofBottomCenter(doorPos));
                            if (dToDoor > 2.0 || !player.isOnGround()) {
                                if (!pathExecutor.isBusy()) pathExecutor.setGoal(doorPos);
                                return; // 还在路上或还没沉到底
                            }
                            if (pathExecutor.isBusy()) pathExecutor.stop();

                            // 检查脚下有没有海草海带，有就砍了
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
                                // 切出门，低头放置
                                InventoryHelper.moveItemToHotbar(client, player, dSlot, player.inventory.selectedSlot);
                                MovementController.setLookDirection(player, player.yaw, 90f);
                                net.minecraft.util.hit.BlockHitResult hit = new net.minecraft.util.hit.BlockHitResult(Vec3d.ofCenter(doorPos.down()), net.minecraft.util.math.Direction.UP, doorPos.down(), false);
                                client.interactionManager.interactBlock((ClientPlayerEntity) player, client.world, net.minecraft.util.Hand.MAIN_HAND, hit);
                                uwWaitTicks = 5; // 给服务器发包缓冲时间
                            }
                            uwPhase = UwDoorPhase.MINE_TARGETS;
                            return;

                        case MINE_TARGETS:
                            if (uwWaitTicks > 0) { uwWaitTicks--; return; }

                            if (player.squaredDistanceTo(Vec3d.ofCenter(targetPos)) > 25.0) {
                                uwPhase = UwDoorPhase.RETURN_AND_BREAK;
                                return;
                            }

                            boolean isMined = client.world.getBlockState(targetPos).isAir() || client.world.getBlockState(targetPos).getMaterial().isLiquid();
                            if (!isMined) {
                                // 【核心注入点 1】：在还没挖掉之前，把这个方块转化为掉落物记入白名单
                                registerValidDrop(client.world.getBlockState(targetPos).getBlock());

                                float[] angles = MiningHelper.getValidMiningAngle(player, targetPos);
                                yaw = angles[0];
                                pitch = angles[1];
                                MovementController.setLookDirection(player, yaw, pitch);
                                ToolsHelper.equipBestTool(player, targetPos, false);
                                client.interactionManager.updateBlockBreakingProgress(targetPos, net.minecraft.util.math.Direction.UP);
                                if (!player.handSwinging) player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                            } else {
                                // 挖完了...
                                if (MiningHelper.blockToMine != null && !MiningHelper.blockToMine.isEmpty()) {
                                    targetPos = MiningHelper.blockToMine.remove(0);
                                } else {
                                    uwPhase = UwDoorPhase.COLLECT_DROPS;
                                    pickupCoolDownTick = 0;
                                }
                            }
                            return;

                        case COLLECT_DROPS:
                            if (pickupCoolDownTick <= 5) { pickupCoolDownTick++; return; }

                            // 扩大到你要求的 30x30x20 的范围 (以doorPos为中心，expand 15相当于正负15，即长宽30)
                            net.minecraft.util.math.Box searchBox = new net.minecraft.util.math.Box(doorPos).expand(15.0, 10.0, 15.0);

                            // 【核心注入点 2】：利用 Predicate 强行过滤，只拿白名单里的东西！
                            java.util.List<net.minecraft.entity.ItemEntity> drops = client.world.getEntities(
                                    net.minecraft.entity.ItemEntity.class,
                                    searchBox,
                                    e -> validDrops.isEmpty() || validDrops.contains(e.getStack().getItem())
                            );
                            if (!drops.isEmpty()) {
                                net.minecraft.entity.ItemEntity nearestDrop = drops.get(0);
                                double minDist = player.squaredDistanceTo(nearestDrop);
                                for (net.minecraft.entity.ItemEntity drop : drops) {
                                    double d = player.squaredDistanceTo(drop);
                                    if (d < minDist) { minDist = d; nearestDrop = drop; }
                                }
                                walkTowardsEntity(player, nearestDrop);
                            } else {
                                uwPhase = UwDoorPhase.RETURN_AND_BREAK; // 捡完了，回去拆门
                            }
                            return;

                        case RETURN_AND_BREAK:
                            // 游回门里准备拆家
                            if (player.squaredDistanceTo(Vec3d.ofBottomCenter(doorPos)) > 3.0 || !player.isOnGround()) {
                                if (!pathExecutor.isBusy()) pathExecutor.setGoal(doorPos);
                                return;
                            }
                            if (pathExecutor.isBusy()) pathExecutor.stop();

                            BlockPos foundation = doorPos.down();
                            boolean foundationMined = client.world.getBlockState(foundation).isAir() || client.world.getBlockState(foundation).getMaterial().isLiquid();

                            // 如果地基没挖掉，且门还在，就开始挖地基
                            if (!foundationMined && client.world.getBlockState(doorPos).getBlock() instanceof DoorBlock) {
                                float[] angs = MiningHelper.getValidMiningAngle(player, foundation);
                                MovementController.setLookDirection(player, angs[0], angs[1]);
                                ToolsHelper.equipBestTool(player, foundation, false);
                                client.interactionManager.updateBlockBreakingProgress(foundation, net.minecraft.util.math.Direction.UP);
                                if (!player.handSwinging) player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                            } else {
                                System.out.println("FredoBot: 水下目标挖掘完毕，门已回收，恢复正常状态！");
                                // 彻底重置状态，结束任务
                                uwPhase = UwDoorPhase.NONE;
                                doorPos = null;
                                pickupCoolDownTick = 0;
                                pathingFailTicks = 0;
                                targetEntity = null;
                                pathExecutor.resumeSuspendedGoal();
                                stopTask();
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

                    if (distSq > 20.0) {
                        if (!pathExecutor.isBusy() && player.isOnGround()) {
                            pathExecutor.setGoal(targetPos);
                            pathingFailTicks++;
                        } else if (pathExecutor.isBusy()) {
                            pathingFailTicks = 0;
                        }

                        if (pathingFailTicks > 20) {
                            System.out.println("FredoBot: 寻路到目标方块失败，跳过该节点，继续下一个！");
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
                    pathingFailTicks = 0;

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

                } else {
                    if (MiningHelper.blockToMine != null && !MiningHelper.blockToMine.isEmpty()) {
                        targetPos = MiningHelper.blockToMine.remove(0);
                        pickupCoolDownTick = 0;
                        pathingFailTicks = 0;
                        return;
                    }

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

                    Box searchBox = new Box(player.getBlockPos()).expand(15.0, 10.0, 15.0);
                    List<ItemEntity> drops = client.world.getEntities(
                            ItemEntity.class,
                            searchBox,
                            e -> validDrops.isEmpty() || validDrops.contains(e.getStack().getItem())
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
                            walkTowardsEntity(player, nearestDrop);
                            return;
                        }
                    } else {
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
}