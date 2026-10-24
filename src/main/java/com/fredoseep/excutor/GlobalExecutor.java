package com.fredoseep.excutor;

import com.fredoseep.Test;
import com.fredoseep.logging.GlobalStateLogger;
import com.fredoseep.preflight.EarlyResetPreflight;
import com.fredoseep.preflight.ResetHistory;
import com.fredoseep.testing.nether.NetherStateAuto;
import com.fredoseep.testing.nether.NetherTestScenario;
import com.fredoseep.utils.nether.NetherAnalyzer;
import com.fredoseep.utils.nether.bastion.BastionAnalyzer;
import net.minecraft.world.dimension.DimensionType;
import org.apache.logging.log4j.LogManager;
import com.fredoseep.behave.CraftingController;
import com.fredoseep.behave.IBotModule;
import com.fredoseep.behave.MiscController;
import com.fredoseep.utils.bt.BtStuff;
import com.fredoseep.utils.player.MiningHelper;
import com.fredoseep.utils.prenether.NetherPortalBuilding;
import com.fredoseep.utils.prenether.PreNether;
import me.voidxwalker.autoreset.Atum;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;

public class GlobalExecutor implements IBotModule {

    private final MinecraftClient minecraftClient = MinecraftClient.getInstance();
    private boolean globalInitialized = false;
    private static final boolean autoNetherOn = true;
    public  boolean isAutoNetherFinished = false;
    private int debugTick = 0;
    public GlobalState currentState = GlobalState.IDLE;

    public enum GlobalState {
        IDLE(0),
        GOING_TO_BT_STANDING_PLACE(1),
        DIGGING_BT(3),
        LOOTING_BT(4),
        LOOKING_FOR_TREES(5),
        CRAFTING(6),
        PRE_NETHER(7),
        BUILDING_TWO_BY_ONE_NETHER_PORTAL(8),
        ANALYZE_THE_BASTION(9),
        NEXT(0x7FFFFFF);

        private final int missionOrder;

        GlobalState(int missionOrder) {
            this.missionOrder = missionOrder;
        }

        public int getMissionOrder() {
            return missionOrder;
        }
    }

    public void resetWorld() {
        resetWorld("未注明分支（阶段 " + currentState.name() + "）");
    }

    public void resetWorld(String reason) {
        ResetHistory.record(reason);
        LogManager.getLogger("FredoBotReset").info("[Reset] reason={} stage={}", reason, currentState);
        GlobalStateLogger.onWorldReset(reason, currentState);
        EarlyResetPreflight.resetScheduled();
        resetState();
        PathExecutor pathExecutor = BotEngine.getInstance().getModule(PathExecutor.class);
        if (pathExecutor != null) {
            pathExecutor.stop();
            pathExecutor.suspendedDestinations.clear();
        }
        MiscController miscController = BotEngine.getInstance().getModule(MiscController.class);
        miscController.targetEntity = null;
        miscController.stopTask();

        CraftingController craftingController = BotEngine.getInstance().getModule(CraftingController.class);
        craftingController.resetStatus();
        Atum.scheduleReset();
    }

    private void resetState() {
        currentState = GlobalState.IDLE;
        isAutoNetherFinished = false;
        NetherStateAuto.reset();
        debugTick = 0;
        globalInitialized = false;
        BtStuff.reset();
        PreNether.reset();
        MiningHelper.blockToMine.clear();
        MiningHelper.currentTargetBlocks.clear();
        NetherPortalBuilding.resetState();
        NetherAnalyzer.reset();
    }

    public void startNetherTest() {
        isAutoNetherFinished = false;
        NetherStateAuto.reset();
        currentState = GlobalState.ANALYZE_THE_BASTION;
    }

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
        if(Test.currentMission!= Test.CurrentTestingMission.IDLE)return;
        if (minecraftClient.world == null || minecraftClient.player == null) {
            BtStuff.waitTicks = 0;
            return;
        }
        if (!globalInitialized) initializeSettings();

        if(autoNetherOn){
            if(!isAutoNetherFinished) {
                currentState = GlobalState.ANALYZE_THE_BASTION;
                NetherStateAuto.autoNether();
                if (!isAutoNetherFinished) return;
            }
        }

        if (currentState.getMissionOrder() < 7) {
            BtStuff.btStaff((ClientPlayerEntity) player);
        }
        if(currentState.getMissionOrder()==7){
            PreNether.preNetherStuff(player);
        }
        if(currentState.getMissionOrder()==8){
            NetherPortalBuilding.twoByOneBuild();
        }
        if(currentState.getMissionOrder()==9){
            if(!client.world.getDimension().hasCeiling()){
                System.out.println("Fredobot: waiting for player to get into the nether");
                return;
            }
            if(!client.isPaused())client.openPauseMenu(true);
            NetherAnalyzer.analyzeBastion();
        }
    }

    private void initializeSettings() {
        minecraftClient.options.viewDistance = 20;
        minecraftClient.options.entityDistanceScaling = 5.0f;
        minecraftClient.options.gamma = 5.0;
        if (minecraftClient.worldRenderer != null) {
            minecraftClient.worldRenderer.reload();
        }
        globalInitialized = true;
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


}
