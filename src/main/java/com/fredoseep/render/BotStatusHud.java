package com.fredoseep.render;

import com.fredoseep.behave.CraftingController;
import com.fredoseep.behave.MiscController;
import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.GlobalExecutor;
import com.fredoseep.excutor.PathExecutor;
import com.fredoseep.utils.bt.BtStuff;
import com.fredoseep.utils.prenether.NetherPortalBuilding;
import com.fredoseep.utils.prenether.PreNether;
import com.fredoseep.preflight.ResetHistory;
import com.fredoseep.preflight.EarlyResetPreflight;
import net.minecraft.entity.player.PlayerEntity;
import java.util.ArrayList;
import java.util.List;

/** Read current task state on the client thread; rendering never advances a task. */
public final class BotStatusHud {
    private BotStatusHud() {}
    public static List<String> lines(BotEngine engine,PlayerEntity player) {
        PathExecutor path=engine.getModule(PathExecutor.class);
        MiscController misc=engine.getModule(MiscController.class);
        CraftingController craft=engine.getModule(CraftingController.class);
        GlobalExecutor global=engine.getModule(GlobalExecutor.class);
        boolean active=path.isBusy() || misc.isBusy() || craft.isBusy();
        List<String> lines=new ArrayList<>();
        lines.add("上次 reset 原因：" + ResetHistory.lastReason());
        if (!EarlyResetPreflight.status().isEmpty()) lines.add(EarlyResetPreflight.status());
        if(!engine.isRunning() && !active) { lines.add("[FredoBot] 已停止"); return lines; }
        lines.add("[FredoBot] "+(engine.isRunning()?"运行中":"手动任务")+" | "+mission(global.currentState));
        String task=craft.isBusy()?craft.hudStatus():misc.isBusy()?misc.hudStatus(player):stage(global.currentState);
        lines.add("任务："+task);
        if(path.isBusy()) {
            String movement=path.getCurrentState()==PathExecutor.State.CALCULATING?"计算路线":path.isPaused()?"路径已挂起":"沿路径移动";
            var route=path.getCurrentPath();int index=path.getCurrentPathIndex();
            if(path.getCurrentState()==PathExecutor.State.EXECUTING && route!=null && index<route.size())
                movement+=" "+(index+1)+"/"+route.size()+" → "+route.get(index).pos.toShortString();
            lines.add("移动："+movement);
            lines.add("移动接管："+path.movementOwner());
        }
        return lines;
    }
    private static String mission(GlobalExecutor.GlobalState state) {
        return switch(state) {
            case IDLE -> "准备任务";
            case GOING_TO_BT_STANDING_PLACE -> "前往宝藏";
            case DIGGING_BT -> "挖掘宝藏";
            case LOOTING_BT -> "搜刮宝藏";
            case LOOKING_FOR_TREES -> "采集木头和树叶";
            case CRAFTING -> "准备工具和材料";
            case PRE_NETHER -> "准备进入地狱";
            case BUILDING_TWO_BY_ONE_NETHER_PORTAL -> "搭建地狱门";
            case NEXT -> "下一阶段";
        };
    }
    private static String stage(GlobalExecutor.GlobalState state) {
        if(state==GlobalExecutor.GlobalState.PRE_NETHER && PreNether.magmaFallbackStatus()!=null)
            return PreNether.magmaFallbackStatus();
        if(state==GlobalExecutor.GlobalState.LOOKING_FOR_TREES && BtStuff.tntState!=BtStuff.TNTState.DONE)
            return switch(BtStuff.tntState) {
                case INIT -> "选择 TNT 位置";
                case CLEAR_HEAD -> "清理 TNT 上方";
                case CRAFTING -> "准备 TNT 激活物";
                case PLACE_TNT_AND_TRIGGER -> "放置并激活 TNT";
                case TAKING_COVER_MOVE -> "躲避 TNT 爆炸";
                case WAIT_EXPLOSION -> "等待 TNT 爆炸";
                case COLLECT_LOOT -> "收集爆炸掉落物";
                case DONE -> "TNT 作业完成";
            };
        if(state==GlobalExecutor.GlobalState.BUILDING_TWO_BY_ONE_NETHER_PORTAL)
            return "传送门："+switch(NetherPortalBuilding.currentTBOState) {
                case IDLE -> "准备搭建";
                case SETTING_UP_FIRST_DOOR -> "first door set up";
                case ENTERING_CLEARANCE_DOOR -> "entering clearance door";
                case CLEARING_AREA, CLEARING_SPACE -> "清理施工区域";
                case SETTING_UP_HIT_POINT -> "选择操作位置";
                case SETTING_UP_DOOR -> "放置呼吸门";
                case ADJUSTING_STANDING_POINT,POSITIONING -> "调整站位";
                case BREAKING_FIRST_MAGMA_BLOCK -> "挖掘岩浆块";
                case QUADRUPLE_GRAB -> "收集岩浆";
                case SEALING_SPACE -> "封堵施工空间";
                case FIRST_WATER_MOVE,SECOND_WATER_MOVE,THIRD_WATER_MOVE,FOURTH_WATER_MOVE -> "放水浇筑";
                case PENDING -> "等待施工条件";
                case FORCING_FLINT -> "获取燧石";
                case CRAFTING_LIGHTER -> "合成打火石";
                case LIGHTING -> "点燃传送门";
                case NEXT -> "进入下一阶段";
            };
        if(state==GlobalExecutor.GlobalState.CRAFTING)
            return switch(BtStuff.btCraftPhase) {
                case CRUSH_LOGS,CRUSH_NEW_LOGS -> "合成木板";
                case MAKE_TABLE -> "合成工作台";
                case PLACE_TABLE -> "放置工作台";
                case OPEN_TABLE,OPEN_TABLE_RETURN -> "打开工作台";
                case MAKE_STICKS_1,MAKE_STICKS_2 -> "合成木棍";
                case PROCESS_QUEUE -> "按清单合成工具";
                case CHECK_40_PLANKS -> "检查木板数量";
                case GATHER_MISSING_PLANKS -> "补充木头";
                case GATHER_MISSING_COBBLE -> "挖石料制作石工具";
                case RETURN_TO_TABLE -> "返回工作台";
                case BREAK_TABLE -> "回收工作台";
                case GETTING_LEAVES -> "补充树叶";
                case INVENTORY_MANAGEMENT -> "整理背包";
                case DONE -> "材料准备完成";
            };
        return mission(state);
    }
}
