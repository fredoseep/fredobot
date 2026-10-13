package com.fredoseep.algorithm.planning;

/** All action durations and material prices use game ticks (20 ticks = 1 nominal second). */
public final class PlannerSettings {
    public int margin = 16;
    public int maxExpanded = 0; // Default: search the full route; a configured positive cap aborts rather than executing a prefix
    public int maxEdits = 32;
    public int optimizationMillis = 3000; // 0: no deadline; applies after the terrain/cost snapshot is ready
    public int optimizationMaxKnown = 250000; // 0: no state-memory guard
    public double searchWeight = 1.5; // goal-directed ordering; 1 restores ordinary A*
    public int captureBudgetMillis = 3;
    public int terrainWaitSeconds = 30;
    public int terrainStableMillis = 500;
    public double walkTicks = 4.7;
    public double swimTicks = 9.5;
    public double jumpTicks = 10;
    public double bridgeTicks = 10;
    public double pillarTicks = 12;
    public double aimTicks = 3;
    public double breakDelayTicks = 5;
    public double materialPriceTicks = 5; // existing PlaceableBlock price 3 => 15 ticks per block
    public boolean dijkstra = false;
    public boolean preferVerticalSurfacing = true;
    public boolean smoothPaths = true;
    public double maxSmoothDistance = 12;
    public boolean diagnosticsEnabled = true;
    public boolean keepSuccessfulDiagnostics = false; // Error/no-route archives always retain the newest 50.
    public int stuckWindowTicks = 30; // 1.5 nominal seconds, excluding intentional mining/paused ticks.
    public double stuckMinMovement = .3; // Horizontal and vertical displacement thresholds, in blocks.
    public int minimumRouteY = Integer.MIN_VALUE;
    public boolean allowWorldEdits = true;
    public PlannerSettings copy() {
        PlannerSettings p = new PlannerSettings();
        p.margin=margin; p.maxExpanded=maxExpanded; p.maxEdits=maxEdits;
        p.optimizationMillis=optimizationMillis; p.optimizationMaxKnown=optimizationMaxKnown; p.searchWeight=searchWeight;
        p.captureBudgetMillis=captureBudgetMillis; p.terrainWaitSeconds=terrainWaitSeconds; p.terrainStableMillis=terrainStableMillis;
        p.walkTicks=walkTicks; p.swimTicks=swimTicks; p.jumpTicks=jumpTicks;
        p.bridgeTicks=bridgeTicks; p.pillarTicks=pillarTicks; p.aimTicks=aimTicks;
        p.breakDelayTicks=breakDelayTicks; p.materialPriceTicks=materialPriceTicks; p.dijkstra=dijkstra;p.diagnosticsEnabled=diagnosticsEnabled;
        p.preferVerticalSurfacing=preferVerticalSurfacing;
        p.smoothPaths=smoothPaths;p.maxSmoothDistance=maxSmoothDistance;
        p.keepSuccessfulDiagnostics=keepSuccessfulDiagnostics;
        p.stuckWindowTicks=stuckWindowTicks;p.stuckMinMovement=stuckMinMovement;
        p.minimumRouteY=minimumRouteY;p.allowWorldEdits=allowWorldEdits;
        if (!Double.isFinite(maxSmoothDistance) || maxSmoothDistance<1 || optimizationMillis < 0 || optimizationMaxKnown < 0 || !Double.isFinite(searchWeight) || searchWeight < 1
                || captureBudgetMillis < 1 || terrainWaitSeconds < 1 || terrainStableMillis < 0 || margin < 1 || maxExpanded < 0 || maxEdits < 0 || walkTicks < 1 || swimTicks < 1
                || jumpTicks < 2 || bridgeTicks < 1 || pillarTicks < 1 || aimTicks < 0
                || breakDelayTicks < 0 || materialPriceTicks < 0 || stuckWindowTicks < 1
                || !Double.isFinite(stuckMinMovement) || stuckMinMovement <= 0)
            throw new IllegalArgumentException("Invalid planner settings");
        return p;
    }
    public static final PlannerSettings DEFAULT = new PlannerSettings();
}
