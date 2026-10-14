package com.fredoseep.preflight;

import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.tag.BlockTags;
import net.minecraft.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import java.util.function.Predicate;

/** Read-only geometry with explicit origins. Called exclusively on the owning world thread. */
public final class PreflightGeometry {
    public record Workspace(BlockPos stand, BlockPos table) {}
    private PreflightGeometry() {}
    public static int ground(World world, BlockPos column) {
        int top = Math.min(255, world.getTopPosition(Heightmap.Type.WORLD_SURFACE, column).getY());
        for (int y = top; y > 40; y--) {
            BlockState state = world.getBlockState(new BlockPos(column.getX(), y, column.getZ()));
            if (state.getMaterial().isSolid() && !state.isIn(BlockTags.LEAVES) && !state.isIn(BlockTags.LOGS)) return y;
        }
        return -1;
    }
    public static BlockPos tnt(World world, BlockPos origin, Runnable checkpoint) {
        BlockPos two = null, one = null;
        for (int r = 0; r <= 50; r++) {
            checkpoint.run();
            for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
                if (Math.abs(x) != r && Math.abs(z) != r) continue;
                int px = origin.getX() + x, pz = origin.getZ() + z;
                if (!world.getChunkManager().isChunkLoaded(px >> 4, pz >> 4)) continue;
                int y = ground(world, new BlockPos(px, 0, pz));
                if (y <= 40 || !tntClear(world, new BlockPos(px, y + 1, pz))) continue;
                int trees = 0;
                outer: for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
                    if (dx * dx + dz * dz > 9) continue;
                    for (int dy = 1; dy <= 2; dy++) if (world.getBlockState(new BlockPos(px + dx, y + dy, pz + dz)).isIn(BlockTags.LOGS)) {
                        trees++; if (trees >= 3) break outer; break;
                    }
                }
                if (trees == 0) continue;
                BlockPos candidate = new BlockPos(px, y + (trees >= 3 ? 1 : trees == 2 ? 2 : 3), pz);
                if (!tntClear(world, candidate)) continue;
                if (trees >= 3) return candidate;
                if (trees == 2 && two == null) two = candidate;
                if (trees == 1 && one == null) one = candidate;
            }
        }
        return two != null ? two : one;
    }
    private static boolean tntClear(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.getMaterial().isLiquid() && (state.isAir() || state.isIn(BlockTags.LEAVES)
                || state.getMaterial().isReplaceable() || state.getHardness(world, pos) == 0.0f);
    }
    public static BlockPos table(World world, BlockPos stand) {
        for (Direction direction : new Direction[]{Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH}) {
            BlockPos pos = stand.offset(direction);
            for (int dy : new int[]{0, 1, -1}) {
                BlockPos candidate = pos.add(0, dy, 0);
                if (world.getBlockState(candidate).getMaterial().isReplaceable()
                        && world.getBlockState(candidate.down()).getMaterial().isSolid()) return candidate;
            }
        }
        return null;
    }
    public static Workspace workspace(World world, BlockPos origin, boolean avoidBlast, Runnable checkpoint) {
        for (int r = avoidBlast ? 7 : 0; r <= 32; r++) {
            checkpoint.run();
            for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
                if (Math.abs(x) != r && Math.abs(z) != r) continue;
                if (!world.getChunkManager().isChunkLoaded((origin.getX() + x) >> 4, (origin.getZ() + z) >> 4)) continue;
                BlockPos col = origin.add(x, 0, z);
                int y = ground(world, col);
                if (y <= 40) continue;
                BlockPos feet = new BlockPos(col.getX(), y + 1, col.getZ());
                if (!clear(world, feet) || !clear(world, feet.up())
                        || !world.getBlockState(feet).getFluidState().isEmpty()) continue;
                BlockPos table = table(world, feet);
                if (table != null) return new Workspace(feet, table);
            }
        }
        return null;
    }
    public static boolean clear(World world, BlockPos pos) { return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty(); }
    public static boolean exposedGravel(World world, BlockPos pos) {
        return clear(world, pos.up()) && clear(world, pos.up(2))
                && !world.getBlockState(pos.up()).getFluidState().isIn(FluidTags.LAVA)
                && !world.getBlockState(pos.up(2)).getFluidState().isIn(FluidTags.LAVA);
    }
    public static BlockPos floor(World world, BlockPos column) {
        for (int y = 100; y >= 11; y--) {
            BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
            if (world.getBlockState(pos).getMaterial().isSolid()) return pos;
        }
        return new BlockPos(column.getX(), 11, column.getZ());
    }
    public static BlockPos find(World world, BlockPos origin, int radius, Predicate<BlockPos> target, Runnable checkpoint) {
        for (int r = 0; r <= radius; r++) {
            checkpoint.run();
            for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
                if (Math.abs(x) != r && Math.abs(z) != r || x * x + z * z > radius * radius) continue;
                for (int y = Math.max(0, origin.getY() - radius); y <= Math.min(255, origin.getY() + radius); y++) {
                    BlockPos pos = origin.add(x, y - origin.getY(), z);
                    if (pos.getSquaredDistance(origin) <= radius * radius && target.test(pos)) return pos;
                }
            }
        }
        return null;
    }
}
