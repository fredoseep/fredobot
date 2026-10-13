package com.fredoseep.algorithm.planning;

import com.fredoseep.algorithm.SimplePathfinder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Render-thread pump + dedicated cancellable worker. No waiting/blocking on the render thread. */
public final class AsyncPlanner {
    private static volatile Thread workerThread;
    private static volatile long activeWorkerJob=-1;
    private final long jobId;
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor(r -> {
        Thread thread=new Thread(r,"fredobot-path-search"); thread.setDaemon(true); workerThread=thread; return thread;
    });
    private static final ScheduledExecutorService STATUS=Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread=new Thread(r,"fredobot-path-status"); thread.setDaemon(true); return thread;
    });
    private enum Stage { WAITING_TERRAIN, WAITING_PAUSE, CAPTURING, WORKER, READY, FAILED, CANCELLED }
    private final World world;
    private final PlayerEntity player;
    private final BlockPos destination;
    private final Set<BlockPos> blacklist;
    private final PlannerSettings settings;
    private final TerrainReadiness terrain;
    private final AtomicBoolean cancelled=new AtomicBoolean();
    public final PlannerDebug debug;
    private volatile Stage stage=Stage.WAITING_TERRAIN;
    private GameMenuScreen ownedPause;
    private boolean observedPause;
    private long nextLoadCheck,pauseStarted,pauseObservedAt,waitingStartAt;
    private ActionPathfinder.CaptureSession capture;
    private Future<?> worker;
    private final ScheduledFuture<?> heartbeat;
    private volatile List<SimplePathfinder.Node> result;
    private volatile Throwable failure;
    public AsyncPlanner(MinecraftClient client,BlockPos destination,Set<BlockPos> blacklist,PlannerSettings settings,long generation) {
        jobId=generation;
        world=client.world; player=client.player;
        this.destination=destination.toImmutable(); this.blacklist=Set.copyOf(blacklist); this.settings=settings.copy();
        BlockPos start=player.getBlockPos(); long now=System.nanoTime()/1_000_000;
        terrain=new TerrainReadiness(start.getX(),start.getZ(),destination.getX(),destination.getZ(),this.settings.margin,now,
                this.settings.terrainWaitSeconds*1000L,this.settings.terrainStableMillis);
        PlannerDiagnostics diagnostics=this.settings.diagnosticsEnabled?new PlannerDiagnostics(client.runDirectory.toPath(),generation,Thread.currentThread(),
                error -> org.apache.logging.log4j.LogManager.getLogger("FredoBotPathfinder").error("Diagnostic file error",error),this.settings.keepSuccessfulDiagnostics):null;
        debug=new PlannerDebug(generation,diagnostics);
        if(diagnostics!=null)diagnostics.runtimeContext(() -> Map.of("requested_job",jobId,"active_worker_job",activeWorkerJob,"pipeline_stage",stage.name()));
        debug.phase("WAITING_TERRAIN","start="+start.toShortString()+" goal="+destination.toShortString());
        heartbeat=STATUS.scheduleAtFixedRate(debug::heartbeat,0,2,TimeUnit.SECONDS);
    }
    private boolean isCancelled() { return cancelled.get() || Thread.currentThread().isInterrupted(); }
    public void pump(MinecraftClient client) {
        if(stage==Stage.CANCELLED || stage==Stage.READY || stage==Stage.FAILED) return;
        if(client.world!=world || client.player!=player) { cancel(client,"world/player changed"); return; }
        try {
            long now=System.nanoTime()/1_000_000;
            if(failure!=null) { stage=Stage.FAILED; heartbeat.cancel(false); debug.error(failure); debug.closeDiagnostics("failed"); releasePause(client); return; }
            if(ownedPause!=null && client.currentScreen!=ownedPause) {
                cancel(client,"vanilla pause was closed/replaced by user"); return;
            }
            if(stage==Stage.WAITING_TERRAIN) {
                if(now<nextLoadCheck) return; nextLoadCheck=now+200;
                TerrainReadiness.Check check=terrain.check(now,(x,z) -> world.getChunkManager().isChunkLoaded(x,z));
                if(!check.ready || waitingStartAt==0)debug.phase("WAITING_TERRAIN","chunks="+check.loaded+"/"+check.total+" missing="+check.missing);
                if(check.timedOut) throw new IllegalStateException("Terrain loading timed out; missing chunks="+check.missing+
                        ". Increase render distance/move nearer, or resume a manually paused world to allow loading.");
                if(!check.ready) return;
                if(!client.isInSingleplayer()) throw new IllegalStateException("Snapshot planning requires pausable singleplayer");
                // Do not freeze a falling/jumping player before gravity can finish.
                // An existing manual pause is preserved; the captured contact flag still
                // distinguishes an actually grounded pit-rim start from an air start.
                if(!client.isPaused() && !player.isOnGround() && !player.isTouchingWater()) {
                    if(waitingStartAt==0)waitingStartAt=now;
                    debug.phase("WAITING_START","pos="+player.getBlockPos().toShortString()+" ground=false water=false; waiting to land");
                    if(now-waitingStartAt>5000)throw new IllegalStateException("Player has no stable ground/water start after 5s; pause/search was not started");
                    return;
                }
                // A landing may cross a chunk boundary. Recheck the capture rectangle
                // for the CURRENT start rather than relying on the request's old position.
                TerrainReadiness currentTerrain=new TerrainReadiness(player.getBlockPos().getX(),player.getBlockPos().getZ(),destination.getX(),destination.getZ(),
                        settings.margin,now,settings.terrainWaitSeconds*1000L,0);
                if(!currentTerrain.check(now,(x,z) -> world.getChunkManager().isChunkLoaded(x,z)).ready)return;
                stage=Stage.WAITING_PAUSE; pauseStarted=now;
                debug.phase("WAITING_PAUSE","request vanilla F3+Esc pause");
            }
            if(stage==Stage.WAITING_PAUSE) {
                if(client.currentScreen==null) { ownedPause=new GameMenuScreen(false); client.openScreen(ownedPause); return; }
                if(!client.isPaused()) {
                    if(now-pauseStarted>5000) throw new IllegalStateException("Vanilla pause not active; close other GUI / check LAN mode");
                    return;
                }
                // Allow at least two nominal server ticks for the pause handshake, without blocking a frame.
                if(!observedPause) { observedPause=true; pauseObservedAt=now; return; }
                if(now-pauseObservedAt<100) return;
                if(!player.isOnGround() && !player.isTouchingWater()) {
                    if(ownedPause==null)throw new IllegalStateException("Manual pause freezes an airborne player; resume the world and allow landing before requesting a route");
                    releasePause(client);observedPause=false;stage=Stage.WAITING_TERRAIN;nextLoadCheck=now;
                    if(waitingStartAt==0)waitingStartAt=now;
                    debug.phase("WAITING_START","player became airborne during pause handshake; resume gravity before capture");
                    return;
                }
                BlockPos start=new BlockPos(player.getX(),player.getY()+.2,player.getZ());
                capture=ActionPathfinder.beginCapture(world,player,start,destination,blacklist,settings);
                capture.diagnostics(debug.diagnostics);
                stage=Stage.CAPTURING;
                debug.phase("CAPTURING","budget="+settings.captureBudgetMillis+"ms/frame total="+capture.total+" blocks");
                return;
            }
            if(stage==Stage.CAPTURING) {
                if(!client.isPaused()) { cancel(client,"world resumed during capture"); return; }
                boolean done=capture.step(this::isCancelled); debug.capture(capture.captured(),capture.total);
                if(!done) return;
                ActionPathfinder.Snapshot snapshot=capture.finish(); capture=null;
                ActionPathfinder.diagnose(snapshot,debug.diagnostics);
                stage=Stage.WORKER;
                debug.phase("PREPARING_COSTS","queued on fredobot-path-search");
                worker=WORKER.submit(() -> calculate(snapshot));
                if(debug.diagnostics!=null)debug.diagnostics.attachWorker(workerThread);
                return;
            }
            if(stage==Stage.WORKER && result!=null) {
                stage=Stage.READY; heartbeat.cancel(false);
                debug.phase("READY","nodes="+result.size()); debug.closeDiagnostics("ready"); releasePause(client);
            }
        } catch(CancellationException error) { cancel(client,"capture cancelled"); }
          catch(Exception error) { failure=error; stage=Stage.FAILED; heartbeat.cancel(false); debug.error(error); debug.closeDiagnostics("failed"); releasePause(client); }
    }
    private void calculate(ActionPathfinder.Snapshot snapshot) {
        activeWorkerJob=jobId;
        try {
            if(debug.diagnostics!=null)debug.diagnostics.event("worker_start",Map.of("job",jobId,"thread",Thread.currentThread().getName()));
            if(debug.diagnostics!=null)debug.diagnostics.attachWorker(Thread.currentThread());
            if(isCancelled()) return;
            debug.phase("PREPARING_COSTS","worker="+Thread.currentThread().getName());
            ActionPathfinder.prepareMiningCosts(snapshot,this::isCancelled);
            if(isCancelled()) return;
            debug.phase("EXPORTING_TERRAIN","compressed planning snapshot");
            ActionPathfinder.exportDiagnosticTerrain(snapshot,this::isCancelled);
            if(isCancelled()) return;
            debug.phase("SEARCHING","worker="+Thread.currentThread().getName());
            var plan=ActionPathfinder.search(snapshot,this::isCancelled,debug::search,
                    pass -> debug.phase(pass,"budget="+settings.optimizationMillis+"ms maxKnown="+settings.optimizationMaxKnown+
                            " weight="+(pass.equals("SEARCH_BASELINE") || settings.dijkstra?1:settings.searchWeight)));
            if(isCancelled() || plan.status==SearchEngine.Status.CANCELLED) return;
            debug.phase("SEARCH_DONE",plan.status+" optimal="+plan.optimal+" reason="+plan.termination+" expanded="+plan.expanded+" ticks="+plan.ticks+" price="+plan.penalty+" steps="+plan.steps.size());
            // A geometrically close prefix is not a route to the mission goal. Never execute it.
            if(plan.status==SearchEngine.Status.LIMIT)
                throw new IllegalStateException("No full route found: "+plan.termination+
                        "; partial route discarded. Increase search budgets or disable their guards for exact search.");
            if(plan.status==SearchEngine.Status.EXHAUSTED) {
                debug.phase("NO_ROUTE","search graph exhausted; partial route discarded");
                result=new ArrayList<>(); return;
            }
            if(plan.status!=SearchEngine.Status.COMPLETE)
                throw new IllegalStateException("Refusing incomplete search result: "+plan.status);
            debug.phase("SMOOTHING","steps="+plan.steps.size());
            plan=ActionPathfinder.smooth(snapshot,plan,this::isCancelled);
            debug.phase("ROUTE_READY","steps="+plan.steps.size()+" ticks="+plan.ticks+" optimal="+plan.optimal+" reason="+plan.termination);
            for(var edge:plan.steps) debug.action(edge.action.kind+" -> "+edge.next.pos.toShortString()+
                    " break="+edge.action.breaks+" material="+edge.action.material+" ticks="+edge.ticks+" price="+edge.penalty);
            if(isCancelled()) return;
            result=plan.steps.isEmpty() && !snapshot.start.pos.equals(destination)?new ArrayList<>():ActionPathfinder.toNodes(snapshot,plan);
        } catch(CancellationException ignored) { }
          catch(Throwable error) { failure=error; }
          finally {if(activeWorkerJob==jobId)activeWorkerJob=-1;}
    }
    private void releasePause(MinecraftClient client) {
        if(ownedPause!=null && client.currentScreen==ownedPause) client.openScreen(null);
        ownedPause=null;
    }
    public void cancel(MinecraftClient client,String reason) {
        // Preserve the stuck operation and stack before interruption changes the worker's state.
        if(debug.diagnostics!=null)debug.diagnostics.sample();
        cancelled.set(true); heartbeat.cancel(false); if(worker!=null) worker.cancel(true);
        stage=Stage.CANCELLED; capture=null; releasePause(client); debug.phase("CANCELLED",reason);debug.closeDiagnostics("cancelled: "+reason);
    }
    public boolean ready() { return stage==Stage.READY; }
    public boolean failed() { return stage==Stage.FAILED; }
    public boolean cancelled() { return stage==Stage.CANCELLED; }
    public List<SimplePathfinder.Node> result() { return result; }
}
