package com.fredoseep.utils.prenether;

import com.fredoseep.behave.MovementController;
import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.GlobalExecutor;
import com.fredoseep.utils.player.InventoryHelper;
import com.fredoseep.utils.player.MiningHelper;
import com.fredoseep.utils.player.RelevantDirectionHelper;
import com.fredoseep.utils.player.ToolsHelper;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BucketItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.system.CallbackI;

import java.time.chrono.MinguoEra;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class NetherPortalBuilding {
    private static LinkedHashSet<BlockPos> twoByOneAreaClearPos = new LinkedHashSet<>();
    private static LinkedHashSet<BlockPos> missingObiPosList = new LinkedHashSet<>();
    private static BlockHitResult topHitPosHitResult = null;
    private static BlockPos magmaSideMiddleFragmentPos = null;
    private static BlockPos alignedSideMiddleFragmentPos = null;
    public static int lavaPlaceStep = 0;
    private static Vec3d storedRealPos = null;
    private static float storedRealYaw = 0f;
    private static float storedRealPitch = 0f;
    private static BlockPos activePlacePos = null;          // 【新增】
    private static Direction activePlaceDirection = null;
    private static boolean initGestureAdjusted = false;
    private static boolean againstTheDoor = false;

    public static int lavaGrabStep = 0;
    private static Vec3d grabStoredRealPos = null;
    private static float grabStoredRealYaw = 0f;
    private static float grabStoredRealPitch = 0f;
    private static BlockPos activeGrabPos = null;           // 【新增】

    public static void resetState() {
        currentTBOState = TwoByOneBuildState.IDLE;
        topHitPosHitResult = null;
        magmaSideMiddleFragmentPos = null;
        alignedSideMiddleFragmentPos = null;
        twoByOneAreaClearPos.clear();
        missingObiPosList.clear();
        lavaPlaceStep = 0;
        lavaGrabStep = 0;
        activePlacePos = null;
        activePlaceDirection = null;
        activeGrabPos = null;
        initGestureAdjusted = false;
        againstTheDoor = false;
        KeyBinding.setKeyPressed(MinecraftClient.getInstance().options.keyLeft.getDefaultKey(), false);
        KeyBinding.setKeyPressed(MinecraftClient.getInstance().options.keyForward.getDefaultKey(), false);

    }

    public enum TwoByOneBuildState {
        IDLE, CLEARING_AREA, SETTING_UP_HIT_POINT, SETTING_UP_DOOR, ADJUSTING_STANDING_POINT, BREAKING_FIRST_MAGMA_BLOCK, QUADRUPLE_GRAB, SEALING_SPACE, FIRST_WATER_MOVE, SECOND_WATER_MOVE,PENDING, THIRD_WATER_MOVE, FOURTH_WATER_MOVE, CLEARING_SPACE, FORCING_LIGHTER, NEXT;
    }

    public static TwoByOneBuildState currentTBOState = TwoByOneBuildState.IDLE;

    public static void twoByOneBuild() {
        MinecraftClient client = MinecraftClient.getInstance();
        GlobalExecutor globalExecutor = BotEngine.getInstance().getModule(GlobalExecutor.class);
        boolean sneak = client.world.getBlockState(client.player.getBlockPos().down()).getBlock() == Blocks.MAGMA_BLOCK;
        if (client.player.isSwimming()) {
            MovementController.setLookDirection(client.player, client.player.yaw, -90);
            initGestureAdjusted = false;
            sneak = false;
        }
        if (!initGestureAdjusted) {
            KeyBinding.setKeyPressed(client.options.keySneak.getDefaultKey(), sneak);
            initGestureAdjusted = true;
            return;
        }
        if (client.player.pitch == -90) client.player.pitch = 2;
        KeyBinding.setKeyPressed(client.options.keySneak.getDefaultKey(), sneak);


        if (lavaGrabStep > 0) {
            grabLava(client, client.player, null);
            return;
        }
        if (lavaPlaceStep > 0) {
            placeLava(client, client.player, null, null);
            return;
        }

        switch (currentTBOState) {
            case IDLE:
                if (PreNether.alignedMagmaPos == null) {
                    globalExecutor.resetWorld();
                    System.out.println("Fredodebug: reset because alignedMagmaPos is null somehow");
                } else System.out.println("Fredodebug: alignedMagmaPos : " + PreNether.alignedMagmaPos.toShortString());
                magmaSideMiddleFragmentPos = PreNether.magmaPos.offset(PreNether.fromMagmaToAligned.getOpposite());
                alignedSideMiddleFragmentPos = PreNether.alignedMagmaPos.offset(PreNether.fromMagmaToAligned);
                currentTBOState = TwoByOneBuildState.CLEARING_AREA;
                break;
            case CLEARING_AREA:
                if (areaNotClear()) {
                    if (twoByOneAreaClearPos.isEmpty()) {
                        System.out.println("Fredodebug: twoByOneAreaClearPos is empty ");
                        return;
                    }
                    System.out.println("Fredodebug: twoByOneAreaClearPos: " + twoByOneAreaClearPos.toString());
                    BlockPos currentPos = twoByOneAreaClearPos.getFirst();
                    ToolsHelper.equipBestTool(client.player, currentPos, false);
                    System.out.println("Fredodubug: clearing area Pos: " + currentPos.toShortString() + " state: " + client.interactionManager.updateBlockBreakingProgress(currentPos, Direction.UP));
                    return;
                }
                currentTBOState = TwoByOneBuildState.SETTING_UP_HIT_POINT;
                break;
            case SETTING_UP_HIT_POINT:
                Direction topHitPosRelevantDirection = RelevantDirectionHelper.getRightDirection(RelevantDirectionHelper.getDirectionBetween(PreNether.magmaPos, PreNether.alignedMagmaPos));
                BlockPos topHitPos = PreNether.magmaPos.up().up().offset(topHitPosRelevantDirection);
                topHitPosHitResult = new BlockHitResult(Vec3d.ofCenter(topHitPos), topHitPosRelevantDirection.getOpposite(), topHitPos, false);
                boolean topHitPosHittable = RelevantDirectionHelper.isValidHitResult(client.player, client.world, topHitPosHitResult);
                if (topHitPosHittable) {
                    currentTBOState = TwoByOneBuildState.SETTING_UP_DOOR;
                    return;
                }
                client.player.inventory.selectedSlot = 6;
                BlockHitResult downBlockHitResult = new BlockHitResult(Vec3d.ofCenter(topHitPos.down()), Direction.UP, topHitPos.down(), false);
                boolean downHitPosHittable = RelevantDirectionHelper.isValidHitResult(client.player, client.world, downBlockHitResult);
                InventoryHelper.selectBuildingBlock(client.player, true);
                if (downHitPosHittable) {
                    System.out.println("Fredodebug: top Block gap but Placeable trying to place the block: " + client.interactionManager.interactBlock(client.player, client.world, Hand.MAIN_HAND, downBlockHitResult));
                    currentTBOState = TwoByOneBuildState.SETTING_UP_DOOR;
                    return;
                } else {
                    System.out.println("Fredodebug: downHitPosInvalid, trying to place the block pos: " + topHitPos.down(1).toShortString() + " state: " + client.interactionManager.interactBlock(client.player, client.world, Hand.MAIN_HAND, new BlockHitResult(Vec3d.ofCenter(topHitPos.down(2)), Direction.UP, topHitPos.down(2), false)));
                }
                break;
            case SETTING_UP_DOOR:

                if (!(client.world.getBlockState(PreNether.magmaPos.up()).getBlock() instanceof DoorBlock)) {
                    if (!(DoorBlock.getBlockFromItem(client.player.inventory.getStack(8).getItem()) instanceof DoorBlock && client.player.inventory.getStack(8).getCount() >= 2)) {
                        System.out.println("Fredodebug: slot 8 is : " + client.player.inventory.getStack(8).getItem().toString());
                        System.out.println("Fredodebug: reset because the doors are not enough");
                        globalExecutor.resetWorld();
                        return;
                    }
                    tryPlaceDoor(client.player, PreNether.magmaPos, PreNether.fromMagmaToAligned.getOpposite(), RelevantDirectionHelper.getRightDirection(PreNether.fromMagmaToAligned.getOpposite()));
                    return;
                }
                BlockPos secondDoorBasePos = PreNether.magmaPos.offset(RelevantDirectionHelper.getRightDirection(PreNether.fromMagmaToAligned).getOpposite());
                if (!(client.world.getBlockState(secondDoorBasePos.up()).getBlock() instanceof DoorBlock)) {
                    if (!(DoorBlock.getBlockFromItem(client.player.inventory.getStack(8).getItem()) instanceof DoorBlock && client.player.inventory.getStack(8).getCount() >= 1)) {
                        System.out.println("Fredodebug: slot 8 is : " + client.player.inventory.getStack(8).getItem().toString());
                        System.out.println("Fredodebug: reset because the doors are not enough");
                        globalExecutor.resetWorld();
                        return;
                    }
                    tryPlaceDoor(client.player, secondDoorBasePos, PreNether.fromMagmaToAligned.getOpposite(), RelevantDirectionHelper.getRightDirection(PreNether.fromMagmaToAligned.getOpposite()));
                    return;
                }
                System.out.println("Fredodebug: two doors are all set");
                currentTBOState = TwoByOneBuildState.ADJUSTING_STANDING_POINT;
                break;
            case ADJUSTING_STANDING_POINT:
                KeyBinding.setKeyPressed(client.options.keyForward.getDefaultKey(), !againstTheDoor);
                System.out.println("Fredodebug: player speed: " + client.player.getVelocity().getComponentAlongAxis(PreNether.fromMagmaToAligned.getAxis()));
                if (Math.abs(client.player.getVelocity().getComponentAlongAxis(PreNether.fromMagmaToAligned.getAxis())) < 0.001f && !againstTheDoor) {
                    againstTheDoor = true;
                    return;
                }
                if (client.world.getBlockState(client.player.getBlockPos().down()).getBlock() == Blocks.MAGMA_BLOCK)
                    KeyBinding.setKeyPressed(client.options.keyLeft.getDefaultKey(), true);
                else {
                    KeyBinding.setKeyPressed(client.options.keyLeft.getDefaultKey(), false);
                    currentTBOState = TwoByOneBuildState.BREAKING_FIRST_MAGMA_BLOCK;
                }
                break;
            case BREAKING_FIRST_MAGMA_BLOCK:
                client.player.inventory.selectedSlot = 1;
                if (client.world.getBlockState(PreNether.magmaPos).getBlock() == Blocks.MAGMA_BLOCK) {
                    client.interactionManager.updateBlockBreakingProgress(PreNether.magmaPos, Direction.UP);
                    return;
                }
                currentTBOState = TwoByOneBuildState.QUADRUPLE_GRAB;
                break;
            case QUADRUPLE_GRAB:
                if (client.world.getBlockState(PreNether.magmaPos.down()).getBlock() == Blocks.COBBLESTONE)
                    System.out.println("Fredodebug: turned into cobble stone");
                client.player.inventory.selectedSlot = 3;
                if (client.world.getBlockState(PreNether.magmaPos.down()).getBlock() == Blocks.LAVA && client.world.getFluidState(PreNether.magmaPos.down()).isStill()) {
                    float[] angles = MiningHelper.getValidFluidAngle(client.player, PreNether.magmaPos.down());
                    client.player.yaw = angles[0];
                    client.player.pitch = angles[1];
                    client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookOnly(angles[0], angles[1], client.player.isOnGround()));
                    System.out.println("Fredodebug: first lava grab: " + client.interactionManager.interactItem(client.player, client.world, Hand.MAIN_HAND));
                    return;
                }
                if (client.player.inventory.getStack(3).getItem() == Items.LAVA_BUCKET && client.world.getBlockState(magmaSideMiddleFragmentPos.up()).getBlock() != Blocks.OBSIDIAN && client.world.getBlockState(magmaSideMiddleFragmentPos.up()).getBlock() != Blocks.LAVA) {
                    float[] angles = MiningHelper.getValidMiningAngleForFace(client.player, magmaSideMiddleFragmentPos, Direction.UP);
                    client.player.yaw = angles[0];
                    client.player.pitch = angles[1];
                    client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookOnly(angles[0], angles[1], client.player.isOnGround()));
                    System.out.println("Fredodebug: first lava place: " + client.interactionManager.interactItem(client.player, client.world, Hand.MAIN_HAND));
                    return;
                }
                if (client.world.getBlockState(PreNether.magmaPos.down().offset(RelevantDirectionHelper.getRightDirection(PreNether.fromMagmaToAligned))).getBlock() == Blocks.LAVA && client.world.getFluidState(PreNether.magmaPos.down().offset(RelevantDirectionHelper.getRightDirection(PreNether.fromMagmaToAligned))).isStill()) {
                    float[] angles = MiningHelper.getValidFluidAngle(client.player, PreNether.magmaPos.down().offset(RelevantDirectionHelper.getRightDirection(PreNether.fromMagmaToAligned)));
                    client.player.yaw = angles[0];
                    client.player.pitch = angles[1];
                    client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookOnly(angles[0], angles[1], client.player.isOnGround()));
                    System.out.println("Fredodebug: second lava grab: " + client.interactionManager.interactItem(client.player, client.world, Hand.MAIN_HAND));
                    return;
                }
                if (client.player.inventory.getStack(3).getItem() == Items.LAVA_BUCKET && client.world.getBlockState(PreNether.magmaPos.up(2)).getBlock() != Blocks.OBSIDIAN && client.world.getBlockState(PreNether.magmaPos.up(2)).getBlock() != Blocks.LAVA) {
                    float[] angles = MiningHelper.getValidMiningAngleForFace(client.player, topHitPosHitResult.getBlockPos(), RelevantDirectionHelper.getRightDirection(PreNether.fromMagmaToAligned).getOpposite());
                    client.player.yaw = angles[0];
                    client.player.pitch = angles[1];
                    client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookOnly(angles[0], angles[1], client.player.isOnGround()));
                    System.out.println("Fredodebug: second lava place: " + client.interactionManager.interactItem(client.player, client.world, Hand.MAIN_HAND));
                    return;
                }

                //==================================================================

                client.player.inventory.selectedSlot = 3;
                if (client.world.getBlockState(PreNether.alignedMagmaPos.down()).getBlock() == Blocks.LAVA && client.world.getFluidState(PreNether.alignedMagmaPos.down()).isStill()) {
                    float[] angles = MiningHelper.getValidFluidAngle(client.player, PreNether.alignedMagmaPos.down());
                    client.player.yaw = angles[0];
                    client.player.pitch = angles[1];
                    client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookOnly(angles[0], angles[1], client.player.isOnGround()));
                    System.out.println("Fredodebug: third lava grab: " + client.interactionManager.interactItem(client.player, client.world, Hand.MAIN_HAND));
                    return;
                }
                if (client.player.inventory.getStack(3).getItem() == Items.LAVA_BUCKET && client.world.getBlockState(alignedSideMiddleFragmentPos.up()).getBlock() != Blocks.OBSIDIAN && client.world.getBlockState(alignedSideMiddleFragmentPos.up()).getBlock() != Blocks.LAVA) {
                    float[] angles = MiningHelper.getValidMiningAngleForFace(client.player, alignedSideMiddleFragmentPos, Direction.UP);
                    client.player.yaw = angles[0];
                    client.player.pitch = angles[1];
                    client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookOnly(angles[0], angles[1], client.player.isOnGround()));
                    System.out.println("Fredodebug: third lava place: " + client.interactionManager.interactItem(client.player, client.world, Hand.MAIN_HAND));
                    return;
                }
                if (client.world.getBlockState(PreNether.alignedMagmaPos.offset(RelevantDirectionHelper.getRightDirection(PreNether.fromMagmaToAligned)).down()).getBlock() == Blocks.LAVA && client.world.getFluidState(PreNether.alignedMagmaPos.offset(RelevantDirectionHelper.getRightDirection(PreNether.fromMagmaToAligned)).down()).isStill()) {
                    float[] angles = MiningHelper.getValidFluidAngle(client.player, PreNether.alignedMagmaPos.offset(RelevantDirectionHelper.getRightDirection(PreNether.fromMagmaToAligned)).down());
                    client.player.yaw = angles[0];
                    client.player.pitch = angles[1];
                    client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookOnly(angles[0], angles[1], client.player.isOnGround()));
                    System.out.println("Fredodebug: fourth lava grab: " + client.interactionManager.interactItem(client.player, client.world, Hand.MAIN_HAND));
                    return;
                }

                if (!client.world.getBlockState(alignedSideMiddleFragmentPos.up(2)).getMaterial().isSolid()) {
                    InventoryHelper.selectBuildingBlock(client.player, true);
                    BlockHitResult blockHitResult = new BlockHitResult(Vec3d.ofCenter(alignedSideMiddleFragmentPos.up()), Direction.UP, alignedSideMiddleFragmentPos.up(), false);
                    client.interactionManager.interactBlock(client.player, client.world, Hand.MAIN_HAND, blockHitResult);
                    return;
                }
                client.player.inventory.selectedSlot = 3;
                if (client.player.inventory.getStack(3).getItem() == Items.LAVA_BUCKET && client.world.getBlockState(PreNether.alignedMagmaPos.up(2)).getBlock() != Blocks.OBSIDIAN && client.world.getBlockState(PreNether.alignedMagmaPos.up(2)).getBlock() != Blocks.LAVA) {
                    float[] angles = MiningHelper.getValidMiningAngleForFace(client.player, alignedSideMiddleFragmentPos.up(2), PreNether.fromMagmaToAligned.getOpposite());
                    client.player.yaw = angles[0];
                    client.player.pitch = angles[1];
                    client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookOnly(angles[0], angles[1], client.player.isOnGround()));
                    System.out.println("Fredodebug: fourth lava place: " + client.interactionManager.interactItem(client.player, client.world, Hand.MAIN_HAND));
                    return;
                }


                currentTBOState = TwoByOneBuildState.SEALING_SPACE;
                break;


            case SEALING_SPACE:
                InventoryHelper.selectBuildingBlock(client.player, true);
                if (!client.world.getBlockState(PreNether.alignedMagmaPos.offset(RelevantDirectionHelper.getRightDirection(PreNether.fromMagmaToAligned)).up()).getMaterial().isSolid()) {
                    BlockHitResult blockHitResultOne = new BlockHitResult(Vec3d.ofCenter(PreNether.alignedMagmaPos.offset(RelevantDirectionHelper.getRightDirection(PreNether.fromMagmaToAligned))), Direction.UP, PreNether.alignedMagmaPos.offset(RelevantDirectionHelper.getRightDirection(PreNether.fromMagmaToAligned)), false);
                    System.out.println("Fredodebug: second seal block place : " + client.interactionManager.interactBlock(client.player, client.world, Hand.MAIN_HAND, blockHitResultOne));
                    return;
                }
                if (!client.world.getBlockState(PreNether.alignedMagmaPos.offset(RelevantDirectionHelper.getRightDirection(PreNether.fromMagmaToAligned).getOpposite()).up()).getMaterial().isSolid()) {
                    BlockHitResult blockHitResultOne = new BlockHitResult(Vec3d.ofCenter(PreNether.alignedMagmaPos.offset(RelevantDirectionHelper.getRightDirection(PreNether.fromMagmaToAligned).getOpposite())), Direction.UP, PreNether.alignedMagmaPos.offset(RelevantDirectionHelper.getRightDirection(PreNether.fromMagmaToAligned).getOpposite()), false);
                    System.out.println("Fredodebug: third seal block place : " + client.interactionManager.interactBlock(client.player, client.world, Hand.MAIN_HAND, blockHitResultOne));
                    return;
                }
                currentTBOState = TwoByOneBuildState.FIRST_WATER_MOVE;
                break;
            case FIRST_WATER_MOVE:
                client.player.inventory.selectedSlot = 3;
                if (client.player.inventory.getStack(3).getItem() == Items.BUCKET && client.world.getBlockState(PreNether.alignedMagmaPos.up()).getBlock() == Blocks.BUBBLE_COLUMN) {
                    float[] angles = MiningHelper.getValidFluidAngle(client.player, PreNether.alignedMagmaPos.up());
                    client.player.yaw = angles[0];
                    client.player.pitch = angles[1];
                    client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookOnly(angles[0], angles[1], client.player.isOnGround()));
                    System.out.println("Fredodebug: first water grab: " + client.interactionManager.interactItem(client.player, client.world, Hand.MAIN_HAND));
                    return;
                }
                currentTBOState = TwoByOneBuildState.SECOND_WATER_MOVE;
                break;
            case SECOND_WATER_MOVE:
                if (client.player.inventory.getStack(3).getItem() == Items.WATER_BUCKET) {
                    float[] angles = MiningHelper.getValidMiningAngleForFace(client.player, PreNether.alignedMagmaPos, PreNether.fromMagmaToAligned.getOpposite());
                    client.player.yaw = angles[0];
                    client.player.pitch = angles[1];
                    client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookOnly(angles[0], angles[1], client.player.isOnGround()));
                    System.out.println("Fredodebug: first water place: " + client.interactionManager.interactItem(client.player, client.world, Hand.MAIN_HAND));
                    return;
                }
                currentTBOState = TwoByOneBuildState.PENDING;
                break;
            case PENDING:
                if(client.player.inventory.getStack(3).getItem()!=Items.BUCKET||!client.world.getFluidState(PreNether.magmaPos).isStill())break;
                currentTBOState = TwoByOneBuildState.THIRD_WATER_MOVE;
                break;
            case THIRD_WATER_MOVE:
                if (client.player.inventory.getStack(3).getItem() == Items.BUCKET) {
                    float[] angles = MiningHelper.getValidFluidAngle(client.player, PreNether.magmaPos);
                    client.player.yaw = angles[0];
                    client.player.pitch = angles[1];
                    client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookOnly(angles[0], angles[1], client.player.isOnGround()));
                    System.out.println("Fredodebug: second water grab: " + client.interactionManager.interactItem(client.player, client.world, Hand.MAIN_HAND));
                    return;
                }
                currentTBOState = TwoByOneBuildState.FOURTH_WATER_MOVE;
                break;
            case FOURTH_WATER_MOVE:
                if (client.player.inventory.getStack(3).getItem() == Items.WATER_BUCKET) {
                    float[] angles = MiningHelper.getValidMiningAngleForFace(client.player, magmaSideMiddleFragmentPos.down(), PreNether.fromMagmaToAligned);
                    client.player.yaw = angles[0];
                    client.player.pitch = angles[1];
                    client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookOnly(angles[0], angles[1], client.player.isOnGround()));
                    System.out.println("Fredodebug: last water place: " + client.interactionManager.interactItem(client.player, client.world, Hand.MAIN_HAND));
                    return;
                }
                currentTBOState = TwoByOneBuildState.CLEARING_SPACE;
                break;
            case CLEARING_SPACE:
                if (client.player.inventory.getStack(3).getItem() == Items.BUCKET) {
                    if (client.world.getBlockState(PreNether.alignedMagmaPos.down()).getBlock() != Blocks.WATER) {
                        System.out.println("Fredodebug: waiting for water flowing");
                        return;
                    }
                    float[] angles = MiningHelper.getValidFluidAngle(client.player, PreNether.magmaPos.down());
                    client.player.yaw = angles[0];
                    client.player.pitch = angles[1];
                    client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookOnly(angles[0], angles[1], client.player.isOnGround()));
                    System.out.println("Fredodebug: last water grab: " + client.interactionManager.interactItem(client.player, client.world, Hand.MAIN_HAND));
                    return;
                }
                if (!client.world.getBlockState(PreNether.magmaPos.offset(RelevantDirectionHelper.getRightDirection(PreNether.fromMagmaToAligned)).down()).getMaterial().isSolid()) {
                    InventoryHelper.selectBuildingBlock(client.player, true);
                    BlockHitResult blockHitResult = new BlockHitResult(Vec3d.ofCenter(PreNether.magmaPos.offset(RelevantDirectionHelper.getRightDirection(PreNether.fromMagmaToAligned)).down(2)), Direction.UP, PreNether.magmaPos.offset(RelevantDirectionHelper.getRightDirection(PreNether.fromMagmaToAligned)).down(2), false);
                    client.interactionManager.interactBlock(client.player, client.world, Hand.MAIN_HAND, blockHitResult);
                    return;
                }
                if (!client.world.getBlockState(PreNether.alignedMagmaPos.offset(RelevantDirectionHelper.getRightDirection(PreNether.fromMagmaToAligned)).down()).getMaterial().isSolid()) {
                    InventoryHelper.selectBuildingBlock(client.player, true);
                    BlockHitResult blockHitResult = new BlockHitResult(Vec3d.ofCenter(PreNether.alignedMagmaPos.offset(RelevantDirectionHelper.getRightDirection(PreNether.fromMagmaToAligned)).down(2)), Direction.UP, PreNether.alignedMagmaPos.offset(RelevantDirectionHelper.getRightDirection(PreNether.fromMagmaToAligned)).down(2), false);
                    client.interactionManager.interactBlock(client.player, client.world, Hand.MAIN_HAND, blockHitResult);
                    return;
                }
                if (client.world.getBlockState(PreNether.alignedMagmaPos).getBlock() == Blocks.MAGMA_BLOCK) {
                    client.player.inventory.selectedSlot = 1;
                    client.interactionManager.updateBlockBreakingProgress(PreNether.alignedMagmaPos, Direction.UP);
                    return;
                }
                currentTBOState = TwoByOneBuildState.FORCING_LIGHTER;
                break;
            case FORCING_LIGHTER:
                KeyBinding.setKeyPressed(client.options.keyForward.getDefaultKey(),true);
                InventoryHelper.moveItemToHotbar(client, client.player, Items.FLINT_AND_STEEL, 8);
                client.player.inventory.selectedSlot = 8;
                BlockHitResult blockHitResult = new BlockHitResult(Vec3d.ofCenter(magmaSideMiddleFragmentPos), PreNether.fromMagmaToAligned, magmaSideMiddleFragmentPos, false);
                client.interactionManager.interactBlock(client.player, client.world, Hand.MAIN_HAND, blockHitResult);
                if (client.world.getBlockState(PreNether.magmaPos).getBlock() == Blocks.NETHER_PORTAL) {
                    currentTBOState = TwoByOneBuildState.NEXT;
                }
                break;
            case NEXT:
                KeyBinding.setKeyPressed(client.options.keyForward.getDefaultKey(),false);
                break;
        }
    }

    private static void tryPlaceDoor(PlayerEntity player, BlockPos basePos, Direction doorOutFacingDirection, Direction hingeOffsetDirection) {
        System.out.println("Fredodebug: hOffset: X: " + hingeOffsetDirection.getOffsetX() + " Z: " + hingeOffsetDirection.getOffsetZ() + " dOffset: X: " + doorOutFacingDirection.getOffsetX() + " Z: " + doorOutFacingDirection.getOffsetZ());
        double hitX = basePos.getX() + 0.5 + hingeOffsetDirection.getOffsetX() * 0.25;
        double hitY = basePos.getY() + 1.0;
        double hitZ = basePos.getZ() + 0.5 + hingeOffsetDirection.getOffsetZ() * 0.25;
        Vec3d hitVec = new Vec3d(hitX, hitY, hitZ);

        BlockHitResult hitResult = new BlockHitResult(
                hitVec,
                Direction.UP,
                basePos,
                false
        );
        player.inventory.selectedSlot = 8;
        player.yaw = RelevantDirectionHelper.getYawFromDirection(doorOutFacingDirection.getOpposite());
        MinecraftClient client = MinecraftClient.getInstance();
        System.out.println("Fredodebug: trying to placing the door, Door pos: " + basePos.up().toShortString() + " result: " + client.interactionManager.interactBlock((ClientPlayerEntity) player, client.world, Hand.MAIN_HAND, hitResult));
    }

    public static boolean placeLava(MinecraftClient client, PlayerEntity player, BlockPos foundationPos, Direction placeDirection) {
        if (client == null || player == null || client.getNetworkHandler() == null) return false;
        player.inventory.selectedSlot = 3;

        if (lavaPlaceStep == 0) {
            if (foundationPos == null || placeDirection == null) return false;
            activePlacePos = foundationPos;
            activePlaceDirection = placeDirection;

            storedRealPos = player.getPos();
            storedRealYaw = player.yaw;
            storedRealPitch = player.pitch;

            double eyeX = activePlacePos.getX() + 0.5 + activePlaceDirection.getOffsetX() * 0.55;
            double eyeY = activePlacePos.getY() + 0.5 + activePlaceDirection.getOffsetY() * 0.55;
            double eyeZ = activePlacePos.getZ() + 0.5 + activePlaceDirection.getOffsetZ() * 0.55;

            double ghostX = eyeX;
            double ghostY = eyeY - player.getEyeHeight(player.getPose());
            double ghostZ = eyeZ;

            float ghostYaw = storedRealYaw;
            float ghostPitch = storedRealPitch;

            if (activePlaceDirection == Direction.UP) ghostPitch = 90.0f;
            else if (activePlaceDirection == Direction.DOWN) ghostPitch = -90.0f;
            else {
                ghostPitch = 0.0f;
                if (activePlaceDirection == Direction.NORTH) ghostYaw = 0.0f;
                if (activePlaceDirection == Direction.SOUTH) ghostYaw = 180.0f;
                if (activePlaceDirection == Direction.WEST) ghostYaw = -90.0f;
                if (activePlaceDirection == Direction.EAST) ghostYaw = 90.0f;
            }

            player.updatePosition(ghostX, ghostY, ghostZ);
            player.yaw = ghostYaw;
            player.pitch = ghostPitch;
            client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Both(
                    ghostX, ghostY, ghostZ, ghostYaw, ghostPitch, player.isOnGround()
            ));

            lavaPlaceStep = 1;
            return false;

        } else if (lavaPlaceStep == 1) {
            client.interactionManager.interactItem(player, client.world, Hand.MAIN_HAND);
            lavaPlaceStep = 2;
            return false;

        } else if (lavaPlaceStep == 2) {
            if (storedRealPos != null) {
                player.updatePosition(storedRealPos.x, storedRealPos.y, storedRealPos.z);
                player.yaw = storedRealYaw;
                player.pitch = storedRealPitch;

                client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Both(
                        storedRealPos.x, storedRealPos.y, storedRealPos.z,
                        storedRealYaw, storedRealPitch, player.isOnGround()
                ));
            }
            lavaPlaceStep = 0;
            System.out.println("FredoBot [时序修正版]: 倒岩浆成功执行 -> " + (activePlacePos != null ? activePlacePos.toShortString() : "未知"));
            activePlacePos = null;
            activePlaceDirection = null;
            return true;
        }
        return false;
    }

    private static List<BlockPos> findLeakingPos() {
        BlockPos magmaPos = PreNether.magmaPos;
        BlockPos alignedMagmaPos = PreNether.alignedMagmaPos;
        List<BlockPos> result = new ArrayList<>();
        Direction[] directions = RelevantDirectionHelper.getIrrelevantDirections(magmaPos, alignedMagmaPos);
        for (Direction offset : directions) {
            if (!MinecraftClient.getInstance().world.getBlockState(magmaPos.offset(offset)).getMaterial().isSolid())
                result.add(magmaPos.offset(offset));
            if (!MinecraftClient.getInstance().world.getBlockState(alignedMagmaPos.offset(offset)).getMaterial().isSolid())
                result.add(alignedMagmaPos.offset(offset));
        }
        return result;
    }

    private static BlockPos getMissingObiPos() {
        BlockPos magmaPos = PreNether.magmaPos;
        BlockPos alignedMagmaPos = PreNether.alignedMagmaPos;
        if (missingObiPosList.isEmpty()) {
            if (!isObiFragmentSettled(magmaSideMiddleFragmentPos.up())) {
                missingObiPosList.add(magmaSideMiddleFragmentPos.up());
            }
            if (!isObiFragmentSettled(alignedSideMiddleFragmentPos.up())) {
                missingObiPosList.add(alignedMagmaPos.up());
            }
            if (!isObiFragmentSettled(alignedMagmaPos.up(2))) {
                missingObiPosList.add(alignedMagmaPos.up(2));
            }
            if (!isObiFragmentSettled(magmaPos.up(2))) {
                missingObiPosList.add(magmaPos.up(2));
            }
            if (!isObiFragmentSettled(magmaPos.down(2))) {
                missingObiPosList.add(magmaPos.down(2));
            }
            if (!isObiFragmentSettled(alignedMagmaPos.down(2))) {
                missingObiPosList.add(alignedMagmaPos.down(2));
            }
            if (!isObiFragmentSettled(magmaSideMiddleFragmentPos.down())) {
                missingObiPosList.add(magmaSideMiddleFragmentPos.down());
            }
            if (!isObiFragmentSettled(alignedSideMiddleFragmentPos.down())) {
                missingObiPosList.add(alignedSideMiddleFragmentPos.down());
            }
            if (!isObiFragmentSettled(alignedSideMiddleFragmentPos)) {
                missingObiPosList.add(alignedSideMiddleFragmentPos);
            }
            if (!isObiFragmentSettled(magmaSideMiddleFragmentPos)) {
                missingObiPosList.add(magmaSideMiddleFragmentPos);
            }
        } else {
            missingObiPosList.removeIf(NetherPortalBuilding::isObiFragmentSettled);
        }
        if (missingObiPosList.isEmpty()) return null;
        return missingObiPosList.getFirst();
    }

    private static boolean isObiFragmentSettled(BlockPos checkPos) {
        BlockState blockState = MinecraftClient.getInstance().world.getBlockState(checkPos);
        return blockState.getBlock() == Blocks.OBSIDIAN || blockState.getBlock() == Blocks.LAVA;
    }

    private static BlockPos findLavaSource() {
        BlockPos magmaPos = PreNether.magmaPos;
        BlockPos alignedMagmaPos = PreNether.alignedMagmaPos;
        if (isLavaSource(magmaPos.down())) return magmaPos.down();
        if (isLavaSource(alignedMagmaPos.down())) return alignedMagmaPos.down();
        for (Direction offset : RelevantDirectionHelper.getIrrelevantDirections(magmaPos, alignedMagmaPos)) {
            if (isLavaSource(magmaPos.down().offset(offset))) return magmaPos.down().offset(offset);
            if (isLavaSource(alignedMagmaPos.down().offset(offset))) return alignedMagmaPos.down().offset(offset);

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
            if (isNotClear(magmaPos.offset(PreNether.fromMagmaToAligned))) {
                isNotClear = true;
                twoByOneAreaClearPos.add(magmaPos.offset(PreNether.fromMagmaToAligned.getOpposite()));
            }
            if (isNotClear(alignedMagmaPos.offset((PreNether.fromMagmaToAligned)))) {
                isNotClear = true;
                twoByOneAreaClearPos.add(alignedMagmaPos.offset((PreNether.fromMagmaToAligned)));
            }
            if (isNotClear(PreNether.magmaPos.down(2))) {
                isNotClear = true;
                twoByOneAreaClearPos.add(PreNether.magmaPos.down(2));
            }
            if (isNotClear(PreNether.alignedMagmaPos.down(2))) {
                isNotClear = true;
                twoByOneAreaClearPos.add((PreNether.alignedMagmaPos.down(2)));
            }
        } else {
            twoByOneAreaClearPos.removeIf(currentPos -> !isNotClear(currentPos));
            isNotClear = !twoByOneAreaClearPos.isEmpty();
        }
        System.out.println("Fredodebug: twoByOneisNotClear: " + isNotClear);
        System.out.println("Fredodebug: twoByOneAreaClearPosList: " + twoByOneAreaClearPos.toString());
        return isNotClear;
    }

    private static boolean isNotClear(BlockPos pos) {
        AbstractBlock.AbstractBlockState blockState = MinecraftClient.getInstance().world.getBlockState(pos);
        return blockState.getMaterial().isSolid() || blockState.getBlock().is(Blocks.KELP) || blockState.getBlock().is(Blocks.KELP_PLANT) || blockState.getBlock().is(Blocks.SEAGRASS) || blockState.getBlock().is(Blocks.TALL_SEAGRASS) || blockState.getBlock().is(Blocks.SEA_PICKLE);
    }

    public static boolean grabLava(MinecraftClient client, PlayerEntity player, BlockPos lavaPos) {
        if (client == null || player == null || client.getNetworkHandler() == null) return false;
        player.inventory.selectedSlot = 3;

        if (lavaGrabStep == 0) {
            if (lavaPos == null) return false;
            activeGrabPos = lavaPos;

            grabStoredRealPos = player.getPos();
            grabStoredRealYaw = player.yaw;
            grabStoredRealPitch = player.pitch;

            double ghostX = activeGrabPos.getX() + 0.5;
            double ghostY = activeGrabPos.getY() + 0.5 - player.getEyeHeight(player.getPose());
            double ghostZ = activeGrabPos.getZ() + 0.5;

            player.updatePosition(ghostX, ghostY, ghostZ);
            player.yaw = grabStoredRealYaw;
            player.pitch = 90.0f;
            client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Both(
                    ghostX, ghostY, ghostZ, grabStoredRealYaw, 90.0f, player.isOnGround()
            ));

            lavaGrabStep = 1;
            return false;

        } else if (lavaGrabStep == 1) {
            client.interactionManager.interactItem(player, client.world, Hand.MAIN_HAND);
            lavaGrabStep = 2;
            return false;

        } else if (lavaGrabStep == 2) {
            if (grabStoredRealPos != null) {
                player.updatePosition(grabStoredRealPos.x, grabStoredRealPos.y, grabStoredRealPos.z);
                player.yaw = grabStoredRealYaw;
                player.pitch = grabStoredRealPitch;
                client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Both(
                        grabStoredRealPos.x, grabStoredRealPos.y, grabStoredRealPos.z,
                        grabStoredRealYaw, grabStoredRealPitch, player.isOnGround()
                ));
            }
            lavaGrabStep = 0;
            System.out.println("FredoBot [时序修正版]: 盛起液体成功执行 -> " + (activeGrabPos != null ? activeGrabPos.toShortString() : "未知"));
            activeGrabPos = null;
            return true;
        }
        return false;
    }

}
