package com.fredoseep.utils.prenether;

import com.fredoseep.behave.MiscController;
import com.fredoseep.preflight.EarlyResetPreflight;
import com.fredoseep.preflight.RavineFinder;
import com.fredoseep.preflight.PreflightGeometry;
import com.fredoseep.behave.CraftingController;
import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.GlobalExecutor;
import com.fredoseep.excutor.PathExecutor;
import com.fredoseep.utils.bt.BtStuff;
import com.fredoseep.utils.player.InventoryHelper;
import com.fredoseep.utils.player.MiningHelper;
import com.fredoseep.utils.player.PlayerHelper;
import com.fredoseep.utils.player.RelevantDirectionHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biomes;

import java.util.Map;

public class PreNether {
    public static BlockPos magmaPos = null;
    public static BlockPos alignedMagmaPos = null;
    public static Direction fromMagmaToAligned = null;
    private static BlockPos gravelPos = null;
    private static MagmaDescent magmaDescent;
    private static BlockPos requestedMagma;
    private static World requestWorld;
    private static int pendingRavineTicks;
    public static String magmaFallbackStatus(){return magmaDescent==null?null:magmaDescent.hudStatus();}
    public static void requestMagmaRoute(PlayerEntity player,PathExecutor path) {
        if(magmaPos==null || magmaDescent!=null)return;
        requestedMagma=magmaPos.toImmutable();requestWorld=MinecraftClient.getInstance().world;
        magmaDescent=new MagmaDescent(MinecraftClient.getInstance(),requestedMagma);
    }

    public static void reset() {
        pendingRavineTicks = 0;
        magmaPos = null;
        alignedMagmaPos = null;
        gravelPos = null;
        fromMagmaToAligned = null;
        if(magmaDescent!=null)MagmaDescent.release(MinecraftClient.getInstance());
        magmaDescent=null;requestedMagma=null;requestWorld=null;
    }

    public static void preNetherStuff(PlayerEntity player) {
        GlobalExecutor globalExecutor = BotEngine.getInstance().getModule(GlobalExecutor.class);
        PathExecutor pathExecutor = BotEngine.getInstance().getModule(PathExecutor.class);
        MinecraftClient client=MinecraftClient.getInstance();
        if(requestedMagma!=null && (client.world!=requestWorld || !requestedMagma.equals(magmaPos))) {
            if(magmaDescent!=null)MagmaDescent.release(client);
            magmaDescent=null;requestedMagma=null;requestWorld=null;
        }
        if(magmaDescent!=null) {
            if(BotEngine.getInstance().getModule(MiscController.class).isBusy()
                    || BotEngine.getInstance().getModule(CraftingController.class).isBusy())return;
            if (magmaDescent.descending() && PlayerHelper.isDrivingBoat(player)) {
                MagmaDescent.release(client);
                BotEngine.getInstance().getModule(MiscController.class).startBoatRecoveryForDescent(magmaPos.up());
                return;
            }
            magmaDescent.tick(client,player,pathExecutor);
            if(magmaDescent.failed()) {BotEngine.getInstance().stop();return;}
            if(!magmaDescent.done())return;
            magmaDescent=null;requestedMagma=null;requestWorld=null;
            globalExecutor.currentState=GlobalExecutor.GlobalState.BUILDING_TWO_BY_ONE_NETHER_PORTAL;return;
        }

        if (magmaPos == null) {
            var saved = EarlyResetPreflight.plan(client);
            if (saved != null) {
                if (!client.world.getChunkManager().isChunkLoaded(saved.ravine().magma().getX() >> 4,
                        saved.ravine().magma().getZ() >> 4)) {
                    if (!pathExecutor.isBusy()) pathExecutor.setGoal(saved.workspace().stand(), "return to preflight workbench");
                    return;
                }
                if (saved.ravine().pendingTicks() && !RavineFinder.validNow(client.world, saved.ravine())) {
                    if (++pendingRavineTicks <= 40) return; // allow the scheduled bubble/mixing ticks after resume
                    magmaPos = findOceanRavine(player, client.world, 100);
                } else {
                    magmaPos = saved.ravine().magma(); alignedMagmaPos = saved.ravine().aligned();
                    fromMagmaToAligned = saved.ravine().direction();
                }
            } else magmaPos = findOceanRavine(player, client.world, 100);
            if (magmaPos == null) {
                globalExecutor.resetWorld("找不到可进入的 magma 裂谷");
                System.out.println("Fredodebug: reset because no enterable ravine found");
                return;
            } else {
                System.out.println("Fredodebug: magmaPos:" + magmaPos.toShortString());

                if (InventoryHelper.countItem(player, Items.GRAVEL) >= 4 || InventoryHelper.countItem(player, Items.FLINT) >= 1 || InventoryHelper.countItem(player, Items.FLINT_AND_STEEL) == 1) {
                    System.out.println("Fredodebug: already have lighter");
                    return;
                }
                if (MinecraftClient.getInstance().world.getBiome(magmaPos) == Biomes.WARM_OCEAN || MinecraftClient.getInstance().world.getBiome(magmaPos) == Biomes.DEEP_WARM_OCEAN || MinecraftClient.getInstance().world.getBiome(magmaPos) == Biomes.LUKEWARM_OCEAN || MinecraftClient.getInstance().world.getBiome(magmaPos) == Biomes.DEEP_LUKEWARM_OCEAN) {
                    System.out.println("Fredodebug: magmaPos is in warm ocean . get lighter now");
                    MiningHelper.mineAndCollect(BtStuff.workbenchAnchor != null ? BtStuff.workbenchAnchor : player.getBlockPos(), Map.of(Blocks.GRAVEL, 4), 50);
                    return;
                }
            }
            if (magmaPos == null) return;
            World world=MinecraftClient.getInstance().world;
            var exposed=MiningHelper.findNearestBlocks(BtStuff.oceanFloorNoKelp(magmaPos),Map.of(Blocks.GRAVEL,4),40,
                    p -> exposedGravelApproach(world,p));
            if(exposed.isEmpty()) {
                System.out.println("FredoBot: no exposed gravel with a clear approach near the ravine; reset instead of pathing into buried sand");
                globalExecutor.resetWorld("裂谷附近无可挖沙砾");return;
            }
            gravelPos=exposed.getFirst();
            System.out.println("Fredodebug: ocean floor gravel: " + gravelPos.toShortString());

        }
        if (pathExecutor.isBusy() || BotEngine.getInstance().getModule(MiscController.class).isBusy()) return;
        if (!PlayerHelper.isNear(player, magmaPos.up(), 1)) {
            if (InventoryHelper.countItem(player, Items.GRAVEL) >= 4 || InventoryHelper.countItem(player, Items.FLINT) >= 1 || InventoryHelper.countItem(player, Items.FLINT_AND_STEEL) == 1) {
                System.out.println("Fredodebug: already have lighter goto magma spot");
                requestMagmaRoute(player,pathExecutor);
                return;
            } else {
                if (gravelPos != null) {
                    if(!exposedGravelApproach(MinecraftClient.getInstance().world,gravelPos)) {magmaPos=null;gravelPos=null;return;}
                    if (!PlayerHelper.isNear(player, gravelPos.up(), 1)) {
                        System.out.println("Fredodebug : set goal to the gravel Pos");
                        pathExecutor.setGoal(gravelPos.up(), "the gravel pos");
                    } else {
                        MiningHelper.mineAndCollect(player, Map.of(Blocks.GRAVEL, 4), 10);
                        System.out.println("Fredobug: already at gravel spot");

                    }
                }
            }
        } else {
            globalExecutor.currentState = GlobalExecutor.GlobalState.BUILDING_TWO_BY_ONE_NETHER_PORTAL;
            System.out.println("Fredodebug: building two by one");
        }
    }

    private static boolean exposedGravelApproach(World world, BlockPos gravel) {
        return world.getChunkManager().isChunkLoaded(gravel.getX() >> 4, gravel.getZ() >> 4)
                && PreflightGeometry.exposedGravel(world, gravel);
    }

    /**
     * 极速扫描海底峡谷 (针对 1.16.1 Y=10 岩浆块生成规律的特化版)
     * * @param player 当前玩家
     *
     * @param world  当前世界
     * @param radius 水平扫描半径 (例如 100 代表 200x200 的正方形范围)
     * @return 距离玩家最近的海底峡谷峡谷底坐标 (Y=10 的岩浆块)，如果没找到则返回 null
     */
    public static BlockPos findOceanRavine(PlayerEntity player, World world, int radius) {
        BlockPos origin = BtStuff.workbenchAnchor != null ? BtStuff.workbenchAnchor : player.getBlockPos();
        var found = RavineFinder.find(world, origin, radius, () -> {});
        if (found == null) return null;
        alignedMagmaPos = found.aligned(); fromMagmaToAligned = found.direction();
        return found.magma();
    }
}
