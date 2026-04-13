package com.fredoseep.behave;

import com.fredoseep.algorithm.SimplePathfinder;
import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.PathExecutor;
import com.fredoseep.utils.*;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.Material;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.tag.FluidTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RayTraceContext;
import net.minecraft.world.World;

import java.util.List;

public class MovementController implements IBotModule {

    private boolean pressBack = false;
    private boolean pressRight = false;
    private boolean pressLeft = false;
    private boolean pressForward = false;
    private boolean pressJump = false;
    private boolean pressUse = false;
    private boolean pressSneak = false;
    private boolean pressAttack = false;
    private boolean pressSprint = false;

    private float targetYaw;
    private float targetPitch;

    private boolean isAdjustingPosture = false;
    private SimplePathfinder.MovementState lastState = SimplePathfinder.MovementState.WALKING;
    private BlockPos lastTurningBlockPos = null;

    private int aimStabilizationTicks = 0;
    private int swimStateStabilizationTicks = 0;
    private int boatInventorySleepingTick = 0;
    private int horizontalCollisionTicks = 0;
    // 【新增】：冰川撞墙防死锁计时器
    private int boatHorizontalCollisionTicks = 0;
    private boolean boatHasBeenPlacedDown = false;

    @Override
    public String getName() {
        return "Movement_Controller";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public boolean isBusy() {
        PathExecutor pathExecutor = BotEngine.getInstance().getModule(PathExecutor.class);
        return pathExecutor != null && pathExecutor.getCurrentState().equals(PathExecutor.State.EXECUTING) && !pathExecutor.isPaused();
    }

    @Override
    public void onEnable() {
        resetKeys();
    }

    @Override
    public void onDisable() {
        resetKeys();
    }

    @Override
    public void onTick(MinecraftClient client, PlayerEntity player) {
        if (BotEngine.getInstance().highPriorityIsBusy(this.getPriority())) return;
        if (BotEngine.getInstance().getModule(PathExecutor.class).getCurrentState() == PathExecutor.State.IDLE) return;
        applyKeys(client);
    }

    public void executeMovementNode(MinecraftClient client, PlayerEntity player, SimplePathfinder.Node originalNode) {
        SimplePathfinder.Node targetNode = new SimplePathfinder.Node(originalNode.pos, originalNode.extraPos, originalNode.parent, originalNode.costFromStart, originalNode.estimatedCostToEnd, originalNode.state, originalNode.blocksUsed);
        PathExecutor pathExecutor = BotEngine.getInstance().getModule(PathExecutor.class);

        // 【最前置拦截】：智能转换悬崖边的 Pillar 为 Bridge
        if (targetNode.state == SimplePathfinder.MovementState.BUILDING_PILLAR && (targetNode.parent == null || targetNode.parent.state != SimplePathfinder.MovementState.BUILDING_PILLAR)) {
            if (MinecraftClient.getInstance().world.getBlockState(targetNode.pos.down()).isAir() && MinecraftClient.getInstance().world.getBlockState(targetNode.pos.down().down()).isAir() && MinecraftClient.getInstance().player.isOnGround()) {
                targetNode.state = SimplePathfinder.MovementState.BUILDING_BRIDGE;
                targetNode.pos = targetNode.pos.down();
                System.out.println("Fredobot: 已将悬崖边的 Pillar 智能转换为 Bridge！");
            }
        }

        if ((targetNode.state == SimplePathfinder.MovementState.JUMPING_UP || targetNode.state == SimplePathfinder.MovementState.BUILDING_BRIDGE) && targetNode.parent != null && targetNode.parent.state == SimplePathfinder.MovementState.BUILDING_PILLAR && MinecraftClient.getInstance().world.getBlockState(targetNode.parent.pos.down()).isAir()) {
            targetNode.state = SimplePathfinder.MovementState.BUILDING_PILLAR;
            targetNode.pos = targetNode.parent.pos;
        }
        if (targetNode.state == SimplePathfinder.MovementState.JUMPING_AIR) {
            List<SimplePathfinder.Node> nodeList = pathExecutor.getCurrentPath();
            int currentIndex = pathExecutor.getCurrentPathIndex();
            while (currentIndex < nodeList.size() && nodeList.get(currentIndex).state == SimplePathfinder.MovementState.JUMPING_AIR) {
                currentIndex++;
            }
            targetNode.pos = nodeList.get(currentIndex).pos;
        }
        if (targetNode.state == SimplePathfinder.MovementState.MINING) {
            boolean extraAir = targetNode.extraPos == null || MinecraftClient.getInstance().world.getBlockState(targetNode.extraPos).isAir();
            boolean posAir = MinecraftClient.getInstance().world.getBlockState(targetNode.pos).isAir();

            if (extraAir && posAir) {
                targetNode.state = SimplePathfinder.MovementState.WALKING;
            }
        }

        double targetX = targetNode.pos.getX() + 0.5D;
        double targetZ = targetNode.pos.getZ() + 0.5D;
        double distSq = (player.getX() - targetX) * (player.getX() - targetX) + (player.getZ() - targetZ) * (player.getZ() - targetZ);

        if (targetNode.state == SimplePathfinder.MovementState.BUILDING_BRIDGE) {
            targetYaw = player.yaw;
        } else if (targetNode.state == SimplePathfinder.MovementState.BUILDING_PILLAR && distSq <= 0.05) {
            targetYaw = player.yaw;
        } else {
            targetYaw = (float) Math.toDegrees(Math.atan2(-(targetX - player.getX()), targetZ - player.getZ()));
        }

        if (targetNode.state == SimplePathfinder.MovementState.BUILDING_BRIDGE && lastState != SimplePathfinder.MovementState.BUILDING_BRIDGE) {
            isAdjustingPosture = true;
        } else if (targetNode.state != SimplePathfinder.MovementState.BUILDING_BRIDGE) {
            isAdjustingPosture = false;
        }

        if (targetNode.state == SimplePathfinder.MovementState.SWIMMING && lastState != SimplePathfinder.MovementState.SWIMMING && !player.isTouchingWater() && !PlayerHelper.isDrivingBoat(player)) {
            targetNode.state = SimplePathfinder.MovementState.WALKING;
        }
        if (lastState == SimplePathfinder.MovementState.SWIMMING && targetNode.state != SimplePathfinder.MovementState.SWIMMING) {
            if (pathExecutor != null) pathExecutor.pause();
            BotEngine.getInstance().getModule(MiscController.class).startTask(MiscController.MiscType.BACK_FROM_SWIMMING, targetNode.pos);
        }


        targetPitch = player.pitch;
        if(lastState== SimplePathfinder.MovementState.BUILDING_PILLAR&&targetNode.state!= SimplePathfinder.MovementState.BUILDING_BRIDGE){
            targetPitch = 0.0F;
        }

        pressBack = false;
        pressRight = false;
        pressLeft = false;
        pressForward = false;
        pressJump = false;
        pressUse = false;
        pressSneak = false;
        pressAttack = false;
        pressSprint = false;

        if (PlayerHelper.isDrivingBoat(player) && player.getVehicle().horizontalCollision) {
            boatHorizontalCollisionTicks++;
            if (boatHorizontalCollisionTicks > 20) {
                System.out.println("FredoBot: 船只在冰川区卡死，执行壮士断腕，破拆船只！");
                if (pathExecutor != null) pathExecutor.pause();
                BotEngine.getInstance().getModule(MiscController.class).startTask(MiscController.MiscType.BACK_FROM_SWIMMING, targetNode.pos);
                boatHorizontalCollisionTicks = 0;
                return;
            }
        } else {
            boatHorizontalCollisionTicks = 0;
        }

        if (pathExecutor != null && pathExecutor.getCurrentPath() != null) {
            int currentIndex = pathExecutor.getCurrentPathIndex();
            List<SimplePathfinder.Node> path = pathExecutor.getCurrentPath();
            if (currentIndex > 0 && path.get(currentIndex - 1).state == SimplePathfinder.MovementState.JUMPING_AIR) {
                if (player.isOnGround()) {
                    pressJump = true;
                }
            }
        }

        switch (targetNode.state) {
            case WALKING:
                pressSprint = true;
            case FALLING:
                if(MinecraftClient.getInstance().world.getBlockState(targetNode.pos).getBlock()== Blocks.SWEET_BERRY_BUSH){
                    client.interactionManager.updateBlockBreakingProgress(targetNode.pos,Direction.UP);
                }
                pressForward = true;
                if (pathExecutor != null && pathExecutor.getCurrentPath() != null) {
                    int nextIndex = pathExecutor.getCurrentPathIndex() + 1;
                    List<SimplePathfinder.Node> path = pathExecutor.getCurrentPath();
                    if (nextIndex < path.size()) {
                        SimplePathfinder.Node nextNode = path.get(nextIndex);
                        if (nextNode.state == SimplePathfinder.MovementState.JUMPING_UP) {
                            double distToNextXZ = Math.sqrt(Math.pow(player.getX() - (nextNode.pos.getX() + 0.5), 2) + Math.pow(player.getZ() - (nextNode.pos.getZ() + 0.5), 2));
                            if (distToNextXZ < 1.15) {
                                pressJump = true;
                            }
                        }
                    }
                }

                if (player.horizontalCollision) {
                    horizontalCollisionTicks++;
                    if (horizontalCollisionTicks >= 3) pressJump = true;
                } else {
                    horizontalCollisionTicks = 0;
                }
                break;

            case JUMPING_UP:
                if(MinecraftClient.getInstance().world.getBlockState(targetNode.pos).getBlock()== Blocks.SWEET_BERRY_BUSH){
                    client.interactionManager.updateBlockBreakingProgress(targetNode.pos,Direction.UP);
                }
                pressForward = true;
                pressSprint = true;
                pressJump = true;
                break;

            case JUMPING_AIR:
                pressForward = true;
                pressSprint = true;

                RelevantDirectionHelper.RelevantDirection jumpDir = RelevantDirectionHelper.getRelevantDirection(player, targetNode.pos);

                if (isApproachingEdge(MinecraftClient.getInstance().world, player, jumpDir, 0.5)) {
                    pressJump = true;
                }
                break;

            case BUILDING_PILLAR:
                pressForward = false;
                pressSneak = true;

                BlockPos footPos = targetNode.pos.down();
                BlockState footState = MinecraftClient.getInstance().world.getBlockState(footPos);
                if (!footState.getMaterial().isReplaceable() && footState.getCollisionShape(MinecraftClient.getInstance().world, footPos).isEmpty()) {
                    System.out.println("FredoBot: 发现脚下有花/火把等障碍物，使用协议发包清除...");
                    client.interactionManager.updateBlockBreakingProgress(footPos, Direction.UP);
                    player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                    break;
                }

                if (distSq > 0.05) {
                    targetPitch = 60f;
                    if (Math.abs(MathHelper.wrapDegrees(player.yaw - targetYaw)) < 5.0f) {
                        aimStabilizationTicks++;
                        if (aimStabilizationTicks >= 3) pressForward = true;
                    } else {
                        aimStabilizationTicks = 0;
                        pressForward = false;
                    }
                } else {
                    aimStabilizationTicks = 0;
                    targetPitch = 90f;
                    pressJump = true;

                    if (targetNode.extraPos != null && !MinecraftClient.getInstance().world.getBlockState(targetNode.extraPos).isAir()) {
                        if (pathExecutor != null) pathExecutor.pause();
                        BotEngine.getInstance().getModule(MiscController.class).startTask(MiscController.MiscType.MINE_BLOCK_ABOVE_HEAD, targetNode.extraPos);
                    }

                    if (selectBuildingBlock(player)) {
                        if (player.fallDistance > 0.0F && !player.isOnGround() && !player.isClimbing() && !player.isTouchingWater()) {
                            pressUse = true;
                        }
                    } else {
                        System.out.println("FredoBot: 包里没方块用来垫脚了.");
                    }
                }
                break;

            case BUILDING_BRIDGE:
                targetPitch = 78.9f;
                RelevantDirectionHelper.RelevantDirection relevantDirection = RelevantDirectionHelper.getRelevantDirection(player, targetNode.pos);

                if (selectBuildingBlock(player)) {
                    if (targetNode.parent != null && targetNode.parent.parent != null &&
                            (!targetNode.pos.equals(lastTurningBlockPos)) &&
                            RelevantDirectionHelper.getDirectionBetween(targetNode.parent.parent.pos, targetNode.parent.pos) != RelevantDirectionHelper.getDirectionBetween(targetNode.parent.pos, targetNode.pos)) {

                        isAdjustingPosture = true;
                        lastTurningBlockPos = targetNode.pos;
                    }

                    if (isAdjustingPosture) {
                        targetYaw = adjustPostureForSpeedbridging(player, targetNode.pos, relevantDirection);
                    } else {
                        targetYaw = (float) relevantDirection.getSpeedbridgeYaw();
                        pressBack = true;
                        pressRight = true;
                        pressUse = true;
                        if (isApproachingEdge(MinecraftClient.getInstance().world, player, relevantDirection, 0.4)) {
                            pressSneak = true;
                        }
                    }
                }
                break;

            case MINING:
                BlockPos targetToMine = targetNode.pos;
                if (targetNode.extraPos != null && !MinecraftClient.getInstance().world.getBlockState(targetNode.extraPos).isAir()) {
                    targetToMine = targetNode.extraPos;
                }

                float[] angles = MiningHelper.getValidMiningAngle(player, targetToMine);
                targetYaw = angles[0];
                targetPitch = angles[1];

                ToolsHelper.equipBestTool(player, targetToMine, false);

                client.interactionManager.updateBlockBreakingProgress(targetToMine, Direction.UP);
                pressForward = true;
                break;

            case SWIMMING:
                if (PlayerHelper.isDrivingBoat(player)) {
                    boatInventorySleepingTick = 0;
                    BoatEntity boat = (BoatEntity) player.getVehicle();
                    SimplePathfinder.Node aimNode = targetNode;

                    if (pathExecutor != null && pathExecutor.getCurrentPath() != null) {
                        List<SimplePathfinder.Node> path = pathExecutor.getCurrentPath();
                        int currentIndex = pathExecutor.getCurrentPathIndex();

                        for (int i = currentIndex; i < path.size(); i++) {
                            SimplePathfinder.Node checkNode = path.get(i);
                            if (checkNode.state != SimplePathfinder.MovementState.SWIMMING) {
                                break;
                            }

                            Vec3d startVec = new Vec3d(boat.getX(), boat.getY() + 0.5D, boat.getZ());
                            Vec3d endVec = new Vec3d(checkNode.pos.getX() + 0.5D, boat.getY() + 0.5D, checkNode.pos.getZ() + 0.5D);
                            RayTraceContext context = new RayTraceContext(startVec, endVec, RayTraceContext.ShapeType.COLLIDER, RayTraceContext.FluidHandling.NONE, boat);
                            HitResult hitResult = client.world.rayTrace(context);

                            if (hitResult.getType() == HitResult.Type.BLOCK) {
                                if (i > currentIndex) aimNode = path.get(i - 1);
                                break;
                            }

                            aimNode = checkNode;
                            double dx = checkNode.pos.getX() + 0.5D - boat.getX();
                            double dz = checkNode.pos.getZ() + 0.5D - boat.getZ();
                            if ((dx * dx + dz * dz) > 64.0) break;
                        }
                    }

                    double bX = boat.getX();
                    double bZ = boat.getZ();
                    double tX = aimNode.pos.getX() + 0.5D;
                    double tZ = aimNode.pos.getZ() + 0.5D;

                    float targetBoatYaw = (float) Math.toDegrees(Math.atan2(-(tX - bX), tZ - bZ));
                    float deltaYaw = MathHelper.wrapDegrees(targetBoatYaw - boat.yaw);
                    float absDeltaYaw = Math.abs(deltaYaw);

                    boolean shouldTurn = false;
                    if (absDeltaYaw > 30.0F) shouldTurn = true;
                    else if (absDeltaYaw > 10.0F) shouldTurn = (player.age % 2 == 0);
                    else if (absDeltaYaw > 4.0F) shouldTurn = (player.age % 3 == 0);
                    else if (absDeltaYaw > 1.0F) shouldTurn = (player.age % 5 == 0);

                    if (shouldTurn) {
                        if (deltaYaw < 0.0F) pressLeft = true;
                        else pressRight = true;
                    }
                    if (absDeltaYaw < 60.0F) pressForward = true;

                    targetYaw = targetBoatYaw;
                    targetPitch = 0.0F;

                } else if (player.isSwimming()) {
                    if(MinecraftClient.getInstance().world.getBlockState(targetNode.pos.up()).getBlock()== Blocks.LILY_PAD){
                        client.interactionManager.updateBlockBreakingProgress(targetNode.pos.up(),Direction.UP);
                    }
                    targetPitch = -1.0F;
                    pressForward = true;
                    pressSprint = true;

                    if (swimStateStabilizationTicks <= 25) {
                        swimStateStabilizationTicks++;
                    } else {
                        pressJump = player.isSubmergedIn(FluidTags.WATER) && player.getVelocity().y < 0.03D;
                    }
                } else if (!isBoatingNeeded(player) || (!InventoryHelper.hasBoat(player)) && !boatHasBeenPlacedDown) {
                    if (MinecraftClient.getInstance().world != null && (!isWaterDeepEnough() || MinecraftClient.getInstance().world.getBlockState(new BlockPos(player.getX(), player.getY(), player.getZ()).down()).getMaterial() != Material.WATER) || player.getHungerManager().getFoodLevel() <= 6) {
                        trySurfaceFloat();
                        break;
                    }
                    tryToSwim(player);
                    targetPitch = 10.0F;
                } else {
                    if(MinecraftClient.getInstance().world.getBlockState(targetNode.pos.up()).getBlock()== Blocks.LILY_PAD){
                        client.interactionManager.updateBlockBreakingProgress(targetNode.pos.up(),Direction.UP);
                    }
                    tryToBoating(player);
                }
                break;
        }

        if (!player.isOnGround() && targetNode.state != SimplePathfinder.MovementState.JUMPING_AIR && targetNode.state != SimplePathfinder.MovementState.SWIMMING)
            pressSprint = false;//防止跳跃惯性
        setLookDirection(player, targetYaw, targetPitch);
        lastState = targetNode.state;
    }

    private void applyKeys(MinecraftClient client) {
        if (client.options == null) return;
        KeyBinding.setKeyPressed(client.options.keyBack.getDefaultKey(), pressBack);
        KeyBinding.setKeyPressed(client.options.keyRight.getDefaultKey(), pressRight);
        KeyBinding.setKeyPressed(client.options.keyLeft.getDefaultKey(), pressLeft);
        KeyBinding.setKeyPressed(client.options.keyForward.getDefaultKey(), pressForward);
        KeyBinding.setKeyPressed(client.options.keyJump.getDefaultKey(), pressJump);
        KeyBinding.setKeyPressed(client.options.keyUse.getDefaultKey(), pressUse);
        KeyBinding.setKeyPressed(client.options.keySneak.getDefaultKey(), pressSneak);
        KeyBinding.setKeyPressed(client.options.keyAttack.getDefaultKey(), pressAttack);
        KeyBinding.setKeyPressed(client.options.keySprint.getDefaultKey(), pressSprint);
    }

    public void resetKeys() {
        this.pressBack = false;
        this.pressRight = false;
        this.pressLeft = false;
        this.pressForward = false;
        this.pressJump = false;
        this.pressUse = false;
        this.pressSneak = false;
        this.pressAttack = false;
        this.pressSprint = false;

        this.aimStabilizationTicks = 0;
        this.swimStateStabilizationTicks = 0;
        this.boatInventorySleepingTick = 0;
        this.boatHasBeenPlacedDown = false;
        this.horizontalCollisionTicks = 0;
        this.boatHorizontalCollisionTicks = 0;

        lastState = SimplePathfinder.MovementState.WALKING;
        isAdjustingPosture = false;
        lastTurningBlockPos = null;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.options != null) {
            KeyBinding.setKeyPressed(client.options.keyBack.getDefaultKey(), false);
            KeyBinding.setKeyPressed(client.options.keyRight.getDefaultKey(), false);
            KeyBinding.setKeyPressed(client.options.keyLeft.getDefaultKey(), false);
            KeyBinding.setKeyPressed(client.options.keyForward.getDefaultKey(), false);
            KeyBinding.setKeyPressed(client.options.keyJump.getDefaultKey(), false);
            KeyBinding.setKeyPressed(client.options.keyUse.getDefaultKey(), false);
            KeyBinding.setKeyPressed(client.options.keySneak.getDefaultKey(), false);
            KeyBinding.setKeyPressed(client.options.keyAttack.getDefaultKey(), false);
            KeyBinding.setKeyPressed(client.options.keySprint.getDefaultKey(), false);
        }
    }

    private void tryToBoating(PlayerEntity player) {
        if (InventoryHelper.putBoatToHotBar(player)) {
            player.inventory.selectedSlot = 7;
            targetPitch = 30F;
            boatInventorySleepingTick++;
        }
        if (boatInventorySleepingTick > 0) boatHasBeenPlacedDown = true;
        if (boatInventorySleepingTick >= 4) {
            float deltaYaw = MathHelper.wrapDegrees(player.yaw - targetYaw);
            if (Math.abs(deltaYaw) < 2.0f) {
                pressJump = player.isTouchingWater();
                pressUse = true;
            }
        }
    }

    private void tryToSwim(PlayerEntity player) {
        pressForward = true;
        pressSprint = true;
        swimStateStabilizationTicks = 0;
    }

    private void trySurfaceFloat() {
        pressJump = true;
        pressSprint = true;
        pressForward = true;
    }

    private float adjustPostureForSpeedbridging(PlayerEntity player, BlockPos pos, RelevantDirectionHelper.RelevantDirection relevantDirection) {
        float adjustedYaw = (float) relevantDirection.getAdjustPostureForSpeedbridgingYaw();
        pressSneak = true;

        if (Math.abs(MathHelper.wrapDegrees(player.yaw - adjustedYaw)) > 2.0f) {
            return adjustedYaw;
        }

        if (((relevantDirection == RelevantDirectionHelper.RelevantDirection.SOUTH || relevantDirection == RelevantDirectionHelper.RelevantDirection.NORTH) && Math.abs(player.getX() - (pos.getX() + 0.5)) <= 0.02) ||
                ((relevantDirection == RelevantDirectionHelper.RelevantDirection.EAST || relevantDirection == RelevantDirectionHelper.RelevantDirection.WEST) && Math.abs(player.getZ() - (pos.getZ() + 0.5)) <= 0.02)) {
            isAdjustingPosture = false;
            return adjustedYaw;
        }

        if ((relevantDirection == RelevantDirectionHelper.RelevantDirection.SOUTH && player.getX() < pos.getX() + 0.5) ||
                (relevantDirection == RelevantDirectionHelper.RelevantDirection.NORTH && player.getX() > pos.getX() + 0.5) ||
                (relevantDirection == RelevantDirectionHelper.RelevantDirection.EAST && player.getZ() > pos.getZ() + 0.5) ||
                (relevantDirection == RelevantDirectionHelper.RelevantDirection.WEST && player.getZ() < pos.getZ() + 0.5)) {
            pressRight = true;
        } else {
            pressLeft = true;
        }

        return adjustedYaw;
    }

    private boolean isWaterDeepEnough() {
        PathExecutor pathExecutor = BotEngine.getInstance().getModule(PathExecutor.class);
        if (pathExecutor == null) return false;
        MinecraftClient minecraftClient = MinecraftClient.getInstance();
        if (minecraftClient.world == null) return false;
        return minecraftClient.world.getBlockState(pathExecutor.getCurrentNode().pos.down()).getMaterial() == Material.WATER;
    }

    private boolean isBoatingNeeded(PlayerEntity player) {
        PathExecutor pathExecutor = BotEngine.getInstance().getModule(PathExecutor.class);
        if (player.hasStatusEffect(StatusEffects.DOLPHINS_GRACE) && player.isSwimming()) return false;
        if (player.getHungerManager().getFoodLevel() <= 6) return true;
        if (pathExecutor == null) return false;
        List<SimplePathfinder.Node> currentPathList = pathExecutor.getCurrentPath();
        int waterCounter = 0;
        for (int i = pathExecutor.getCurrentPathIndex(); i < currentPathList.size(); i++) {
            if (waterCounter > 7) return true;
            if (currentPathList.get(i).state != SimplePathfinder.MovementState.SWIMMING) return false;
            waterCounter++;
        }
        return false;
    }

    public static void setLookDirection(PlayerEntity player, float targetYaw, float targetPitch) {
        float maxYawTurn = 40f;
        float maxPitchTurn = 30f;
        player.yaw = approachAngle(player.yaw, targetYaw, maxYawTurn);
        player.pitch = approachAngle(player.pitch, targetPitch, maxPitchTurn);
    }

    private static float approachAngle(float current, float target, float maxDelta) {
        float delta = MathHelper.wrapDegrees(target - current);
        if (delta > maxDelta) delta = maxDelta;
        else if (delta < -maxDelta) delta = -maxDelta;
        return current + delta;
    }

    private static boolean selectBuildingBlock(PlayerEntity player) {
        if (!player.inventory.getMainHandStack().isEmpty() &&
                InventoryHelper.PlaceableBlock.getPlaceable(player.inventory.getMainHandStack().getItem()) != null) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            if (!player.inventory.main.get(i).isEmpty()) {
                if (InventoryHelper.PlaceableBlock.getPlaceable(player.inventory.main.get(i).getItem()) != null) {
                    player.inventory.selectedSlot = i;
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean canPlace(MinecraftClient client, PlayerEntity player, BlockPos targetPos) {
        World world = client.world;
        if (world == null || player == null) return false;
        Vec3d eyePos = player.getCameraPosVec(1.0F);
        double maxReachSq = 20.25;

        for (Direction dir : Direction.values()) {
            BlockPos adjPos = targetPos.offset(dir);
            BlockState adjState = world.getBlockState(adjPos);
            if (adjState.getMaterial().isReplaceable()) continue;

            Direction faceToClick = dir.getOpposite();
            Vec3d hitPoint = new Vec3d(
                    adjPos.getX() + 0.5 + faceToClick.getOffsetX() * 0.5,
                    adjPos.getY() + 0.5 + faceToClick.getOffsetY() * 0.5,
                    adjPos.getZ() + 0.5 + faceToClick.getOffsetZ() * 0.5
            );

            if (eyePos.squaredDistanceTo(hitPoint) > maxReachSq) continue;

            RayTraceContext context = new RayTraceContext(eyePos, hitPoint, RayTraceContext.ShapeType.OUTLINE, RayTraceContext.FluidHandling.NONE, player);
            BlockHitResult hitResult = world.rayTrace(context);

            if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK) {
                if (hitResult.getBlockPos().equals(adjPos)) return true;
            }
        }
        return false;
    }

    public static boolean isApproachingEdge(World world, PlayerEntity player, RelevantDirectionHelper.RelevantDirection direction, double lookAhead) {
        double dx = 0.0;
        double dz = 0.0;
        if (direction != null) {
            switch (direction) {
                case NORTH:
                    dz = -lookAhead;
                    break;
                case SOUTH:
                    dz = lookAhead;
                    break;
                case EAST:
                    dx = lookAhead;
                    break;
                case WEST:
                    dx = -lookAhead;
                    break;
            }
        } else {
            return false;
        }

        net.minecraft.util.math.Box box = player.getBoundingBox().offset(dx, -0.1, dz);

        int minX = MathHelper.floor(box.minX);
        int maxX = MathHelper.floor(box.maxX - 1.0E-7D);
        int minY = MathHelper.floor(box.minY);
        int minZ = MathHelper.floor(box.minZ);
        int maxZ = MathHelper.floor(box.maxZ - 1.0E-7D);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos checkPos = new BlockPos(x, minY, z);
                if (world.getBlockState(checkPos).getMaterial().isSolid()) {
                    return false;
                }
            }
        }
        return true;
    }
}