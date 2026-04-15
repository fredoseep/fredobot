package com.fredoseep.excutor;

import com.fredoseep.BtPosContext;
import com.fredoseep.behave.IBotModule;
import com.fredoseep.behave.MiscController;
import com.fredoseep.utils.bt.BtStuff;
import com.fredoseep.utils.player.InventoryHelper;
import com.fredoseep.utils.player.PlayerHelper;
import com.fredoseep.utils.player.ToolsHelper;
import me.voidxwalker.autoreset.Atum;
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

public class GlobalExecutor implements IBotModule {

    private final MinecraftClient minecraftClient = MinecraftClient.getInstance();

    private PathExecutor pathExecutor ;
    private BlockPos btPos = null;
    private BlockPos TNTSetupPos = null;
    private BlockPos btStandingPos = null;
    private boolean globalInitialized = false;

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
                    if(!BtStuff.evaluateBt(player))resetWorld();
                    currentState = GlobalState.LOOKING_FOR_TREES;
                    break;
                }
            case LOOKING_FOR_TREES:
                if(BtStuff.hasTNT&&!BtStuff.itemsToCraft.contains(Items.STONE_BUTTON)){
                    TNTSetupPos = BtStuff.calTNTSetupPos(player);
                    pathExecutor.setGoal(TNTSetupPos);
                }
                break;
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