package com.fredoseep.preflight;

import com.fredoseep.utils.bt.TntTriggerPolicy;
import java.util.Map;

/** Pure projection of evaluateBt's metal/diamond reservations; no inventory/crafting mutations. */
public final class ResourceProjection {
    private ResourceProjection() {}
    public static boolean needsStone(Map<String, Integer> loot) {
        int iron = loot.getOrDefault("minecraft:iron_ingot", 0);
        int gold = loot.getOrDefault("minecraft:gold_ingot", 0);
        int diamond = loot.getOrDefault("minecraft:diamond", 0);
        if (diamond >= 3) diamond -= 3; else iron -= 3;
        iron -= 3; // bucket
        boolean axe = diamond >= 3;
        if (axe) diamond -= 3;
        boolean shovel = diamond >= 1;
        if (shovel) diamond--;
        boolean button = false;
        if (loot.getOrDefault("minecraft:tnt", 0) > 0) {
            var trigger = TntTriggerPolicy.choose(false, 0, iron, iron >= 3, gold >= 2);
            if (trigger == TntTriggerPolicy.Trigger.IRON_PLATE) iron -= 2;
            else if (trigger == TntTriggerPolicy.Trigger.GOLD_PLATE) gold -= 2;
            else button = true;
        } else if (iron >= 3) iron -= 2; // shears
        if (!axe && iron >= 4) { axe = true; iron -= 3; }
        if (!shovel && iron >= 2) { shovel = true; iron--; }
        if (!axe && gold >= 3) { axe = true; gold -= 3; }
        if (!shovel && gold >= 1) shovel = true;
        return button || !axe || !shovel;
    }
}
