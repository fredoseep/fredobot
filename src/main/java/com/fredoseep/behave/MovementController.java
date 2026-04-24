package com.fredoseep.behave;

import com.fredoseep.algorithm.SimplePathfinder;
import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.PathExecutor;
import com.fredoseep.utils.bt.BtStuff;
import com.fredoseep.utils.player.*;
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

import net.minecraft.util.math.*;
import net.minecraft.world.RayTraceContext;
import net.minecraft.world.World;

import java.util.ArrayList;
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
    private int boatHorizontalCollisionTicks = 0;
    private boolean boatHasBeenPlacedDown = false;
    private BlockPos lastPlacedDoorPos = null;
    private int collectDoorTicks = 0;
    private SimplePathfinder.MovementState nextState = SimplePathfinder.MovementState.WALKING;


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
        lastPlacedDoorPos = null;
        collectDoorTicks = 0;

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

    @Override
    public String getName() {
        return "Movement_Controller";
    }

    @Override
    public int getPriority() {
        return 40;
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
        List<SimplePathfinder.Node> nodeList = pathExecutor.getCurrentPath();
        int currentIndex = pathExecutor.getCurrentPathIndex();
        if (currentIndex + 1 < nodeList.size()) nextState = nodeList.get(currentIndex + 1).state;

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


            while (currentIndex < nodeList.size() && nodeList.get(currentIndex).state == SimplePathfinder.MovementState.JUMPING_AIR) {
                currentIndex++;
            }
            targetNode.pos = nodeList.get(currentIndex).pos;
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
        if (lastState == SimplePathfinder.MovementState.SWIMMING && targetNode.state != SimplePathfinder.MovementState.SWIMMING ) {
            pathExecutor.pause();
            BotEngine.getInstance().getModule(MiscController.class).startTask(MiscController.MiscType.BACK_FROM_SWIMMING, targetNode.pos);
            return;
        }
        if(targetNode.state == SimplePathfinder.MovementState.BUILDING_PILLAR&&!targetNode.pos.isWithinDistance(player.getBlockPos(),2.5)){
            targetNode.state = SimplePathfinder.MovementState.JUMPING_UP;
        }


        if (targetNode.pos.getY() > player.getY() && targetNode.state != SimplePathfinder.MovementState.SWIMMING)
            targetPitch = -60f;
        else targetPitch = -2f;
        if (lastState == SimplePathfinder.MovementState.BUILDING_PILLAR && targetNode.state != SimplePathfinder.MovementState.BUILDING_BRIDGE) {
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
                pathExecutor.pause();
                BotEngine.getInstance().getModule(MiscController.class).startTask(MiscController.MiscType.BACK_FROM_SWIMMING, targetNode.pos);
                boatHorizontalCollisionTicks = 0;
                return;
            }
        } else {
            boatHorizontalCollisionTicks = 0;
        }

        if (pathExecutor.getCurrentPath() != null) {
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
                if (targetNode.state == SimplePathfinder.MovementState.FALLING) {
                    pressForward = player.isOnGround();
                }
                if (MinecraftClient.getInstance().world.getBlockState(targetNode.pos).getBlock() == Blocks.SWEET_BERRY_BUSH) {
                    client.interactionManager.updateBlockBreakingProgress(targetNode.pos, Direction.UP);
                }
                pressForward = true;
                if (pathExecutor.getCurrentPath() != null) {
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
                if (MinecraftClient.getInstance().world.getBlockState(targetNode.pos).getBlock() == Blocks.SWEET_BERRY_BUSH) {
                    client.interactionManager.updateBlockBreakingProgress(targetNode.pos, Direction.UP);
                }
                pressForward = true;
                pressSprint = true;
                pressJump = true;
                break;

            case JUMPING_AIR:
                pressForward = true;
                pressSprint = lastState == SimplePathfinder.MovementState.JUMPING_AIR || nextState == SimplePathfinder.MovementState.JUMPING_AIR;

                RelevantDirectionHelper.RelevantDirection jumpDir = RelevantDirectionHelper.getRelevantDirection(player, targetNode.pos);

                if (isApproachingEdge(MinecraftClient.getInstance().world, player, jumpDir, 0.1)) {
                    pressJump = true;
                }
                break;

            case BUILDING_PILLAR:
                pressForward = false;
                pressSneak = true;

                BlockPos footPos = targetNode.pos.down();
                BlockState footState = MinecraftClient.getInstance().world.getBlockState(footPos);

                boolean isFlowerOrTorch = !footState.getMaterial().isReplaceable() && footState.getCollisionShape(MinecraftClient.getInstance().world, footPos).isEmpty();
                boolean isSnowLayer = footState.getBlock() == Blocks.SNOW; // 识别薄雪层

                if (isFlowerOrTorch || isSnowLayer) {
                    InventoryHelper.putShovelToHotbar(player);
                    player.inventory.selectedSlot = 2;
                    client.interactionManager.updateBlockBreakingProgress(footPos, Direction.UP);
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
                        pathExecutor.pause();
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

                if (lastPlacedDoorPos != null) {
                    if (!(client.world.getBlockState(lastPlacedDoorPos).getBlock() instanceof net.minecraft.block.DoorBlock)) {
                        collectDoorTicks++;
                        if (collectDoorTicks < 25) {
                            net.minecraft.util.math.Box searchBox = new net.minecraft.util.math.Box(lastPlacedDoorPos).expand(4.0);
                            java.util.List<net.minecraft.entity.ItemEntity> drops = client.world.getEntities(
                                    net.minecraft.entity.ItemEntity.class, searchBox,
                                    e -> e.getStack().getItem().isIn(net.minecraft.tag.ItemTags.DOORS)
                            );

                            if (!drops.isEmpty()) {
                                net.minecraft.entity.ItemEntity nearest = drops.get(0);
                                targetYaw = (float) Math.toDegrees(Math.atan2(-(nearest.getX() - player.getX()), nearest.getZ() - player.getZ()));
                                targetPitch = 30f;
                                pressForward = true;
                                pressSprint = true;
                                pressJump = false; // 防止乱跳
                                return; // 拦截成功！在捡到门之前，强行 return，绝不执行后续的任何挖掘逻辑！
                            } else if (collectDoorTicks <= 5) {
                                pressForward = false;
                                return; // 拦截！等待服务器爆出掉落物
                            } else {
                                lastPlacedDoorPos = null; // 门被水流冲没了或烧了，放弃
                            }
                        } else {
                            lastPlacedDoorPos = null; // 捡太久了，强行放行
                        }
                    } else {
                        if (player.squaredDistanceTo(Vec3d.ofCenter(lastPlacedDoorPos)) > 16.0) {
                            lastPlacedDoorPos = null; // 走太远了，忘掉这扇门
                        }
                    }
                }
                List<BlockPos> obstacles = getObstaclesInTunnel(player, client.world, targetNode.pos);

                if (!obstacles.isEmpty()) {
                    pressForward = false;
                    pressJump = false;
                    pressSprint = false;

                    BlockPos blockToMine = obstacles.get(0);

                    BlockPos playerPos = player.getBlockPos();
                    boolean isHeadInWater = client.world.getBlockState(new BlockPos(player.getX(), player.getEyeY(), player.getZ())).getMaterial() == net.minecraft.block.Material.WATER;

                    if (isHeadInWater) {
                        boolean is1x1 = client.world.getBlockState(playerPos.north()).getMaterial().isSolid() &&
                                client.world.getBlockState(playerPos.south()).getMaterial().isSolid() &&
                                client.world.getBlockState(playerPos.east()).getMaterial().isSolid() &&
                                client.world.getBlockState(playerPos.west()).getMaterial().isSolid();

                        if (blockToMine.getY() < playerPos.getY() && is1x1) {
                            BlockPos roofPos = playerPos.up(2);
                            if (client.world.getBlockState(roofPos).getMaterial().isLiquid()) {
                                if (selectBuildingBlock(player)) {
                                    targetPitch = -90f;
                                    BlockPos wallPos = roofPos.north();
                                    if (!client.world.getBlockState(roofPos.south()).getMaterial().isReplaceable()) wallPos = roofPos.south();
                                    else if (!client.world.getBlockState(roofPos.east()).getMaterial().isReplaceable()) wallPos = roofPos.east();
                                    else if (!client.world.getBlockState(roofPos.west()).getMaterial().isReplaceable()) wallPos = roofPos.west();

                                    BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(wallPos), Direction.DOWN, wallPos, false);
                                    client.interactionManager.interactBlock(client.player, client.world, net.minecraft.util.Hand.MAIN_HAND, hit);
                                    return;
                                }
                            }
                        }
                        else {
                            int doorSlot = -1;
                            for (int i = 0; i < 36; i++) {
                                if (player.inventory.getStack(i).getItem().isIn(net.minecraft.tag.ItemTags.DOORS)) {
                                    doorSlot = i;
                                    break;
                                }
                            }
                            if (doorSlot != -1) {
                                BlockPos doorPlacePos = null;

                                if (client.world.getBlockState(blockToMine.up()).getMaterial().isLiquid()) {
                                    doorPlacePos = blockToMine.up();
                                }
                                else if (client.world.getBlockState(playerPos).getMaterial().isLiquid() && client.world.getBlockState(playerPos.down()).getMaterial().isSolid()) {
                                    if (!playerPos.down().equals(blockToMine)) {
                                        doorPlacePos = playerPos;
                                    }
                                }

                                if (doorPlacePos != null && !(client.world.getBlockState(doorPlacePos).getBlock() instanceof net.minecraft.block.DoorBlock)) {
                                    BlockPos foundation = doorPlacePos.down();
                                    if (client.world.getBlockState(foundation).getMaterial().isSolid()) {
                                        InventoryHelper.moveItemToHotbar(client, player, doorSlot, player.inventory.selectedSlot);
                                        float[] angles = MiningHelper.getValidMiningAngle(player, foundation);
                                        targetYaw = angles[0];
                                        targetPitch = angles[1];

                                        if (Math.abs(MathHelper.wrapDegrees(player.yaw - targetYaw)) < 15.0f) {
                                            BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(foundation), Direction.UP, foundation, false);
                                            client.interactionManager.interactBlock(client.player, client.world, net.minecraft.util.Hand.MAIN_HAND, hit);

                                            lastPlacedDoorPos = doorPlacePos;
                                            collectDoorTicks = 0;
                                        }
                                        return;
                                    }
                                }
                            }
                        }
                    }
                    float[] miningAngles = MiningHelper.getValidMiningAngle(player, blockToMine);
                    targetYaw = miningAngles[0];
                    targetPitch = miningAngles[1];

                    ToolsHelper.equipBestTool(player, blockToMine, false);
                    client.interactionManager.updateBlockBreakingProgress(blockToMine, Direction.UP);

                    if (!player.handSwinging) {
                        player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                    }
                } else {
                    pressForward = true;
                    pressSprint = true;

                    double tX = targetNode.pos.getX() + 0.5D;
                    double tZ = targetNode.pos.getZ() + 0.5D;
                    targetYaw = (float) Math.toDegrees(Math.atan2(-(tX - player.getX()), tZ - player.getZ()));

                    if (targetNode.pos.getY() < player.getY()) {
                        targetPitch = 15f;
                    } else {
                        targetPitch = 0f;
                    }

                    if (player.horizontalCollision) {
                        horizontalCollisionTicks++;
                        if (horizontalCollisionTicks >= 3) pressJump = true;
                    } else {
                        horizontalCollisionTicks = 0;
                    }
                }
                break;
            case DIVING:
                double dX = targetX - player.getX();
                double dY = targetNode.pos.getY() + 0.5D - (player.getY() + player.getEyeHeight(player.getPose()));
                double dZ = targetZ - player.getZ();
                double horizontalDist = Math.sqrt(dX * dX + dZ * dZ);

                targetPitch = (float) -Math.toDegrees(Math.atan2(dY, horizontalDist));
                if (horizontalDist > 0.1) {
                    targetYaw = (float) Math.toDegrees(Math.atan2(-dX, dZ));
                }
                pressForward = true;
                pressSprint = true;

                break;

            case SWIMMING:
                if (PlayerHelper.isDrivingBoat(player)) {
                    boatInventorySleepingTick = 0;
                    BoatEntity boat = (BoatEntity) player.getVehicle();
                    SimplePathfinder.Node aimNode = targetNode;

                    if (pathExecutor.getCurrentPath() != null) {
                        List<SimplePathfinder.Node> path = pathExecutor.getCurrentPath();

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
                    if (MinecraftClient.getInstance().world.getBlockState(targetNode.pos.up()).getBlock() == Blocks.LILY_PAD) {
                        client.interactionManager.updateBlockBreakingProgress(targetNode.pos.up(), Direction.UP);
                    }
                    targetPitch = -1.0F;
                    pressForward = true;
                    pressSprint = true;

                    if (swimStateStabilizationTicks <= 25) {
                        swimStateStabilizationTicks++;
                    } else {
                        pressJump = (player.isSubmergedIn(FluidTags.WATER) && player.getVelocity().y < 0.03D) || player.getY() <= 61;
                    }
                } else if (!isBoatingNeeded(player) || (!InventoryHelper.hasBoat(player)) && !boatHasBeenPlacedDown) {
                    if (MinecraftClient.getInstance().world != null && (!isWaterDeepEnough() || MinecraftClient.getInstance().world.getBlockState(new BlockPos(player.getX(), player.getY(), player.getZ()).down()).getMaterial() != Material.WATER) || player.getHungerManager().getFoodLevel() <= 6) {
                        trySurfaceFloat();
                        break;
                    }
                    tryToSwim(player);
                    targetPitch = 10.0F;
                } else {
                    if (MinecraftClient.getInstance().world.getBlockState(targetNode.pos.up()).getBlock() == Blocks.LILY_PAD) {
                        client.interactionManager.updateBlockBreakingProgress(targetNode.pos.up(), Direction.UP);
                    }
                    tryToBoating(player);
                }
                break;
        }
        boolean isLastNode = (currentIndex == nodeList.size() - 1);
        boolean isNormalWalk = (targetNode.state == SimplePathfinder.MovementState.WALKING || targetNode.state == SimplePathfinder.MovementState.FALLING);

        if (isLastNode && isNormalWalk) {
            if (distSq < 0.6) { // 已经踏入最终方块的领地
                pressSprint = false; // 禁用奔跑，防止惯性冲过头
                pressJump = false;   // 禁用跳跃，防止乱飞
                targetPitch = 45f;   // 稍微低头，稳定视角

                if (distSq > 0.03) { // 还没完美居中 (容差 0.03，与 PathExecutor 严格对齐)
                    if (Math.abs(MathHelper.wrapDegrees(player.yaw - targetYaw)) < 5.0f) {
                        aimStabilizationTicks++;
                        if (aimStabilizationTicks >= 2) {
                            pressForward = true; // 视角稳定，往前蹭一步
                        } else {
                            pressForward = false; // 视角还在转，先刹车
                        }
                    } else {
                        aimStabilizationTicks = 0;
                        pressForward = false; // 视角偏了，立刻刹车转头
                    }
                } else {
                    pressForward = false; // 已经完美踩在正中心，停止移动！
                }
            }
        }

        if (!player.isOnGround() && targetNode.state != SimplePathfinder.MovementState.JUMPING_AIR && targetNode.state != SimplePathfinder.MovementState.SWIMMING && targetNode.state != SimplePathfinder.MovementState.DIVING)
            pressSprint = false;
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



    private void tryToBoating(PlayerEntity player) {
        if (InventoryHelper.putBoatToHotBar(player)) {
            player.inventory.selectedSlot = 7;
            targetPitch = 45F;
            boatInventorySleepingTick++;
        }
        if (boatInventorySleepingTick > 0) boatHasBeenPlacedDown = true;
        if(boatHasBeenPlacedDown)boatInventorySleepingTick++;
        if (boatInventorySleepingTick >= 4) {
            float deltaYaw = MathHelper.wrapDegrees(player.yaw - targetYaw);
            if (Math.abs(deltaYaw) < 2.0f) {
                pressJump = player.isTouchingWater();
                targetPitch = 60;
                pressUse = true;
            }
        }
    }

    private void tryToSwim(PlayerEntity player) {
        pressForward = true;
        pressSprint = true;
        if(player.isTouchingWater()&&MinecraftClient.getInstance().world.getBlockState(player.getBlockPos().down(2)).getMaterial().isLiquid())pressSneak = true;
        swimStateStabilizationTicks = 0;
    }

    private void trySurfaceFloat() {
        pressJump = true;
        pressSprint = true;
        pressForward = true;
    }
    /**
     * 动态获取阻挡玩家前往目标位置的方块 (基于碰撞箱隧道检测)
     */
    public static List<BlockPos> getObstaclesInTunnel(PlayerEntity player, World world, BlockPos targetPos) {
        List<BlockPos> obstacles = new ArrayList<>();
        BlockPos playerPos = player.getBlockPos();

        // 计算水平方向的坐标差
        int dx = Math.abs(playerPos.getX() - targetPos.getX());
        int dz = Math.abs(playerPos.getZ() - targetPos.getZ());

        // ==========================================
        // 【核心修复 1】：扩展的“跳坑/垂直下挖”特判
        // 只要目标在水平方向的距离 <= 1（即原地或紧挨着的方块），并且目标高度比当前低，
        // 绝对不要触发倾斜射线检测！直接清空目标位置的那一根垂直柱子！
        // ==========================================
        if (dx <= 1 && dz <= 1 && targetPos.getY() < playerPos.getY()) {
            // 从玩家当前高度 + 1（保证头不磕碰）开始，一路挖到目标坑底
            int maxY = Math.max(playerPos.getY() + 1, targetPos.getY() + 1);
            for (int y = maxY; y >= targetPos.getY(); y--) {
                BlockPos p = new BlockPos(targetPos.getX(), y, targetPos.getZ());

                // 工作台免死金牌
                if (com.fredoseep.utils.bt.BtStuff.craftingTablePos != null && p.equals(com.fredoseep.utils.bt.BtStuff.craftingTablePos)) {
                    continue;
                }

                BlockState state = world.getBlockState(p);
                // 【免死金牌 1】：垂直下挖时，绝对不把门当成障碍物
                if (state.getBlock() instanceof net.minecraft.block.DoorBlock) continue;

                if (!state.isAir() && state.getMaterial().isSolid()) {
                    obstacles.add(p);
                }
            }
            return obstacles;
        }

        // ==========================================
        // 【核心修复 2】：射线“瘦身”与防贴墙偏移
        // ==========================================
        double radiusX = 0.05;
        double radiusY = 0.9;
        double radiusZ = 0.05;

        // 防止玩家贴墙走导致射线起点陷入墙内，将起点坐标强行向当前方块中心拉拢 50%
        double startX = player.getX();
        double startZ = player.getZ();
        double centerX = playerPos.getX() + 0.5;
        double centerZ = playerPos.getZ() + 0.5;
        startX = startX * 0.5 + centerX * 0.5;
        startZ = startZ * 0.5 + centerZ * 0.5;

        Vec3d startCenter = new Vec3d(startX, player.getBoundingBox().getCenter().y, startZ);
        Vec3d targetCenter = new Vec3d(
                targetPos.getX() + 0.5, targetPos.getY() + 0.9, targetPos.getZ() + 0.5
        );

        Box targetBox = new Box(
                targetPos.getX() + 0.5 - radiusX, targetPos.getY(), targetPos.getZ() + 0.5 - radiusZ,
                targetPos.getX() + 0.5 + radiusX, targetPos.getY() + 1.8, targetPos.getZ() + 0.5 + radiusZ
        );
        Box movementTunnel = player.getBoundingBox().union(targetBox).expand(0.01);

        int minX = MathHelper.floor(movementTunnel.minX);
        int maxX = MathHelper.ceil(movementTunnel.maxX);
        int minY = MathHelper.floor(movementTunnel.minY);
        int maxY = MathHelper.ceil(movementTunnel.maxY);
        int minZ = MathHelper.floor(movementTunnel.minZ);
        int maxZ = MathHelper.ceil(movementTunnel.maxZ);

        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    mutable.set(x, y, z);

                    if (y < targetPos.getY()) continue;

                    // 工作台免死金牌
                    if (com.fredoseep.utils.bt.BtStuff.craftingTablePos != null && mutable.equals(com.fredoseep.utils.bt.BtStuff.craftingTablePos)) {
                        continue;
                    }

                    BlockState state = world.getBlockState(mutable);

                    // 【免死金牌 2】：水平扫描时，也绝对不把门当成障碍物
                    if (state.getBlock() instanceof net.minecraft.block.DoorBlock) continue;

                    if (!state.isAir() && !state.getMaterial().isLiquid() && state.getMaterial().isSolid()) {
                        net.minecraft.util.shape.VoxelShape shape = state.getCollisionShape(world, mutable);

                        if (!shape.isEmpty()) {
                            Box blockBox = shape.getBoundingBox().offset(mutable);
                            Box expandedBox = blockBox.expand(radiusX, radiusY, radiusZ);

                            if (com.fredoseep.utils.player.PlayerHelper.isLineIntersectingBox(startCenter, targetCenter, expandedBox)) {
                                obstacles.add(mutable.toImmutable());
                            }
                        }
                    }
                }
            }
        }

        obstacles.sort((p1, p2) -> Integer.compare(p2.getY(), p1.getY()));
        return obstacles;
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
        BlockState downState = minecraftClient.world.getBlockState(pathExecutor.getCurrentNode().pos.down());
        return downState.getMaterial() == Material.WATER || downState.getFluidState().isIn(FluidTags.WATER);
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
        int bestSlot = -1;
        int lowestCost = Integer.MAX_VALUE;

        for (int i = 0; i < 36; i++) {
            if (!player.inventory.main.get(i).isEmpty()) {
                InventoryHelper.PlaceableBlock block = InventoryHelper.PlaceableBlock.getPlaceable(player.inventory.main.get(i).getItem());

                if (block != null) {
                    if (block.getCost() < lowestCost) {
                        lowestCost = block.getCost();
                        bestSlot = i;
                    } else if (block.getCost() == lowestCost) {
                        if (i < 9 && bestSlot >= 9) {
                            bestSlot = i;
                        }
                    }
                }
            }
        }

        if (bestSlot == -1) {
            return false;
        }

        if (bestSlot < 9) {
            player.inventory.selectedSlot = bestSlot;
            return true;
        }

        MinecraftClient.getInstance().interactionManager.clickSlot(0, bestSlot, 5, net.minecraft.screen.slot.SlotActionType.SWAP, player);
        player.inventory.selectedSlot = 5;
        return true;
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

        Box box = player.getBoundingBox().offset(dx, -0.1, dz);

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