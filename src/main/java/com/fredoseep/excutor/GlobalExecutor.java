package com.fredoseep.excutor;

import com.fredoseep.BtPosContext;
import com.fredoseep.behave.IBotModule;
import com.fredoseep.behave.MiscController;
import com.fredoseep.utils.PlayerHelper;
import me.voidxwalker.autoreset.Atum;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.WorldView;

public class GlobalExecutor implements IBotModule {

    private final MinecraftClient minecraftClient = MinecraftClient.getInstance();
    private BlockPos btPos = null;
    private BlockPos btStandingPos = null;

    private int waitTicks = 0;

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
        if(currentState.missionOrder<3){
            btStaff();
        }


    }

    public enum GlobalState{
        IDLE(0),GOING_TO_BT_STANDING_PLACE(1),DIGGING_BT(3);
        private final int missionOrder;
        GlobalState(int missionOrder){
            this.missionOrder = missionOrder;
        }

        public int getMissionOrder() {
            return missionOrder;
        }
    }
    private void btStaff(){
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
            if(btStandingPos==null)btStandingPos = findAProperPosThen();
            if (currentState.getMissionOrder() < 1) {
                PathExecutor pathExecutor = BotEngine.getInstance().getModule(PathExecutor.class);
                pathExecutor.setGoal(btStandingPos);
                currentState = GlobalState.GOING_TO_BT_STANDING_PLACE;
            }
            if(PlayerHelper.isNear(minecraftClient.player, btStandingPos,1))currentState = GlobalState.DIGGING_BT;


        }
    }

    private BlockPos findAProperPosThen() {
        BlockPos closeToBtPos = btPos;
        for(BlockPos currentPos = btPos;currentPos.getY()<100;currentPos = currentPos.up()){
            if(minecraftClient.world.getBlockState(currentPos).getMaterial().isLiquid()){
                closeToBtPos = currentPos;
                break;
            }
            if(currentPos==btPos.up()){
                if(minecraftClient.world.getBlockState(currentPos.east()).getMaterial().isLiquid()){
                    closeToBtPos = currentPos.east();
                    break;
                }
                else if(minecraftClient.world.getBlockState(currentPos.west()).getMaterial().isLiquid()){
                    closeToBtPos = currentPos.west();
                    break;
                }
                else if(minecraftClient.world.getBlockState(currentPos.south()).getMaterial().isLiquid()){
                    closeToBtPos = currentPos.south();
                    break;
                }
                else if(minecraftClient.world.getBlockState(currentPos.north()).getMaterial().isLiquid()){
                    closeToBtPos = currentPos.north();
                    break;
                }
            }
            if(minecraftClient.world.getBlockState(currentPos).isAir()){
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
        Atum.scheduleReset(); // 调用 Atum 进行秒级重置
    }

    private void resetState(){
        btPos = null;
        btStandingPos = null;
        currentState = GlobalState.IDLE;
        waitTicks = 0;
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