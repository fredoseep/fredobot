package com.fredoseep.testing;

import com.fredoseep.preflight.TreasureLootPredictor;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.Registry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Real-game test: command seed -> command locate -> client terrain -> actual chest packet.
 * Does not read ServerWorld, chest NBT, or the seed-provider cache.
 */
public final class TreasurePredictionScenario implements CreativeWorldScenario {
    private enum Phase { SEED, LOCATE, TERRAIN, ACCESS, OPEN, INVENTORY }
    private Phase phase;
    private long seed;
    private int x, z;
    private BlockPos chest;
    private Map<String, Integer> predicted;
    private int opens;
    private long lastOpen;

    @Override public String name() { return "treasure"; }

    @Override public void start(CreativeTestContext context) {
        phase = Phase.SEED;
        context.phase("WAIT_SEED");
        context.command("/seed");
    }

    @Override public void onChat(CreativeTestContext context, Text message) {
        if (context.done()) return;
        List<String> keys = VanillaCommandFeedback.keys(message);
        if (phase == Phase.SEED) {
            Long reply = VanillaCommandFeedback.seed(message);
            if (reply != null) {
                seed = reply;
                if (!context.seedMatchesCreatedWorld(seed)) {
                    finish(context, TestBatch.Outcome.ERROR, "CREATED_WORLD_SEED_MISMATCH", Map.of("observedSeed", seed));
                    return;
                }
                context.event("observed_seed", Map.of("seed", seed));
                phase = Phase.LOCATE;
                context.phase("WAIT_LOCATE");
                context.command("/locate buried_treasure");
            }
        } else if (phase == Phase.LOCATE) {
            int[] located = VanillaCommandFeedback.treasure(message);
            if (located != null) {
                x = located[0]; z = located[1];
                try { predicted = TreasureLootPredictor.predict(seed, x, z); }
                catch (IllegalArgumentException exception) {
                    finish(context, TestBatch.Outcome.SKIP, "NONDETERMINISTIC_LOOT_SEED", Map.of());
                    return;
                }
                context.event("prediction", Map.of("seed", seed, "locateX", x, "locateZ", z,
                        "lootSeed", TreasureLootPredictor.lootSeed(seed, x, z), "expected", predicted));
                phase = Phase.TERRAIN;
                context.phase("WAIT_TREASURE_TERRAIN");
                context.command("/tp @s " + (x + 0.5) + " 120 " + (z + 0.5));
            } else if (keys.contains("commands.locate.failed")) {
                finish(context, TestBatch.Outcome.SKIP, "LOCATE_NO_TREASURE", Map.of());
            }
        }
        if (keys.contains("commands.generic.permission") || keys.contains("command.unknown.command")) {
            finish(context, TestBatch.Outcome.ERROR, "COMMAND_REJECTED", Map.of("feedback", message.getString()));
        }
    }

    @Override public void tick(CreativeTestContext context) {
        if (context.done() || context.client.player == null) return;
        if (context.client.isPaused()) return; // user may inspect/pause; no actions while paused
        if (context.phaseMillis() > (phase == Phase.LOCATE ? 180_000L : 60_000L)) {
            finish(context, TestBatch.Outcome.ERROR, "TIMEOUT_" + phase, Map.of());
            return;
        }
        ClientPlayerEntity player = context.client.player;
        // Creative flight avoids a falling or underwater player invalidating opening reach.
        if (player.abilities.allowFlying && !player.abilities.flying) {
            player.abilities.flying = true;
            player.sendAbilitiesUpdate();
        }
        if (phase == Phase.TERRAIN) {
            if (Math.abs(player.getX() - (x + 0.5)) > 2 || Math.abs(player.getZ() - (z + 0.5)) > 2) return;
            if (!context.world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) return;
            // /locate returns the structure anchor; buried treasure chest uses chunk local 9,9.
            int chestX = ((x >> 4) << 4) + 9, chestZ = ((z >> 4) << 4) + 9;
            for (int y = 255; y >= 0; y--) {
                BlockPos pos = new BlockPos(chestX, y, chestZ);
                if (context.world.getBlockState(pos).getBlock() != Blocks.CHEST) continue;
                chest = pos;
                phase = Phase.ACCESS;
                context.phase("CLEAR_CHEST_ACCESS");
                context.command("/fill " + chestX + " " + (y + 1) + " " + chestZ + " "
                        + chestX + " " + (y + 2) + " " + chestZ + " minecraft:air replace");
                context.command("/tp @s " + (chestX + 0.5) + " " + (y + 1.2) + " " + (chestZ + 0.5));
                return;
            }
            // Chunk receipt and block packets may be separate; wait instead of claiming an empty chest.
        } else if (phase == Phase.ACCESS) {
            if (player.getPos().squaredDistanceTo(new Vec3d(chest.getX() + 0.5, chest.getY() + 1.2, chest.getZ() + 0.5)) > 0.2) return;
            if (!context.world.getBlockState(chest.up()).getCollisionShape(context.world, chest.up()).isEmpty()) return;
            if (!context.world.getBlockState(chest.up(2)).getCollisionShape(context.world, chest.up(2)).isEmpty()) return;
            phase = Phase.OPEN;
            context.phase("OPEN_REAL_CHEST");
        } else if (phase == Phase.OPEN || phase == Phase.INVENTORY) {
            if (player.currentScreenHandler instanceof GenericContainerScreenHandler) {
                phase = Phase.INVENTORY;
                // Wait for a FULL inventory packet; the empty handler constructor is not the actual loot.
                return;
            }
            if (opens < 3 && System.nanoTime() - lastOpen > 1_000_000_000L) {
                opens++; lastOpen = System.nanoTime();
                player.pitch = 90.0f;
                BlockHitResult hit = new BlockHitResult(new Vec3d(chest.getX() + 0.5, chest.getY() + 0.875,
                        chest.getZ() + 0.5), Direction.UP, chest, false);
                context.client.interactionManager.interactBlock(player, context.world, Hand.MAIN_HAND, hit);
                context.event("open_chest", Map.of("chest", chest.toShortString(), "attempt", opens));
            }
        }
    }

    @Override public void onInventory(CreativeTestContext context, InventoryS2CPacket packet) {
        if (context.done() || (phase != Phase.OPEN && phase != Phase.INVENTORY)) return;
        if (!(context.client.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) return;
        if (packet.getSyncId() != handler.syncId || handler.getRows() != 3 || packet.getContents().size() < 27) return;
        Map<String, Integer> actual = new TreeMap<>();
        for (int i = 0; i < 27; i++) {
            ItemStack stack = packet.getContents().get(i);
            if (!stack.isEmpty()) actual.merge(Registry.ITEM.getId(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("chest", chest.toShortString());
        details.put("lootSeed", TreasureLootPredictor.lootSeed(seed, x, z));
        details.put("expected", predicted); details.put("actual", actual);
        details.put("differences", differences(predicted, actual));
        finish(context, predicted.equals(actual) ? TestBatch.Outcome.PASS : TestBatch.Outcome.MISMATCH,
                predicted.equals(actual) ? "REAL_CHEST_MATCH" : "REAL_CHEST_MISMATCH", details);
    }

    public static Map<String, Integer> differences(Map<String, Integer> expected, Map<String, Integer> actual) {
        Map<String, Integer> delta = new TreeMap<>(actual);
        expected.forEach((item, count) -> delta.merge(item, -count, Integer::sum));
        delta.values().removeIf(value -> value == 0);
        return delta;
    }

    private void finish(CreativeTestContext context, TestBatch.Outcome outcome, String reason, Map<String, ?> data) {
        Map<String, Object> details = new LinkedHashMap<>(data);
        details.put("seed", seed); details.put("locateX", x); details.put("locateZ", z);
        context.complete(outcome, reason, details);
    }

    @Override public void close(CreativeTestContext context) {
        if (context.client.player != null && context.client.player.currentScreenHandler instanceof GenericContainerScreenHandler)
            context.client.player.closeHandledScreen();
    }
}
