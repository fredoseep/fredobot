package com.fredoseep.motion.sim;

import com.fredoseep.Test;
import com.fredoseep.excutor.BotEngine;
import com.fredoseep.logging.GlobalStateLogger;
import com.fredoseep.mixin.MotionClientAccess;
import com.fredoseep.motion.GroundRouteSearch;
import com.fredoseep.motion.LocalGroundSearch;
import com.fredoseep.motion.MotionCoordinator;
import com.fredoseep.motion.MotionDebug;
import com.fredoseep.motion.MotionInput;
import com.fredoseep.motion.MotionPlan;
import com.fredoseep.motion.MotionSequence;
import com.fredoseep.motion.MotionState;
import com.fredoseep.preflight.EarlyResetPreflight;
import com.fredoseep.testing.CreativeWorldTestRunner;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.LiteralText;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Production path lease: quiesce -> vanilla pause -> full prediction -> exclusive execution.
 * World reads/simulation stay on the client thread; render callbacks slice work while paused. */
public final class RuntimeGroundMotion {
    public enum Status {COMPLETE,FAILED,CANCELLED}
    public record Outcome(Status status,String reason,int frames) {}
    private enum Phase {SETTLING,PAUSING,SEARCHING,SUBMITTING,EXECUTING}
    private static Task task;
    private static boolean enabled=true;
    private static long settingsRevision;
    private RuntimeGroundMotion(){}
    private static final class Task {
        final ClientWorld world;
        final ClientPlayerEntity player;
        final LocalGroundSearch.Goal goal;
        final Set<GroundRouteSearch.Cell> blacklist;
        final BooleanSupplier current;
        final Consumer<Outcome> completed;
        final long started=System.currentTimeMillis();
        final int startAge;
        Phase phase=Phase.SETTLING;
        int lastAge=-1,stable;
        boolean faced;
        GameMenuScreen pause;
        long pauseObserved,lastLog;
        GroundRouteSearch search;
        MotionPlan plan;
        Task(MinecraftClient c,LocalGroundSearch.Goal goal,Set<GroundRouteSearch.Cell> blacklist,BooleanSupplier current,Consumer<Outcome> completed){
            world=c.world;player=c.player;startAge=player.age;this.goal=goal;this.blacklist=Set.copyOf(blacklist);this.current=current;this.completed=completed;
        }
    }
    public static boolean enabled(){return enabled;}
    public static long settingsRevision(){return settingsRevision;}
    public static boolean command(String message){
        if(FusionRuntime.command(message))return true;
        if(!message.equals("#fredomotion auto") && !message.startsWith("#fredomotion auto "))return false;
        String[] words=message.trim().split("\\s+");String mode=words.length==2?"status":words.length==3?words[2]:"invalid";
        MinecraftClient c=MinecraftClient.getInstance();
        String reply;
        if(mode.equals("on") || mode.equals("off")){
            enabled=mode.equals("on");settingsRevision++;
            if(!enabled && task!=null)MotionCoordinator.cancel("GROUND_AUTO_DISABLED");
            reply="自动地面接管："+(enabled?"开启":"关闭，沿用原寻路执行");
        }else if(mode.equals("status"))reply="自动地面接管："+(enabled?"开启":"关闭");
        else reply="用法：#fredomotion auto on|off|status";
        if(c.player!=null)c.player.sendMessage(new LiteralText("[Motion] "+reply),false);
        return true;
    }
    public static boolean ownsTask(){return task!=null || FusionRuntime.ownsTask() || FusionBoatRuntime.ownsTask();}
    public static boolean waterAllowed(){return FusionRuntime.ownsTask() || FusionBoatRuntime.ownsTask();}
    public static String hudStatus(){
        Task t=task;if(t==null)return FusionBoatRuntime.ownsTask()?FusionBoatRuntime.hudStatus():FusionRuntime.hudStatus();
        return t.search==null?"自动地面接管："+t.phase:"自动地面接管："+t.search.phase()+" 模拟="+t.search.simulatedTicks()+" 节省="+t.search.savedFrames()+"tick";
    }
    public static boolean begin(LocalGroundSearch.Goal goal,Set<GroundRouteSearch.Cell> blacklist,BooleanSupplier current,Consumer<Outcome> completed){
        MinecraftClient c=MinecraftClient.getInstance();
        if(enabled && task==null && FusionRuntime.enabled())return FusionRuntime.begin(goal,blacklist,current,completed);
        if(!enabled || !c.isOnThread() || task!=null || MotionCoordinator.ownsControl() || GroundMotionExperiments.pending()
                || c.world==null || c.player==null || c.getServer()==null || c.currentScreen!=null || c.isPaused()
                || !BotEngine.getInstance().isRunning() || CreativeWorldTestRunner.isActive()
                || EarlyResetPreflight.blocking(c) || Test.currentMission!=Test.CurrentTestingMission.IDLE || !current.getAsBoolean())return false;
        try {GroundSafety.requireStart(c.player);GroundSafety.requireGoal(c.player,goal);}
        catch(IllegalArgumentException unsupported){return false;}
        task=new Task(c,goal,blacklist,current,completed);MotionDebug.begin(c.world,goal);
        log("ground_handoff_started",task,Map.of());return true;
    }
    /** Only the owned job may use the production submit entry point. */
    public static boolean authorizes(MotionPlan plan){
        Task t=task;return FusionBoatRuntime.authorizes(plan) || FusionRuntime.authorizes(plan) || t!=null && t.phase==Phase.SUBMITTING && t.plan==plan && t.current.getAsBoolean();
    }
    public static void watchdog(MinecraftClient c){
        FusionBoatRuntime.watchdog(c);
        FusionRuntime.watchdog(c);
        Task t=task;if(t==null)return;
        if(!enabled || c.world!=t.world || c.player!=t.player || !BotEngine.getInstance().isRunning()
                || !t.current.getAsBoolean() || CreativeWorldTestRunner.isActive() || EarlyResetPreflight.blocking(c)
                || Test.currentMission!=Test.CurrentTestingMission.IDLE){MotionCoordinator.cancel("GROUND_OWNER_CHANGED");return;}
        if(!t.player.isAlive()){MotionCoordinator.cancel("GROUND_PLAYER_DIED");return;}
        if(t.phase==Phase.EXECUTING)return;
        if(System.currentTimeMillis()-t.started>100_000){finish(t,Status.FAILED,"GROUND_JOB_TIMEOUT",0);return;}
        if(c.currentScreen!=null && c.currentScreen!=t.pause){MotionCoordinator.cancel("GROUND_SCREEN_REPLACED");return;}
        if(t.phase==Phase.SETTLING){
            if(t.player.age-t.startAge>40){finish(t,Status.FAILED,"GROUND_SETTLE_TIMEOUT",0);return;}
            if(t.lastAge==t.player.age)return;t.lastAge=t.player.age;
            try {GroundSafety.requireStart(t.player);}
            catch(IllegalArgumentException unsupported){finish(t,Status.FAILED,"GROUND_START_CHANGED: "+unsupported.getMessage(),0);return;}
            if(Math.hypot(t.player.getVelocity().x,t.player.getVelocity().z)<=.01
                    && ((MotionClientAccess)t.player).fredobot$getAutoJumpTicks()==0)t.stable++;else t.stable=0;
            if(t.stable>=3)t.phase=Phase.PAUSING;
        }
    }
    public static void pump(MinecraftClient c){
        FusionBoatRuntime.pump(c);
        FusionRuntime.pump(c);
        watchdog(c);Task t=task;if(t==null || t.phase==Phase.SETTLING || t.phase==Phase.EXECUTING)return;
        try {
            if(t.pause==null){
                if(!t.faced){
                    double dx=t.goal.x()-t.player.getX(),dz=t.goal.z()-t.player.getZ();
                    double dy=t.goal.y()+.9-t.player.getEyeY();
                    if(Math.hypot(dx,dz)>.001)t.player.yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));
                    t.player.pitch=(float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz)));
                    t.faced=true;log("ground_handoff_facing",t,Map.of());return;
                }
                t.pause=new GameMenuScreen(false);c.openScreen(t.pause);return;
            }
            if(c.currentScreen!=t.pause){MotionCoordinator.cancel("GROUND_PAUSE_CLOSED");return;}
            if(!c.isPaused())return;
            if(t.pauseObserved==0){t.pauseObserved=System.currentTimeMillis();return;}
            if(System.currentTimeMillis()-t.pauseObserved<150)return;
            if(t.search==null){
                GroundSafety.requireStart(t.player);GroundSafety.requireGoal(t.player,t.goal);
                GroundTerrain terrain=new GroundTerrain(t.player);
                GroundRouteSearch.Terrain allowed=new GroundRouteSearch.Terrain(){
                    public boolean stand(GroundRouteSearch.Cell cell){return !t.blacklist.contains(cell) && terrain.stand(cell);}
                    public boolean walkClear(GroundRouteSearch.Cell from,GroundRouteSearch.Cell to){return terrain.walkClear(from,to);}
                };
                t.search=new GroundRouteSearch("runtime-ground-"+System.nanoTime(),new GuardedAgent(new GroundAgent(c,t.player),t.blacklist),t.goal,allowed);
                t.phase=Phase.SEARCHING;t.lastLog=System.currentTimeMillis();
            }
            long deadline=System.nanoTime()+3_000_000;
            do {t.search.step();}while(!t.search.finished() && System.nanoTime()<deadline);
            MotionDebug.search(t.search.route(),t.search.preview());
            if(System.currentTimeMillis()-t.lastLog>=1000){log("ground_handoff_progress",t,t.search.metrics());t.lastLog=System.currentTimeMillis();}
            if(!t.search.finished())return;
            log("ground_handoff_search_done",t,t.search.metrics());
            t.plan=t.search.result();
            if(t.plan==null){finish(t,Status.FAILED,t.search.termination(),0);return;}
            if(!t.current.getAsBoolean()){MotionCoordinator.cancel("GROUND_OWNER_CHANGED");return;}
            t.phase=Phase.SUBMITTING;
            if(!MotionCoordinator.submitForPath(t.plan,result->{
                if(task!=t)return;
                boolean reached=result.phase()==MotionSequence.Phase.COMPLETE && t.goal.reached(MotionCoordinator.capture(t.player));
                finish(t,reached?Status.COMPLETE:Status.FAILED,result.reason().isEmpty()?result.phase().name():result.reason(),result.completedFrames());
            })){finish(t,Status.FAILED,"GROUND_SUBMIT_REJECTED",0);return;}
            t.phase=Phase.EXECUTING;if(c.currentScreen==t.pause)c.openScreen(null);
        }catch(RuntimeException failure){
            log("ground_handoff_prediction_error",t,Map.of("error",failure.toString(),"stack",Arrays.stream(failure.getStackTrace()).limit(8).map(Object::toString).toList()));
            if(MotionCoordinator.ownsControl())MotionCoordinator.cancel("GROUND_PREDICTION_ERROR: "+failure);
            else finish(t,Status.FAILED,"GROUND_PREDICTION_ERROR: "+failure,0);
        }
    }
    /** Called by MotionCoordinator.cancel, which also releases any active input sequence. */
    public static void cancel(String reason){
        FusionBoatRuntime.cancel(reason);
        FusionRuntime.cancel(reason);
        Task t=task;if(t==null)return;
        if(t.search!=null && !t.search.finished())t.search.cancel();
        finish(t,Status.CANCELLED,reason,0);
    }
    private static void finish(Task t,Status status,String reason,int frames){
        if(task!=t)return;task=null;
        MinecraftClient c=MinecraftClient.getInstance();
        if(c.currentScreen==t.pause && t.pause!=null)c.openScreen(null);
        log("ground_handoff_finished",t,Map.of("status",status.name(),"reason",reason,"completed_frames",frames));
        try {t.completed.accept(new Outcome(status,reason,frames));}
        catch(RuntimeException failure){log("ground_handoff_callback_error",t,Map.of("error",failure.toString(),"stack",Arrays.stream(failure.getStackTrace()).limit(8).map(Object::toString).toList()));}
    }
    private static void log(String event,Task t,Map<String,Object> extra){
        Map<String,Object> data=new LinkedHashMap<>();data.put("phase",t.phase.name());data.put("goal",t.goal.toString());
        data.put("elapsed_ms",System.currentTimeMillis()-t.started);data.putAll(extra);
        if(!event.equals("ground_handoff_progress")){
            data.put("player",MotionCoordinator.capture(t.player).toString());data.put("pose",t.player.getPose().name());
            data.put("yaw",t.player.yaw);data.put("pitch",t.player.pitch);
        }
        try {GlobalStateLogger.logCustomEvent(event,data);}catch(RuntimeException error){System.err.println("FredoBot ground handoff log failed: "+error);}
    }
    /** Simulated airborne/walking states must obey the original path request's blacklist too. */
    private static final class GuardedAgent implements GroundRouteSearch.Agent {
        private final GroundRouteSearch.Agent agent;private final Set<GroundRouteSearch.Cell> blacklist;
        GuardedAgent(GroundRouteSearch.Agent agent,Set<GroundRouteSearch.Cell> blacklist){this.agent=agent;this.blacklist=blacklist;}
        public GuardedAgent copy(){return new GuardedAgent(agent.copy(),blacklist);}
        public MotionState state(){return agent.state();}
        public boolean horizontalCollision(){return agent.horizontalCollision();}
        public float lookYaw(){return agent.lookYaw();}
        public MotionState step(MotionInput input){
            MotionState s=agent.step(input);
            if(blacklist.contains(new GroundRouteSearch.Cell((int)Math.floor(s.x()),(int)Math.floor(s.y()),(int)Math.floor(s.z()))))
                throw new IllegalArgumentException("Simulated route enters request blacklist");
            return s;
        }
    }
}
