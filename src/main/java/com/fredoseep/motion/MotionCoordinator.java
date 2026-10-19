package com.fredoseep.motion;

import com.fredoseep.Test;
import com.fredoseep.mixin.MotionClientAccess;
import com.fredoseep.motion.sim.GroundMotionExperiments;
import com.fredoseep.motion.sim.FusionBoatRuntime;
import com.fredoseep.motion.sim.RuntimeGroundMotion;
import com.fredoseep.excutor.BotEngine;
import com.fredoseep.logging.GlobalStateLogger;
import com.fredoseep.preflight.EarlyResetPreflight;
import com.fredoseep.testing.CreativeWorldTestRunner;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.LiteralText;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Client-thread-only exclusive lease. Existing modules keep their controls while no plan owns it. */
public final class MotionCoordinator {
    public record Result(String id,MotionSequence.Phase phase,int completedFrames,String reason) {}
    private static volatile MotionSequence sequence;
    private static ClientWorld world;
    private static ClientPlayerEntity player;
    private static Input previousInput;
    private static PlannedInput plannedInput;
    private static Consumer<Result> callback;
    private static Result lastResult;
    private static int pendingAge=-1;
    private static boolean diagnostic,previousAutoJump,previousAutoJumpEnabled;
    private static double maxPositionError,maxVelocityError;
    public static boolean submitDiagnostic(MotionPlan plan,Consumer<Result> onFinished) {
        boolean accepted=submit(plan,onFinished);
        if(accepted)diagnostic=true;
        return accepted;
    }
    private MotionCoordinator() {}

    public static boolean ownsControl(){return sequence!=null && sequence.ownsControl();}
    public static boolean blocksBot(){return ownsControl() || RuntimeGroundMotion.ownsTask();}
    public static String hudStatus() {
        return ownsControl()?(RuntimeGroundMotion.ownsTask()?"自动地面执行：":"动作接管：")+sequence.phase()+" "+sequence.index()+"/"+sequence.plan().frames().size():RuntimeGroundMotion.hudStatus();
    }

    /** Call on the client thread after planning, with a side-effect-free callback for completion/replan. */
    public static boolean submit(MotionPlan plan,Consumer<Result> onFinished) {
        return submitInternal(plan,onFinished,false);
    }
    public static boolean submitForPath(MotionPlan plan,Consumer<Result> onFinished){
        if(!RuntimeGroundMotion.authorizes(plan))return false;
        return submitInternal(plan,onFinished,true);
    }
    private static boolean submitInternal(MotionPlan plan,Consumer<Result> onFinished,boolean runtime){
        MinecraftClient client=MinecraftClient.getInstance();
        if(!client.isOnThread())throw new IllegalStateException("Motion submission must use the client thread");
        if(!runtime && RuntimeGroundMotion.ownsTask())return false;
        if(ownsControl() || client.world==null || client.player==null || client.getServer()==null
                || CreativeWorldTestRunner.isActive() || EarlyResetPreflight.blocking(client)
                || Test.currentMission!=Test.CurrentTestingMission.IDLE || !supported(client.player)
                || client.player.isUsingItem())return false;
        sequence=new MotionSequence(plan);MotionDebug.acquire(client.world,plan);world=client.world;player=client.player;callback=onFinished;
        previousInput=player.input;plannedInput=new PlannedInput();plannedInput.copyHistory(previousInput);
        player.input=plannedInput;pendingAge=-1;maxPositionError=maxVelocityError=0;
        previousAutoJump=client.options.autoJump;client.options.autoJump=false;
        previousAutoJumpEnabled=player.isAutoJumpEnabled();((MotionClientAccess)player).fredobot$setAutoJumpEnabled(false);
        releaseKeys(client);event("motion_acquired",null);
        return true;
    }

    /** Prevent queued attack/use clicks from leaking into a movement-only lease. */
    public static void beforeInputEvents() {
        if(blocksBot())releaseKeys(MinecraftClient.getInstance());
    }

    /** Watchdog also runs while the pause menu prevents player ticks. */
    public static void watchdog(MinecraftClient client) {
        GroundMotionExperiments.watchdog(client);
        RuntimeGroundMotion.watchdog(client);
        MotionDebug.visible(client.world);
        if(!ownsControl())return;
        if(CreativeWorldTestRunner.isActive() || EarlyResetPreflight.blocking(client)
                || Test.currentMission!=Test.CurrentTestingMission.IDLE){cancel("OTHER_EXCLUSIVE_TASK");return;}
        if(client.world!=world || client.player!=player){cancel("WORLD_OR_PLAYER_CHANGED");return;}
        if(!supported(player)){cancel("UNSUPPORTED_PLAYER_STATE");return;}
        if(player.input!=plannedInput){cancel("INPUT_OWNER_REPLACED");return;}
        if(client.currentScreen!=null || client.isPaused()) {
            if(sequence.phase()!=MotionSequence.Phase.SUSPENDED) {
                sequence.suspend();pendingAge=-1;plannedInput.clear();releaseKeys(client);
                event("motion_suspended",null);
            }
        }
    }

    /** Player tick HEAD, before vanilla reads input and advances physics. */
    public static void beforePlayerTick(ClientPlayerEntity current) {
        MinecraftClient client=MinecraftClient.getInstance();watchdog(client);
        if(!ownsControl() || current!=player || client.currentScreen!=null || client.isPaused())return;
        MotionInput input=sequence.beforeTick(capture(player));
        if(input==null){if(!ownsControl())finish();return;}
        plannedInput.setFrame(input);player.yaw=input.yaw();player.pitch=input.pitch();
        client.options.keySprint.setPressed(input.sprint());pendingAge=player.age;
    }

    /** Player tick TAIL, after that exact input has produced a real physics result. */
    public static void afterPlayerTick(ClientPlayerEntity current) {
        FusionBoatRuntime.afterPlayerTick(current);
        if(!ownsControl() || current!=player || pendingAge<0)return;
        pendingAge=-1;
        MotionState actual=capture(player),expected=sequence.plan().frames().get(sequence.index()).after();
        maxPositionError=Math.max(maxPositionError,expected.positionError(actual));
        maxVelocityError=Math.max(maxVelocityError,expected.velocityError(actual));
        sequence.afterTick(actual);MotionDebug.advance(sequence.index());
        if(!sequence.ownsControl())finish();
    }
    public static void cancel(String reason) {
        MinecraftClient client=MinecraftClient.getInstance();
        if(!client.isOnThread()){client.execute(()->cancel(reason));return;}
        GroundMotionExperiments.cancel(reason);
        RuntimeGroundMotion.cancel(reason);
        if(!ownsControl())return;
        sequence.cancel(reason);finish();
    }

    public static MotionState capture(ClientPlayerEntity current) {
        Vec3d velocity=current.getVelocity();
        return new MotionState(current.getX(),current.getY(),current.getZ(),velocity.x,velocity.y,velocity.z,current.isOnGround());
    }
    private static boolean supported(ClientPlayerEntity current) {
        return current.isAlive() && !current.hasVehicle() && (!current.isTouchingWater() || RuntimeGroundMotion.waterAllowed())
                && !current.isInLava() && !current.isFallFlying() && (!current.isClimbing() || RuntimeGroundMotion.waterAllowed())
                && !current.abilities.flying && !current.isSpectator();
    }
    private static void releaseKeys(MinecraftClient client) {
        if(client.options==null)return;
        for(KeyBinding key:List.of(client.options.keyForward,client.options.keyBack,client.options.keyLeft,
                client.options.keyRight,client.options.keyJump,client.options.keySneak,client.options.keySprint,
                client.options.keyAttack,client.options.keyUse)) {
            key.setPressed(false);
            while(key.wasPressed()) {}
        }
    }
    private static void finish() {
        MinecraftClient client=MinecraftClient.getInstance();
        Result result=new Result(sequence.plan().id(),sequence.phase(),sequence.index(),sequence.reason());
        lastResult=result;MotionDebug.finish(sequence.deviation());
        event(sequence.phase()==MotionSequence.Phase.FAILED?"motion_deviation_dump":"motion_released",sequence.deviation());
        if(plannedInput!=null)plannedInput.clear();
        if(player!=null && player.input==plannedInput) {
            player.input=previousInput;((MotionClientAccess)player).fredobot$setAutoJumpEnabled(previousAutoJumpEnabled);
        }
        releaseKeys(client);client.options.autoJump=previousAutoJump;pendingAge=-1;
        Consumer<Result> completed=callback;
        boolean notify=diagnostic;
        sequence=null;world=null;player=null;previousInput=null;plannedInput=null;callback=null;diagnostic=false;
        if(notify && client.player!=null)client.player.sendMessage(new LiteralText("[Motion] "+result.phase()+" frames="+result.completedFrames()+" "+result.reason()),false);
        if(completed!=null)client.execute(()->{
            try {completed.accept(result);}
            catch(RuntimeException failure){System.err.println("FredoBot motion completion callback failed: "+failure);}
        });
    }
    private static Map<String,Object> state(MotionState state) {
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("pos",List.of(state.x(),state.y(),state.z()));
        data.put("velocity",List.of(state.vx(),state.vy(),state.vz()));data.put("on_ground",state.grounded());return data;
    }
    private static void event(String type,MotionSequence.Deviation deviation) {
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("input_profile",MotionPlanStats.summarize(sequence.plan()));
        data.put("plan_id",sequence.plan().id());data.put("phase",sequence.phase().name());
        data.put("completed_frames",sequence.index());data.put("total_frames",sequence.plan().frames().size());
        data.put("reason",sequence.reason());
        data.put("max_position_error",maxPositionError);data.put("max_velocity_error",maxVelocityError);
        if(player!=null){data.put("actual",state(capture(player)));data.put("pose",player.getPose().name());
            data.put("yaw",player.yaw);data.put("pitch",player.pitch);}
        if(deviation!=null) {
            data.put("boundary",deviation.boundary());data.put("frame",deviation.frame());
            data.put("expected",state(deviation.expected()));data.put("actual",state(deviation.actual()));
            data.put("position_error",deviation.positionError());data.put("velocity_error",deviation.velocityError());
            data.put("position_tolerance",sequence.plan().positionTolerance());data.put("velocity_tolerance",sequence.plan().velocityTolerance());
            data.put("input",sequence.plan().frames().get(deviation.frame()).input().toString());
        }
        try {GlobalStateLogger.logCustomEvent(type,data);}
        catch(RuntimeException failure){System.err.println("FredoBot motion log failure: "+failure);}

    }

    /** Deliberately stationary: verifies takeover/tick timing without unvalidated movement physics. */
    public static boolean command(String message) {
        if(RuntimeGroundMotion.command(message))return true;
        if(GroundMotionExperiments.command(message))return true;
        if(!message.equals("#fredomotion") && !message.startsWith("#fredomotion "))return false;
        MinecraftClient client=MinecraftClient.getInstance();
        String action=message.substring("#fredomotion".length()).trim();
        if(action.equals("cancel"))cancel("MANUAL_CANCEL");
        else if(action.equals("probe")) {
            if(client.player==null || !client.player.abilities.creativeMode || BotEngine.getInstance().isRunning()
                    || !client.player.isOnGround() || horizontalSpeedSquared(client.player.getVelocity())>.000001) {
                say(client,"请停止机器人，在创造模式的平坦地面静止后运行自检。");return true;
            }
            MotionState initial=capture(client.player);
            List<MotionPlan.Frame> frames=new ArrayList<>();
            MotionInput idle=new MotionInput(false,false,false,false,false,false,false,client.player.yaw,client.player.pitch);
            for(int i=0;i<6;i++)frames.add(new MotionPlan.Frame(idle,initial));
            if(submit(new MotionPlan("handoff-probe-"+client.player.age,initial,frames,.015,.015),null)) {
                diagnostic=true;say(client,"已接管：保持 6 个物理 tick；结束结果写入全局日志。");
            } else say(client,"接管被拒绝：已有任务或当前状态不适用。");
        } else say(client,ownsControl()?sequence.plan().id()+" "+sequence.phase()+" "+sequence.index()+"/"+sequence.plan().frames().size():"当前由原控制器管理；上次="+lastResult+"。命令：probe / walk / sprint / jump / goto / cancel");
        return true;
    }
    private static double horizontalSpeedSquared(Vec3d velocity){return velocity.x*velocity.x+velocity.z*velocity.z;}
    private static void say(MinecraftClient client,String text) {
        if(client.player!=null)client.player.sendMessage(new LiteralText("[Motion] "+text),false);
    }
}
