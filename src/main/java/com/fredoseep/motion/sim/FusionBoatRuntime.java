package com.fredoseep.motion.sim;

import com.fredoseep.algorithm.planning.TerrainReadiness;
import com.fredoseep.algorithm.planning.UnderwaterBoatRecovery;
import com.fredoseep.excutor.BotEngine;
import com.fredoseep.Test;
import com.fredoseep.preflight.EarlyResetPreflight;
import com.fredoseep.testing.CreativeWorldTestRunner;
import com.fredoseep.logging.GlobalStateLogger;
import com.fredoseep.motion.FusionSearch;
import com.fredoseep.motion.FusionBudget;
import com.fredoseep.motion.LocalGroundSearch;
import com.fredoseep.motion.MotionCoordinator;
import com.fredoseep.motion.MotionDebug;
import com.fredoseep.motion.MotionPlan;
import com.fredoseep.motion.MotionInput;
import com.fredoseep.motion.MotionSequence;
import com.fredoseep.motion.MotionState;
import com.fredoseep.motion.PlannedInput;
import com.fredoseep.utils.player.InventoryHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.BoatItem;
import net.minecraft.item.Item;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.Map;

/** Fusion boat segment: simulated launch, confirmed boarding, native-physics search, predicted input playback. */
public final class FusionBoatRuntime {
    private static Job job;
    private static BoatEntity recoverableBoat;
    private static final class Job {
        final ClientWorld world;final ClientPlayerEntity player;final LocalGroundSearch.Goal goal;
        final BooleanSupplier current;final Consumer<RuntimeGroundMotion.Outcome> callback;
        final long started=System.currentTimeMillis();TerrainReadiness terrain;
        String phase="WAITING_TERRAIN";GameMenuScreen pause;long pauseAt,lastLog,lastPreview;
        BoatLaunchSimulation launchSearch;BoatLaunchSimulation.Launch launch;MotionPlan authorized;
        UnderwaterBoatRecovery recovery;BoatEntity boat;FusionSearch search;List<MotionPlan.Frame> frames;int index,waitAge,pendingAge=-1,replans;
        Input saved;PlannedInput planned;boolean waitingMotion;LocalGroundSearch.Goal waterGoal;
        Job(MinecraftClient c,LocalGroundSearch.Goal g,BooleanSupplier current,Consumer<RuntimeGroundMotion.Outcome> cb){
            world=c.world;player=c.player;goal=g;this.current=current;callback=cb;
            terrain=new TerrainReadiness(player.getBlockPos().getX(),player.getBlockPos().getZ(),(int)Math.floor(g.x()),(int)Math.floor(g.z()),4,started,30000,500);
        }
    }
    private FusionBoatRuntime(){}
    /** Transfer a nearby just-used boat to the existing underwater boat-recovery task. */
    public static BoatEntity takeRecoveryBoat(ClientPlayerEntity player){
        BoatEntity b=recoverableBoat;recoverableBoat=null;
        return b!=null && b.isAlive() && b.world==player.world && b.getPos().squaredDistanceTo(player.getPos())<=16?b:null;
    }
    public static boolean ownsTask(){return job!=null;}
    public static String hudStatus(){Job j=job;return j==null?null:"Fusion 船："+j.phase+(j.frames==null?"":" "+j.index+"/"+j.frames.size());}
    public static boolean authorizes(MotionPlan plan){return job!=null && job.authorized==plan;}
    public static boolean begin(LocalGroundSearch.Goal goal,BooleanSupplier current,Consumer<RuntimeGroundMotion.Outcome> cb){
        MinecraftClient c=MinecraftClient.getInstance();
        if(job!=null || c.player==null || c.world==null || c.getServer()==null || c.currentScreen!=null)return false;
        job=new Job(c,goal,current,cb);log(job,"started",Map.of("goal",goal.toString()));return true;
    }
    public static void pump(MinecraftClient c){
        watchdog(c);Job j=job;if(j==null || j.waitingMotion || j.frames!=null)return;
        if(j.phase.equals("SPAWN") || j.phase.equals("BOARDING") || j.phase.equals("DISMOUNT"))return;
        try{
            if(j.pause==null){
                var terrain=j.terrain.check(System.currentTimeMillis(),(x,z)->j.world.getChunkManager().isChunkLoaded(x,z));
                if(terrain.timedOut){finish(j,RuntimeGroundMotion.Status.FAILED,"BOAT_TERRAIN_TIMEOUT");return;}
                if(!terrain.ready)return;
                if(j.player.getVehicle() instanceof BoatEntity b)j.boat=b;
                else{
                    var nearby=j.world.getEntities(BoatEntity.class,j.player.getBoundingBox().expand(3),b->b.isAlive() && !b.hasPassengers());
                    if(!nearby.isEmpty()){j.boat=nearby.get(0);j.phase="BOARDING";j.waitAge=j.player.age;c.interactionManager.interactEntity(j.player,j.boat,Hand.MAIN_HAND);return;}
                }
                j.pause=new GameMenuScreen(false);j.pauseAt=System.currentTimeMillis();c.openScreen(j.pause);j.phase="PAUSING";return;
            }
            if(c.currentScreen!=j.pause || !c.isPaused() || System.currentTimeMillis()-j.pauseAt<150)return;
            long deadline=System.nanoTime()+3_000_000;
            if(j.boat==null){
                if(j.launchSearch==null){j.launchSearch=new BoatLaunchSimulation(c,j.goal);j.phase="SIMULATING_LAUNCH";}
                do{j.launchSearch.step();}while(!j.launchSearch.finished() && System.nanoTime()<deadline);
                if(!j.launchSearch.finished())return;
                j.launch=j.launchSearch.result();if(j.launch==null){finish(j,RuntimeGroundMotion.Status.FAILED,"NO_SIMULATED_BOAT_LAUNCH");return;}
                c.openScreen(null);j.pause=null;
                if(j.launch.approach()!=null){
                    j.authorized=j.launch.approach();j.waitingMotion=true;j.phase="LAUNCH_APPROACH";
                    if(!MotionCoordinator.submitForPath(j.authorized,r->{
                        if(job!=j)return;j.waitingMotion=false;j.authorized=null;
                        if(r.phase()!=MotionSequence.Phase.COMPLETE){finish(j,RuntimeGroundMotion.Status.FAILED,"BOAT_LAUNCH_APPROACH_"+r.reason());return;}
                        place(c,j);
                    }))finish(j,RuntimeGroundMotion.Status.FAILED,"BOAT_APPROACH_LEASE_REJECTED");
                }else place(c,j);
                return;
            }
            if(j.player.getVehicle()!=j.boat){finish(j,RuntimeGroundMotion.Status.FAILED,"BOAT_BOARDING_LOST");return;}
            if(j.search==null){
                j.phase="SEARCHING";
                var root=new BoatAgent(j.boat,j.player);j.waterGoal=new LocalGroundSearch.Goal(j.goal.x(),root.state().y(),j.goal.z());
                double distance=Math.hypot(j.goal.x()-root.state().x(),j.goal.z()-root.state().z());
                var budget=FusionBudget.forDistance(distance,Runtime.getRuntime().maxMemory());
                j.search=new FusionSearch(root,j.waterGoal,budget.searchMillis(),budget.maxKnown()).staged(budget.initialKnown(distance),true,false);
                MotionDebug.begin(j.world,j.waterGoal);log(j,"search_started",Map.of("distance",distance,"boat_yaw",j.boat.yaw));
            }
            do{j.search.step();}while(!j.search.finished() && System.nanoTime()<deadline);
            if(System.currentTimeMillis()-j.lastPreview>=250){MotionDebug.searchFusion(j.search.preview(),j.search.explored());j.lastPreview=System.currentTimeMillis();}
            if(!j.search.finished())return;
            if(j.search.result()==null || !j.search.result().complete()){finish(j,RuntimeGroundMotion.Status.FAILED,"BOAT_SEARCH_"+j.search.reason());return;}
            List<MotionPlan.Frame> frames=new ArrayList<>();for(var leg:j.search.result().legs()){
                if(leg.motion()==null)throw new IllegalArgumentException("boat route contains unsupported edit");frames.addAll(leg.motion().frames());
            }
            j.frames=List.copyOf(frames);j.index=0;j.saved=j.player.input;j.planned=new PlannedInput();j.planned.copyHistory(j.saved);j.player.input=j.planned;
            j.phase="EXECUTING";c.openScreen(null);j.pause=null;log(j,"route_ready",Map.of("frames",frames.size(),"expanded",j.search.result().expanded()));watchdog(c);
        }catch(RuntimeException e){finish(j,RuntimeGroundMotion.Status.FAILED,"BOAT_SIMULATION_"+e);}
    }
    private static void place(MinecraftClient c,Job j){
        var pos=BoatLaunchSimulation.placement(c,j.player,j.launch.yaw(),j.launch.pitch());
        if(pos==null || pos.distanceTo(j.launch.position())>.4){finish(j,RuntimeGroundMotion.Status.FAILED,"BOAT_PLACEMENT_CHANGED");return;}
        Item item=null;for(var stack:j.player.inventory.main)if(stack.getItem() instanceof BoatItem && !stack.isEmpty()){item=stack.getItem();break;}
        if(item==null || !InventoryHelper.moveItemToHotbar(c,j.player,item,7)){finish(j,RuntimeGroundMotion.Status.FAILED,"BOAT_ITEM_MISSING");return;}
        j.player.inventory.selectedSlot=7;j.player.yaw=j.launch.yaw();j.player.pitch=j.launch.pitch();
        c.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookOnly(j.player.yaw,j.player.pitch,j.player.isOnGround()));
        c.interactionManager.interactItem(j.player,j.world,Hand.MAIN_HAND);
        j.phase="SPAWN";j.waitAge=j.player.age;log(j,"placed_wait_spawn",Map.of("predicted",pos.toString(),"yaw",j.player.yaw,"pitch",j.player.pitch));
    }
    public static void watchdog(MinecraftClient c){
        Job j=job;if(j==null)return;
        if(c.world!=j.world || c.player!=j.player || !j.current.getAsBoolean() || !BotEngine.getInstance().isRunning() || !j.player.isAlive()
                || CreativeWorldTestRunner.isActive() || EarlyResetPreflight.blocking(c) || Test.currentMission!=Test.CurrentTestingMission.IDLE){
            finish(j,RuntimeGroundMotion.Status.CANCELLED,"BOAT_OWNER_CHANGED");return;
        }
        if(System.currentTimeMillis()-j.started>240000){finish(j,RuntimeGroundMotion.Status.FAILED,"BOAT_JOB_TIMEOUT");return;}
        if((j.pause!=null && c.currentScreen!=j.pause) || (c.currentScreen!=null && c.currentScreen!=j.pause)){finish(j,RuntimeGroundMotion.Status.CANCELLED,"BOAT_SCREEN_CHANGED");return;}
        if(System.currentTimeMillis()-j.lastLog>=2000){log(j,"progress",j.search==null?Map.of():j.search.metrics());j.lastLog=System.currentTimeMillis();}
        if(j.frames!=null && j.planned!=null && j.player.input!=j.planned){finish(j,RuntimeGroundMotion.Status.FAILED,"BOAT_INPUT_OWNER_CHANGED");return;}
        if(c.isPaused())return;
        if(j.phase.equals("SPAWN")){
            var boats=j.world.getEntities(BoatEntity.class,new Box(j.launch.position(),j.launch.position()).expand(2),b->b.isAlive() && !b.hasPassengers());
            boats.sort(Comparator.comparingDouble(b->b.getPos().squaredDistanceTo(j.launch.position())));
            if(!boats.isEmpty()){j.boat=boats.get(0);j.phase="BOARDING";j.waitAge=j.player.age;c.interactionManager.interactEntity(j.player,j.boat,Hand.MAIN_HAND);}
            else if(j.player.age-j.waitAge>40)finish(j,RuntimeGroundMotion.Status.FAILED,"BOAT_SPAWN_TIMEOUT");
        }else if(j.phase.equals("BOARDING")){
            if(j.player.getVehicle()==j.boat){j.phase="WAITING_TERRAIN";j.search=null;
                j.terrain=new TerrainReadiness(j.player.getBlockPos().getX(),j.player.getBlockPos().getZ(),(int)Math.floor(j.goal.x()),(int)Math.floor(j.goal.z()),4,System.currentTimeMillis(),30000,500);log(j,"boarded",Map.of("boat",j.boat.getEntityId()));}
            else if(j.player.age-j.waitAge>40)finish(j,RuntimeGroundMotion.Status.FAILED,"BOAT_BOARD_TIMEOUT");
        }else if(j.phase.equals("DISMOUNT")){
            if(!j.player.hasVehicle() && j.player.age-j.waitAge>=5){
                j.phase="RECOVERING";
                j.recovery=new UnderwaterBoatRecovery(j.boat,new BlockPos(j.goal.x(),j.goal.y(),j.goal.z()),j.player.age);
                j.planned.setFrame(new MotionInput(false,false,false,false,false,false,false,j.player.yaw,j.player.pitch));
                log(j,"recovery_started",Map.of());
            }
            else if(j.player.age-j.waitAge>40)finish(j,RuntimeGroundMotion.Status.FAILED,"BOAT_DISMOUNT_TIMEOUT");
        }else if(j.phase.equals("RECOVERING")){
            var result=j.recovery.tick(c,j.player,(dismount,drop)->{
                boolean forward=false;
                if(drop!=null){
                    double dx=drop.getX()-j.player.getX(),dz=drop.getZ()-j.player.getZ();
                    j.player.yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));forward=dx*dx+dz*dz>.16;
                }
                j.player.setSprinting(false);j.player.pitch=0;
                j.planned.setFrame(new MotionInput(forward,false,false,false,!dismount && j.player.isTouchingWater(),dismount,false,j.player.yaw,0));
            });
            if(result==UnderwaterBoatRecovery.Result.DONE)finish(j,RuntimeGroundMotion.Status.COMPLETE,"BOAT_RECOVERED");
            else if(result==UnderwaterBoatRecovery.Result.FAILED)finish(j,RuntimeGroundMotion.Status.FAILED,"BOAT_RECOVERY_FAILED");
        }else if(j.frames!=null && !j.waitingMotion && j.pendingAge<0){
            if(j.player.getVehicle()!=j.boat){finish(j,RuntimeGroundMotion.Status.FAILED,"BOAT_VEHICLE_LOST");return;}
            if(j.index>=j.frames.size()){
                j.boat.setInputs(false,false,false,false);
                j.planned.setFrame(new MotionInput(false,false,false,false,false,true,false,j.player.yaw,0));
                j.phase="DISMOUNT";j.waitAge=j.player.age;log(j,"dismount",Map.of());return;
            }
            var input=j.frames.get(j.index).input();j.planned.setFrame(input);j.player.yaw=input.yaw();j.player.pitch=input.pitch();
            j.boat.setInputs(input.left(),input.right(),input.forward(),input.back());j.pendingAge=j.player.age;
        }
    }
    /** Passenger tick follows the boat's real tick; compare the same boat-centre state as the simulator. */
    public static void afterPlayerTick(ClientPlayerEntity player){
        Job j=job;if(j==null || j.player!=player || j.pendingAge<0 || j.frames==null)return;
        j.pendingAge=-1;
        var expected=j.frames.get(j.index).after();var actual=BoatAgent.capture(j.boat);
        if(actual.positionError(expected)>.4 || actual.velocityError(expected)>.2){
            log(j,"prediction_diverged",Map.of("actual",actual.toString(),"expected",expected.toString()));
            restore(j);j.frames=null;j.search=null;j.index=0;
            if(++j.replans>3){finish(j,RuntimeGroundMotion.Status.FAILED,"BOAT_PREDICTION_DIVERGED");return;}
            j.phase="WAITING_TERRAIN";return;
        }
        j.index++;
    }
    private static void restore(Job j){
        if(j.boat!=null)j.boat.setInputs(false,false,false,false);
        if(j.planned!=null && j.player.input==j.planned)j.player.input=j.saved;j.planned=null;j.saved=null;
    }
    public static void cancel(String reason){if(job!=null)finish(job,RuntimeGroundMotion.Status.CANCELLED,reason);}
    private static void finish(Job j,RuntimeGroundMotion.Status status,String reason){
        if(job!=j)return;job=null;restore(j);if(status==RuntimeGroundMotion.Status.COMPLETE)recoverableBoat=j.boat;MinecraftClient c=MinecraftClient.getInstance();
        if(j.pause!=null && c.currentScreen==j.pause)c.openScreen(null);
        if(status==RuntimeGroundMotion.Status.COMPLETE)MotionDebug.finish(null);else MotionDebug.begin(c.world,null);
        log(j,"finished",Map.of("status",status.toString(),"reason",reason));
        j.callback.accept(new RuntimeGroundMotion.Outcome(status,reason,j.index));
    }
    private static void log(Job j,String event,Map<String,?> extra){
        var data=new LinkedHashMap<String,Object>(extra);data.put("phase",j.phase);data.put("pos",j.player.getPos().toString());
        data.put("boat",j.boat==null?"none":j.boat.getEntityId());GlobalStateLogger.logCustomEvent("fusion_boat_"+event,data);
    }
}
