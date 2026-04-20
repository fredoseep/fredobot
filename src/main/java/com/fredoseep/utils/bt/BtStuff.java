package com.fredoseep.utils.bt;

import com.fredoseep.BtPosContext;
import com.fredoseep.behave.CraftingController;
import com.fredoseep.behave.MiscController;
import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.GlobalExecutor;
import com.fredoseep.excutor.PathExecutor;
import com.fredoseep.utils.player.InventoryHelper;
import com.fredoseep.utils.player.MiningHelper;
import com.fredoseep.utils.player.PlayerHelper;
import com.fredoseep.utils.player.ToolsHelper;
import net.fabricmc.loader.impl.lib.sat4j.specs.IVec;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.tag.BlockTags;
import net.minecraft.tag.ItemTags;
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

    public enum BtCraftPhase {
        CRUSH_LOGS, MAKE_TABLE, PLACE_TABLE, OPEN_TABLE, MAKE_STICKS_1, MAKE_STICKS_2, PROCESS_QUEUE, CHECK_40_PLANKS, GATHER_MISSING_PLANKS, CRUSH_NEW_LOGS, GATHER_MISSING_COBBLE, RETURN_TO_TABLE, OPEN_TABLE_RETURN, BREAK_TABLE, GETTING_LEAVES, DONE,INVENTORY_MANAGEMENT;
    }

    public enum TNTCraftPhase {
        EVALUATE, CRAFT_PLANKS, CRAFT_TRIGGER, DONE
    }

    public static TNTState tntState = TNTState.INIT;
    private static int tntWaitTicks = 0;
    private static BlockPos coverPos = null;
    private static int craftingWaitTicks = 0;
    public static boolean hasCheckedPlanksForFinalCrafts = false;
    public static BtCraftPhase btCraftPhase = BtCraftPhase.CRUSH_LOGS;
    public static TNTCraftPhase tntCraftPhase = TNTCraftPhase.EVALUATE;

    public static void reset() {
        hasTNT = false;
        ironIngotCount = 0;
        goldIngotCount = 0;
        diamondCount = 0;
        MIN_IRON_NEEDED = 7;
        itemsToCraft.clear();
        tntState = TNTState.INIT;
        tntWaitTicks = 0;
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
        btCraftPhase = BtCraftPhase.CRUSH_LOGS;
        tntCraftPhase = TNTCraftPhase.EVALUATE;
        InventoryHelper.doorType = null;
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

                    boolean isSurfaceClear = surfaceState.isAir() || surfaceState.isIn(BlockTags.LEAVES) || surfaceState.getMaterial().isReplaceable() || surfaceState.getHardness(world, surfacePos) == 0.0f;

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

                    boolean canPlaceTNT = candidateState.isAir() || candidateState.isIn(BlockTags.LEAVES) || candidateState.getMaterial().isReplaceable() || candidateState.getHardness(world, candidate) == 0.0f;

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
            System.out.println("Fredobot: reset because no place to set up TNT");
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
                    System.out.println("Fredobot: reset because no land found");
                }
                return;
            }
            MiningHelper.blockToMine.clear();
            MiningHelper.blockToMine.addAll(MiningHelper.findNearestBlocks(player, new HashSet<>(Collections.singletonList(Blocks.GRASS_BLOCK)), neededBlockCount, 30));

            if (MiningHelper.blockToMine.size() < neededBlockCount) {
                BotEngine.getInstance().getModule(GlobalExecutor.class).resetWorld();
                System.out.println("Fredobot : reset because cannot find enough grassblock");
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
            if (MinecraftClient.getInstance().world.getBlockState(currentBlockPos.up()).isAir())
                return currentBlockPos.up();
            if (MinecraftClient.getInstance().world.getBlockState(currentBlockPos.down()).isAir())
                return currentBlockPos.down();

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
            if (ironIngotCount != 0 && goldIngotCount != 0 && diamondCount != 0 && hasTNT) break;
            if (slot == 35) slot = 39;
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
        System.out.println("Fredodebug: craftingList: " + itemsToCraft);
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
                CraftingController tntCrafter = BotEngine.getInstance().getModule(CraftingController.class);

                if (tntCrafter.isBusy() || pathExecutor.isBusy() || BotEngine.getInstance().getModule(MiscController.class).isBusy())
                    return;

                if (tntCrafter.isFailed()) {
                    System.out.println("FredoBot: reset because fail to craft trigger");
                    tntCrafter.resetStatus();
                    globalExecutor.resetWorld();
                    return;
                }

                switch (tntCraftPhase) {
                    case EVALUATE:
                        boolean needIronPlate = itemsToCraft.contains(Items.HEAVY_WEIGHTED_PRESSURE_PLATE);
                        boolean needGoldPlate = itemsToCraft.contains(Items.LIGHT_WEIGHTED_PRESSURE_PLATE);

                        if (needIronPlate) {
                            tempCraftTarget = Items.HEAVY_WEIGHTED_PRESSURE_PLATE;
                            tntCraftPhase = TNTCraftPhase.CRAFT_TRIGGER;
                        } else if (needGoldPlate) {
                            tempCraftTarget = Items.LIGHT_WEIGHTED_PRESSURE_PLATE;
                            tntCraftPhase = TNTCraftPhase.CRAFT_TRIGGER;
                        } else {
                            tempCraftTarget = InventoryHelper.getTargetButtonType(player);
                            List<Item> plankList = InventoryHelper.getTargetPlankType(player);
                            tempPlankTarget = plankList.isEmpty() ? Items.OAK_PLANKS : plankList.get(0);

                            if (InventoryHelper.findItemSlot(player, tempPlankTarget) == -1) {
                                tntCraftPhase = TNTCraftPhase.CRAFT_PLANKS;
                            } else {
                                tntCraftPhase = TNTCraftPhase.CRAFT_TRIGGER;
                            }
                        }
                        break;

                    case CRAFT_PLANKS:
                        if (tntCrafter.isDone()) {
                            tntCrafter.resetStatus(); // 结算
                            tntCraftPhase = TNTCraftPhase.CRAFT_TRIGGER; // 进入下一步
                        } else {
                            tntCrafter.startCrafting(tempPlankTarget, 0); // 下达指令
                        }
                        break;

                    case CRAFT_TRIGGER:
                        if (tntCrafter.isDone()) {
                            tntCrafter.resetStatus(); // 结算
                            itemsToCraft.remove(Items.HEAVY_WEIGHTED_PRESSURE_PLATE);
                            itemsToCraft.remove(Items.LIGHT_WEIGHTED_PRESSURE_PLATE);
                            itemsToCraft.remove(Items.STONE_BUTTON);
                            tntState = TNTState.PLACE_TNT_AND_TRIGGER;
                            tntCraftPhase = TNTCraftPhase.EVALUATE;
                        } else {
                            tntCrafter.startCrafting(tempCraftTarget, 1); // 下达指令
                        }
                        break;
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
                        System.out.println("Fredbot: reset because cannot found TNT");
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
                        System.out.println("Fredobot: reset because cannot find trigger item");
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
                List<net.minecraft.entity.ItemEntity> drops = MinecraftClient.getInstance().world.getEntities(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(TNTSetupPos).expand(12.0), e -> true);

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
                System.out.println("Fredobot: reset because bt chunk cannot loaded");
                return;
            }

            setBtPos();
            if (btPos == null) {
                globalExecutor.resetWorld();
                System.out.println("Fredobot: reset because btPos is empty");
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
                        if (!InventoryHelper.BtUsefulStaff.isUseful(player.currentScreenHandler.getSlot(slot).getStack()))
                            continue;
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
                CraftingController crafter = BotEngine.getInstance().getModule(CraftingController.class);

                if (crafter.isBusy() || pathExecutor.isBusy() || BotEngine.getInstance().getModule(MiscController.class).isBusy())
                    return;

                if (crafter.isFailed()) {
                    System.out.println("FredoBot [异常]: 主流程合成模块报错失败！");
                    crafter.resetStatus();
                    globalExecutor.resetWorld();
                    return;
                }

                // 专用于等待工作台 GUI 打开的网络延迟
                if (craftingWaitTicks > 0) {
                    craftingWaitTicks--;
                    return;
                }

                switch (btCraftPhase) {
                    case CRUSH_LOGS:
                        if (crafter.isDone()) {
                            crafter.resetStatus(); // 结算上一轮粉碎
                        } else {
                            List<Item> plankTypes = InventoryHelper.getPlankTypesToCraftFromLogs(player);
                            if (plankTypes != null && !plankTypes.isEmpty()) {
                                crafter.startCrafting(plankTypes.get(0), 0); // 发包粉碎
                            } else {
                                btCraftPhase = BtCraftPhase.MAKE_TABLE; // 没原木了，下一步！
                            }
                        }
                        break;

                    case MAKE_TABLE:
                        if (crafter.isDone()) {
                            crafter.resetStatus();
                            btCraftPhase = BtCraftPhase.PLACE_TABLE;
                        } else {
                            if (InventoryHelper.findItemSlot(player, Items.CRAFTING_TABLE) == -1) {
                                crafter.startCrafting(Items.CRAFTING_TABLE, 1);
                            } else {
                                btCraftPhase = BtCraftPhase.PLACE_TABLE;
                            }
                        }
                        break;

                    case PLACE_TABLE:
                        if (itemsToCraft.isEmpty()) {
                            btCraftPhase = BtCraftPhase.BREAK_TABLE;
                            break;
                        }

                        int tableSlot = InventoryHelper.findItemSlot(player, Items.CRAFTING_TABLE);
                        if (tableSlot == -1) {
                            btCraftPhase = BtCraftPhase.MAKE_TABLE;
                            return;
                        }

                        InventoryHelper.moveItemToHotbar(minecraftClient, player, tableSlot, 4);
                        craftingTablePos = findAProperCraftingTablePos(player);
                        if (craftingTablePos == null) {
                            globalExecutor.resetWorld();
                            return;
                        }

                        player.inventory.selectedSlot = 4;

                        Vec3d tableTopCenter = new Vec3d(craftingTablePos.getX() + 0.5, craftingTablePos.getY() + 1.0, craftingTablePos.getZ() + 0.5);

                        // 【核心修复 1】：放工作台和开工作台前，强行扭头看向它！
                        double eyeY = player.getY() + player.getStandingEyeHeight();
                        double dx = tableTopCenter.x - player.getX();
                        double dy = tableTopCenter.y - eyeY;
                        double dz = tableTopCenter.z - player.getZ();
                        player.yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
                        player.pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));

                        BlockHitResult placeHit = new BlockHitResult(tableTopCenter, Direction.UP, craftingTablePos.down(), false);
                        minecraftClient.interactionManager.interactBlock(player, minecraftClient.world, net.minecraft.util.Hand.MAIN_HAND, placeHit);

                        BlockHitResult openHit = new BlockHitResult(tableTopCenter, Direction.UP, craftingTablePos, false);
                        minecraftClient.interactionManager.interactBlock(player, minecraftClient.world, net.minecraft.util.Hand.MAIN_HAND, openHit);

                        craftingWaitTicks = 10; // 【核心修复 2】：将等待开 GUI 的时间增加到 10 Tick
                        btCraftPhase = BtCraftPhase.OPEN_TABLE;
                        break;

                    case OPEN_TABLE:
                        if (player.currentScreenHandler instanceof CraftingScreenHandler) {
                            btCraftPhase = BtCraftPhase.MAKE_STICKS_1;
                        } else {
                            System.out.println("FredoBot [警告]: 初次打开工作台 GUI 超时，转入重新右键交互...");
                            btCraftPhase = BtCraftPhase.RETURN_TO_TABLE;
                        }
                        break;

                    case MAKE_STICKS_1:
                        if (crafter.isDone()) {
                            crafter.resetStatus();
                            btCraftPhase = BtCraftPhase.MAKE_STICKS_2;
                        } else {
                            crafter.startCrafting(Items.STICK, 1);
                        }
                        break;

                    case MAKE_STICKS_2:
                        if (crafter.isDone()) {
                            crafter.resetStatus();
                            btCraftPhase = BtCraftPhase.PROCESS_QUEUE;
                        } else {
                            crafter.startCrafting(Items.STICK, 1);
                        }
                        break;

                    case PROCESS_QUEUE:
                        if (itemsToCraft.isEmpty()) {
                            btCraftPhase = BtCraftPhase.BREAK_TABLE;
                            break;
                        }

                        if (crafter.isDone()) {
                            crafter.resetStatus();
                            itemsToCraft.remove(0); // 结算成功，划掉清单
                            break; // 结束本 Tick，下个 Tick 重新进入 PROCESS_QUEUE 处理下一项
                        }

                        Item targetItem = itemsToCraft.get(0);

                        // --- 拦截检查 ---
                        if ((targetItem == Items.OAK_BOAT || targetItem == Items.OAK_DOOR) && !hasCheckedPlanksForFinalCrafts) {
                            btCraftPhase = BtCraftPhase.CHECK_40_PLANKS;
                            break;
                        }

                        if (targetItem == Items.OAK_BOAT) targetItem = InventoryHelper.getTargetBoatType(player);
                        if (targetItem == Items.OAK_DOOR) {
                            targetItem = InventoryHelper.getTargetDoorType(player);
                            InventoryHelper.doorType = targetItem;
                        }

                        if (targetItem == Items.STONE_AXE || targetItem == Items.STONE_SHOVEL) {
                            int cobbleNeeded = (targetItem == Items.STONE_AXE) ? 3 : 1;
                            int cobbleCount = 0;
                            for (int i = 0; i < 36; i++) {
                                if (player.inventory.getStack(i).getItem() == Items.COBBLESTONE)
                                    cobbleCount += player.inventory.getStack(i).getCount();
                            }
                            if (cobbleCount < cobbleNeeded) {
                                btCraftPhase = BtCraftPhase.GATHER_MISSING_COBBLE;
                                break;
                            }
                        }

                        crafter.startCrafting(targetItem, 1); // 发起合成
                        break;

                    case CHECK_40_PLANKS:
                        int totalPlanks = 0;
                        for (int i = 0; i < 36; i++) {
                            if (player.inventory.getStack(i).getItem().isIn(net.minecraft.tag.ItemTags.PLANKS))
                                totalPlanks += player.inventory.getStack(i).getCount();
                        }
                        if (totalPlanks < 40) {
                            int logsNeeded = (int) Math.ceil((40.0 - totalPlanks) / 4.0);
                            player.closeHandledScreen();
                            minecraftClient.openScreen(null);

                            InventoryHelper.putAxeToHotbar(player);
                            player.inventory.selectedSlot = 0;

                            MiningHelper.mineAndCollect(player, InventoryHelper.anyLogs, logsNeeded, 50);

                            if (MiningHelper.blockToMine.isEmpty()) {
                                System.out.println("FredoBot [妥协]: 50格内彻底没树了！直接利用现有残余材料尽力造船/门！");
                                hasCheckedPlanksForFinalCrafts = true;
                                btCraftPhase = BtCraftPhase.RETURN_TO_TABLE;
                            } else {
                                btCraftPhase = BtCraftPhase.GATHER_MISSING_PLANKS;
                            }
                        } else {
                            hasCheckedPlanksForFinalCrafts = true;
                            btCraftPhase = BtCraftPhase.PROCESS_QUEUE;
                        }
                        break;

                    case GATHER_MISSING_PLANKS:
                        if (!MiningHelper.blockToMine.isEmpty()) {
                            MiningHelper.dispatchNextMineAndCollectTask();
                        } else {
                            btCraftPhase = BtCraftPhase.CRUSH_NEW_LOGS;
                        }
                        break;

                    case CRUSH_NEW_LOGS:
                        if (crafter.isDone()) {
                            crafter.resetStatus();
                        } else {
                            List<Item> newLogs = InventoryHelper.getPlankTypesToCraftFromLogs(player);
                            if (newLogs != null && !newLogs.isEmpty()) {
                                crafter.startCrafting(newLogs.get(0), 0);
                            } else {
                                hasCheckedPlanksForFinalCrafts = true;
                                btCraftPhase = BtCraftPhase.RETURN_TO_TABLE;
                            }
                        }
                        break;

                    case GATHER_MISSING_COBBLE:
                        int neededCobble = (itemsToCraft.get(0) == Items.STONE_AXE) ? 3 : 1;
                        int currentCobble = 0;
                        for (int i = 0; i < 36; i++) {
                            if (player.inventory.getStack(i).getItem() == Items.COBBLESTONE)
                                currentCobble += player.inventory.getStack(i).getCount();
                        }

                        player.closeHandledScreen();
                        minecraftClient.openScreen(null);

                        int pickSlot = InventoryHelper.findItemSlot(player, PickaxeItem.class);
                        if (pickSlot != -1) {
                            InventoryHelper.moveItemToHotbar(minecraftClient, player, pickSlot, 0);
                            player.inventory.selectedSlot = 0;
                        }

                        MiningHelper.mineAndCollect(player, new HashSet<>(Collections.singletonList(Blocks.STONE)), neededCobble - currentCobble, 15);
                        btCraftPhase = BtCraftPhase.RETURN_TO_TABLE;
                        break;

                    case RETURN_TO_TABLE:
                        if (craftingTablePos == null) {
                            globalExecutor.resetWorld();
                            return;
                        }
                        if (minecraftClient.world.getBlockState(craftingTablePos).getBlock() != Blocks.CRAFTING_TABLE) {
                            System.out.println("FredoBot [严重异常]: 原坐标的工作台不翼而飞！退回重新放置阶段...");
                            craftingTablePos = null; // 清空无效坐标
                            btCraftPhase = BtCraftPhase.MAKE_TABLE; // 重新造桌子（包里刚好有40个木板，完美衔接）
                            return;
                        }

                        if (MiningHelper.blockToMine.contains(craftingTablePos)) {
                            System.out.println("FredoBot [拦截]: 寻路试图将工作台作为障碍物挖掉，已被拦截！");
                            MiningHelper.blockToMine.remove(craftingTablePos);
                        }

                        double distToTable = player.squaredDistanceTo(Vec3d.ofCenter(craftingTablePos));
                        if (distToTable > 4.0) {
                            if (!MiningHelper.blockToMine.isEmpty()) {
                                MiningHelper.dispatchNextMineAndCollectTask();
                                break;
                            }
                            if (!pathExecutor.isBusy()) {
                                pathExecutor.setGoal(craftingTablePos.up());
                            }
                            return;
                        }
                        if (!MiningHelper.blockToMine.isEmpty()) MiningHelper.blockToMine.clear();
                        if (pathExecutor.isBusy()) pathExecutor.stop();

                        com.fredoseep.behave.MovementController mc = BotEngine.getInstance().getModule(com.fredoseep.behave.MovementController.class);
                        if (mc != null) mc.resetKeys();

                        System.out.println("FredoBot [调试]: 已到达工作台前，准备右键打开...");

                        Vec3d tableTopCenterReturn = new Vec3d(craftingTablePos.getX() + 0.5, craftingTablePos.getY() + 1.0, craftingTablePos.getZ() + 0.5);

                        double eyeYRet = player.getY() + player.getStandingEyeHeight();
                        double dxRet = tableTopCenterReturn.x - player.getX();
                        double dyRet = tableTopCenterReturn.y - eyeYRet;
                        double dzRet = tableTopCenterReturn.z - player.getZ();
                        player.yaw = (float) Math.toDegrees(Math.atan2(dzRet, dxRet)) - 90.0F;
                        player.pitch = (float) -Math.toDegrees(Math.atan2(dyRet, Math.sqrt(dxRet * dxRet + dzRet * dzRet)));

                        BlockHitResult returnHit = new BlockHitResult(tableTopCenterReturn, Direction.UP, craftingTablePos, false);
                        minecraftClient.interactionManager.interactBlock(player, minecraftClient.world, net.minecraft.util.Hand.MAIN_HAND, returnHit);

                        craftingWaitTicks = 10;
                        btCraftPhase = BtCraftPhase.OPEN_TABLE_RETURN;
                        break;

                    case OPEN_TABLE_RETURN:
                        if (player.currentScreenHandler instanceof CraftingScreenHandler) {
                            System.out.println("FredoBot [调试]: 成功打开工作台 GUI，进入合成队列！");
                            btCraftPhase = BtCraftPhase.PROCESS_QUEUE;
                        } else {
                            System.out.println("FredoBot [警告]: 工作台打开失败，退回重试交互！");
                            btCraftPhase = BtCraftPhase.RETURN_TO_TABLE;
                        }
                        break;
                    case BREAK_TABLE:
                        player.closeHandledScreen();
                        minecraftClient.openScreen(null);
                        InventoryHelper.putAxeToHotbar(player);
                        player.inventory.selectedSlot = 0;
                        MiningHelper.mineAndCollect(player, new HashSet<>(Collections.singletonList(Blocks.CRAFTING_TABLE)), 1, 10);
                        btCraftPhase = BtCraftPhase.DONE;
                        break;


                    case DONE:
                        if (!MiningHelper.blockToMine.isEmpty()) {
                            MiningHelper.dispatchNextMineAndCollectTask();
                            break;
                        }
                        craftingTablePos = null;
                        btCraftPhase = BtCraftPhase.CRUSH_LOGS;

                        boolean hasShears = InventoryHelper.findItemSlot(player, Items.SHEARS) != -1;
                        int currentLeaves = InventoryHelper.countItem(player, net.minecraft.tag.ItemTags.LEAVES);
                        if (!hasShears || currentLeaves >= 64) {
                            if (!hasShears) {
                                System.out.println("FredoBot : no shears go next");
                            } else {
                                System.out.println("FredoBot : 64 leaves done go next");
                            }
                            btCraftPhase = BtCraftPhase.INVENTORY_MANAGEMENT;
                        } else {
                            System.out.println("FredoBot: current leaves count:  " + currentLeaves );
                            btCraftPhase = BtCraftPhase.GETTING_LEAVES;
                        }
                        break;

                    case GETTING_LEAVES:
                        int existingLeaves = InventoryHelper.countItem(player, net.minecraft.tag.ItemTags.LEAVES);
                        int neededLeaves = 64 - existingLeaves;

                        if (neededLeaves > 0) {
                            MiningHelper.mineAndCollect(player, new HashSet<>(BlockTags.LEAVES.values()), neededLeaves, 80);
                            if (MiningHelper.blockToMine.isEmpty()) {
                                System.out.println("FredoBot : leaves is not enough go next");
                                btCraftPhase = BtCraftPhase.INVENTORY_MANAGEMENT;
                            } else {
                                btCraftPhase = BtCraftPhase.DONE;
                            }
                        } else {
                            btCraftPhase = BtCraftPhase.INVENTORY_MANAGEMENT;
                        }
                        break;
                    case INVENTORY_MANAGEMENT:
                        InventoryHelper.moveItemToHotbar(minecraftClient,player,AxeItem.class,0);
                        InventoryHelper.moveItemToHotbar(minecraftClient,player,PickaxeItem.class,1);
                        InventoryHelper.moveItemToHotbar(minecraftClient,player, ShovelItem.class,2);
                        InventoryHelper.moveItemToHotbar(minecraftClient,player,Items.BUCKET,3);
                        InventoryHelper.moveItemToHotbar(minecraftClient,player, Items.COOKED_SALMON,4);
                        InventoryHelper.moveItemToHotbar(minecraftClient,player,Items.COOKED_COD,4);
                        InventoryHelper.moveItemToHotbar(minecraftClient,player,InventoryHelper.getCheapestBlock(player,false),5);
                        InventoryHelper.moveItemToHotbar(minecraftClient,player,InventoryHelper.getCheapestBlock(player,false),6);
                        InventoryHelper.moveItemToHotbar(minecraftClient,player,InventoryHelper.getCheapestBlock(player,true),6);
                        InventoryHelper.moveItemToHotbar(minecraftClient,player,BoatItem.class,7);
                        InventoryHelper.moveItemToHotbar(minecraftClient,player,InventoryHelper.doorType,8);
                        globalExecutor.currentState = GlobalExecutor.GlobalState.NEXT;
                        System.out.println("Fredobot: Inventory management done");
                        break;
                }
                break;
        }
    }
}