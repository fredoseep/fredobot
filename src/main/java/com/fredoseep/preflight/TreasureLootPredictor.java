package com.fredoseep.preflight;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/** Vanilla Java 1.16.1, default buried_treasure table, aggregate item counts.
 * No Minecraft classes, chest data, world access or server API are used here.
 * Item slot shuffling is deliberately omitted: it cannot change aggregate counts.
 */
public final class TreasureLootPredictor {
    private TreasureLootPredictor() {}

    public static long lootSeed(long worldSeed, int treasureX, int treasureZ) {
        Random random = new Random(worldSeed);
        long xMultiplier = random.nextLong() | 1L;
        long zMultiplier = random.nextLong() | 1L;
        // Arithmetic shift matters for negative block coordinates.
        int chunkOriginX = (treasureX >> 4) << 4;
        int chunkOriginZ = (treasureZ >> 4) << 4;
        long populationSeed = ((long) chunkOriginX * xMultiplier
                + (long) chunkOriginZ * zMultiplier) ^ worldSeed;
        // BURIED_TREASURE: UNDERGROUND_STRUCTURES (3), structure index 1.
        random.setSeed(populationSeed + 30001L);
        // This structure does not consume a template NBT seed before its chest seed.
        return random.nextLong();
    }

    public static Map<String, Integer> predict(long worldSeed, int treasureX, int treasureZ) {
        return fromLootSeed(lootSeed(worldSeed, treasureX, treasureZ));
    }

    public static Map<String, Integer> fromLootSeed(long lootSeed) {
        if (lootSeed == 0L) {
            // Vanilla LootContext.Builder.random(0) uses a nondeterministic random.
            throw new IllegalArgumentException("ZERO_LOOT_SEED: prediction is unavailable");
        }
        Random random = new Random(lootSeed);
        Map<String, Integer> totals = new LinkedHashMap<>();
        add(totals, "heart_of_the_sea", 1); // One entry: no weighted-selection draw.
        int rolls = between(random, 5, 8);
        for (int i = 0; i < rolls; i++) {
            int choice = random.nextInt(35);
            if (choice < 20) add(totals, "iron_ingot", between(random, 1, 4));
            else if (choice < 30) add(totals, "gold_ingot", between(random, 1, 4));
            else add(totals, "tnt", between(random, 1, 2));
        }
        rolls = between(random, 1, 3);
        for (int i = 0; i < rolls; i++) {
            int choice = random.nextInt(15);
            if (choice < 5) add(totals, "emerald", between(random, 4, 8));
            else if (choice < 10) add(totals, "diamond", between(random, 1, 2));
            else add(totals, "prismarine_crystals", between(random, 1, 5));
        }
        if (between(random, 0, 1) == 1) {
            add(totals, random.nextInt(2) == 0 ? "leather_chestplate" : "iron_sword", 1);
        }
        for (int i = 0; i < 2; i++) {
            String fish = random.nextInt(2) == 0 ? "cooked_cod" : "cooked_salmon";
            add(totals, fish, between(random, 2, 4));
        }
        return Collections.unmodifiableMap(totals);
    }

    public static boolean resourcesSufficient(Map<String, Integer> loot) {
        int iron = loot.getOrDefault("minecraft:iron_ingot", 0);
        int diamond = loot.getOrDefault("minecraft:diamond", 0);
        return iron >= (diamond >= 3 ? 4 : 7);
    }

    private static int between(Random random, int min, int max) {
        return min >= max ? min : min + random.nextInt(max - min + 1);
    }

    private static void add(Map<String, Integer> totals, String item, int count) {
        totals.merge("minecraft:" + item, count, Integer::sum);
    }
}
