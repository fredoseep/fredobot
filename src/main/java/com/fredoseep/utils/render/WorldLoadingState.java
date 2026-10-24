package com.fredoseep.utils.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.chunk.ChunkStatus;

public class WorldLoadingState {

    private static long lastLogNanos;

    public static boolean areAllChunksLoaded() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;

        if (world == null || client.player == null) {
            return false;
        }

        int radius = client.options.viewDistance;
        int centerX = client.player.getBlockPos().getX() >> 4;
        int centerZ = client.player.getBlockPos().getZ() >> 4;

        int missing = 0;
        int missingInner = 0;
        int missingEdge = 0;
        int firstMissingX = 0;
        int firstMissingZ = 0;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int chunkX = centerX + dx;
                int chunkZ = centerZ + dz;

                if (world.getChunkManager().getChunk(
                        chunkX, chunkZ, ChunkStatus.FULL, false) != null) {
                    continue;
                }

                if (missing == 0) {
                    firstMissingX = chunkX;
                    firstMissingZ = chunkZ;
                }

                missing++;

                if (Math.abs(dx) == radius || Math.abs(dz) == radius) {
                    missingEdge++;
                } else {
                    missingInner++;
                }
            }
        }

        long now = System.nanoTime();

        if (now - lastLogNanos >= 2_000_000_000L) {
            lastLogNanos = now;

            IntegratedServer server = client.getServer();
            int serverDistance = server == null
                    ? -1
                    : server.getPlayerManager().getViewDistance();

            int width = radius * 2 + 1;
            int required = width * width;

            System.out.println(
                    "[ChunkLoad] dimension=" + world.getRegistryKey().getValue()
                            + " paused=" + client.isPaused()
                            + " clientDistance=" + radius
                            + " serverDistance=" + serverDistance
                            + " center=" + centerX + "," + centerZ
                            + " ready=" + (required - missing) + "/" + required
                            + " missingInner=" + missingInner
                            + " missingEdge=" + missingEdge
                            + " firstMissing="
                            + (missing == 0
                            ? "none"
                            : firstMissingX + "," + firstMissingZ)
            );
        }

        return missing == 0;
    }

}
