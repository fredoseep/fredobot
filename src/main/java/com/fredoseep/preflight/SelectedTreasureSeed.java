package com.fredoseep.preflight;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Published by the existing seed provider, before Atum creates the world. */
public final class SelectedTreasureSeed {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static volatile Selection current;
    private static final LinkedHashMap<Long, Selection> RECENT = new LinkedHashMap<>();

    private SelectedTreasureSeed() {}

    public static synchronized void accepted(long seed, int treasureX, int treasureZ) {
        Selection selection = new Selection(SEQUENCE.incrementAndGet(), seed, treasureX, treasureZ);
        RECENT.put(seed, selection);
        while (RECENT.size() > 32) RECENT.remove(RECENT.keySet().iterator().next());
        current = selection;
    }

    public static synchronized Selection forSeed(long seed) { return RECENT.get(seed); }

    public static Selection current() { return current; }

    public static final class Selection {
        public final long id;
        public final long seed;
        public final int treasureX;
        public final int treasureZ;
        public final long lootSeed;
        public final Map<String, Integer> loot;

        private Selection(long id, long seed, int x, int z) {
            this.id = id;
            this.seed = seed;
            this.treasureX = x;
            this.treasureZ = z;
            this.lootSeed = TreasureLootPredictor.lootSeed(seed, x, z);
            this.loot = lootSeed == 0L ? null : TreasureLootPredictor.fromLootSeed(lootSeed);
        }
    }
}
