package com.fredoseep.utils.bt;

import com.fredoseep.BtPosContext;
import com.fredoseep.behave.MiscController;
import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.GlobalExecutor;
import com.fredoseep.excutor.PathExecutor;
import com.fredoseep.utils.player.InventoryHelper;
import com.fredoseep.utils.player.MiningHelper;
import com.fredoseep.utils.player.PlayerHelper;
import com.fredoseep.utils.player.ToolsHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.tag.BlockTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BtStuff {
    public static boolean hasTNT = false;
    private static int MIN_IRON_NEEDED = 7;
    private static int ironIngotCount = 0;
    private static int goldIngotCount = 0;
    private static int diamondCount = 0;
    private static Item tempCraftTarget = null;
    private static Item tempPlankTarget = null;
    public static int waitTicks = 0;

    public static BlockPos btPos = null;
    public static BlockPos btStandingPos = null;
    public static BlockPos craftingTablePos = null;
    public static BlockPos TNTSetupPos = null;
    public static boolean treeQueueGenerated = false;
    public static boolean tntQueueGenerated = false;

    public static final List<Item> itemsToCraft = new ArrayList<>();

    public enum TNTState {
        INIT, CLEAR_HEAD, CRAFTING, PLACE_TNT_AND_TRIGGER, TAKING_COVER_MOVE, WAIT_EXPLOSION, COLLECT_LOOT, DONE
    }

    public static TNTState tntState = TNTState.INIT;
    private static int tntWaitTicks = 0;
    private static BlockPos coverPos = null;
    private static int craftingStep = 0;
    private static int craftingWaitTicks = 0;
    public static boolean hasCheckedPlanksForFinalCrafts = false;

    public static void reset() {
        hasTNT = false;
        ironIngotCount = 0;
        goldIngotCount = 0;
        diamondCount = 0;
        MIN_IRON_NEEDED = 7;
        itemsToCraft.clear();
        tntState = TNTState.INIT;
        tntWaitTicks = 0;
        craftingStep = 0;
        craftingWaitTicks = 0;
        coverPos = null;
        waitTicks = 0;
        btPos = null;
        btStandingPos = null;
        craftingTablePos = null;
        TNTSetupPos = null;
        treeQueueGenerated = false;
        tntQueueGenerated = false;
        hasCheckedPlanksForFinalCrafts = false;
    }

    public static int groundYNoLeavesOrTrees(BlockPos checkCol) {
        BlockPos topPos = MinecraftClient.getInstance().world.getTopPosition(Heightmap.Type.WORLD_SURFACE, checkCol);
        int groundY = -1;
        for (int y = topPos.getY(); y > 40; y--) {
            BlockState state = MinecraftClient.getInstance().world.getBlockState(new BlockPos(checkCol.getX(), y, checkCol.getZ()));
            if (state.getMaterial().isSolid() && !state.isIn(BlockTags.LEAVES) && !state.isIn(BlockTags.LOGS)) {
                groundY = y;
                break;
            }
        }
        return groundY;
    }

    public static BlockPos calTNTSetupPos(PlayerEntity player) {
        World world = player.world;
        BlockPos pPos = player.getBlockPos();
        int pX = pPos.getX();
        int pZ = pPos.getZ();
        BlockPos bestTwoTreePos = null;
        BlockPos bestOneTreePos = null;

        for (int r = 0; r <= 50; r++) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (Math.abs(x) != r && Math.abs(z) != r) continue;
                    int worldX = pX + x;
                    int worldZ = pZ + z;
                    if (!world.getChunkManager().isChunkLoaded(worldX >> 4, worldZ >> 4)) continue;

                    BlockPos checkCol = new BlockPos(worldX, 0, worldZ);
                    int groundY = groundYNoLeavesOrTrees(checkCol);
                    if (groundY <= 40) continue;

                    BlockPos surfacePos = new BlockPos(worldX, groundY + 1, worldZ);
                    BlockState surfaceState = world.getBlockState(surfacePos);

                    boolean isSurfaceClear = surfaceState.isAir() ||
                            surfaceState.isIn(BlockTags.LEAVES) ||
                            surfaceState.getMaterial().isReplaceable() ||
                            surfaceState.getHardness(world, surfacePos) == 0.0f;

                    if (!isSurfaceClear || surfaceState.getMaterial().isLiquid()) continue;

                    int treeCount = 0;
                    treeSearchLoop:
                    for (int dx = -3; dx <= 3; dx++) {
                        for (int dz = -3; dz <= 3; dz++) {
                            if (dx * dx + dz * dz > 9) continue;
                            for (int dy = 1; dy <= 2; dy++) {
                                BlockPos treeCheckPos = new BlockPos(worldX + dx, groundY + dy, worldZ + dz);
                                if (world.getBlockState(treeCheckPos).isIn(BlockTags.LOGS)) {
                                    treeCount++;
                                    if (treeCount >= 3) break treeSearchLoop;
                                    break;
                                }
                            }
                        }
                    }

                    int targetY = -1;
                    if (treeCount >= 3) targetY = groundY + 1;
                    else if (treeCount == 2) targetY = groundY + 2;
                    else if (treeCount == 1) targetY = groundY + 3;
                    else continue;

                    BlockPos candidate = new BlockPos(worldX, targetY, worldZ);
                    BlockState candidateState = world.getBlockState(candidate);

                    boolean canPlaceTNT = candidateState.isAir() ||
                            candidateState.isIn(BlockTags.LEAVES) ||
                            candidateState.getMaterial().isReplaceable() ||
                            candidateState.getHardness(world, candidate) == 0.0f;

                    if (!canPlaceTNT || candidateState.getMaterial().isLiquid()) continue;

                    if (treeCount >= 3) {
                        return candidate;
                    } else if (treeCount == 2 && bestTwoTreePos == null) {
                        bestTwoTreePos = candidate;
                    } else if (treeCount == 1 && bestOneTreePos == null) {
                        bestOneTreePos = candidate;
                    }
                }
            }
        }

        if (bestTwoTreePos != null) {
            return bestTwoTreePos;
        }
        if (bestOneTreePos != null) {
            return bestOneTreePos;
        }
        return new BlockPos(-1, -1, -1);
    }

    public static void TNTSetupStaffs(PlayerEntity player) {
        TNTSetupPos = calTNTSetupPos(player);
        if (TNTSetupPos.equals(new BlockPos(-1, -1, -1))) {
            BotEngine.getInstance().getModule(GlobalExecutor.class).resetWorld();
            return;
        }

        int TNTSetupPosY = groundYNoLeavesOrTrees(TNTSetupPos);
        int neededBlockCount = TNTSetupPos.getY() - TNTSetupPosY - 1;

        if (InventoryHelper.countAvailableBuildingBlocks(player).pillaringBlocks < neededBlockCount) {
            if (player.isTouchingWater() || player.isSwimming()) {
                List<BlockPos> lands = MiningHelper.findNearestBlocks(player, new HashSet<>(Collections.singletonList(Blocks.GRASS_BLOCK)), 1, 50);
                if (!lands.isEmpty()) {
                    BotEngine.getInstance().getModule(PathExecutor.class).setGoal(lands.get(0));
                } else {
                    BotEngine.getInstance().getModule(GlobalExecutor.class).resetWorld();
                }
                return;
            }
            MiningHelper.blockToMine.clear();
            MiningHelper.blockToMine.addAll(MiningHelper.findNearestBlocks(
                    player,
                    new HashSet<>(Collections.singletonList(Blocks.GRASS_BLOCK)),
                    neededBlockCount,
                    30
            ));

            if (MiningHelper.blockToMine.size() < neededBlockCount) {
                BotEngine.getInstance().getModule(GlobalExecutor.class).resetWorld();
                return;
            }
            tntQueueGenerated = true;
            MiningHelper.dispatchNextMineAndCollectTask();
        } else {
            tntQueueGenerated = true;
        }
    }

    public static BlockPos findAProperCraftingTablePos(PlayerEntity player) {
        BlockPos playerPos = player.getBlockPos();
        for (Direction dir : new Direction[]{Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH}) {
            BlockPos currentBlockPos = playerPos.offset(dir);
            if (MinecraftClient.getInstance().world.getBlockState(currentBlockPos).isAir()) return currentBlockPos;
            if (MinecraftClient.getInstance().world.getBlockState(currentBlockPos.up()).isAir()) return currentBlockPos.up();
        }
        return null;
    }

    public static BlockPos findAProperPosThen() {
        BlockPos closeToBtPos = btPos;
        for (BlockPos currentPos = btPos; currentPos.getY() < 100; currentPos = currentPos.up()) {
            if (MinecraftClient.getInstance().world.getBlockState(currentPos).getMaterial().isLiquid() || MinecraftClient.getInstance().world.getBlockState(currentPos).isAir()) {
                closeToBtPos = currentPos;
                break;
            }
        }
        return closeToBtPos;
    }

    public static void setBtPos() {
        int rawX = BtPosContext.btXPos;
        int rawZ = BtPosContext.btZPos;
        int x = (rawX >> 4 << 4) + 9;
        int z = (rawZ >> 4 << 4) + 9;
        for (int i = 100; i >= 20; i--) {
            BlockPos currentPos = new BlockPos(x, i, z);
            if (MinecraftClient.getInstance().world.getBlockState(currentPos).getBlock() == Blocks.CHEST) {
                btPos = currentPos;
                return;
            }
        }
    }

    public static boolean evaluateBt(PlayerEntity player) {
        for (int slot = 0; slot < 36 || slot == 40; slot++) {
            if (player.inventory.getStack(slot).getItem() == Items.IRON_INGOT) {
                ironIngotCount = player.inventory.getStack(slot).getCount();
            } else if (player.inventory.getStack(slot).getItem() == Items.GOLD_INGOT) {
                goldIngotCount = player.inventory.getStack(slot).getCount();
            } else if (player.inventory.getStack(slot).getItem() == Items.DIAMOND) {
                diamondCount = player.inventory.getStack(slot).getCount();
            } else if (player.inventory.getStack(slot).getItem() == Items.TNT) {
                hasTNT = true;
            }
            if (ironIngotCount != 0 && goldIngotCount != 0 && diamondCount != 0) break;
            if (slot == 35) slot = 40;
        }

        if (diamondCount >= 3) MIN_IRON_NEEDED = 4;
        if (ironIngotCount < MIN_IRON_NEEDED) return false;

        int currentIronCount = ironIngotCount;
        int currentGoldCount = goldIngotCount;
        boolean pickaxeCrafted = false;
        boolean axeCrafted = false;
        boolean shovelCrafted = false;
        int currentDiamondCount = diamondCount;

        if (currentDiamondCount >= 3) {
            itemsToCraft.add(Items.DIAMOND_PICKAXE);
            pickaxeCrafted = true;
            currentDiamondCount -= 3;
        }
        if (!pickaxeCrafted) {
            itemsToCraft.add(Items.IRON_PICKAXE);
            pickaxeCrafted = true;
            currentIronCount -= 3;
        }

        itemsToCraft.add(Items.BUCKET);
        currentIronCount -= 3;

        if (currentDiamondCount == 3) {
            itemsToCraft.add(Items.DIAMOND_AXE);
            axeCrafted = true;
            currentDiamondCount -= 3;
        }
        if (currentDiamondCount == 2) {
            itemsToCraft.add(Items.DIAMOND_SWORD);
            currentDiamondCount -= 2;
        }
        if (currentDiamondCount == 1) {
            itemsToCraft.add(Items.DIAMOND_SHOVEL);
            shovelCrafted = true;
            currentDiamondCount--;
        }

        if (hasTNT) {
            if (currentIronCount >= 3) {
                itemsToCraft.add(Items.HEAVY_WEIGHTED_PRESSURE_PLATE);
                currentIronCount -= 2;
            } else if (currentGoldCount >= 2) {
                itemsToCraft.add(Items.LIGHT_WEIGHTED_PRESSURE_PLATE);
                currentGoldCount -= 2;
            } else {
                itemsToCraft.add(Items.STONE_BUTTON);
            }
        } else {
            if (currentIronCount >= 3) {
                itemsToCraft.add(Items.SHEARS);
                currentIronCount -= 2;
            }
        }

        if (!axeCrafted && currentIronCount >= 4) {
            itemsToCraft.add(Items.IRON_AXE);
            axeCrafted = true;
            currentIronCount -= 3;
        }
        if (!shovelCrafted && currentIronCount >= 2) {
            itemsToCraft.add(Items.IRON_SHOVEL);
            shovelCrafted = true;
            currentIronCount--;
        }
        if (!axeCrafted && currentGoldCount >= 3) {
            itemsToCraft.add(Items.GOLDEN_AXE);
            axeCrafted = true;
            currentGoldCount -= 3;
        }
        if (!shovelCrafted && currentGoldCount >= 1) {
            itemsToCraft.add(Items.GOLDEN_SHOVEL);
            shovelCrafted = true;
            currentGoldCount--;
        }
        if (!axeCrafted) itemsToCraft.add(Items.STONE_AXE);
        if (!shovelCrafted) itemsToCraft.add(Items.STONE_SHOVEL);

        itemsToCraft.add(Items.OAK_BOAT);
        itemsToCraft.add(Items.OAK_DOOR);

        return true;
    }



    public static void lightUpTNT(PlayerEntity player) {
        GlobalExecutor globalExecutor = BotEngine.getInstance().getModule(GlobalExecutor.class);
        PathExecutor pathExecutor = BotEngine.getInstance().getModule(PathExecutor.class);

        switch (tntState) {
            case INIT:
                BlockPos headPos = TNTSetupPos.up(2);
                if (!MinecraftClient.getInstance().world.getBlockState(headPos).isAir()) {
                    BotEngine.getInstance().getModule(MiscController.class).startTask(MiscController.MiscType.MINE_BLOCK_ABOVE_HEAD, headPos);
                    tntState = TNTState.CLEAR_HEAD;
                } else {
                    tntState = TNTState.CRAFTING;
                }
                break;

            case CLEAR_HEAD:
                if (!BotEngine.getInstance().getModule(MiscController.class).isBusy()) {
                    tntState = TNTState.CRAFTING;
                }
                break;

            case CRAFTING:
                if (craftingWaitTicks > 0) {
                    craftingWaitTicks--;
                    break;
                }

                if (craftingStep == 0) {
                    boolean needIronPlate = itemsToCraft.contains(Items.HEAVY_WEIGHTED_PRESSURE_PLATE);
                    boolean needGoldPlate = itemsToCraft.contains(Items.LIGHT_WEIGHTED_PRESSURE_PLATE);

                    if (needIronPlate) {
                        tempCraftTarget = Items.HEAVY_WEIGHTED_PRESSURE_PLATE;
                        craftingStep = 3;
                    } else if (needGoldPlate) {
                        tempCraftTarget = Items.LIGHT_WEIGHTED_PRESSURE_PLATE;
                        craftingStep = 3;
                    } else {
                        tempCraftTarget = InventoryHelper.getTargetButtonType(player);
                        List<Item> plankList = InventoryHelper.getTargetPlankType(player);
                        tempPlankTarget = plankList.isEmpty() ? Items.OAK_PLANKS : plankList.get(0);

                        if (InventoryHelper.findItemSlot(player, tempPlankTarget) == -1) {
                            craftingStep = 1;
                        } else {
                            craftingStep = 3;
                        }
                    }
                } else if (craftingStep == 1) {
                    if (!InventoryHelper.requestSearchCrafting(player, tempPlankTarget, 0)) {
                        globalExecutor.resetWorld();
                        return;
                    }
                    craftingWaitTicks = 2;
                    craftingStep = 2;
                } else if (craftingStep == 2) {
                    InventoryHelper.collectCraftingResult(player);
                    craftingStep = 3;
                } else if (craftingStep == 3) {
                    if (!InventoryHelper.requestSearchCrafting(player, tempCraftTarget, 1)) {
                        globalExecutor.resetWorld();
                        return;
                    }
                    craftingWaitTicks = 2;
                    craftingStep = 4;
                } else if (craftingStep == 4) {
                    InventoryHelper.collectCraftingResult(player);
                    itemsToCraft.remove(Items.HEAVY_WEIGHTED_PRESSURE_PLATE);
                    itemsToCraft.remove(Items.LIGHT_WEIGHTED_PRESSURE_PLATE);
                    itemsToCraft.remove(Items.STONE_BUTTON);
                    tntState = TNTState.PLACE_TNT_AND_TRIGGER;
                    craftingStep = 0;
                }
                break;
            case PLACE_TNT_AND_TRIGGER:
                if (player.isOnGround()) {
                    player.jump();
                    break;
                }

                if (player.getY() - TNTSetupPos.getY() >= 0.8) {
                    player.pitch = 90f;

                    int tntSlot = InventoryHelper.findItemSlot(player, Items.TNT);
                    if (tntSlot == -1) {
                        BotEngine.getInstance().getModule(GlobalExecutor.class).resetWorld();
                        return;
                    }
                    InventoryHelper.moveItemToHotbar(MinecraftClient.getInstance(), player, tntSlot, 0);

                    BlockHitResult tntHit = new BlockHitResult(Vec3d.ofCenter(TNTSetupPos), Direction.UP, TNTSetupPos.down(), false);
                    player.inventory.selectedSlot = 0;
                    MinecraftClient.getInstance().interactionManager.interactBlock((ClientPlayerEntity) player, MinecraftClient.getInstance().world, net.minecraft.util.Hand.MAIN_HAND, tntHit);

                    Item triggerItem = Items.OAK_BUTTON;
                    if (InventoryHelper.findItemSlot(player, Items.HEAVY_WEIGHTED_PRESSURE_PLATE) != -1) {
                        triggerItem = Items.HEAVY_WEIGHTED_PRESSURE_PLATE;
                    } else if (InventoryHelper.findItemSlot(player, Items.LIGHT_WEIGHTED_PRESSURE_PLATE) != -1) {
                        triggerItem = Items.LIGHT_WEIGHTED_PRESSURE_PLATE;
                    } else {
                        for (int i = 0; i < 36; i++) {
                            Item item = player.inventory.getStack(i).getItem();
                            if (item.isIn(net.minecraft.tag.ItemTags.BUTTONS)) {
                                triggerItem = item;
                                break;
                            }
                        }
                    }

                    int triggerSlot = InventoryHelper.findItemSlot(player, triggerItem);
                    if (triggerSlot == -1) {
                        BotEngine.getInstance().getModule(GlobalExecutor.class).resetWorld();
                        return;
                    }

                    InventoryHelper.moveItemToHotbar(MinecraftClient.getInstance(), player, triggerSlot, 1);
                    BlockPos triggerPos = TNTSetupPos.up();
                    BlockHitResult triggerPlaceHit = new BlockHitResult(Vec3d.ofCenter(triggerPos), Direction.UP, TNTSetupPos, false);
                    player.inventory.selectedSlot = 1;
                    MinecraftClient.getInstance().interactionManager.interactBlock((ClientPlayerEntity) player, MinecraftClient.getInstance().world, net.minecraft.util.Hand.MAIN_HAND, triggerPlaceHit);

                    if (triggerItem.isIn(net.minecraft.tag.ItemTags.BUTTONS)) {
                        MinecraftClient.getInstance().interactionManager.interactBlock((ClientPlayerEntity) player, MinecraftClient.getInstance().world, net.minecraft.util.Hand.MAIN_HAND, new BlockHitResult(Vec3d.ofCenter(triggerPos), Direction.UP, triggerPos, false));
                    }
                    tntState = TNTState.TAKING_COVER_MOVE;
                    tntWaitTicks = 0;
                }
                break;

            case TAKING_COVER_MOVE:
                tntWaitTicks++;
                if (coverPos == null) {
                    for (Direction dir : new Direction[]{Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH}) {
                        BlockPos check = TNTSetupPos.offset(dir);
                        if (MinecraftClient.getInstance().world.getBlockState(check).isAir() && MinecraftClient.getInstance().world.getBlockState(check.up()).isAir()) {
                            coverPos = check;
                            break;
                        }
                    }
                    if (coverPos == null) coverPos = TNTSetupPos.east();
                    Set<BlockPos> blacklist = new HashSet<>();
                    blacklist.add(TNTSetupPos);
                    blacklist.add(TNTSetupPos.down());
                    pathExecutor.setGoal(coverPos.down(2), blacklist);
                }

                if (tntWaitTicks > 90) {
                    if (pathExecutor.isBusy()) pathExecutor.stop();
                    tntState = TNTState.COLLECT_LOOT;
                    tntWaitTicks = 0;
                    break;
                }

                if (!pathExecutor.isBusy() && PlayerHelper.isNear(player, coverPos.down(2), 1)) {
                    tntState = TNTState.WAIT_EXPLOSION;
                }
                break;

            case WAIT_EXPLOSION:
                tntWaitTicks++;
                if (tntWaitTicks > 90) {
                    tntState = TNTState.COLLECT_LOOT;
                    tntWaitTicks = 0;
                }
                break;

            case COLLECT_LOOT:
                List<net.minecraft.entity.ItemEntity> drops = MinecraftClient.getInstance().world.getEntities(
                        net.minecraft.entity.ItemEntity.class,
                        new net.minecraft.util.math.Box(TNTSetupPos).expand(12.0),
                        e -> true
                );

                if (!drops.isEmpty()) {
                    if (!pathExecutor.isBusy()) {
                        net.minecraft.entity.ItemEntity nearestDrop = drops.get(0);
                        double minDist = player.squaredDistanceTo(nearestDrop);
                        for (net.minecraft.entity.ItemEntity drop : drops) {
                            double d = player.squaredDistanceTo(drop);
                            if (d < minDist) {
                                minDist = d;
                                nearestDrop = drop;
                            }
                        }
                        BlockPos safePos = new BlockPos(nearestDrop.getX(), nearestDrop.getY() + 0.5, nearestDrop.getZ());
                        pathExecutor.setGoal(safePos);
                    }
                } else {
                    tntState = TNTState.DONE;
                }
                break;
        }
    }

    public static void btStaff(ClientPlayerEntity player) {
        GlobalExecutor globalExecutor = BotEngine.getInstance().getModule(GlobalExecutor.class);
        PathExecutor pathExecutor = BotEngine.getInstance().getModule(PathExecutor.class);
        MinecraftClient minecraftClient = MinecraftClient.getInstance();

        if (btPos == null) {
            int x = BtPosContext.btXPos;
            int z = BtPosContext.btZPos;

            if (!minecraftClient.world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) {
                waitTicks++;
                if (waitTicks > 60) globalExecutor.resetWorld();
                return;
            }

            setBtPos();
            if (btPos == null) {
                globalExecutor.resetWorld();
                return;
            }
            if (btStandingPos == null) {
                btStandingPos = findAProperPosThen();
            }
        }

        switch (globalExecutor.currentState) {
            case IDLE:
                pathExecutor.setGoal(btStandingPos);
                globalExecutor.currentState = GlobalExecutor.GlobalState.GOING_TO_BT_STANDING_PLACE;
                break;

            case GOING_TO_BT_STANDING_PLACE:
                if (PlayerHelper.isNear(player, btStandingPos, 1) && !pathExecutor.isBusy()) {
                    globalExecutor.currentState = GlobalExecutor.GlobalState.DIGGING_BT;
                }
                break;

            case DIGGING_BT:
                if (ChestBlock.isChestBlocked(minecraftClient.world, btPos)) {
                    ToolsHelper.equipBestTool(player, btPos.up(), false);
                    minecraftClient.interactionManager.updateBlockBreakingProgress(btPos.up(), Direction.UP);
                    player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                } else {
                    globalExecutor.currentState = GlobalExecutor.GlobalState.LOOTING_BT;
                    BlockHitResult hitResult = new BlockHitResult(Vec3d.ofCenter(btPos), Direction.UP, btPos, false);
                    minecraftClient.interactionManager.interactBlock(player, minecraftClient.world, net.minecraft.util.Hand.MAIN_HAND, hitResult);
                }
                break;

            case LOOTING_BT:
                if (player.currentScreenHandler instanceof GenericContainerScreenHandler) {
                    int syncId = player.currentScreenHandler.syncId;
                    for (int slot = 0; slot < 27; slot++) {
                        if (!InventoryHelper.BtUsefulStaff.isUseful(player.currentScreenHandler.getSlot(slot).getStack())) continue;
                        if (player.currentScreenHandler.getSlot(slot).hasStack()) {
                            minecraftClient.interactionManager.clickSlot(syncId, slot, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, player);
                        }
                    }
                    player.closeHandledScreen();
                    minecraftClient.openScreen(null);
                    if (!evaluateBt(player)) {
                        globalExecutor.resetWorld();
                        System.out.println("Fredodebut: reset because bt not enough");
                        return;
                    }
                }
                globalExecutor.currentState = GlobalExecutor.GlobalState.LOOKING_FOR_TREES;
                break;

            case LOOKING_FOR_TREES:
                if (BotEngine.getInstance().getModule(MiscController.class).isBusy()) break;
                if (!MiningHelper.blockToMine.isEmpty()) {
                    if (pathExecutor.isBusy()) break;
                    MiningHelper.dispatchNextMineAndCollectTask();
                    break;
                }
                if (!treeQueueGenerated) {
                    if (pathExecutor.isBusy()) break;
                    if (hasTNT) {
                        if (itemsToCraft.contains(Items.STONE_BUTTON)) {
                            MiningHelper.mineAndCollect(player, InventoryHelper.anyLogs, 1, 100);
                        }
                    } else {
                        MiningHelper.mineAndCollect(player, InventoryHelper.anyLogs, 2, 100);
                    }
                    treeQueueGenerated = true;
                    break;
                }

                if (hasTNT) {
                    if (!tntQueueGenerated) {
                        if (pathExecutor.isBusy()) break;
                        TNTSetupStaffs(player);
                        break;
                    }

                    if (tntState == TNTState.INIT) {
                        double dX = player.getX() - (TNTSetupPos.getX() + 0.5);
                        double dZ = player.getZ() - (TNTSetupPos.getZ() + 0.5);
                        double dY = Math.abs(player.getY() - TNTSetupPos.getY());
                        boolean isCentered = (dX * dX + dZ * dZ <= 0.15) && (dY <= 0.5);

                        if (!isCentered) {
                            if (!pathExecutor.isBusy()) pathExecutor.setGoal(TNTSetupPos);
                            break;
                        }
                    }
                    lightUpTNT(player);
                    if (tntState != TNTState.DONE) break;
                }

                if (!pathExecutor.isBusy()) {
                    globalExecutor.currentState = GlobalExecutor.GlobalState.CRAFTING;
                }
                break;

            case CRAFTING:
                if (pathExecutor.isBusy() || BotEngine.getInstance().getModule(MiscController.class).isBusy()) return;
                if (craftingWaitTicks > 0) {
                    craftingWaitTicks--;
                    return;
                }

                if (craftingStep == 0) {
                    List<Item> plankTypes = InventoryHelper.getPlankTypesToCraftFromLogs(player);
                    if (plankTypes != null && !plankTypes.isEmpty()) {
                        Item plank = plankTypes.get(0);
                        if (!InventoryHelper.requestSearchCrafting(player, plank, 0)) {
                            System.out.println("FredoBot [异常]: 初始原木粉碎失败，触发重置！");
                            globalExecutor.resetWorld();
                            return;
                        }
                        craftingWaitTicks = 2;
                        craftingStep = 1;
                    } else {
                        craftingStep = 2;
                    }
                } else if (craftingStep == 1) {
                    InventoryHelper.collectCraftingResult(player);
                    craftingStep = 0;
                } else if (craftingStep == 2) {
                    if (InventoryHelper.findItemSlot(player, Items.CRAFTING_TABLE) == -1) {
                        if (!InventoryHelper.requestSearchCrafting(player, Items.CRAFTING_TABLE, 1)) {
                            System.out.println("FredoBot [异常]: 工作台配方发包失败，触发重置！");
                            globalExecutor.resetWorld();
                            return;
                        }
                        craftingWaitTicks = 2;
                        craftingStep = 3;
                    } else {
                        craftingStep = 4;
                    }
                } else if (craftingStep == 3) {
                    InventoryHelper.collectCraftingResult(player);
                    craftingStep = 4;
                } else if (craftingStep == 4) {
                    if (itemsToCraft.isEmpty()) {
                        craftingStep = 14;
                        break;
                    }

                    int tableSlot = InventoryHelper.findItemSlot(player, Items.CRAFTING_TABLE);
                    if (tableSlot == -1) {
                        craftingStep = 2;
                        return;
                    }

                    InventoryHelper.moveItemToHotbar(MinecraftClient.getInstance(), player, tableSlot, 4);
                    craftingTablePos = findAProperCraftingTablePos(player);

                    if (craftingTablePos == null) {
                        System.out.println("FredoBot [异常]: 找不到合适的地方放置工作台，触发重置！");
                        globalExecutor.resetWorld();
                        return;
                    }

                    player.inventory.selectedSlot = 4;
                    BlockHitResult craftingTablePlaceHit = new BlockHitResult(Vec3d.ofCenter(craftingTablePos), Direction.UP, craftingTablePos.down(), false);
                    MinecraftClient.getInstance().interactionManager.interactBlock(player, MinecraftClient.getInstance().world, net.minecraft.util.Hand.MAIN_HAND, craftingTablePlaceHit);

                    BlockHitResult craftingTableHit = new BlockHitResult(Vec3d.ofCenter(craftingTablePos), Direction.UP, craftingTablePos, false);
                    MinecraftClient.getInstance().interactionManager.interactBlock(player, MinecraftClient.getInstance().world, net.minecraft.util.Hand.MAIN_HAND, craftingTableHit);

                    craftingWaitTicks = 5;
                    craftingStep = 5;
                } else if (craftingStep == 5) {
                    if (player.currentScreenHandler instanceof CraftingScreenHandler) {
                        InventoryHelper.requestSearchCrafting(player, Items.STICK, 1);
                        craftingWaitTicks = 2;
                        craftingStep = 6;
                    } else {
                        craftingWaitTicks = 2;
                    }
                } else if (craftingStep == 6) {
                    InventoryHelper.collectCraftingResult(player);
                    craftingStep = 51;
                } else if (craftingStep == 51) {
                    InventoryHelper.requestSearchCrafting(player, Items.STICK, 1);
                    craftingWaitTicks = 2;
                    craftingStep = 52;
                } else if (craftingStep == 52) {
                    InventoryHelper.collectCraftingResult(player);
                    craftingStep = 7;
                } else if (craftingStep == 7) {
                    if (!itemsToCraft.isEmpty()) {
                        Item targetItem = itemsToCraft.get(0);

                        if (targetItem == Items.OAK_BOAT && !hasCheckedPlanksForFinalCrafts) {
                            int totalPlanks = 0;
                            for (int i = 0; i < 36; i++) {
                                Item item = player.inventory.getStack(i).getItem();
                                if (item.isIn(net.minecraft.tag.ItemTags.PLANKS)) {
                                    totalPlanks += player.inventory.getStack(i).getCount();
                                }
                            }

                            if (totalPlanks < 40) {
                                int logsNeeded = (int) Math.ceil((40.0 - totalPlanks) / 4.0);
                                System.out.println("FredoBot: 准备造船/门！木板不足40个 (当前 " + totalPlanks + ")，去砍 " + logsNeeded + " 个原木！");

                                player.closeHandledScreen();
                                MinecraftClient.getInstance().openScreen(null);

                                // 【核心修复】：强行把背包里的斧子切到 0 号快捷栏并拿在手上！(兼容金斧子)
                                InventoryHelper.putAxeToHotbar(player);
                                player.inventory.selectedSlot = 0;

                                MiningHelper.mineAndCollect(player, InventoryHelper.anyLogs, logsNeeded, 50);
                                craftingStep = 16;
                                return;
                            } else {
                                hasCheckedPlanksForFinalCrafts = true;
                            }
                        }

                        if (targetItem == Items.OAK_BOAT) targetItem = InventoryHelper.getTargetBoatType(player);
                        if (targetItem == Items.OAK_DOOR) targetItem = InventoryHelper.getTargetDoorType(player);

                        if (targetItem == Items.STONE_AXE || targetItem == Items.STONE_SHOVEL) {
                            int cobbleNeeded = (targetItem == Items.STONE_AXE) ? 3 : 1;
                            int cobbleCount = 0;
                            for (int i = 0; i < 36; i++) {
                                if (player.inventory.getStack(i).getItem() == Items.COBBLESTONE) {
                                    cobbleCount += player.inventory.getStack(i).getCount();
                                }
                            }
                            if (cobbleCount < cobbleNeeded) {
                                player.closeHandledScreen();
                                MinecraftClient.getInstance().openScreen(null);

                                // 【核心修复】：强行切出稿子去挖石头
                                int pickSlot = InventoryHelper.findItemSlot(player, net.minecraft.item.PickaxeItem.class);
                                if (pickSlot != -1) {
                                    InventoryHelper.moveItemToHotbar(MinecraftClient.getInstance(), player, pickSlot, 0);
                                    player.inventory.selectedSlot = 0;
                                }

                                MiningHelper.mineAndCollect(player, new HashSet<>(Collections.singletonList(Blocks.STONE)), cobbleNeeded - cobbleCount, 15);
                                craftingStep = 11;
                                return;
                            }
                        }

                        InventoryHelper.requestSearchCrafting(player, targetItem, 1);
                        craftingWaitTicks = 2;
                        craftingStep = 8;
                    } else {
                        craftingStep = 14;
                    }
                } else if (craftingStep == 8) {
                    InventoryHelper.collectCraftingResult(player);
                    itemsToCraft.remove(0);
                    craftingStep = 7;
                } else if (craftingStep == 11) {
                    if (!MiningHelper.blockToMine.isEmpty()) {
                        if (!pathExecutor.isBusy() && !BotEngine.getInstance().getModule(MiscController.class).isBusy()) {
                            MiningHelper.dispatchNextMineAndCollectTask();
                        }
                        return;
                    }
                    craftingStep = 12;
                } else if (craftingStep == 12) {
                    if (craftingTablePos == null) {
                        System.out.println("FredoBot [异常]: 返回时工作台坐标丢失，触发重置！");
                        globalExecutor.resetWorld();
                        return;
                    }
                    double dSq = player.squaredDistanceTo(Vec3d.ofCenter(craftingTablePos));
                    if (dSq > 16.0) {
                        if (!pathExecutor.isBusy()) pathExecutor.setGoal(craftingTablePos);
                        return;
                    }
                    BlockHitResult craftingTableHit = new BlockHitResult(Vec3d.ofCenter(craftingTablePos), Direction.UP, craftingTablePos, false);
                    MinecraftClient.getInstance().interactionManager.interactBlock(player, MinecraftClient.getInstance().world, net.minecraft.util.Hand.MAIN_HAND, craftingTableHit);
                    craftingWaitTicks = 5;
                    craftingStep = 13;
                } else if (craftingStep == 13) {
                    if (player.currentScreenHandler instanceof CraftingScreenHandler) {
                        craftingStep = 7;
                    } else {
                        craftingWaitTicks = 2;
                    }
                } else if (craftingStep == 14) {
                    player.closeHandledScreen();
                    MinecraftClient.getInstance().openScreen(null);

                    // 【核心修复】：挖工作台前强行切出斧子，并将扫描半径从3扩大到10，防止丢失！
                    InventoryHelper.putAxeToHotbar(player);
                    player.inventory.selectedSlot = 0;
                    MiningHelper.mineAndCollect(player, new HashSet<>(Collections.singletonList(Blocks.CRAFTING_TABLE)), 1, 10);

                    craftingStep = 15;
                } else if (craftingStep == 15) {
                    craftingTablePos = null;
                    craftingStep = 0;
                    System.out.println("FredoBot [完结]: 船与门合成完毕！工作台已回收！所有BT流程结束，进入 NEXT 阶段！");
                    globalExecutor.currentState = GlobalExecutor.GlobalState.NEXT;
                } else if (craftingStep == 16) {
                    if (!MiningHelper.blockToMine.isEmpty()) {
                        if (!pathExecutor.isBusy() && !BotEngine.getInstance().getModule(MiscController.class).isBusy()) {
                            MiningHelper.dispatchNextMineAndCollectTask();
                        }
                        return;
                    }
                    craftingStep = 17;
                } else if (craftingStep == 17) {
                    List<Item> plankTypes = InventoryHelper.getPlankTypesToCraftFromLogs(player);
                    if (plankTypes != null && !plankTypes.isEmpty()) {
                        Item plank = plankTypes.get(0);
                        if (!InventoryHelper.requestSearchCrafting(player, plank, 0)) {
                            System.out.println("FredoBot [异常]: 中途补木板时粉碎原木失败，触发重置！");
                            globalExecutor.resetWorld();
                            return;
                        }
                        craftingWaitTicks = 2;
                        craftingStep = 18;
                    } else {
                        hasCheckedPlanksForFinalCrafts = true;
                        craftingStep = 12;
                    }
                } else if (craftingStep == 18) {
                    InventoryHelper.collectCraftingResult(player);
                    craftingStep = 17;
                }
                break;
        }
    }
}