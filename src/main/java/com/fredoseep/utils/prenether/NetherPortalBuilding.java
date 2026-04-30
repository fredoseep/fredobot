package com.fredoseep.utils.prenether;

import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.GlobalExecutor;
import com.fredoseep.utils.player.InventoryHelper;
import com.fredoseep.utils.player.MiningHelper;
import com.fredoseep.utils.player.RelevantDirectionHelper;
import com.fredoseep.utils.player.ToolsHelper;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.item.BucketItem;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.LinkedHashSet;

public class NetherPortalBuilding {
    private static LinkedHashSet<BlockPos> twoByOneAreaClearPos = new LinkedHashSet<>();
    private static LinkedHashSet<BlockPos> missingObiPosList = new LinkedHashSet<>();
    private static BlockHitResult topHitPosHitResult = null;
    private static BlockPos magmaSideMiddleFragmentPos = null;
    private static BlockPos alignedSideMiddleFragmentPos = null;

    public static void resetState() {
        currentTBOState = TwoByOneBuildState.IDLE;
        topHitPosHitResult = null;
        magmaSideMiddleFragmentPos = null;
        alignedSideMiddleFragmentPos = null;
        twoByOneAreaClearPos.clear();
        missingObiPosList.clear();
    }

    private enum TwoByOneBuildState {
        IDLE, CLEARING_AREA,SETTING_UP_HIT_POINT,BUILDING,CLEARING_SPACE;
    }

    private static TwoByOneBuildState currentTBOState = TwoByOneBuildState.IDLE;

    public static void twoByOneBuild() {
        MinecraftClient client = MinecraftClient.getInstance();
        GlobalExecutor globalExecutor = BotEngine.getInstance().getModule(GlobalExecutor.class);
        KeyBinding.setKeyPressed(client.options.keySneak.getDefaultKey(), true);
        switch (currentTBOState) {
            case IDLE:
                if (PreNether.alignedMagmaPos == null) {
                    globalExecutor.resetWorld();
                    System.out.println("Fredodebug: reset because alignedMagmaPos is null somehow");
                } else System.out.println("Fredodebug: alignedMagmaPos : " + PreNether.alignedMagmaPos.toShortString());
                currentTBOState = TwoByOneBuildState.CLEARING_AREA;
                break;
            case CLEARING_AREA:
                if (twoByOneAreaClearPos.isEmpty() && areaNotClear()) {
                    if(twoByOneAreaClearPos.isEmpty())return;
                    BlockPos currentPos = twoByOneAreaClearPos.getFirst();
                    ToolsHelper.equipBestTool(client.player, currentPos,false);
                    client.interactionManager.updateBlockBreakingProgress(currentPos,Direction.UP);
                    return;
                }
                currentTBOState = TwoByOneBuildState.SETTING_UP_HIT_POINT;
                break;
            case SETTING_UP_HIT_POINT:
                Direction topHitPosRelevantDirection =  RelevantDirectionHelper.getIrrelevantDirections(PreNether.magmaPos,PreNether.alignedMagmaPos)[0];
                BlockPos topHitPos = PreNether.alignedMagmaPos.up().up().offset(topHitPosRelevantDirection);
                topHitPosHitResult = new BlockHitResult(Vec3d.ofCenter(topHitPos),topHitPosRelevantDirection.getOpposite(),topHitPos,false);
                boolean topHitPosHittable = RelevantDirectionHelper.isValidHitResult(client.player, client.world,topHitPosHitResult);
                if(topHitPosHittable){
                    currentTBOState = TwoByOneBuildState.BUILDING;
                    return;
                }
                BlockHitResult downBlockHitResult = new BlockHitResult(Vec3d.ofCenter(topHitPos.down()),Direction.UP,topHitPos.down(),false);
                boolean downHitPosHittable = RelevantDirectionHelper.isValidHitResult(client.player,client.world,downBlockHitResult);
                if(downHitPosHittable){
                    System.out.println("Fredodebug: top Block gap but Placeable trying to place the block: " + client.interactionManager.interactBlock(client.player,client.world,Hand.MAIN_HAND,downBlockHitResult));
                }
                else{
                    System.out.println("Fredodebug: downHitPosInvalid, trying to place the block : "+ client.interactionManager.interactBlock(client.player,client.world, Hand.MAIN_HAND,new BlockHitResult(Vec3d.ofCenter(topHitPos.down(2)),Direction.UP,topHitPos.down(2),false)));
                }
                return;

            case BUILDING:
                InventoryHelper.moveItemToHotbar(client,client.player, BucketItem.class,3);
                BlockPos missingObiPos = getMissingObiPos();
                if(missingObiPos==null){
                    System.out.println("Fredodebug: no obi place is missing . Clearing Space");
                    currentTBOState = TwoByOneBuildState.CLEARING_SPACE;
                    return;
                }
                BlockPos lavaSourcePos = findLavaSource();
                if(lavaSourcePos==null){
                    globalExecutor.resetWorld();
                    System.out.println("Fredodebug: reset because lava is not enough");
                }
                if(missingObiPos==magmaSideMiddleFragmentPos.up()){

                }
        }


    }

    private static BlockPos getMissingObiPos() {
        BlockPos magmaPos = PreNether.magmaPos;
        BlockPos alignedMagmaPos = PreNether.alignedMagmaPos;
        Direction relevantDirectionFromMagmaToAligned = RelevantDirectionHelper.getDirectionBetween(magmaPos,alignedMagmaPos);
        magmaSideMiddleFragmentPos = magmaPos.offset(relevantDirectionFromMagmaToAligned.getOpposite());
       alignedSideMiddleFragmentPos = alignedMagmaPos.offset(relevantDirectionFromMagmaToAligned);
        if(missingObiPosList.isEmpty()){
            if(!isObiFragmentSettled(magmaSideMiddleFragmentPos.up())){
                missingObiPosList.add(magmaSideMiddleFragmentPos.up());
            }
            if(!isObiFragmentSettled(alignedSideMiddleFragmentPos.up())){
                missingObiPosList.add(alignedMagmaPos.up());
            }
            if(!isObiFragmentSettled(magmaPos.up(2))){
                missingObiPosList.add(magmaPos.up(2));
            }
            if(!isObiFragmentSettled(alignedMagmaPos.up(2))){
                missingObiPosList.add(alignedMagmaPos.up(2));
            }
            if(!isObiFragmentSettled(magmaPos.down(2))){
                missingObiPosList.add(magmaPos.down(2));
            }
            if(!isObiFragmentSettled(alignedMagmaPos.down(2))){
                missingObiPosList.add(alignedMagmaPos.down(2));
            }
            if(!isObiFragmentSettled(magmaSideMiddleFragmentPos.down())){
                missingObiPosList.add(magmaSideMiddleFragmentPos.down());
            }
            if(!isObiFragmentSettled(alignedSideMiddleFragmentPos.down())){
                missingObiPosList.add(alignedSideMiddleFragmentPos.down());
            }
        }
        else{
            missingObiPosList.removeIf(NetherPortalBuilding::isObiFragmentSettled);
        }
        if(missingObiPosList.isEmpty())return null;
        return missingObiPosList.getFirst();
    }

    private static boolean isObiFragmentSettled(BlockPos checkPos){
        BlockState blockState = MinecraftClient.getInstance().world.getBlockState(checkPos);
        return blockState.getBlock() == Blocks.OBSIDIAN || blockState.getBlock() == Blocks.LAVA;
    }

    private static BlockPos findLavaSource() {
        BlockPos magmaPos = PreNether.magmaPos;
        BlockPos alignedMagmaPos = PreNether.alignedMagmaPos;
        if(isLavaSource(magmaPos.down()))return magmaPos.down();
        if(isLavaSource(alignedMagmaPos.down()))return alignedMagmaPos.down();
        for(Direction offset : RelevantDirectionHelper.getIrrelevantDirections(magmaPos,alignedMagmaPos)){
            if(isLavaSource(magmaPos.down().offset(offset)))return magmaPos.down().offset(offset);
            if(isLavaSource(alignedMagmaPos.down().offset(offset)))return alignedMagmaPos.down().offset(offset);

        }
        return null;
    }

    private static boolean isLavaSource(BlockPos pos) {
        return MinecraftClient.getInstance().world.getBlockState(pos).getBlock() == Blocks.LAVA;
    }

    private static boolean areaNotClear() {
        boolean isNotClear = false;
        if (twoByOneAreaClearPos.isEmpty()) {
            BlockPos magmaPos = PreNether.magmaPos.up();
            BlockPos alignedMagmaPos = PreNether.alignedMagmaPos.up();
            if (isNotClear(magmaPos)) {
                isNotClear = true;
                twoByOneAreaClearPos.add(magmaPos);
            }
            if (isNotClear(alignedMagmaPos)) {
                isNotClear = true;
                twoByOneAreaClearPos.add(alignedMagmaPos);
            }
            if(isNotClear(magmaPos.offset(RelevantDirectionHelper.getDirectionBetween(alignedMagmaPos,magmaPos)))){
                isNotClear = true;
                twoByOneAreaClearPos.add(magmaPos.offset(RelevantDirectionHelper.getDirectionBetween(alignedMagmaPos,magmaPos)));
            }
            if(isNotClear(alignedMagmaPos.offset((RelevantDirectionHelper.getDirectionBetween(magmaPos,alignedMagmaPos))))){
                isNotClear = true;
                twoByOneAreaClearPos.add(alignedMagmaPos.offset((RelevantDirectionHelper.getDirectionBetween(magmaPos,alignedMagmaPos))));
            }
            if(isNotClear(PreNether.magmaPos.down(2))){
                isNotClear = true;
                twoByOneAreaClearPos.add(PreNether.magmaPos.down(2));
            }
            if(isNotClear(PreNether.alignedMagmaPos.down(2))){
                isNotClear = true;
                twoByOneAreaClearPos.add((PreNether.alignedMagmaPos.down(2)));
            }
        }
        else{
            for(BlockPos currentPos : twoByOneAreaClearPos){
                if(isNotClear(currentPos)){
                    isNotClear = true;
                }
                else twoByOneAreaClearPos.remove(currentPos);
            }
        }
        return isNotClear;
    }

    private static boolean isNotClear(BlockPos pos) {
        AbstractBlock.AbstractBlockState blockState = MinecraftClient.getInstance().world.getBlockState(pos);
        return blockState.getMaterial().isSolid() || blockState.getBlock().is(Blocks.KELP) || blockState.getBlock().is(Blocks.KELP_PLANT) || blockState.getBlock().is(Blocks.SEAGRASS) || blockState.getBlock().is(Blocks.TALL_SEAGRASS) || blockState.getBlock().is(Blocks.SEA_PICKLE);
    }

}
