package com.fredoseep.motion.sim;

import com.fredoseep.excutor.BotEngine;
import com.fredoseep.logging.GlobalStateLogger;
import com.fredoseep.mixin.MotionClientAccess;
import com.fredoseep.mixin.MotionLivingAccess;
import com.fredoseep.mixin.MotionPlayerAccess;
import com.fredoseep.motion.GroundSchedules;
import com.fredoseep.motion.GroundRouteSearch;
import com.fredoseep.motion.MotionDebug;
import com.fredoseep.motion.LocalGroundSearch;
import com.fredoseep.motion.MotionCoordinator;
import com.fredoseep.motion.MotionInput;
import com.fredoseep.motion.MotionPlan;
import com.fredoseep.motion.MotionState;
import com.fredoseep.motion.MotionSequence;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.LiteralText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Opt-in creative-world conformance probes. Simulation is sliced on the world-owning client thread. */
public final class GroundMotionExperiments {
    private static Job job;
    private GroundMotionExperiments() {}
    private static final class Job {
        final String kind;
        final int ticks;
        final ClientWorld world;
        final ClientPlayerEntity player;
        final long started=System.currentTimeMillis();
        GameMenuScreen pause;
        long pauseObserved;
        GroundAgent agent;
        MotionState initial;
        List<MotionInput> inputs;
        final List<MotionPlan.Frame> frames=new ArrayList<>();
        String id;
        LocalGroundSearch.Goal goal;
        GroundRouteSearch search;
        long lastProgress;
        Job(String kind,int ticks,MinecraftClient client){this.kind=kind;this.ticks=ticks;world=client.world;player=client.player;}
    }
    public static boolean pending(){return job!=null;}
    public static void watchdog(MinecraftClient client) {
        if(job!=null && (client.world!=job.world || client.player!=job.player || BotEngine.getInstance().isRunning()))
            cancel("WORLD_PLAYER_OR_BOT_CHANGED");
    }
    public static String hudStatus(){
        if(job==null)return null;
        if(job.search!=null)return "地形搜索："+job.search.phase()+" 节点="+job.search.expanded()
                +" 模拟="+job.search.simulatedTicks()+" 已验证="+job.search.verifiedFrames()+" 节省="+job.search.savedFrames()+"tick";
        return "运动预测："+job.kind+" "+job.frames.size()+"/"+(job.inputs==null?"?":job.inputs.size());
    }
    public static boolean command(String message) {
        if(message.equals("#fredomotion cancel")){cancel("MANUAL_CANCEL");return false;}
        if(!message.startsWith("#fredomotion "))return false;
        String[] parts=message.trim().split("\\s+");
        if(parts.length<2 || !List.of("walk","sprint","jump","goto").contains(parts[1]))return false;
        MinecraftClient client=MinecraftClient.getInstance();
        try {
            boolean local=parts[1].equals("goto");
            if(local?parts.length!=5:parts.length>3)throw new IllegalArgumentException("用法：#fredomotion goto x y z，或 walk|sprint|jump [ticks]");
            int ticks=local?0:parts.length==3?Integer.parseInt(parts[2]):parts[1].equals("jump")?12:10;
            if(!local && (ticks<1 || ticks>40))throw new IllegalArgumentException("ticks 必须在 1..40");
            if(job!=null || MotionCoordinator.ownsControl())throw new IllegalArgumentException("已有运动预测或执行任务");
            if(client.getServer()==null || client.player==null || !client.player.abilities.creativeMode
                    || BotEngine.getInstance().isRunning())throw new IllegalArgumentException("请先停止机器人，在单机创造模式测试");
            GroundSafety.requireStart(client.player);
            if(Math.hypot(client.player.getVelocity().x,client.player.getVelocity().z)>.01)
                throw new IllegalArgumentException("请先在地面静止再开始测试");
            Job request=new Job(parts[1],ticks,client);
            if(local) {
                request.goal=new LocalGroundSearch.Goal(coordinate(parts[2],client.player.getX())+.5,
                        coordinate(parts[3],client.player.getY()),coordinate(parts[4],client.player.getZ())+.5);
                if(Math.hypot(request.goal.x()-client.player.getX(),request.goal.z()-client.player.getZ())>24
                        || request.goal.y()-client.player.getY()>8.01 || request.goal.y()-client.player.getY()<-8.01)
                    throw new IllegalArgumentException("地形测试范围：水平 24 格以内，高差 ±8 格");
                GroundSafety.requireGoal(client.player,request.goal);
            }
            job=request;MotionDebug.begin(client.world,request.goal);say(local?"开始局部运动搜索；只执行已完整模拟且能稳定到达目标的路线":"准备预测；完整轨迹验证后才会实际执行，请给前方留出移动空间");
        } catch(IllegalArgumentException error){say(error.getMessage());}
        return true;
    }
    /** Render callback keeps working during vanilla F3+Esc pause. No worker thread touches ClientWorld. */
    public static void pump(MinecraftClient client) {
        Job task=job;if(task==null)return;
        try {
            if(client.player!=task.player || client.world!=task.world || BotEngine.getInstance().isRunning()) {
                cancel("WORLD_PLAYER_OR_BOT_CHANGED");return;
            }
            if(System.currentTimeMillis()-task.started>(task.goal==null?15000:100000))throw new IllegalArgumentException("运动预测等待超时");
            if(task.pause==null) {
                if(client.currentScreen!=null)return;
                task.pause=new GameMenuScreen(false);client.openScreen(task.pause);return;
            }
            if(client.currentScreen!=task.pause){cancel("OWNED_PAUSE_CLOSED_OR_REPLACED");return;}
            if(!client.isPaused())return;
            if(task.pauseObserved==0){task.pauseObserved=System.currentTimeMillis();return;}
            if(System.currentTimeMillis()-task.pauseObserved<150)return;
            if(task.initial==null) {
                GroundSafety.requireStart(task.player);
                if(((MotionClientAccess)task.player).fredobot$getAutoJumpTicks()!=0)
                    throw new IllegalArgumentException("自动跳跃仍有待执行输入，请静止后重试");
                task.initial=MotionCoordinator.capture(task.player);
                task.id="ground-"+task.kind+"-"+task.player.age+"-"+System.nanoTime();
                if(task.goal==null) {
                    task.agent=new GroundAgent(client,task.player);
                    task.inputs=GroundSchedules.probe(task.kind,task.ticks,task.player.yaw,task.player.pitch);
                } else {
                    GroundSafety.requireGoal(task.player,task.goal);
                    task.search=new GroundRouteSearch(task.id,new GroundAgent(client,task.player),task.goal,new GroundTerrain(task.player));
                    task.lastProgress=System.currentTimeMillis();
                }
                record(task.goal==null?"motion_prediction_started":"motion_local_search_started",task,Map.of("initial_hidden_state",hidden(task.player)));
            }
            long deadline=System.nanoTime()+3_000_000;
            if(task.search!=null) {
                do {task.search.step();}while(!task.search.finished() && System.nanoTime()<deadline);
                MotionDebug.search(task.search.route(),task.search.preview());
                if(System.currentTimeMillis()-task.lastProgress>=1000) {
                    record("motion_local_search_progress",task,task.search.metrics());task.lastProgress=System.currentTimeMillis();
                }
                if(!task.search.finished())return;
                record("motion_local_search_done",task,task.search.metrics());
                if(task.search.result()==null)throw new IllegalArgumentException("没有完整可执行路线："+task.search.termination());
                task.frames.addAll(task.search.result().frames());
            } else {
                do {
                    MotionInput input=task.inputs.get(task.frames.size());
                    task.frames.add(new MotionPlan.Frame(input,task.agent.step(input)));
                } while(task.frames.size()<task.inputs.size() && System.nanoTime()<deadline);
                if(task.frames.size()<task.inputs.size())return;
            }
            MotionState end=task.frames.get(task.frames.size()-1).after();
            if(!end.grounded() || Math.hypot(end.vx(),end.vz())>.02)
                throw new IllegalArgumentException("候选动作没有稳定落地/停止，取消执行");
            MotionPlan plan=new MotionPlan(task.id,task.initial,task.frames,.002,.002);
            Map<String,Object> extra=new LinkedHashMap<>();extra.put("expected_end",end.toString());
            extra.put("frames",task.frames.size());extra.put("model","vanilla-1.16.1-client-tickMovement");
            record("motion_prediction_ready",task,extra);
            job=null;
            if(!MotionCoordinator.submitDiagnostic(plan,result -> {
                boolean reached=task.goal==null || task.goal.reached(MotionCoordinator.capture(task.player));
                record(task.goal==null?"motion_conformance_result":"motion_local_result",task,
                        Map.of("phase",result.phase().name(),"completed_frames",result.completedFrames(),"reason",result.reason(),"goal_reached",reached));
                if(task.goal!=null)say(reached && result.phase()==MotionSequence.Phase.COMPLETE?"已稳定到达局部目标":"局部执行未完成目标，详情见全局日志");
            })) {
                record("motion_prediction_rejected",task,Map.of("reason","EXECUTOR_UNAVAILABLE"));say("执行器拒绝接管");
            }
            if(client.currentScreen==task.pause)client.openScreen(null);
        } catch(RuntimeException error) {
            List<String> stack=new ArrayList<>();
            for(StackTraceElement frame:error.getStackTrace()){if(stack.size()>=8)break;stack.add(frame.toString());}
            record("motion_prediction_failed",task,Map.of("reason",String.valueOf(error),"stack",stack,
                    "search",task.search==null?Map.of():task.search.metrics()));
            say("预测终止："+error.getMessage());job=null;
            if(client.currentScreen==task.pause)client.openScreen(null);
        }
    }
    public static void cancel(String reason) {
        Job task=job;if(task==null)return;job=null;
        if(task.search!=null)task.search.cancel();
        record("motion_prediction_cancelled",task,Map.of("reason",reason));
        MinecraftClient client=MinecraftClient.getInstance();
        if(client.currentScreen==task.pause)client.openScreen(null);
    }
    private static int coordinate(String text,double current) {
        try {
            long value=text.startsWith("~")?(long)Math.floor(current)+(text.length()==1?0:Integer.parseInt(text.substring(1))):Long.parseLong(text);
            if(value < -29999984 || value > 29999984)throw new NumberFormatException();
            return (int)value;
        } catch(NumberFormatException error){throw new IllegalArgumentException("坐标应为整数或 ~相对整数："+text);}
    }
    private static Map<String,Object> hidden(ClientPlayerEntity player) {
        MotionClientAccess input=(MotionClientAccess)player;
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("jump_cooldown",((MotionLivingAccess)player).fredobot$getJumpCooldown());
        data.put("sprint_window",input.fredobot$getSprintWindow());
        data.put("flight_window",((MotionPlayerAccess)player).fredobot$getFlightWindow());
        data.put("sprinting",player.isSprinting());data.put("flying_speed",player.flyingSpeed);
        data.put("movement_speed",player.getMovementSpeed());data.put("food",player.getHungerManager().getFoodLevel());
        data.put("previous_forward",player.input.movementForward);data.put("previous_jump",player.input.jumping);
        data.put("previous_sneak",player.input.sneaking);return data;
    }
    private static void record(String event,Job task,Map<String,Object> extra) {
        Map<String,Object> data=new LinkedHashMap<>();data.put("kind",task.kind);data.put("plan_id",task.id);
        data.put("predicted_frames",task.frames.size());
        if(task.goal!=null)data.put("goal",List.of(task.goal.x(),task.goal.y(),task.goal.z()));
        data.putAll(extra);
        try {GlobalStateLogger.logCustomEvent(event,data);}
        catch(RuntimeException error){System.err.println("FredoBot prediction log failed: "+error);}
    }
    private static void say(String message) {
        MinecraftClient client=MinecraftClient.getInstance();
        if(client.player!=null)client.player.sendMessage(new LiteralText("[Motion] "+message),false);
    }
}
