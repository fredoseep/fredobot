package com.fredoseep.testing.nether;

import com.fredoseep.testing.CreativeTestContext;
import com.fredoseep.testing.CreativeWorldScenario;

/** One normal creative world, then hand control to the user's NetherAuto/NetherAnalyzer. */
public final class NetherTestScenario implements CreativeWorldScenario {
    @Override public String name() { return "nether"; }
    @Override public void start(CreativeTestContext context) {
        context.phase("HANDOFF_TO_NETHER_ANALYZE");
        context.handoffToNether();
    }
    @Override public void tick(CreativeTestContext context) {}
}
