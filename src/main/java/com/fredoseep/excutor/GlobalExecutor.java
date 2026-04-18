package com.fredoseep.excutor;

import com.fredoseep.BtPosContext;
import com.fredoseep.behave.IBotModule;
import com.fredoseep.behave.MiscController;
import com.fredoseep.utils.bt.BtStuff;
import com.fredoseep.utils.player.InventoryHelper;
import com.fredoseep.utils.player.MiningHelper;
import com.fredoseep.utils.player.PlayerHelper;
import com.fredoseep.utils.player.ToolsHelper;
import me.voidxwalker.autoreset.Atum;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class GlobalExecutor implements IBotModule {

    private final MinecraftClient minecraftClient = MinecraftClient.getInstance();

    private PathExecutor pathExecutor;
    private BlockPos btPos = null;
    public  BlockPos TNTSetupPos = null;
    private BlockPos btStandingPos = null;
    private boolean globalInitialized = false;
    private boolean treeQueueGenerated = false;
    private boolean tntQueueGenerated = false;
    private static final BlockPos INVALID_BLOCKPOS = new BlockPos(-1, -1, -1);

    private int waitTicks = 0;
    private int debugTick = 0;

    private GlobalState currentState = GlobalState.IDLE;



    @Override
    public void onEnable() {
        resetState();
    }

    @Override
    public void onDisable() {
        resetState();
    }

    @Override
    public void onTick(MinecraftClient client, PlayerEntity player) {
        if (minecraftClient.world == null || minecraftClient.player == null) {
            waitTicks = 0;
            return;
        }
        if (!globalInitialized) initializeSettings();
        pathExecutor = BotEngine.getInstance().getModule(PathExecutor.class);

        if (currentState.missionOrder < 6) {
            btStaff((ClientPlayerEntity) player);
        }


    }

    private void initializeSettings() {
        minecraftClient.getInstance().options.viewDistance = 20;
        minecraftClient.getInstance().options.entityDistanceScaling = 5.0f;
        minecraftClient.getInstance().options.gamma = 5.0;
        if (minecraftClient.worldRenderer != null) {
            minecraftClient.worldRenderer.reload();
        }
        globalInitialized = true;
    }

    public enum GlobalState {
        IDLE(0), GOING_TO_BT_STANDING_PLACE(1), DIGGING_BT(3), LOOTING_BT(4), LOOKING_FOR_TREES(5), NEXT(0x7FFFFFF);
        private final int missionOrder;

        GlobalState(int missionOrder) {
            this.missionOrder = missionOrder;
        }

        public int getMissionOrder() {
            return missionOrder;
        }
    }

    private void btStaff(ClientPlayerEntity player) {
        if (btPos == null) {
            int x = BtPosContext.btXPos;
            int z = BtPosContext.btZPos;

            if (!minecraftClient.world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) {
                waitTicks++;
                if (waitTicks > 60) {
                    System.out.println("Fredobot: Reset because BT chunk took too long to load!");
                    resetWorld();
                }
                return;
            }

            setBtPos();
            if (btPos == null) {
                System.out.println("Fredobot: Reset because no bt found in the loaded chunk!");
                resetWorld();
                return;
            }
            if (btStandingPos == null) {
                btStandingPos = findAProperPosThen();
                System.out.println("FredoBot [Debug]: 宝藏已锁定! 目标挖掘站位点计算为 -> " + btStandingPos.toShortString());
            }
        }

        switch (currentState) {
            case IDLE:
                pathExecutor.setGoal(btStandingPos);
                currentState = GlobalState.GOING_TO_BT_STANDING_PLACE;
                System.out.println("FredoBot [Debug]: 状态切换 -> 开始前往挖掘站位点");
                break;

            case GOING_TO_BT_STANDING_PLACE:
                boolean isNear = PlayerHelper.isNear(player, btStandingPos, 1);
                boolean isPathBusy = pathExecutor.isBusy();


                if (isNear && !isPathBusy) {
                    System.out.println("FredoBot [Debug]: 状态切换 -> 已抵达站位点，开始暴力挖掘！");
                    currentState = GlobalState.DIGGING_BT;
                }
                break;

            case DIGGING_BT:
                if (ChestBlock.isChestBlocked(minecraftClient.world, btPos)) {
                    ToolsHelper.equipBestTool(player, btPos.up(), false);
                    minecraftClient.interactionManager.updateBlockBreakingProgress(btPos.up(), Direction.UP);
                    player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                } else {
                    System.out.println("FredoBot [Debug]: 状态切换 -> 障碍物已清空，发送秒开箱子请求！");
                    currentState = GlobalState.LOOTING_BT;
                    BlockHitResult hitResult = new BlockHitResult(
                            Vec3d.ofCenter(btPos),
                            Direction.UP,
                            btPos,
                            false
                    );
                    minecraftClient.interactionManager.interactBlock(player, minecraftClient.world, net.minecraft.util.Hand.MAIN_HAND, hitResult);
                }
                break;

            case LOOTING_BT:

                if (player.currentScreenHandler instanceof GenericContainerScreenHandler) {
                    int syncId = player.currentScreenHandler.syncId;
                    System.out.println("FredoBot [Debug]: GUI 已就绪，SyncId = " + syncId + "，开始执行毫秒级拿取...");

                    for (int slot = 0; slot < 27; slot++) {
                        if (!InventoryHelper.BtUsefulStaff.isUseful(player.currentScreenHandler.getSlot(slot).getStack()))
                            continue;
                        if (player.currentScreenHandler.getSlot(slot).hasStack()) {
                            minecraftClient.interactionManager.clickSlot(
                                    syncId,
                                    slot,
                                    0,
                                    net.minecraft.screen.slot.SlotActionType.QUICK_MOVE,
                                    player
                            );
                        }
                    }
                    player.closeHandledScreen();
                    minecraftClient.openScreen(null);
                    System.out.println("FredoBot [Debug]: 战利品洗劫完毕，关闭界面！");
                    if (!BtStuff.evaluateBt(player)) {
                        System.out.println("Fredobot: reset because bt staffs are not enough.");
                        resetWorld();
                        return;
                    }
                }
                currentState = GlobalState.LOOKING_FOR_TREES;
                break;
            case LOOKING_FOR_TREES:
                // 1. 杂项控制器（挖方块/捡东西）绝对优先，它忙的时候全局挂起
                if (BotEngine.getInstance().getModule(MiscController.class).isBusy()) {
                    break;
                }

                // 2. 如果还有没挖完的方块，等待寻路空闲后派发任务
                if (!MiningHelper.blockToMine.isEmpty()) {
                    if (pathExecutor.isBusy()) break;
                    MiningHelper.dispatchNextMineAndCollectTask();
                    break;
                }

                // 3. 生成基础树木任务
                if (!treeQueueGenerated) {
                    if (pathExecutor.isBusy()) break;
                    if (BtStuff.hasTNT) {
                        if (BtStuff.itemsToCraft.contains(Items.STONE_BUTTON)) {
                            MiningHelper.mineAndCollect(player, InventoryHelper.anyLogs, 1, 100);
                        }
                    } else {
                        MiningHelper.mineAndCollect(player, InventoryHelper.anyLogs, 2, 100);
                        System.out.println("Fredodebug: get two woods");
                    }
                    treeQueueGenerated = true;
                    break;
                }

                // 4. TNT 流水线处理
                if (BtStuff.hasTNT) {
                    if (!tntQueueGenerated) {
                        if (pathExecutor.isBusy()) break;
                        TNTSetupStaffs(player);
                        break;
                    }

                    // 前往 TNT 放置点
                    if (BtStuff.tntState == BtStuff.TNTState.INIT) {
                        // 【严苛中心检测】：确保站在正中心，绝不隔空放 TNT！
                        double dX = player.getX() - (TNTSetupPos.getX() + 0.5);
                        double dZ = player.getZ() - (TNTSetupPos.getZ() + 0.5);
                        double dY = Math.abs(player.getY() - TNTSetupPos.getY());
                        boolean isCentered = (dX * dX + dZ * dZ <= 0.15) && (dY <= 0.5);

                        if (!isCentered) {
                            if (!pathExecutor.isBusy()) {
                                pathExecutor.setGoal(TNTSetupPos);
                            }
                            break; // 还没走到完美位置，挂起全局循环！
                        }
                    }

                    // ==========================================
                    // 【核心修复】：进入核爆微操阶段，拆除所有全局拦截！
                    // 无论 pathExecutor 是不是在跑路，必须让 lightUpTNT 保持每 Tick 运行，
                    // 这样里面的秒表 (tntWaitTicks) 才能真实地记录现实时间的流逝！
                    // ==========================================
                    BtStuff.lightUpTNT(player);

                    if (BtStuff.tntState != BtStuff.TNTState.DONE) {
                        break;
                    }
                }

                // 5. 完美收工，进入下一个大状态
                if (!pathExecutor.isBusy()) {
                    System.out.println("FredoBot [Debug]: 树木/TNT前置任务全部完成，进入 NEXT！");
                    currentState = GlobalState.NEXT;
                }
                break;
        }
    }




    private void TNTSetupStaffs(PlayerEntity player) {
        TNTSetupPos = BtStuff.calTNTSetupPos(player);
        if (TNTSetupPos.equals(INVALID_BLOCKPOS)) {
            System.out.println("Fredobot: reset because no place to set up TNT");
            resetWorld();
            return;
        }

        int TNTSetupPosY = BtStuff.groundYNoLeavesOrTrees(TNTSetupPos);
        int neededBlockCount = TNTSetupPos.getY() - TNTSetupPosY - 1;
        System.out.println("Fredodeg: neededBlockCount: " + neededBlockCount);

        if (InventoryHelper.countAvailableBuildingBlocks(player).pillaringBlocks < neededBlockCount) {

            if (player.isTouchingWater() || player.isSwimming()) {
                System.out.println("FredoBot [Debug]: 玩家在水中，优先寻找最近的海岸登陆...");
                BlockPos landPos = MiningHelper.findNearestBlocks(player,new HashSet<>(Collections.singletonList(Blocks.GRASS_BLOCK)) ,1,50).getFirst();
                if (landPos != null) {
                    pathExecutor.setGoal(landPos);
                } else {
                    System.out.println("FredoBot [Debug]: 方圆 60 格内找不到海岸，重置！");
                    resetWorld();
                }
                return;
            }
            System.out.println("FredoBot [Debug]: 玩家已在陆地，开始锁定目标方块...");
            MiningHelper.blockToMine.clear();
            MiningHelper.blockToMine.addAll(MiningHelper.findNearestBlocks(
                    player,
                    new java.util.HashSet<>(Collections.singletonList(Blocks.GRASS_BLOCK)),
                    neededBlockCount,
                    30
            ));

            if (MiningHelper.blockToMine.size() < neededBlockCount) {
                System.out.println("Fredobot: 即使上岸也找不到足够的垫脚石，重置！");
                resetWorld();
                return;
            }

            tntQueueGenerated = true;
            MiningHelper.dispatchNextMineAndCollectTask();
        } else {
            tntQueueGenerated = true;
        }
    }


    private BlockPos findAProperPosThen() {
        BlockPos closeToBtPos = btPos;
        for (BlockPos currentPos = btPos; currentPos.getY() < 100; currentPos = currentPos.up()) {
            if (minecraftClient.world.getBlockState(currentPos).getMaterial().isLiquid() || minecraftClient.world.getBlockState(currentPos).isAir()) {
                closeToBtPos = currentPos;
                break;
            }
        }
        return closeToBtPos;
    }

    @Override
    public String getName() {
        return "GlobalController";
    }

    @Override
    public int getPriority() {
        return 100000;
    }

    @Override
    public boolean isBusy() {
        return false;
    }

    public void resetWorld() {
        resetState();
        BotEngine.getInstance().getModule(PathExecutor.class).stop();
        pathExecutor.suspendedDestinations.clear();
        pathExecutor.currentBlacklist.clear();
        BotEngine.getInstance().getModule(MiscController.class).stopTask();
        Atum.scheduleReset();
    }

    private void resetState() {
        btPos = null;
        btStandingPos = null;
        currentState = GlobalState.IDLE;
        BlockPos coverPos = null;
        waitTicks = 0;
        debugTick = 0;
        globalInitialized = false;
        BtStuff.reset();
        treeQueueGenerated = false;
        tntQueueGenerated = false;
        MiningHelper.blockToMine.clear();
    }

    private void setBtPos() {
        int rawX = BtPosContext.btXPos;
        int rawZ = BtPosContext.btZPos;
        int x = (rawX >> 4 << 4) + 9;
        int z = (rawZ >> 4 << 4) + 9;
        for (int i = 100; i >= 20; i--) {
            BlockPos currentPos = new BlockPos(x, i, z);
            if (minecraftClient.world.getBlockState(currentPos).getBlock() == Blocks.CHEST) {
                btPos = currentPos;
                return;
            }
        }
    }
}