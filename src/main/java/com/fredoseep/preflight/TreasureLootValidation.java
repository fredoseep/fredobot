package com.fredoseep.preflight;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;
import com.fredoseep.testing.CreativeWorldTestRunner;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.TreeMap;

/** Read-only test against the ordinary client chest screen, BEFORE quick-moving loot.
 * This is a validation observer, never a source of the prediction or an early reset.
 */
public final class TreasureLootValidation {
    private static final Logger LOGGER = LogManager.getLogger("FredoBotPreflight");
    private static ClientWorld observedWorld;
    private static long consumedSelection;
    private static SelectedTreasureSeed.Selection worldSelection;
    private static boolean checked;

    private TreasureLootValidation() {}

    public static void pump(MinecraftClient client) {
        if (CreativeWorldTestRunner.isActive()) {
            // The independent test obtains its seed through /seed, never this provider observer.
            observedWorld = client.world;
            worldSelection = null;
            checked = false;
            return;
        }
        if (client.world == observedWorld) return;
        observedWorld = client.world;
        checked = false;
        worldSelection = null;
        if (client.world == null) return;
        SelectedTreasureSeed.Selection selection = SelectedTreasureSeed.current();
        // A selected seed is consumed by one world only. Never reuse it after a manual load.
        if (selection == null || selection.id <= consumedSelection) {
            LOGGER.info("[TreasurePrediction] UNAVAILABLE: this world has no newly captured provider seed");
            return;
        }
        consumedSelection = selection.id;
        worldSelection = selection;
        LOGGER.info("[TreasurePrediction] seed={} treasure={},{} lootSeed={} loot={} sufficient={}",
                selection.seed, selection.treasureX, selection.treasureZ, selection.lootSeed,
                selection.loot, selection.loot != null && TreasureLootPredictor.resourcesSufficient(selection.loot));
    }

    public static void compare(MinecraftClient client, GenericContainerScreenHandler handler, BlockPos treasure) {
        pump(client);
        SelectedTreasureSeed.Selection selection = worldSelection;
        if (checked || selection == null || selection.loot == null || treasure == null
                || (treasure.getX() >> 4) != (selection.treasureX >> 4)
                || (treasure.getZ() >> 4) != (selection.treasureZ >> 4)) return;
        Map<String, Integer> actual = new TreeMap<>();
        for (int slot = 0; slot < 27; slot++) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (!stack.isEmpty()) actual.merge(Registry.ITEM.getId(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        // Empty screen may mean inventory packets have not arrived. Do not certify it.
        if (!actual.containsKey("minecraft:heart_of_the_sea")) return;
        checked = true;
        if (selection.loot.equals(actual)) {
            LOGGER.info("[TreasurePrediction] VALIDATION_PASS seed={} treasure={} expected={} actual={}",
                    selection.seed, treasure.toShortString(), selection.loot, actual);
        } else {
            LOGGER.error("[TreasurePrediction] VALIDATION_FAIL seed={} treasure={} lootSeed={} expected={} actual={}",
                    selection.seed, treasure.toShortString(), selection.lootSeed, selection.loot, actual);
        }
    }
}
