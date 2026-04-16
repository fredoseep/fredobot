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

    private PathExecutor pathExecutor ;
    private BlockPos btPos = null;
    private BlockPos TNTSetupPos = null;
    private BlockPos btStandingPos = null;
    private boolean globalInitialized = false;
    private boolean treeQueueGenerated = false;
    private boolean tntQueueGenerated = false;
    private static final BlockPos INVALID_BLOCKPOS= new BlockPos(-1,-1,-1);

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
        if(minecraftClient.world == null || minecraftClient.player == null) {
            waitTicks = 0;
            return;
        }
        if(!globalInitialized)initializeSettings();
        pathExecutor = BotEngine.getInstance().getModule(PathExecutor.class);

        if(currentState.missionOrder<6){
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

    public enum GlobalState{
        IDLE(0),GOING_TO_BT_STANDING_PLACE(1),DIGGING_BT(3),LOOTING_BT(4),LOOKING_FOR_TREES(5),NEXT(0x7FFFFFF);
        private final int missionOrder;
        GlobalState(int missionOrder){
            this.missionOrder = missionOrder;
        }

        public int getMissionOrder() {
            return missionOrder;
        }
    }
    private void btStaff(ClientPlayerEntity player){
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
            if(btStandingPos == null) {
                btStandingPos = findAProperPosThen();
                System.out.println("FredoBot [Debug]: 宝藏已锁定! 目标挖掘站位点计算为 -> " + btStandingPos.toShortString());
            }
        }

        debugTick++;

        switch(currentState){
            case IDLE:
                pathExecutor.setGoal(btStandingPos);
                currentState = GlobalState.GOING_TO_BT_STANDING_PLACE;
                System.out.println("FredoBot [Debug]: 状态切换 -> 开始前往挖掘站位点");
                break;

            case GOING_TO_BT_STANDING_PLACE:
                boolean isNear = PlayerHelper.isNear(player, btStandingPos, 1);
                boolean isPathBusy = pathExecutor.isBusy();

                if (debugTick % 20 == 0) {
                    double distSq = player.squaredDistanceTo(Vec3d.ofCenter(btStandingPos));
                    System.out.printf("FredoBot [Debug]: isNear: %b, isBusy: %b, distanceSquare: %.2f\n", isNear, isPathBusy, distSq);
                }

                if(isNear && !isPathBusy){
                    System.out.println("FredoBot [Debug]: 状态切换 -> 已抵达站位点，开始暴力挖掘！");
                    currentState = GlobalState.DIGGING_BT;
                }
                break;

            case DIGGING_BT:
                if(ChestBlock.isChestBlocked(minecraftClient.world, btPos)){
                    if (debugTick % 20 == 0) {
                        System.out.println("FredoBot [Debug]: 正在挖掘... 目标方块: " + btPos.up().toShortString());
                    }
                    ToolsHelper.equipBestTool(player, btPos.up(), false);
                    minecraftClient.interactionManager.updateBlockBreakingProgress(btPos.up(), Direction.UP);
                    player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                }
                else{
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
                if (debugTick % 20 == 0) {
                    System.out.println("FredoBot [Debug]: 正在等待服务器下发箱子 GUI 同步协议...");
                }

                if (player.currentScreenHandler instanceof GenericContainerScreenHandler) {
                    int syncId = player.currentScreenHandler.syncId;
                    System.out.println("FredoBot [Debug]: GUI 已就绪，SyncId = " + syncId + "，开始执行毫秒级拿取...");

                    for (int slot = 0; slot < 27; slot++) {
                        if(!InventoryHelper.BtUsefulStaff.isUseful(player.currentScreenHandler.getSlot(slot).getStack())) continue;
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
                    if(!BtStuff.evaluateBt(player)){
                        System.out.println("Fredobot: reset because bt staffs are not enough.");
                        resetWorld();
                        return;
                    }
                    currentState = GlobalState.LOOKING_FOR_TREES;
                    break;
                }
            case LOOKING_FOR_TREES:
                if(BotEngine.getInstance().getModule(MiscController.class).isBusy() || pathExecutor.isBusy()) {
                    break;
                }
                if (!MiningHelper.blockToMine.isEmpty()) {
                    dispatchNextMineAndCollectTask();
                    break;
                }
                if (!treeQueueGenerated) {
                    if(BtStuff.hasTNT){
                        if(BtStuff.itemsToCraft.contains(Items.STONE_BUTTON)) {
                            mineAndCollect(player, InventoryHelper.anyLogs, 1, 100);
                        }
                    } else {
                        mineAndCollect(player, InventoryHelper.anyLogs, 2, 100);
                    }
                    treeQueueGenerated = true;
                    break;
                }
                if (BtStuff.hasTNT) {
                    if (!tntQueueGenerated&&!pathExecutor.isBusy()) {
                        TNTSetupStaffs(player);

                        break;
                    }
                    if(pathExecutor.isBusy())break;
                    lightUpTNT(player);
                }

                // 5. 完美收工，进入下一个大状态
                System.out.println("FredoBot [Debug]: 树木/TNT前置任务全部完成，进入 NEXT！");
                currentState = GlobalState.NEXT;
                break;
        }
    }
    private void lightUpTNT(PlayerEntity player){

    }
    public void mineAndCollect(PlayerEntity player, Set<Block> targetBlocks, int totalCount,int maxRadius) {
        System.out.println("FredoBot: 开始扫描并生成 [混合方块] 挖掘拾取队列...");
        MiningHelper.blockToMine.clear(); // 清空上次的残留
        MiningHelper.blockToMine.addAll(MiningHelper.findNearestBlocks(player, targetBlocks, totalCount,maxRadius));
        if(MiningHelper.blockToMine.isEmpty()){
            System.out.println("Fredobot: reset because cant find enough blocks");
            resetWorld();
            return;
        }
        dispatchNextMineAndCollectTask(); // 启动第一个任务
    }

    /**
     * 重载 2：扫描精确配额的方块
     */
    public void mineAndCollect(PlayerEntity player, java.util.Map<Block, Integer> targetCounts,int maxRadius) {
        System.out.println("FredoBot: 开始扫描并生成 [精确配额] 挖掘拾取队列...");
        MiningHelper.blockToMine.clear();
        MiningHelper.blockToMine.addAll(MiningHelper.findNearestBlocks(player, targetCounts,maxRadius));
        if(MiningHelper.blockToMine.isEmpty()){
            System.out.println("Fredobot: reset because cant find enough blocks");
            resetWorld();
            return;
        }
        dispatchNextMineAndCollectTask(); // 启动第一个任务
    }

    /**
     * 核心派发器：从列表中取出一个方块，丢给 MiscController 执行
     */
    private void dispatchNextMineAndCollectTask() {
        if (!MiningHelper.blockToMine.isEmpty()) {
            // 拿出列表里的第一个坐标，并从列表中删掉它
            BlockPos nextTarget = MiningHelper.blockToMine.remove(0);

            // 启动 MiscController 里的挖+捡一体化分支
            BotEngine.getInstance().getModule(MiscController.class).startTask(
                    MiscController.MiscType.MINE_THE_BLOCK_AND_COLLECT_THE_DROP,
                    nextTarget
            );
        } else {
            System.out.println("FredoBot: 当前挖掘列表已全部执行完毕！");
        }
    }


    private void TNTSetupStaffs(PlayerEntity player){
        TNTSetupPos = BtStuff.calTNTSetupPos(player);
        if(TNTSetupPos.equals(INVALID_BLOCKPOS)){
            System.out.println("Fredobot: reset because no place to set up TNT");
            resetWorld();
            return;
        }

        BlockPos TNTGroundPos = minecraftClient.world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, TNTSetupPos);
        int neededBlockCount = TNTSetupPos.getY() - TNTGroundPos.getY();
        System.out.println("Fredodeg: neededBlockCount: "+ neededBlockCount);

        if(InventoryHelper.countAvailableBuildingBlocks(player).pillaringBlocks < neededBlockCount){

            if (player.isTouchingWater() || player.isSwimming()) {
                System.out.println("FredoBot [Debug]: 玩家在水中，优先寻找最近的海岸登陆...");
                BlockPos landPos = MiningHelper.findNearestLand(player);
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
                    new java.util.HashSet<>(java.util.Arrays.asList(Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.SAND, Blocks.GRAVEL)),
                    neededBlockCount,
                    30
            ));

            if(MiningHelper.blockToMine.size() < neededBlockCount){
                System.out.println("Fredobot: 即使上岸也找不到足够的垫脚石，重置！");
                resetWorld();
                return;
            }

            tntQueueGenerated = true;
            dispatchNextMineAndCollectTask();
        } else {
            pathExecutor.setGoal(TNTGroundPos);
            tntQueueGenerated = true;
        }
    }



    private BlockPos findAProperPosThen() {
        BlockPos closeToBtPos = btPos;
        for(BlockPos currentPos = btPos;currentPos.getY()<100;currentPos = currentPos.up()){
            if(minecraftClient.world.getBlockState(currentPos).getMaterial().isLiquid()||minecraftClient.world.getBlockState(currentPos).isAir()){
                closeToBtPos = currentPos;
                break;
            }
        }
        return  closeToBtPos;
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

    private void resetWorld(){
        resetState();
        BotEngine.getInstance().getModule(PathExecutor.class).stop();
        BotEngine.getInstance().getModule(MiscController.class).stopTask();
        Atum.scheduleReset();
    }

    private void resetState(){
        btPos = null;
        btStandingPos = null;
        currentState = GlobalState.IDLE;
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