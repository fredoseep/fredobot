package com.fredoseep.utils.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.chunk.ChunkStatus;
import java.util.ArrayList;
import java.util.List;

public class WorldLoadingState {

    private static long lastLogNanos;

    public static boolean areAllChunksLoaded() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;

        if (world == null || client.player == null) {
            return false;
        }

        int requestedRadius = client.options.viewDistance;
        IntegratedServer server = client.getServer();
        int serverDistance = server == null ? -1 : server.getPlayerManager().getViewDistance();
        // Singleplayer's actual sending distance can be smaller than the UI setting.
        int radius = serverDistance >= 0 ? Math.min(requestedRadius, serverDistance) : requestedRadius;
        int centerX = client.player.getBlockPos().getX() >> 4;
        int centerZ = client.player.getBlockPos().getZ() >> 4;

        int missing = 0;
        int missingInner = 0;
        int missingEdge = 0;
        List<String> missingPositions = new ArrayList<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int chunkX = centerX + dx;
                int chunkZ = centerZ + dz;

                if (world.getChunkManager().getChunk(
                        chunkX, chunkZ, ChunkStatus.FULL, false) != null) {
                    continue;
                }

                if (missingPositions.size() < 16) {
                    missingPositions.add(chunkX + "," + chunkZ + " (offset=" + dx + "," + dz + ")");
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

            int width = radius * 2 + 1;
            int required = width * width;

            System.out.println(
                    "[ChunkLoad] dimension=" + world.getRegistryKey().getValue()
                            + " paused=" + client.isPaused()
                            + " clientDistance=" + requestedRadius
                            + " checkedRadius=" + radius
                            + " serverDistance=" + serverDistance
                            + " center=" + centerX + "," + centerZ
                            + " ready=" + (required - missing) + "/" + required
                            + " missingInner=" + missingInner
                            + " missingEdge=" + missingEdge
                            + " missingPositions=" + missingPositions
                            + " omitted=" + Math.max(0, missing - missingPositions.size())
            );
        }

        return missing == 0;
    }

}
