package com.fredoseep.motion.sim;

import com.fredoseep.algorithm.planning.TerrainReadiness;
import com.fredoseep.Test;
import com.fredoseep.preflight.EarlyResetPreflight;
import com.fredoseep.testing.CreativeWorldTestRunner;
import com.fredoseep.excutor.BotEngine;
import com.fredoseep.logging.GlobalStateLogger;
import com.fredoseep.motion.FusionSearch;
import com.fredoseep.motion.FusionBudget;
import com.fredoseep.motion.FusionSearchHud;
import com.fredoseep.motion.GroundRouteSearch;
import com.fredoseep.motion.LocalGroundSearch;
import com.fredoseep.motion.MotionCoordinator;
import com.fredoseep.motion.MotionPlan;
import com.fredoseep.motion.MotionSequence;
import com.fredoseep.motion.PlannedInput;
import com.fredoseep.motion.MotionState;
import com.fredoseep.motion.MotionDebug;
import java.util.ArrayList;
import com.fredoseep.utils.player.InventoryHelper;
import com.fredoseep.utils.player.WorkbenchApproach;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Arrays;
import java.util.Locale;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Hand;
import net.minecraft.tag.FluidTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RayTraceContext;

/** Default land/water/edit planning with verified checkpoints on search-budget exhaustion. */
public final class FusionRuntime {
    private static boolean enabled=true;
    private static final int MAX_CONTINUATIONS=16;
    private static final long MAX_JOB_MILLIS=30*60_000L;
    private static final long FALLING_WAIT_MILLIS=3000,FALLING_STABLE_MILLIS=250;
    private static Job job;
    private static final Logger LOGGER=LogManager.getLogger("FredoBotFusion");
    private record Monitor(long started,long updated,Thread owner,String phase,String goal,Map<String,Object> metrics,FusionSearch search) {}
    private static volatile Monitor monitor;
    private static long lastStaleWarning;
    static {
        Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"fredobot-fusion-status");t.setDaemon(true);return t;})
                .scheduleAtFixedRate(FusionRuntime::heartbeat,2,2,TimeUnit.SECONDS);
    }
    private static final class Job {
        final boolean shortLogTask;
        final ClientWorld world;final ClientPlayerEntity player;final LocalGroundSearch.Goal goal;
        final Set<BlockPos> blacklist;final BooleanSupplier current;final Consumer<RuntimeGroundMotion.Outcome> callback;
        final long started=System.currentTimeMillis();TerrainReadiness terrain;
        FusionBudget budget;long segmentStarted=started;double segmentStartDistance,segmentStartY,pitExitRim;
        int continuations,lastSearchStage=1;boolean partial,pitExit;MotionState endpoint;
        GameMenuScreen pause;long pauseTime,lastLog;FusionSearch search;List<FusionSearch.Leg> legs;
        int index,editStart=-1,lastEditAge=-1,totalFrames;MotionPlan authorized;
        Input saved;PlannedInput editInput;boolean motionPending;String phase="WAITING_TERRAIN";
        long lastPreview,lastWatchPublication;String summary="";
        long fallingSince,fallingClearSince;String fallingDetail="";
        Job(MinecraftClient c,LocalGroundSearch.Goal g,Set<GroundRouteSearch.Cell> forbidden,BooleanSupplier current,Consumer<RuntimeGroundMotion.Outcome> callback,boolean shortLogTask){
            this.shortLogTask=shortLogTask;world=c.world;player=c.player;goal=g;this.current=current;this.callback=callback;
            segmentStartDistance=distance(player,g);segmentStartY=player.getY();
            budget=FusionBudget.forDistance(Math.sqrt(Math.pow(g.x()-player.getX(),2)+Math.pow(g.y()-player.getY(),2)
                    +Math.pow(g.z()-player.getZ(),2)),Runtime.getRuntime().maxMemory());
            blacklist=forbidden.stream().map(p->new BlockPos(p.x(),p.y(),p.z())).collect(Collectors.toUnmodifiableSet());
            terrain=new TerrainReadiness(player.getBlockPos().getX(),player.getBlockPos().getZ(),(int)Math.floor(g.x()),(int)Math.floor(g.z()),
                    4,started,30_000,500);
        }
    }
    private FusionRuntime(){}
    public static boolean enabled(){return enabled;}
    public static boolean ownsTask(){return job!=null;}
    public static boolean authorizes(MotionPlan plan){return job!=null && job.authorized==plan && job.current.getAsBoolean();}
    public static String hudStatus(){return job==null?null:"Fusion："+job.phase+" 接续="+job.continuations+job.summary+(job.legs==null?"":" "+job.index+"/"+job.legs.size());}
    /** Render only immutable published counters; hide optimization labels once execution starts. */
    public static List<String> searchHudLines(){
        Job j=job;Monitor view=monitor;
        return j==null || j.legs!=null || view==null?List.of():FusionSearchHud.lines(view.metrics(),j.shortLogTask);
    }
    public static boolean command(String text){
        if(!text.equals("#fredomotion fusion") && !text.startsWith("#fredomotion fusion "))return false;
        String mode=text.substring("#fredomotion fusion".length()).trim();MinecraftClient c=MinecraftClient.getInstance();
        if(mode.equals("on") || mode.equals("off")){
            enabled=mode.equals("on");if(!enabled && job!=null)MotionCoordinator.cancel("FUSION_DISABLED");
        }
        if(c.player!=null)c.player.sendMessage(new LiteralText("[Motion] Fusion="+enabled+"；#fredomotion fusion on|off|status"),false);
        return true;
    }
    public static boolean begin(LocalGroundSearch.Goal goal,Set<GroundRouteSearch.Cell> blacklist,BooleanSupplier current,
                                Consumer<RuntimeGroundMotion.Outcome> callback){
        return begin(goal,blacklist,current,callback,false);
    }
    public static boolean begin(LocalGroundSearch.Goal goal,Set<GroundRouteSearch.Cell> blacklist,BooleanSupplier current,
                                Consumer<RuntimeGroundMotion.Outcome> callback,boolean shortLogTask){
        MinecraftClient c=MinecraftClient.getInstance();
        if(!enabled || !RuntimeGroundMotion.enabled() || job!=null || RuntimeGroundMotion.ownsTask() || MotionCoordinator.ownsControl() || c.world==null || c.player==null || c.getServer()==null
                || c.currentScreen!=null || !c.isOnThread() || !current.getAsBoolean() || !BotEngine.getInstance().isRunning()
                || GroundMotionExperiments.pending() || CreativeWorldTestRunner.isActive() || EarlyResetPreflight.blocking(c)
                || Test.currentMission!=Test.CurrentTestingMission.IDLE)return false;
        try{FusionSafety.start(c.player);FusionSafety.goal(c.player,goal);}
        catch(FusionSafety.TransientCollision falling){/* The retained job settles this before pausing or simulating. */}
        catch(IllegalArgumentException unsupported){return false;}
        job=new Job(c,goal,blacklist,current,callback,shortLogTask);MotionDebug.begin(c.world,goal);log("fusion_started",job,Map.of("max_known",job.budget.maxKnown(),
                "search_budget_ms",job.budget.searchMillis(),"job_budget_ms",job.budget.jobMillis()));return true;
    }
    public static void watchdog(MinecraftClient c){
        Job j=job;if(j==null)return;
        if(!enabled || !RuntimeGroundMotion.enabled() || c.world!=j.world || c.player!=j.player || !j.player.isAlive() || !j.current.getAsBoolean()
                || !BotEngine.getInstance().isRunning() || CreativeWorldTestRunner.isActive() || EarlyResetPreflight.blocking(c)
                || Test.currentMission!=Test.CurrentTestingMission.IDLE){
            MotionCoordinator.cancel("FUSION_OWNER_CHANGED");return;
        }
        if(System.currentTimeMillis()-j.started>MAX_JOB_MILLIS || System.currentTimeMillis()-j.segmentStarted>j.budget.jobMillis()){
            finish(j,RuntimeGroundMotion.Status.FAILED,"FUSION_JOB_TIMEOUT");return;
        }
        if(System.currentTimeMillis()-j.lastWatchPublication>=1000){publish(j);j.lastWatchPublication=System.currentTimeMillis();}
        if(j.legs!=null && !j.motionPending){
            if(c.isPaused() || c.currentScreen!=null)return;
            if(j.index>=j.legs.size()){
                if(j.partial)continueFromCheckpoint(c,j);else finish(j,RuntimeGroundMotion.Status.COMPLETE,"COMPLETE");return;
            }
            FusionSearch.Leg leg=j.legs.get(j.index);
            if(leg.interaction()!=null)interaction(c,j,(FusionAgent.Edit)leg.interaction());
            else submit(c,j);
        }
    }
    public static void pump(MinecraftClient c){
        WorkbenchApproach.pump(c);
        Job j=job;if(j==null || j.legs!=null)return;
        try{
            if(j.pause==null && c.currentScreen!=null){cancel("FUSION_SCREEN_CHANGED");return;}
            if(j.pause!=null && c.currentScreen!=j.pause){cancel("FUSION_PAUSE_CLOSED");return;}
            if(j.search==null && !settledStart(c,j))return;
            var check=j.terrain.check(System.currentTimeMillis(),(x,z)->j.world.getChunkManager().isChunkLoaded(x,z));
            if(check.timedOut){finish(j,RuntimeGroundMotion.Status.FAILED,"TERRAIN_TIMEOUT");return;}
            if(!check.ready)return;
            if(j.pause==null){
                double dx=j.goal.x()-j.player.getX(),dz=j.goal.z()-j.player.getZ();
                j.player.yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));
                j.player.pitch=(float)-Math.toDegrees(Math.atan2(j.goal.y()-j.player.getEyeY(),Math.hypot(dx,dz)));
                j.pause=new GameMenuScreen(false);j.phase="PAUSING";c.openScreen(j.pause);return;
            }
            if(c.currentScreen!=j.pause){cancel("FUSION_PAUSE_CLOSED");return;}
            if(!c.isPaused())return;
            if(j.pauseTime==0){j.pauseTime=System.currentTimeMillis();return;}
            if(System.currentTimeMillis()-j.pauseTime<150)return;
            if(j.search==null){
                FusionSafety.start(j.player);FusionSafety.goal(j.player,j.goal);
                j.phase="PREPARING_SIMULATOR";publish(j);
                // Admission can precede a falling-block wait: anchor execution progress to
                // the actual paused simulation root, not the earlier request position.
                j.segmentStartY=j.player.getY();j.segmentStartDistance=distance(j.player,j.goal);
                j.lastSearchStage=1;
                int first=j.budget.initialKnown(j.segmentStartDistance);
                j.search=new FusionSearch(new FusionAgent(c,j.blacklist),j.goal,j.budget.searchMillis(),j.shortLogTask?first:j.budget.maxKnown())
                        .staged(first,!j.shortLogTask,!j.shortLogTask);
                log("fusion_search_limits",j,Map.of("distance",j.segmentStartDistance,"states_per_block",FusionBudget.INITIAL_STATES_PER_BLOCK,
                        "first_limit",first,"hard_limit",j.shortLogTask?first:j.budget.maxKnown(),"short_log_task",j.shortLogTask));
                j.phase="SEARCHING";publish(j);
            }
            long deadline=System.nanoTime()+3_000_000;
            do{j.search.step();}while(!j.search.finished() && System.nanoTime()<deadline);
            int stage=j.search.stage();
            if(stage!=j.lastSearchStage){j.lastSearchStage=stage;log("fusion_search_limit_increased",j,j.search.metrics());}
            if(System.currentTimeMillis()-j.lastPreview>=250 || j.search.finished()){
                MotionDebug.searchFusion(j.search.preview(),j.search.explored());
                publish(j);j.lastPreview=System.currentTimeMillis();
            }
            if(System.currentTimeMillis()-j.lastLog>=2000){log("fusion_progress",j,j.search.metrics());j.lastLog=System.currentTimeMillis();}
            if(!j.search.finished())return;
            log("fusion_search_done",j,j.search.metrics());
            if(j.search.result()==null){finish(j,RuntimeGroundMotion.Status.FAILED,j.search.reason());return;}
            j.partial=!j.search.result().complete();j.endpoint=j.search.result().endpoint();
            j.pitExit=j.search.result().pitExit();j.pitExitRim=j.pitExit?((Number)j.search.metrics().get("escape_height")).doubleValue():Double.NaN;
            j.legs=join(j.search.result().legs());j.phase=j.partial?"EXECUTING_CHECKPOINT":"EXECUTING";
            log("fusion_route_ready",j,Map.of("complete",!j.partial,"endpoint",j.endpoint.toString(),"cost_ticks",j.search.result().cost(),"legs",j.legs.size(),
                    "checkpoint_kind",j.partial?(j.pitExit?"PIT_EXIT":"PROGRESS"):"FINAL_GOAL","actions",j.legs.stream().map(l->l.motion()==null?l.interaction().kind()+" "+((FusionAgent.Edit)l.interaction()).pos().toShortString():"MOTION "+l.motion().frames().size()).toList()));
            c.openScreen(null);
            if(!j.legs.isEmpty() && j.legs.get(0).motion()!=null)submit(c,j);
        }catch(RuntimeException failure){finish(j,RuntimeGroundMotion.Status.FAILED,"FUSION_ERROR: "+failure);}
    }
    /** Wait only for actual transient falling-block entities, never ignore them in a simulated route. */
    private static boolean settledStart(MinecraftClient c,Job j){
        long now=System.currentTimeMillis();
        try{FusionSafety.start(j.player);FusionSafety.goal(j.player,j.goal);}
        catch(FusionSafety.TransientCollision falling){
            if(j.fallingSince==0){
                j.fallingSince=now;j.fallingDetail=falling.getMessage();j.phase="WAITING_FALLING_BLOCK";
                log("fusion_wait_falling_block",j,Map.of("reason",j.fallingDetail,"timeout_ms",FALLING_WAIT_MILLIS));
            }
            j.fallingClearSince=0;
            if(j.pause!=null && c.currentScreen==j.pause){c.openScreen(null);j.pause=null;j.pauseTime=0;}
            if(now-j.fallingSince>=FALLING_WAIT_MILLIS)finish(j,RuntimeGroundMotion.Status.FAILED,"FALLING_BLOCK_SETTLE_TIMEOUT: "+j.fallingDetail);
            return false;
        }
        if(j.fallingSince!=0){
            if(j.fallingClearSince==0)j.fallingClearSince=now;
            if(now-j.fallingClearSince<FALLING_STABLE_MILLIS)return false;
            log("fusion_falling_block_settled",j,Map.of("waited_ms",now-j.fallingSince));
            j.fallingSince=0;j.fallingClearSince=0;j.fallingDetail="";j.phase="WAITING_TERRAIN";
        }
        return true;
    }
    private static double distance(ClientPlayerEntity p,LocalGroundSearch.Goal g){
        return Math.sqrt(Math.pow(g.x()-p.getX(),2)+Math.pow(g.y()-p.getY(),2)+Math.pow(g.z()-p.getZ(),2));
    }
    /** Retain the same task/final goal. No callback or business-stage completion at a checkpoint. */
    private static void continueFromCheckpoint(MinecraftClient c,Job j){
        double remaining=distance(j.player,j.goal),progress=j.segmentStartDistance-remaining;
        if(j.continuations>=MAX_CONTINUATIONS){finish(j,RuntimeGroundMotion.Status.FAILED,"CHECKPOINT_SEGMENT_LIMIT");return;}
        if(j.endpoint==null || MotionCoordinator.capture(j.player).positionError(j.endpoint)>.25
                || !FusionSafety.checkpoint(j.player)
                || (j.pitExit?!FusionSafety.pitExit(j.player,j.segmentStartY,j.pitExitRim):progress<2.5)){
            finish(j,RuntimeGroundMotion.Status.FAILED,"CHECKPOINT_UNSAFE_OR_NO_PROGRESS");return;
        }
        log("fusion_checkpoint_reached",j,Map.of("progress_blocks",progress,"remaining_blocks",remaining,
                "checkpoint_kind",j.pitExit?"PIT_EXIT":"PROGRESS","height_gain",j.player.getY()-j.segmentStartY));
        restore(j);j.continuations++;j.segmentStartDistance=remaining;j.segmentStartY=j.player.getY();j.segmentStarted=System.currentTimeMillis();
        j.budget=FusionBudget.forDistance(remaining,Runtime.getRuntime().maxMemory());
        j.terrain=new TerrainReadiness(j.player.getBlockPos().getX(),j.player.getBlockPos().getZ(),
                (int)Math.floor(j.goal.x()),(int)Math.floor(j.goal.z()),4,j.segmentStarted,30_000,500);
        j.search=null;j.legs=null;j.index=0;j.authorized=null;j.motionPending=false;j.partial=false;j.pitExit=false;j.endpoint=null;
        j.editStart=-1;j.lastEditAge=-1;j.pauseTime=0;j.lastLog=0;j.lastPreview=0;j.summary="";j.phase="WAITING_TERRAIN";
        // Pause immediately at the verified stop: no unowned ticks sinking/drifting while terrain is rechecked.
        double dx=j.goal.x()-j.player.getX(),dz=j.goal.z()-j.player.getZ();
        j.player.yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));
        j.player.pitch=(float)-Math.toDegrees(Math.atan2(j.goal.y()-j.player.getEyeY(),Math.hypot(dx,dz)));
        j.pause=new GameMenuScreen(false);c.openScreen(j.pause);MotionDebug.begin(j.world,j.goal);
        log("fusion_continuation_started",j,Map.of("remaining_blocks",remaining,"max_known",j.budget.maxKnown(),
                "search_budget_ms",j.budget.searchMillis()));
    }
    /** Merge rollouts: no stop/re-aim/handoff at internal search boundaries. */
    private static List<FusionSearch.Leg> join(List<FusionSearch.Leg> legs){
        var result=new ArrayList<FusionSearch.Leg>();var frames=new ArrayList<MotionPlan.Frame>();
        MotionState initial=null;
        for(FusionSearch.Leg leg:legs){
            if(leg.motion()!=null){
                if(initial==null)initial=leg.motion().initial();
                frames.addAll(leg.motion().frames());
            }else{
                if(!frames.isEmpty()){result.add(new FusionSearch.Leg(new MotionPlan("fusion-run",initial,frames,.2,.15),null));frames.clear();initial=null;}
                result.add(leg);
            }
        }
        if(!frames.isEmpty())result.add(new FusionSearch.Leg(new MotionPlan("fusion-run",initial,frames,.2,.15),null));
        return List.copyOf(result);
    }
    private static void submit(MinecraftClient c,Job j){
        j.authorized=j.legs.get(j.index).motion();j.motionPending=true;
        if(!MotionCoordinator.submitForPath(j.authorized,result->{
            if(job!=j)return;j.authorized=null;j.motionPending=false;
            if(result.phase()!=MotionSequence.Phase.COMPLETE){finish(j,RuntimeGroundMotion.Status.FAILED,"MOTION: "+result.reason());return;}
            j.totalFrames+=result.completedFrames();j.index++;
            if(j.partial && j.index>=j.legs.size())continueFromCheckpoint(c,j);
        })){j.motionPending=false;finish(j,RuntimeGroundMotion.Status.FAILED,"SUBMIT_REJECTED");}
    }
    private static void interaction(MinecraftClient c,Job j,FusionAgent.Edit e){
        if(j.lastEditAge==j.player.age)return;j.lastEditAge=j.player.age;
        if(j.editStart<0){
            if(MotionCoordinator.capture(j.player).positionError(e.initial())>.25
                    || (e.airbornePlacement() && (j.player.isTouchingWater() || j.player.getBoundingBox().intersects(new Box(e.pos()))))
                    || (e.hold()==null && (j.player.isTouchingWater() || !j.player.isOnGround()))
                    || j.world.getBlockState(e.pos())!=e.before()){
                finish(j,RuntimeGroundMotion.Status.FAILED,"EDIT_PRECONDITION");return;
            }
            j.saved=j.player.input;j.editInput=new PlannedInput();j.editInput.copyHistory(j.saved);j.player.input=j.editInput;
            j.editStart=j.player.age;log("fusion_edit_started",j,Map.of("kind",e.kind(),"pos",e.pos().toShortString(),"expected_ticks",e.ticks(),"mode",e.airbornePlacement()?"airborne_place":e.hold()==null?"dry":"upright_water"));
        }
        int elapsed=j.player.age-j.editStart;
        MotionState expected=e.hold()==null?e.initial():elapsed==0?e.hold().initial():e.hold().frames().get(Math.min(elapsed,e.ticks())-1).after();
        MotionState actual=MotionCoordinator.capture(j.player);
        if(j.player.input!=j.editInput || actual.positionError(expected)>.25 || actual.velocityError(expected)>.15
                || (e.hold()==null && (j.player.isTouchingWater() || !j.player.isOnGround()))
                || (e.hold()!=null && (actual.grounded()!=expected.grounded() || j.player.isSubmergedInWater()
                || !j.player.getPose().name().equals("STANDING")))){
            finish(j,RuntimeGroundMotion.Status.FAILED,"EDIT_STATE_CHANGED");return;
        }
        if(e.hold()!=null){
            var input=e.hold().frames().get(Math.min(elapsed,e.ticks()-1)).input();j.editInput.setFrame(input);
            j.player.yaw=input.yaw();j.player.pitch=input.pitch();
        }
        if(j.world.getBlockState(e.pos())==e.after()){
            // Keep at least the planned interaction duration; the next motion starts from its predicted boundary.
            if(j.player.age-j.editStart<e.ticks())return;
            log("fusion_edit_confirmed",j,Map.of("kind",e.kind(),"pos",e.pos().toShortString()));
            restore(j);c.interactionManager.cancelBlockBreaking();j.index++;j.editStart=-1;return;
        }
        if((e.hold()!=null && elapsed>=e.ticks()) || j.player.age-j.editStart>e.ticks()+40){finish(j,RuntimeGroundMotion.Status.FAILED,"EDIT_CONFIRM_TIMEOUT");return;}
        if(e.airbornePlacement() && elapsed>0)return;
        float[] look=FusionAgent.look(j.player,e.hit().getPos());j.player.yaw=look[0];j.player.pitch=look[1];
        if(e.item()!=null){
            if(!InventoryHelper.moveItemToHotbar(c,j.player,e.item(),7)){finish(j,RuntimeGroundMotion.Status.FAILED,"EDIT_ITEM_MISSING");return;}
            j.player.inventory.selectedSlot=7;
        }else{
            int empty=-1;for(int i=0;i<9;i++)if(j.player.inventory.main.get(i).isEmpty()){empty=i;break;}
            if(empty<0){finish(j,RuntimeGroundMotion.Status.FAILED,"NO_EMPTY_HAND_SLOT");return;}j.player.inventory.selectedSlot=empty;
        }
        Vec3d eye=new Vec3d(j.player.getX(),j.player.getEyeY(),j.player.getZ()),point=e.hit().getPos();
        Vec3d end=new Vec3d(point.x+(point.x-eye.x)*.005,point.y+(point.y-eye.y)*.005,point.z+(point.z-eye.z)*.005);
        BlockHitResult hit=j.world.rayTrace(new RayTraceContext(eye,end,RayTraceContext.ShapeType.OUTLINE,RayTraceContext.FluidHandling.NONE,j.player));
        if(hit.getType()!=HitResult.Type.BLOCK || !hit.getBlockPos().equals(e.hit().getBlockPos()) || eye.squaredDistanceTo(hit.getPos())>20.25){
            finish(j,RuntimeGroundMotion.Status.FAILED,"EDIT_RAY_CHANGED");return;
        }
        if(e.kind().equals("BREAK"))c.interactionManager.updateBlockBreakingProgress(e.pos(),hit.getSide());
        else if(hit.getBlockPos().offset(hit.getSide()).equals(e.pos()))c.interactionManager.interactBlock(j.player,j.world,Hand.MAIN_HAND,hit);
        j.player.swingHand(Hand.MAIN_HAND);
    }
    private static void restore(Job j){if(j.editInput!=null && j.player.input==j.editInput)j.player.input=j.saved;j.editInput=null;j.saved=null;}
    public static void cancel(String reason){Job j=job;if(j!=null)finish(j,RuntimeGroundMotion.Status.CANCELLED,reason);}
    private static void finish(Job j,RuntimeGroundMotion.Status status,String reason){
        if(job!=j)return;job=null;monitor=null;if(j.search!=null && !j.search.finished())j.search.cancel();restore(j);
        MinecraftClient c=MinecraftClient.getInstance();if(c.interactionManager!=null)c.interactionManager.cancelBlockBreaking();
        if(status==RuntimeGroundMotion.Status.COMPLETE)MotionDebug.finish(null);else MotionDebug.begin(c.world,null);
        if(j.pause!=null && c.currentScreen==j.pause)c.openScreen(null);
        log("fusion_finished",j,Map.of("status",status.name(),"reason",reason));
        j.callback.accept(new RuntimeGroundMotion.Outcome(status,reason,j.totalFrames));
    }
    private static void log(String event,Job j,Map<String,Object> extra){
        Map<String,Object> data=new HashMap<>(extra);data.put("phase",j.phase);data.put("goal",j.goal.toString());
        data.put("continuation",j.continuations);data.put("pos",j.player.getPos().toString());data.put("pose",j.player.getPose().name());data.put("leg",j.index);
        GlobalStateLogger.logCustomEvent(event,data);
        if(!event.equals("fusion_progress"))LOGGER.info("[Fusion] {} {}",event,data);
        publish(j);
    }
    /** Client thread only: publish immutable counters, never expose live world reads to the heartbeat. */
    private static void publish(Job j){
        if(job!=j)return;
        Map<String,Object> metrics=j.search==null?Map.of():Map.copyOf(j.search.metrics());
        if(j.search!=null)j.summary=" 扩展="+metrics.get("expanded")+" 状态="+metrics.get("known")
                +" 距目标="+String.format(Locale.ROOT,"%.1f",((Number)metrics.get("best_goal_distance")).doubleValue());
        monitor=new Monitor(j.started,System.currentTimeMillis(),Thread.currentThread(),j.phase,j.goal.toString(),metrics,j.search);
    }
    /** This daemon reads immutable Java snapshots and a volatile operation marker, never Minecraft state. */
    private static void heartbeat(){
        try{
            Monitor m=monitor;if(m==null)return;long now=System.currentTimeMillis();
            FusionSearch.Operation operation=m.search()==null?null:m.search().operation();
            LOGGER.info("[Fusion] heartbeat phase={} elapsed_ms={} goal={} metrics={} operation={} operation_ms={} snapshot_age_ms={}",
                    m.phase(),now-m.started(),m.goal(),m.metrics(),operation==null?"none":operation.name(),
                    operation==null?0:now-operation.since(),now-m.updated());
            boolean stuckOperation=m.phase().equals("SEARCHING") && operation!=null && now-operation.since()>5000;
            if((now-m.updated()>5000 || stuckOperation) && now-lastStaleWarning>10000 && monitor==m){
                lastStaleWarning=now;
                LOGGER.warn("[Fusion] client snapshot stale {}ms; owner_stack={}",now-m.updated(),
                        Arrays.stream(m.owner().getStackTrace()).limit(12).map(Object::toString).toList());
            }
        }catch(RuntimeException failure){LOGGER.warn("[Fusion] heartbeat error: {}",failure.toString());}
    }
}
