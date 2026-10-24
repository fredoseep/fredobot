package com.fredoseep.utils.prenether;

import com.fredoseep.Test;
import com.fredoseep.algorithm.SimplePathfinder;
import com.fredoseep.algorithm.planning.DoorPlacementGuard;
import com.fredoseep.algorithm.planning.PortalClearanceGeometry;
import net.minecraft.block.FallingBlock;
import net.minecraft.tag.FluidTags;
import com.fredoseep.algorithm.planning.PortalPositioningGeometry;
import net.minecraft.block.enums.DoubleBlockHalf;
import com.fredoseep.logging.GlobalStateLogger;
import java.util.Map;
import com.fredoseep.behave.CraftingController;
import com.fredoseep.behave.MiscController;
import com.fredoseep.behave.MovementController;
import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.GlobalExecutor;
import com.fredoseep.excutor.PathExecutor;
import com.fredoseep.utils.player.InventoryHelper;
import com.fredoseep.utils.player.MiningHelper;
import com.fredoseep.utils.player.RelevantDirectionHelper;
import com.fredoseep.utils.player.ToolsHelper;
import net.fabricmc.loader.impl.lib.sat4j.core.Vec;
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
    private static String clearanceStatus="";
    private static int alignedLavaRetryTick=-1;
    private static final DoorPlacementGuard doorPlacementGuard=new DoorPlacementGuard();
    private static int positioningBackTicks,positioningStillTicks,positioningSideStableTicks;
    private static double positioningLastBackCoordinate=Double.NaN;

    public static int lavaGrabStep = 0;
    private static Vec3d grabStoredRealPos = null;
    private static float grabStoredRealYaw = 0f;
    private static float grabStoredRealPitch = 0f;
    private static BlockPos activeGrabPos = null;           // 【新增】

    private static final PathExecutor pathExecutor = BotEngine.getInstance().getModule(PathExecutor.class);

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
        clearanceStatus="";alignedLavaRetryTick=-1;
        doorPlacementGuard.clear();positioningBackTicks=positioningStillTicks=positioningSideStableTicks=0;
        positioningLastBackCoordinate=Double.NaN;
        KeyBinding.setKeyPressed(MinecraftClient.getInstance().options.keyLeft.getDefaultKey(), false);
        KeyBinding.setKeyPressed(MinecraftClient.getInstance().options.keyForward.getDefaultKey(), false);

    }

    public enum TwoByOneBuildState {
        IDLE, SETTING_UP_FIRST_DOOR, ENTERING_CLEARANCE_DOOR, CLEARING_AREA, SETTING_UP_HIT_POINT, SETTING_UP_DOOR, ADJUSTING_STANDING_POINT, BREAKING_FIRST_MAGMA_BLOCK, QUADRUPLE_GRAB, SEALING_SPACE, FIRST_WATER_MOVE, SECOND_WATER_MOVE,PENDING, THIRD_WATER_MOVE, FOURTH_WATER_MOVE, CLEARING_SPACE,POSITIONING,FORCING_FLINT, CRAFTING_LIGHTER, NEXT, LIGHTING;
    }

    public static TwoByOneBuildState currentTBOState = TwoByOneBuildState.IDLE;

    private static void stopPositioning(MinecraftClient client){
        KeyBinding.setKeyPressed(client.options.keyForward.getDefaultKey(),false);
        KeyBinding.setKeyPressed(client.options.keyBack.getDefaultKey(),false);
        KeyBinding.setKeyPressed(client.options.keyLeft.getDefaultKey(),false);
        KeyBinding.setKeyPressed(client.options.keyRight.getDefaultKey(),false);
        KeyBinding.setKeyPressed(client.options.keyJump.getDefaultKey(),false);
        KeyBinding.setKeyPressed(client.options.keySprint.getDefaultKey(),false);
        client.player.setSprinting(false);
    }

    public static void twoByOneBuild() {
        MinecraftClient client = MinecraftClient.getInstance();
        GlobalExecutor globalExecutor = BotEngine.getInstance().getModule(GlobalExecutor.class);
        boolean pathPositioning=currentTBOState==TwoByOneBuildState.POSITIONING;
        boolean clearancePositioning=currentTBOState==TwoByOneBuildState.SETTING_UP_FIRST_DOOR
                || currentTBOState==TwoByOneBuildState.ENTERING_CLEARANCE_DOOR
                || currentTBOState==TwoByOneBuildState.CLEARING_AREA;
        // The path executor owns all movement while entering the finished portal shaft.
        if(pathPositioning && pathExecutor.isBusy())return;
        if(currentTBOState!=TwoByOneBuildState.ADJUSTING_STANDING_POINT)stopPositioning(client);
        boolean sneak = !pathPositioning && (currentTBOState==TwoByOneBuildState.ADJUSTING_STANDING_POINT
                || client.world.getBlockState(client.player.getBlockPos().down()).getBlock() == Blocks.MAGMA_BLOCK);
        if (!pathPositioning && !clearancePositioning && client.player.isSwimming()) {
            MovementController.setLookDirection(client.player, client.player.yaw, -90);
            initGestureAdjusted = false;
            sneak = false;
        }
        if (!pathPositioning && !initGestureAdjusted) {
            KeyBinding.setKeyPressed(client.options.keySneak.getDefaultKey(), sneak);
            initGestureAdjusted = true;
            return;
        }
        if (!pathPositioning && client.player.pitch == -90) client.player.pitch = 2;
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
                    globalExecutor.resetWorld("地狱门施工：相邻 magma 坐标丢失");
                    System.out.println("Fredodebug: reset because alignedMagmaPos is null somehow");
                } else System.out.println("Fredodebug: alignedMagmaPos : " + PreNether.alignedMagmaPos.toShortString());
                magmaSideMiddleFragmentPos = PreNether.magmaPos.offset(PreNether.fromMagmaToAligned.getOpposite());
                alignedSideMiddleFragmentPos = PreNether.alignedMagmaPos.offset(PreNether.fromMagmaToAligned);
                clearanceStatus="";alignedLavaRetryTick=-1;
                currentTBOState = TwoByOneBuildState.SETTING_UP_FIRST_DOOR;
                break;
            case SETTING_UP_FIRST_DOOR:
                if(!ensurePortalDoor(client,PreNether.magmaPos,2))return;
                
                currentTBOState=TwoByOneBuildState.ENTERING_CLEARANCE_DOOR;
                GlobalStateLogger.logCustomEvent("portal_clearance_first_door_ready",Map.of("door",PreNether.magmaPos.up().toShortString()));
                return;
            case ENTERING_CLEARANCE_DOOR:
                if(!portalDoorMatches(client,PreNether.magmaPos)){
                    doorPlacementGuard.clear();
                    currentTBOState=TwoByOneBuildState.SETTING_UP_FIRST_DOOR;return;
                }
                if(!holdClearanceDoor(client))return;
                currentTBOState=TwoByOneBuildState.CLEARING_AREA;
                GlobalStateLogger.logCustomEvent("portal_clearance_entered_door",Map.of("pos",client.player.getPos().toString()));
                return;
            case CLEARING_AREA:
                if(!portalDoorMatches(client,PreNether.magmaPos)){
                    doorPlacementGuard.clear();
                    currentTBOState=TwoByOneBuildState.SETTING_UP_FIRST_DOOR;return;
                }
                if(!holdClearanceDoor(client))return;
                boolean blocked=areaNotClear();
                if(blocked){
                    String status="MINING";
                    if(!status.equals(clearanceStatus)){
                        clearanceStatus=status;
                        GlobalStateLogger.logCustomEvent("portal_clearance_progress",Map.of("phase",status,
                                "remaining",twoByOneAreaClearPos.size()));
                    }
                    mineClearanceBlock(client);
                    return;
                }
                client.interactionManager.cancelBlockBreaking();
                GlobalStateLogger.logCustomEvent("portal_clearance_complete",Map.of("pos",client.player.getPos().toString()));
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
                if(!ensurePortalDoor(client,PreNether.magmaPos,2))return;
                BlockPos secondDoorBasePos=secondDoorBase();
                if(secondDoorSpaceBlocked(client,secondDoorBasePos)){
                    doorPlacementGuard.clear();
                    GlobalStateLogger.logCustomEvent("portal_second_door_clearance_required",Map.of(
                            "door",secondDoorBasePos.up().toShortString(),
                            "lower",client.world.getBlockState(secondDoorBasePos.up()).toString(),
                            "upper",client.world.getBlockState(secondDoorBasePos.up(2)).toString(),
                            "gravity_cover",client.world.getBlockState(secondDoorBasePos.up(3)).toString()));
                    currentTBOState=TwoByOneBuildState.CLEARING_AREA;
                    return;
                }
                if(!ensurePortalDoor(client,secondDoorBasePos,1))return;
                // The second door is placed while facing backwards; restore the approach direction.
                facePortalApproach(client);
                againstTheDoor=false;positioningBackTicks=positioningStillTicks=positioningSideStableTicks=0;
                positioningLastBackCoordinate=Double.NaN;
                GlobalStateLogger.logCustomEvent("portal_doors_confirmed",Map.of("first",PreNether.magmaPos.up().toShortString(),
                        "second",secondDoorBasePos.up().toShortString(),
                        "first_facing",portalDoorFacing(PreNether.magmaPos).toString(),"second_facing",portalDoorFacing(secondDoorBasePos).toString(),
                        "first_state",client.world.getBlockState(PreNether.magmaPos.up()).toString(),
                        "second_state",client.world.getBlockState(secondDoorBasePos.up()).toString()));
                currentTBOState=TwoByOneBuildState.ADJUSTING_STANDING_POINT;
                break;
            case ADJUSTING_STANDING_POINT:
                // A server correction can arrive after the initial client-side placement prediction.
                if(!portalDoorMatches(client,PreNether.magmaPos) || !portalDoorMatches(client,secondDoorBase())){
                    stopPositioning(client);doorPlacementGuard.clear();
                    currentTBOState=TwoByOneBuildState.SETTING_UP_DOOR;
                    GlobalStateLogger.logCustomEvent("portal_door_correction",Map.of("pos",client.player.getPos().toString()));
                    return;
                }
                // Own all movement inputs here: calibrate backwards, then shift left in a fixed view frame.
                stopPositioning(client);
                facePortalApproach(client);
                if(!againstTheDoor){
                    positioningBackTicks++;
                    KeyBinding.setKeyPressed(client.options.keyBack.getDefaultKey(),true);
                    double backSpeed=Math.abs(client.player.getVelocity().getComponentAlongAxis(PreNether.fromMagmaToAligned.getAxis()));
                    boolean rearContact=PortalPositioningGeometry.touchesRearDoor(client.player.getBoundingBox(),PreNether.magmaPos.up(),
                            client.world.getBlockState(PreNether.magmaPos.up()).getCollisionShape(client.world,PreNether.magmaPos.up()).getBoundingBoxes(),
                            PreNether.fromMagmaToAligned.getOffsetX(),PreNether.fromMagmaToAligned.getOffsetZ());
                    double backCoordinate=client.player.getX()*PreNether.fromMagmaToAligned.getOffsetX()
                            +client.player.getZ()*PreNether.fromMagmaToAligned.getOffsetZ();
                    double displacement=Double.isFinite(positioningLastBackCoordinate)?Math.abs(backCoordinate-positioningLastBackCoordinate):0;
                    positioningStillTicks=PortalPositioningGeometry.rearStable(backCoordinate,positioningLastBackCoordinate,rearContact)
                            ?positioningStillTicks+1:0;
                    positioningLastBackCoordinate=backCoordinate;
                    if(positioningBackTicks%40==0)GlobalStateLogger.logCustomEvent("portal_positioning_rear_progress",Map.of(
                            "pos",client.player.getPos().toString(),"ticks",positioningBackTicks,"still_ticks",positioningStillTicks,
                            "rear_contact",rearContact,"displacement",displacement,"reported_speed",backSpeed));
                    if(positioningBackTicks>=6 && positioningStillTicks>=3 && rearContact){
                        againstTheDoor=true;
                        KeyBinding.setKeyPressed(client.options.keyBack.getDefaultKey(),false);
                        GlobalStateLogger.logCustomEvent("portal_rear_calibrated",Map.of("pos",client.player.getPos().toString(),
                                "facing",PreNether.fromMagmaToAligned.toString(),"ticks",positioningBackTicks));
                    }
                    return;
                }
                double overlap=PortalPositioningGeometry.overlap(client.player.getBoundingBox(),secondDoorBase().up());
                if(overlap<.5){
                    positioningSideStableTicks=0;
                    KeyBinding.setKeyPressed(client.options.keyLeft.getDefaultKey(),true);
                    return;
                }
                // Release lateral input before construction; wait for remaining motion to decay.
                double speed=Math.hypot(client.player.getVelocity().x,client.player.getVelocity().z);
                positioningSideStableTicks=speed<=.015?positioningSideStableTicks+1:0;
                if(positioningSideStableTicks<3)return;
                GlobalStateLogger.logCustomEvent("portal_positioning_finished",Map.of("pos",client.player.getPos().toString(),
                        "velocity",client.player.getVelocity().toString(),"magma",PreNether.magmaPos.toShortString(),"second_door_overlap",overlap));
                currentTBOState=TwoByOneBuildState.BREAKING_FIRST_MAGMA_BLOCK;
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

                // This is a frame cell, never a temporary building-block support.
                BlockPos alignedFrame = alignedSideMiddleFragmentPos.up();
                BlockState alignedFrameState = client.world.getBlockState(alignedFrame);
                if (alignedFrameState.getMaterial().isSolid() && alignedFrameState.getBlock() != Blocks.OBSIDIAN) {
                    ToolsHelper.equipBestTool(client.player, alignedFrame, false);
                    client.interactionManager.updateBlockBreakingProgress(alignedFrame, Direction.UP);
                    if (!client.player.handSwinging) client.player.swingHand(Hand.MAIN_HAND);
                    return;
                }
                if(!ensureAlignedLavaFrame(client,alignedFrame))return;
                client.player.inventory.selectedSlot = 3;
                if (client.world.getBlockState(PreNether.alignedMagmaPos.offset(RelevantDirectionHelper.getRightDirection(PreNether.fromMagmaToAligned)).down()).getBlock() == Blocks.LAVA && client.world.getFluidState(PreNether.alignedMagmaPos.offset(RelevantDirectionHelper.getRightDirection(PreNether.fromMagmaToAligned)).down()).isStill()) {
                    float[] angles = MiningHelper.getValidFluidAngle(client.player, PreNether.alignedMagmaPos.offset(RelevantDirectionHelper.getRightDirection(PreNether.fromMagmaToAligned)).down());
                    client.player.yaw = angles[0];
                    client.player.pitch = angles[1];
                    client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookOnly(angles[0], angles[1], client.player.isOnGround()));
                    System.out.println("Fredodebug: fourth lava grab: " + client.interactionManager.interactItem(client.player, client.world, Hand.MAIN_HAND));
                    return;
                }

                // The frame is confirmed before grabbing the next bucket or placing its upper support.
                if (!client.world.getBlockState(alignedSideMiddleFragmentPos.up(2)).getMaterial().isSolid()) {
                    InventoryHelper.selectBuildingBlock(client.player, true);
                    BlockHitResult blockHitResult = new BlockHitResult(Vec3d.ofCenter(alignedFrame).add(0, .5, 0), Direction.UP, alignedFrame, false);
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
                currentTBOState = TwoByOneBuildState.POSITIONING;
                break;
            case POSITIONING:
                System.out.println("Fredodebug: player's y level: "+ client.player.getY());
                if(InventoryHelper.findItemSlot(client.player,Items.FLINT)!=-1||hasLighter()){
                    currentTBOState = TwoByOneBuildState.CRAFTING_LIGHTER;
                    break;
                }
                if(pathExecutor.isBusy())return;
                if(client.player.getY()<10&&client.player.isOnGround()){
                    System.out.println("Frededbug: positioning is finished");
                    currentTBOState = TwoByOneBuildState.FORCING_FLINT;
                    break;
                }
                List<SimplePathfinder.Node> positioningBehave = calcEnteringPositioning(PreNether.magmaPos);
                pathExecutor.executeCustomPath(positioningBehave);
                break;
            case FORCING_FLINT:
                if(client.world.getBlockState(PreNether.alignedMagmaPos.down()).getBlock()==Blocks.GRAVEL){
                    ToolsHelper.equipBestTool(client.player,PreNether.alignedMagmaPos.down(),true);
                    client.interactionManager.updateBlockBreakingProgress(PreNether.alignedMagmaPos.down(),Direction.UP);
                    break;
                }
                if(InventoryHelper.findItemSlot(client.player,Items.FLINT)!=-1||hasLighter()){
                    currentTBOState = TwoByOneBuildState.CRAFTING_LIGHTER;
                    break;
                }
                if(InventoryHelper.findItemSlot(client.player,Items.GRAVEL)==-1){
                    System.out.println("Fredodebug: don't have gravel somehow");
                    globalExecutor.resetWorld("地狱门施工：缺少沙砾");
                    return;
                }

                InventoryHelper.moveItemToHotbar(client,client.player,Items.GRAVEL,6);
                client.player.inventory.selectedSlot = 6;
                BlockHitResult blockHitResult1 = new BlockHitResult(Vec3d.ofCenter(PreNether.alignedMagmaPos.down(2)),Direction.UP,PreNether.alignedMagmaPos.down(),false);
                client.interactionManager.interactBlock(client.player,client.world,Hand.MAIN_HAND,blockHitResult1);
                break;
            case CRAFTING_LIGHTER:
                if(hasLighter()){
                    currentTBOState = TwoByOneBuildState.LIGHTING;
                    break;
                }
                CraftingController flintNSteelCrafter = BotEngine.getInstance().getModule(CraftingController.class);
                if (flintNSteelCrafter.isBusy() || pathExecutor.isBusy() || BotEngine.getInstance().getModule(MiscController.class).isBusy())
                    return;

                if (flintNSteelCrafter.isFailed()) {
                    System.out.println("Fredodebug: reset because fail to craft flint&steel");
                    flintNSteelCrafter.resetStatus();
                    globalExecutor.resetWorld("地狱门施工：打火石合成失败");
                    return;
                }
                if(flintNSteelCrafter.isDone()){
                    currentTBOState = TwoByOneBuildState.LIGHTING;
                    break;
                }
                else flintNSteelCrafter.startCrafting(Items.FLINT_AND_STEEL,1);
                break;

            case LIGHTING:
                InventoryHelper.moveItemToHotbar(client, client.player, Items.FLINT_AND_STEEL, 8);
                client.player.inventory.selectedSlot = 8;
                BlockHitResult blockHitResult2 = new BlockHitResult(Vec3d.ofCenter(magmaSideMiddleFragmentPos), PreNether.fromMagmaToAligned, magmaSideMiddleFragmentPos, false);
                client.interactionManager.interactBlock(client.player, client.world, Hand.MAIN_HAND, blockHitResult2);
                if (client.world.getBlockState(PreNether.magmaPos).getBlock() == Blocks.NETHER_PORTAL) {
                    currentTBOState = TwoByOneBuildState.NEXT;
                }
                break;
            case NEXT:
                globalExecutor.currentState = GlobalExecutor.GlobalState.ANALYZE_THE_BASTION;
                break;
        }
    }
    
    private static boolean hasLighter(){
        MinecraftClient client = MinecraftClient.getInstance();
        return InventoryHelper.findItemSlot(client.player, Items.FLINT_AND_STEEL) != -1 || InventoryHelper.findItemSlot(client.player, Items.FIRE_CHARGE) != -1;
    }
    

    private static List<SimplePathfinder.Node> calcEnteringPositioning(BlockPos magmaPos) {
        List<SimplePathfinder.Node> result = new ArrayList<>();
        result.add(new SimplePathfinder.Node(magmaPos.up(1), null, null, 0, 0, SimplePathfinder.MovementState.WALKING, 0));
        result.add(new SimplePathfinder.Node(magmaPos, null, null, 0, 0, SimplePathfinder.MovementState.MINING, 0));
        result.add(new SimplePathfinder.Node(magmaPos.down(1), null, null, 0, 0, SimplePathfinder.MovementState.MINING, 0));
        return result;
    }

    private static void facePortalApproach(MinecraftClient client){
        client.player.yaw=RelevantDirectionHelper.getYawFromDirection(PreNether.fromMagmaToAligned);
        client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookOnly(client.player.yaw,client.player.pitch,client.player.isOnGround()));
    }
    /** Confirm the third frame before the next grab; a client-side lava prediction is not completion. */
    private static boolean ensureAlignedLavaFrame(MinecraftClient client,BlockPos target){
        BlockState state=client.world.getBlockState(target);
        if(state.getBlock()==Blocks.OBSIDIAN){alignedLavaRetryTick=-1;return true;}
        int tick=client.player.age;
        if(alignedLavaRetryTick>=0 && tick>=alignedLavaRetryTick && tick-alignedLavaRetryTick<10)return false;
        client.player.inventory.selectedSlot=3;
        Item held=client.player.inventory.getStack(3).getItem();
        float[] angles;String action;
        if(held==Items.LAVA_BUCKET){
            // Retry even if the client still displays lava: the full bucket may be a server correction.
            angles=MiningHelper.getValidMiningAngleForFace(client.player,alignedSideMiddleFragmentPos,Direction.UP);
            action="RETRY_PLACE";
        }else if(held==Items.BUCKET){
            if(state.getBlock()==Blocks.LAVA && client.world.getFluidState(target).isStill()){
                angles=MiningHelper.getValidFluidAngle(client.player,target);
                action="RECOVER_UNCONFIRMED_LAVA";
            }else{
                BlockPos source=PreNether.alignedMagmaPos.down();
                if(!isLavaSource(source) || !client.world.getFluidState(source).isStill())source=findLavaSource();
                if(source==null){
                    alignedLavaRetryTick=tick;
                    GlobalStateLogger.logCustomEvent("portal_aligned_lava_recovery",Map.of("target",target.toShortString(),
                            "state",state.toString(),"held",held.toString(),"action","WAIT_SOURCE"));
                    return false;
                }
                angles=MiningHelper.getValidFluidAngle(client.player,source);
                action="REFILL_BUCKET";
            }
        }else{
            alignedLavaRetryTick=tick;
            GlobalStateLogger.logCustomEvent("portal_aligned_lava_recovery",Map.of("target",target.toShortString(),
                    "state",state.toString(),"held",held.toString(),"action","WAIT_BUCKET_SYNC"));
            return false;
        }
        alignedLavaRetryTick=tick;
        client.player.yaw=angles[0];client.player.pitch=angles[1];
        client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookOnly(angles[0],angles[1],client.player.isOnGround()));
        var result=client.interactionManager.interactItem(client.player,client.world,Hand.MAIN_HAND);
        GlobalStateLogger.logCustomEvent("portal_aligned_lava_recovery",Map.of("target",target.toShortString(),
                "state",state.toString(),"held",held.toString(),"action",action,"result",result.toString()));
        return false;
    }

    private static BlockPos secondDoorBase(){
        return PreNether.magmaPos.offset(RelevantDirectionHelper.getRightDirection(PreNether.fromMagmaToAligned).getOpposite());
    }
    // DoorBlock.FACING follows the placing player's direction; the door's outside faces opposite.
    private static Direction portalDoorFacing(BlockPos base){
        return base.equals(PreNether.magmaPos)?PreNether.fromMagmaToAligned:PreNether.fromMagmaToAligned.getOpposite();
    }
    private static boolean portalDoorMatches(MinecraftClient client,BlockPos base){
        BlockState lower=client.world.getBlockState(base.up()),upper=client.world.getBlockState(base.up(2));
        return lower.getBlock() instanceof DoorBlock && upper.getBlock()==lower.getBlock()
                && lower.get(DoorBlock.HALF)==DoubleBlockHalf.LOWER && upper.get(DoorBlock.HALF)==DoubleBlockHalf.UPPER
                && lower.get(DoorBlock.FACING)==portalDoorFacing(base)
                && upper.get(DoorBlock.FACING)==portalDoorFacing(base)
                && !lower.get(DoorBlock.OPEN) && !upper.get(DoorBlock.OPEN);
    }
    /** Clear the intended closed door slab before sending a placement interaction. */
    private static boolean yieldPrimaryDoorSpace(MinecraftClient client,BlockPos base){
        Direction facing=portalDoorFacing(base);
        BlockPos door=base.up();
        BlockState intended=Blocks.OAK_DOOR.getDefaultState().with(DoorBlock.FACING,facing)
                .with(DoorBlock.OPEN,false).with(DoorBlock.HALF,DoubleBlockHalf.LOWER);
        var body=client.player.getBoundingBox().expand(.025,0,.025);
        boolean overlaps=false;
        for(var box:intended.getCollisionShape(client.world,door).getBoundingBoxes()){
            if(body.intersects(box.offset(door.getX(),door.getY(),door.getZ()))
                    || body.intersects(box.offset(door.getX(),door.getY()+1,door.getZ()))){
                overlaps=true;break;
            }
        }
        if(!overlaps)return false;
        double dx=door.getX()+.5+facing.getOffsetX()*.06-client.player.getX();
        double dz=door.getZ()+.5+facing.getOffsetZ()*.06-client.player.getZ();
        client.player.yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));
        client.player.pitch=0; // A prior straight-down swim aim must not turn this nudge into a dive.
        client.player.setSprinting(false);
        KeyBinding.setKeyPressed(client.options.keySprint.getDefaultKey(),false);
        KeyBinding.setKeyPressed(client.options.keyForward.getDefaultKey(),true);
        if(client.player.age%20==0)GlobalStateLogger.logCustomEvent("portal_primary_door_make_room",Map.of(
                "target",door.toShortString(),"facing",facing.toString(),
                "pos",client.player.getPos().toString(),"body",client.player.getBoundingBox().toString()));
        return true;
    }
    private static boolean ensurePortalDoor(MinecraftClient client,BlockPos base,int requiredDoors){
        BlockPos door=base.up();int tick=client.player.age;
        if(doorPlacementGuard.observe(door,tick,portalDoorMatches(client,base)))return true;
        if(portalDoorMatches(client,base))return false; // Wait through the server-update window.
        BlockState lower=client.world.getBlockState(door),upper=client.world.getBlockState(door.up());
        if(lower.getBlock() instanceof DoorBlock || upper.getBlock() instanceof DoorBlock){
            BlockPos repair=lower.getBlock() instanceof DoorBlock?door:door.up();
            ToolsHelper.equipBestTool(client.player,repair,false);
            client.interactionManager.updateBlockBreakingProgress(repair,Direction.UP);
            if(doorPlacementGuard.tryIssue(door,tick))GlobalStateLogger.logCustomEvent("portal_door_repair",Map.of(
                    "target",door.toShortString(),"lower",lower.toString(),"upper",upper.toString()));
            return false;
        }
        if(base.equals(PreNether.magmaPos) && yieldPrimaryDoorSpace(client,base))return false;
        if(!doorPlacementGuard.tryIssue(door,tick))return false;
        if(!(DoorBlock.getBlockFromItem(client.player.inventory.getStack(8).getItem()) instanceof DoorBlock)
                || client.player.inventory.getStack(8).getCount()<requiredDoors){
            BotEngine.getInstance().getModule(GlobalExecutor.class).resetWorld("地狱门施工：门材料不足（等待放置同步后检查）");
            return false;
        }
        Direction outside=portalDoorFacing(base).getOpposite();
        tryPlaceDoor(client.player,base,outside,RelevantDirectionHelper.getRightDirection(outside));
        return false;
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
        // TCP packet order: the server must receive the intended facing before the placement packet.
        client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookOnly(player.yaw,player.pitch,player.isOnGround()));
        GlobalStateLogger.logCustomEvent("portal_door_attempt",Map.of("target",basePos.up().toShortString(),
                "pos",player.getPos().toString(),"yaw",player.yaw,"pitch",player.pitch,
                "outside",doorOutFacingDirection.toString(),"expected_facing",doorOutFacingDirection.getOpposite().toString()));
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

    private static boolean clearanceDoorCell(BlockPos pos){
        return pos.equals(PreNether.magmaPos.up()) || pos.equals(PreNether.magmaPos.up(2));
    }

    /** Centre in the existing breathing door without handing movement back to generic pathfinding. */
    private static boolean holdClearanceDoor(MinecraftClient client){
        double dx=PreNether.magmaPos.getX()+.5-client.player.getX();
        double dz=PreNether.magmaPos.getZ()+.5-client.player.getZ();
        double dy=PreNether.magmaPos.getY()+1-client.player.getY();
        boolean wetHead=client.player.isSubmergedIn(FluidTags.WATER);
        if(client.player.isSwimming() || wetHead || dy>.15){
            KeyBinding.setKeyPressed(client.options.keySneak.getDefaultKey(),false);
            KeyBinding.setKeyPressed(client.options.keyJump.getDefaultKey(),true);
        }
        if(Math.hypot(dx,dz)>.12){
            MovementController.setLookDirection(client.player,(float)Math.toDegrees(Math.atan2(-dx,dz)),0);
            KeyBinding.setKeyPressed(client.options.keyForward.getDefaultKey(),true);
            return false;
        }
        return Math.abs(dy)<=.2 && client.player.isOnGround() && !client.player.isSwimming() && !wetHead;
    }

    private static void mineClearanceBlock(MinecraftClient client){
        Vec3d eye=new Vec3d(client.player.getX(),client.player.getEyeY(),client.player.getZ());
        for(BlockPos queued:twoByOneAreaClearPos){
            BlockPos target=queued;
            if(!clearanceDoorCell(target.up()) && client.world.getBlockState(target.up()).getBlock() instanceof FallingBlock)
                target=target.up();
            Vec3d point=Vec3d.ofCenter(target);
            if(eye.squaredDistanceTo(point)>20.25)continue;
            double dx=point.x-eye.x,dy=point.y-eye.y,dz=point.z-eye.z;
            MovementController.setLookDirection(client.player,(float)Math.toDegrees(Math.atan2(-dx,dz)),
                    (float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz))));
            ToolsHelper.equipBestTool(client.player,target,false);
            // Keep direct block digging: the breathing door can obscure the visual ray.
            client.interactionManager.updateBlockBreakingProgress(target,Direction.UP);
            if(!client.player.handSwinging)client.player.swingHand(Hand.MAIN_HAND);
            return;
        }
    }

    private static boolean secondDoorSpaceBlocked(MinecraftClient client,BlockPos base){
        // Even an already installed door needs its gravity cover checked again.
        if(client.world.getBlockState(base.up(3)).getBlock() instanceof FallingBlock)return true;
        return !portalDoorMatches(client,base) && (isNotClear(base.up()) || isNotClear(base.up(2)));
    }

    private static boolean areaNotClear() {
        // Re-scan live blocks each tick, retaining the first breathing door.
        twoByOneAreaClearPos.clear();
        BlockPos secondBase=secondDoorBase();
        boolean secondReady=portalDoorMatches(MinecraftClient.getInstance(),secondBase);
        List<BlockPos> cells=new ArrayList<>(PortalClearanceGeometry.cells(
                PreNether.magmaPos,PreNether.alignedMagmaPos,PreNether.fromMagmaToAligned));
        cells.add(secondBase.up());cells.add(secondBase.up(2));
        for(BlockPos pos:cells){
            if(clearanceDoorCell(pos))continue;
            BlockPos above=pos.up();
            // Check the extra level even when the original cell has just become empty.
            if(!clearanceDoorCell(above) && MinecraftClient.getInstance().world.getBlockState(above).getBlock() instanceof FallingBlock)
                twoByOneAreaClearPos.add(above);
            boolean secondDoorCell=pos.equals(secondBase.up()) || pos.equals(secondBase.up(2));
            if(!(secondReady && secondDoorCell) && isNotClear(pos))twoByOneAreaClearPos.add(pos);
        }
        boolean isNotClear=!twoByOneAreaClearPos.isEmpty();
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
