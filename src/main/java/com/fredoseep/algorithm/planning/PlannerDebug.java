package com.fredoseep.algorithm.planning;

import net.minecraft.util.math.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.*;

/** Only immutable previews cross from the search worker to the renderer. */
public final class PlannerDebug {
    private static final Logger LOG=LogManager.getLogger("FredoBotPathfinder");
    public static final class View {
        public final String phase,detail;
        public final long elapsedMillis;
        public final List<BlockPos> currentPath,closestPath;
        public final int expanded,open,known;
        View(String phase,String detail,long elapsedMillis,List<BlockPos> currentPath,List<BlockPos> closestPath,int expanded,int open,int known) {
            this.phase=phase; this.detail=detail; this.elapsedMillis=elapsedMillis;
            this.currentPath=List.copyOf(currentPath); this.closestPath=List.copyOf(closestPath);
            this.expanded=expanded; this.open=open; this.known=known;
        }
    }
    public final PlannerDiagnostics diagnostics;
    private final long id,started=System.nanoTime();
    private volatile View view=new View("REQUESTED","",0,List.of(),List.of(),0,0,0);
    private long nextHeartbeat,nextPhaseLog;
    public PlannerDebug(long id) { this(id,null); }
    public PlannerDebug(long id,PlannerDiagnostics diagnostics) {
        this.id=id;this.diagnostics=diagnostics;
        if(diagnostics!=null)LOG.info("[ActionPlanner #{}] diagnostic directory={}",id,diagnostics.directory());
    }
    public View view() { return view; }
    private long elapsed() { return (System.nanoTime()-started)/1_000_000; }
    public void phase(String phase,String detail) {
        View old=view;
        view=new View(phase,detail,elapsed(),old.currentPath,old.closestPath,old.expanded,old.open,old.known);
        long now=System.nanoTime();
        if(!old.phase.equals(phase) || now>=nextPhaseLog) {
            nextPhaseLog=now+2_000_000_000L;
            LOG.info("[ActionPlanner #{}] {} {}",id,phase,detail);
            if(diagnostics!=null)diagnostics.phase(phase,detail);
        }
    }
    public void action(String detail) { LOG.info("[ActionPlanner #{}] ACTION {}",id,detail);if(diagnostics!=null)diagnostics.event("action",detail); }
    public void capture(long captured,long total) {
        if(diagnostics!=null)diagnostics.capture(captured,total);
        View old=view;
        view=new View("CAPTURING",captured+"/"+total+" blocks",elapsed(),old.currentPath,old.closestPath,0,0,0);
    }
    public void search(SearchEngine.Progress<ActionPathfinder.State> progress) {
        String pass=view.phase.startsWith("SEARCH_")?view.phase:"SEARCHING";
        String best=Double.isFinite(progress.bestCompleteScore)?" complete="+String.format(java.util.Locale.ROOT,"%.1f",progress.bestCompleteScore):" complete=none";
        view=new View(pass,"g="+String.format(java.util.Locale.ROOT,"%.1f",progress.score)+" h="+String.format(java.util.Locale.ROOT,"%.1f",progress.heuristic)+best,
                elapsed(),positions(progress.currentPath),positions(progress.bestCompletePath.isEmpty()?progress.closestPath:progress.bestCompletePath),progress.expanded,progress.open,progress.known);
    }
    private List<BlockPos> positions(List<ActionPathfinder.State> path) {
        List<BlockPos> result=new ArrayList<>(path.size()); for(var s:path) result.add(s.pos); return result;
    }
    /** Independent status thread: logs the last known phase even when rendering/search makes no progress. */
    public synchronized void heartbeat() {
        long now=System.nanoTime(); if(now<nextHeartbeat) return; nextHeartbeat=now+2_000_000_000L;
        if(diagnostics!=null)diagnostics.sample();
        View v=view; Runtime runtime=Runtime.getRuntime();
        LOG.info("[ActionPlanner #{}] heartbeat phase={} elapsed={}ms {} expanded={} open={} known={} heap={}MiB/{}MiB",
                id,v.phase,elapsed(),v.detail,v.expanded,v.open,v.known,(runtime.totalMemory()-runtime.freeMemory())/(1024*1024),runtime.maxMemory()/(1024*1024));
    }
    public void error(Throwable error) { LOG.error("[ActionPlanner #"+id+"] FAILED phase="+view.phase,error);if(diagnostics!=null)diagnostics.error(error); }
    public void closeDiagnostics(String reason){if(diagnostics!=null){diagnostics.sample();diagnostics.close(reason);LOG.info("[ActionPlanner #{}] diagnostic finalization requested: {}",id,reason);}}
}
