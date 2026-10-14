package com.fredoseep.preflight;

import net.minecraft.block.Blocks;
import net.minecraft.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/** Shared geometry for preflight and execution. No PreNether static state is mutated by a scan. */
public final class RavineFinder {
    public record Candidate(BlockPos magma, BlockPos aligned, Direction direction, boolean pendingTicks) {}
    private RavineFinder() {}
    public static Candidate find(World world, BlockPos origin, int radius, Runnable checkpoint) {
        return find(world, origin, radius, checkpoint, false);
    }
    public static Candidate find(World world, BlockPos origin, int radius, Runnable checkpoint, boolean predictPendingTicks) {
        Candidate best = null;
        double distance = Double.MAX_VALUE;
        for (int x = -radius; x <= radius; x++) {
            checkpoint.run();
            for (int z = -radius; z <= radius; z++) {
                BlockPos pos = new BlockPos(origin.getX() + x, 10, origin.getZ() + z);
                if (world.getBlockState(pos).getBlock() != Blocks.MAGMA_BLOCK || !column(world, pos, predictPendingTicks)) continue;
                for (Direction direction : new Direction[]{Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH}) {
                    BlockPos aligned = pos.offset(direction);
                    if (world.getBlockState(aligned).getBlock() != Blocks.MAGMA_BLOCK || !column(world, aligned, predictPendingTicks)) continue;
                    if (!obsidian(world, aligned.offset(direction), predictPendingTicks)
                            || !obsidian(world, pos.offset(direction.getOpposite()), predictPendingTicks)) continue;
                    double d = origin.getSquaredDistance(pos);
                    if (d < distance) {
                        boolean pending = predictPendingTicks && (!realBubbles(world, pos) || !realBubbles(world, aligned)
                                || world.getBlockState(aligned.offset(direction)).getBlock() != Blocks.OBSIDIAN
                                || world.getBlockState(pos.offset(direction.getOpposite())).getBlock() != Blocks.OBSIDIAN);
                        best = new Candidate(pos, aligned, direction, pending); distance = d;
                    }
                    break;
                }
            }
        }
        return best;
    }
    private static boolean column(World world, BlockPos pos, boolean pending) {
        return world.getBlockState(pos.down()).getBlock() == Blocks.LAVA
                && world.getBlockState(pos.down(2)).getBlock() == Blocks.LAVA
                && (!pending || !obsidian(world, pos.down(), true) && !obsidian(world, pos.down(2), true))
                && bubble(world, pos.up(), pending)
                && bubble(world, pos.up(2), pending)
                && bubble(world, pos.up(3), pending);
    }
    private static boolean realBubbles(World world, BlockPos pos) {
        return world.getBlockState(pos.up()).getBlock() == Blocks.BUBBLE_COLUMN
                && world.getBlockState(pos.up(2)).getBlock() == Blocks.BUBBLE_COLUMN
                && world.getBlockState(pos.up(3)).getBlock() == Blocks.BUBBLE_COLUMN;
    }
    public static boolean validNow(World world, Candidate candidate) {
        return world.getBlockState(candidate.magma()).getBlock() == Blocks.MAGMA_BLOCK
                && world.getBlockState(candidate.aligned()).getBlock() == Blocks.MAGMA_BLOCK
                && column(world, candidate.magma(), false) && column(world, candidate.aligned(), false)
                && obsidian(world, candidate.aligned().offset(candidate.direction()), false)
                && obsidian(world, candidate.magma().offset(candidate.direction().getOpposite()), false);
    }
    private static boolean bubble(World world, BlockPos pos, boolean pending) {
        var state = world.getBlockState(pos);
        return state.getBlock() == Blocks.BUBBLE_COLUMN || pending && state.getBlock() == Blocks.WATER && state.getFluidState().isStill();
    }
    private static boolean obsidian(World world, BlockPos pos, boolean pending) {
        var state = world.getBlockState(pos);
        if (state.getBlock() == Blocks.OBSIDIAN) return true;
        if (!pending || state.getBlock() != Blocks.LAVA || !state.getFluidState().isStill()) return false;
        // Vanilla lava mixing tests above and horizontal neighbors, never below.
        for (Direction direction : Direction.values()) if (direction != Direction.DOWN
                && world.getBlockState(pos.offset(direction)).getFluidState().isIn(FluidTags.WATER)) return true;
        return false;
    }
}
