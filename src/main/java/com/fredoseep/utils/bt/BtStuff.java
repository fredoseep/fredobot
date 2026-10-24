package com.fredoseep.utils.bt;

import com.fredoseep.BtPosContext;
import com.fredoseep.utils.player.DropCollectionOrder;
import com.fredoseep.utils.player.DropPickupReadiness;
import com.fredoseep.algorithm.planning.SquareSpiral;
import com.fredoseep.algorithm.planning.TntPillarSupplySearch;
import com.fredoseep.utils.player.MiningAim;
import net.minecraft.block.Block;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.RayTraceContext;
import com.fredoseep.preflight.EarlyResetPreflight;
import com.fredoseep.preflight.PreflightGeometry;
import com.fredoseep.preflight.TreasureLootValidation;
import com.fredoseep.algorithm.SimplePathfinder;
import com.fredoseep.behave.CraftingController;
import com.fredoseep.behave.MiscController;
import com.fredoseep.behave.MovementController;
import com.fredoseep.utils.player.WorkbenchApproach;
import net.minecraft.util.Hand;
import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.GlobalExecutor;
import com.fredoseep.excutor.PathExecutor;
import com.fredoseep.utils.player.TntLootPolicy;
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
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.tag.BlockTags;
import net.minecraft.tag.ItemTags;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.Biomes;

import java.nio.file.Path;
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
    private static final DropCollectionOrder tntPickupOrder = new DropCollectionOrder();
    private static ItemEntity tntLootTarget;
    private static boolean tntLootFirstTier;
    private static long tntLootFailureSerial;
    public static BlockPos craftingTablePos = null;
    public static BlockPos workbenchAnchor = null;
    private static WorkbenchApproach.Request workbenchRequest;
    private static final Set<BlockPos> rejectedWorkbenchStances = new HashSet<>();
    public static BlockPos TNTSetupPos = null;
    public static boolean treeQueueGenerated = false;
    public static boolean tntQueueGenerated = false;
    private static TntPillarSupplySearch.Plan tntSupplyPlan;
    private static final Set<BlockPos> rejectedTntSupplies = new HashSet<>();
    private static long tntSupplyFailureSerial;
    private static boolean tntSupplyApproachIssued;

    public static final List<Item> itemsToCraft = new ArrayList<>();

    public static BlockPos oceanFloorNoKelp(BlockPos magmaPos) {
        World world = MinecraftClient.getInstance().world;//127 10 203
        Biome biome = world.getBiome(magmaPos);
        BlockPos currentBlockPos = new BlockPos(magmaPos.getX(), 100, magmaPos.getZ());
        while (!world.getBlockState(currentBlockPos).getMaterial().isSolid() && currentBlockPos.getY() > 11) {
            currentBlockPos = currentBlockPos.down();
        }
        return currentBlockPos;
    }

    public enum TNTState {
        INIT, CLEAR_HEAD, CRAFTING, PLACE_TNT_AND_TRIGGER, TAKING_COVER_MOVE, WAIT_EXPLOSION, COLLECT_LOOT, DONE
    }

    public enum BtCraftPhase {
        CRUSH_LOGS, MAKE_TABLE, PLACE_TABLE, OPEN_TABLE, MAKE_STICKS_1, MAKE_STICKS_2, PROCESS_QUEUE, CHECK_40_PLANKS, GATHER_MISSING_PLANKS, CRUSH_NEW_LOGS, GATHER_MISSING_COBBLE, RETURN_TO_TABLE, OPEN_TABLE_RETURN, BREAK_TABLE, GETTING_LEAVES, DONE, INVENTORY_MANAGEMENT;
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

    private static final TntLootPolicy tntLootPolicy = new TntLootPolicy();

    public static void reset() {
        WorkbenchApproach.cancel();
        workbenchRequest = null;
        rejectedWorkbenchStances.clear();
        tntLootPolicy.reset();
        tntPickupOrder.reset();
        tntLootTarget = null;
        tntLootFirstTier = false;
        tntLootFailureSerial = 0;
        hasTNT = false;
        ironIngotCount = 0;
        goldIngotCount = 0;
        diamondCount = 0;
        MIN_IRON_NEEDED = 7;
        itemsToCraft.clear();
        tempCraftTarget = null;
        tempPlankTarget = null;
        tntState = TNTState.INIT;
        tntWaitTicks = 0;
        craftingWaitTicks = 0;
        coverPos = null;
        waitTicks = 0;
        btPos = null;
        btStandingPos = null;
        craftingTablePos = null;
        workbenchAnchor = null;
        TNTSetupPos = null;
        treeQueueGenerated = false;
        tntQueueGenerated = false;
        tntSupplyPlan = null;
        rejectedTntSupplies.clear();
        tntSupplyApproachIssued = false;
        hasCheckedPlanksForFinalCrafts = false;
        btCraftPhase = BtCraftPhase.CRUSH_LOGS;
        tntCraftPhase = TNTCraftPhase.EVALUATE;
        InventoryHelper.doorType = null;
    }

    public static int groundYNoLeavesOrTrees(BlockPos column) {
        return PreflightGeometry.ground(MinecraftClient.getInstance().world, column);
    }

    public static BlockPos calTNTSetupPos(PlayerEntity player) {
        var saved = EarlyResetPreflight.plan(MinecraftClient.getInstance());
        if (saved != null && saved.tnt() != null) return saved.tnt();
        BlockPos result = PreflightGeometry.tnt(player.world, btPos != null ? btPos : player.getBlockPos(), () -> {
        });
        return result == null ? new BlockPos(-1, -1, -1) : result;
    }

    public static void TNTSetupStaffs(PlayerEntity player) {
        MinecraftClient client = MinecraftClient.getInstance();
        PathExecutor path = BotEngine.getInstance().getModule(PathExecutor.class);
        MiscController misc = BotEngine.getInstance().getModule(MiscController.class);
        GlobalExecutor global = BotEngine.getInstance().getModule(GlobalExecutor.class);
        var saved = EarlyResetPreflight.plan(client);
        if (saved != null && saved.tnt() != null && !player.world.getChunkManager()
                .isChunkLoaded(saved.tnt().getX() >> 4, saved.tnt().getZ() >> 4)) return;
        if (TNTSetupPos == null) TNTSetupPos = calTNTSetupPos(player);
        if (TNTSetupPos.equals(new BlockPos(-1, -1, -1))) {
            global.resetWorld("TNT 布置条件不足：无合适位置");
            return;
        }
        int ground = groundYNoLeavesOrTrees(TNTSetupPos);
        int required = Math.max(0, TNTSetupPos.getY() - ground - 1);
        int available = InventoryHelper.countAvailableBuildingBlocks(player).pillaringBlocks;
        if (available >= required) {
            tntSupplyPlan = null;
            tntSupplyApproachIssued = false;
            tntQueueGenerated = true;
            return;
        }
        if (path.isBusy() || misc.isBusy()) return;
        if (tntSupplyPlan == null) {
            BlockPos center = new BlockPos(TNTSetupPos.getX(), ground, TNTSetupPos.getZ());
            tntSupplyPlan = TntPillarSupplySearch.find(center, TNTSetupPos, rejectedTntSupplies, p -> {
                if (!client.world.getChunkManager().isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) return false;
                BlockState state = client.world.getBlockState(p);
                Block block = state.getBlock();
                return state.getFluidState().isEmpty() && (block == Blocks.DIRT || block == Blocks.GRAVEL
                        || block == Blocks.SAND || block == Blocks.GRASS_BLOCK || block == Blocks.COARSE_DIRT);
            }, p -> tntSupplyStance(client, p));
            if (tntSupplyPlan == null) {
                global.resetWorld("TNT 垫高材料不足：TNT 周围没有可挖且可接近的材料，缺少 " + (required - available) + " 块");
                return;
            }
            tntSupplyApproachIssued = false;
        }
        if (!tntSupplyApproachIssued) {
            Set<BlockPos> protectedColumn = new HashSet<>();
            for (int y = 0; y < 256; y++) protectedColumn.add(new BlockPos(TNTSetupPos.getX(), y, TNTSetupPos.getZ()));
            tntSupplyFailureSerial = path.getSearchFailureSerial();
            tntSupplyApproachIssued = true;
            path.setGoal(tntSupplyPlan.stand(), protectedColumn, "TNT pillar material approach");
            return;
        }
        BlockPos target = tntSupplyPlan.target(), stand = tntSupplyPlan.stand();
        if (path.getSearchFailureSerial() != tntSupplyFailureSerial
                || player.squaredDistanceTo(Vec3d.ofBottomCenter(stand)) > 1.0
                || MiningAim.visibleFace(client.world, player, target) == null) {
            rejectedTntSupplies.add(target);
            tntSupplyPlan = null;
            tntSupplyApproachIssued = false;
            return;
        }
        path.stopAndForgetGoal();
        MiningHelper.blockToMine.clear();
        MiningHelper.currentTargetBlocks.clear();
        MiningHelper.currentTargetBlocks.add(client.world.getBlockState(target).getBlock());
        rejectedTntSupplies.add(target);
        tntSupplyPlan = null;
        tntSupplyApproachIssued = false;
        // Only this target is mined/collected; the inventory is checked again before choosing another.
        misc.startTask(MiscController.MiscType.MINE_THE_BLOCK_AND_COLLECT_THE_DROP, target);
    }

    private static BlockPos tntSupplyStance(MinecraftClient client, BlockPos target) {
        for (int radius : new int[]{1, 2, 0})
            for (int dy : new int[]{1, 0, -1, 2})
                for (int x = -radius; x <= radius; x++)
                    for (int z = -radius; z <= radius; z++) {
                        if (Math.max(Math.abs(x), Math.abs(z)) != radius) continue;
                        BlockPos stand = target.add(x, dy, z);
                        if (stand.getX() == TNTSetupPos.getX() && stand.getZ() == TNTSetupPos.getZ()
                                || !WorkbenchApproach.standable(client, stand)) continue;
                        Vec3d eye = Vec3d.ofBottomCenter(stand).add(0, 1.62, 0);
                        for (Direction side : Direction.values()) {
                            Vec3d point = Vec3d.ofCenter(target).add(side.getOffsetX() * .49, side.getOffsetY() * .49, side.getOffsetZ() * .49);
                            if (eye.squaredDistanceTo(point) > 20.25) continue;
                            BlockHitResult hit = client.world.rayTrace(new RayTraceContext(eye, point, RayTraceContext.ShapeType.OUTLINE,
                                    RayTraceContext.FluidHandling.NONE, client.player));
                            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target)) return stand;
                        }
                    }
        return null;
    }

    public static BlockPos findAProperCraftingTablePos(PlayerEntity player) {
        World world = player.world;
        Vec3d eye = new Vec3d(player.getX(), player.getEyeY(), player.getZ());
        for (var offset : SquareSpiral.offsets(4))
            for (int dy : new int[]{0, 1, -1}) {
                BlockPos pos = player.getBlockPos().add(offset.x(), dy, offset.z());
                if (!world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
                BlockState space = world.getBlockState(pos), support = world.getBlockState(pos.down());
                if (!space.getMaterial().isReplaceable() || !space.getFluidState().isEmpty()
                        || !Block.isShapeFullCube(support.getCollisionShape(world, pos.down())) || world.getBlockEntity(pos.down()) != null
                        || player.getBoundingBox().intersects(new Box(pos))) continue;
                Vec3d point = new Vec3d(pos.getX() + .5, pos.getY() - .01, pos.getZ() + .5);
                if (eye.squaredDistanceTo(point) > 20.25) continue;
                BlockHitResult hit = world.rayTrace(new RayTraceContext(eye, point, RayTraceContext.ShapeType.OUTLINE, RayTraceContext.FluidHandling.NONE, player));
                if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos.down()) && hit.getSide() == Direction.UP)
                    return pos;
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

        if (currentDiamondCount >= 3) {
            itemsToCraft.add(Items.DIAMOND_AXE);
            axeCrafted = true;
            currentDiamondCount -= 3;
        }
        if (currentDiamondCount >= 1) {
            itemsToCraft.add(Items.DIAMOND_SHOVEL);
            shovelCrafted = true;
            currentDiamondCount--;
        }
        if (currentDiamondCount >= 2) {
            itemsToCraft.add(Items.DIAMOND_SWORD);
            currentDiamondCount -= 2;
        }


        if (hasTNT) {
            var trigger = TntTriggerPolicy.choose(InventoryHelper.findItemSlot(player, Items.FLINT_AND_STEEL) >= 0,
                    InventoryHelper.countItem(player, Items.FLINT), currentIronCount, currentIronCount >= 3, currentGoldCount >= 2);
            if (trigger == TntTriggerPolicy.Trigger.EXISTING_LIGHTER) {
                // Reuse an existing lighter; no trigger recipe required.
            } else if (trigger == TntTriggerPolicy.Trigger.CRAFT_LIGHTER) {
                itemsToCraft.add(Items.FLINT_AND_STEEL);
                currentIronCount--;
            } else if (trigger == TntTriggerPolicy.Trigger.IRON_PLATE) {
                itemsToCraft.add(Items.HEAVY_WEIGHTED_PRESSURE_PLATE);
                currentIronCount -= 2;
            } else if (trigger == TntTriggerPolicy.Trigger.GOLD_PLATE) {
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


    private static boolean canCraftTntLighter(PlayerEntity player) {
        int reserved = 0;
        for (Item item : itemsToCraft) {
            if (item == Items.IRON_PICKAXE || item == Items.IRON_AXE || item == Items.BUCKET) reserved += 3;
            else if (item == Items.SHEARS || item == Items.IRON_SWORD) reserved += 2;
            else if (item == Items.IRON_SHOVEL) reserved++;
        }
        return TntTriggerPolicy.choose(false, InventoryHelper.countItem(player, Items.FLINT),
                InventoryHelper.countItem(player, Items.IRON_INGOT) - reserved, false, false) == TntTriggerPolicy.Trigger.CRAFT_LIGHTER;
    }

    private static void removeTntTriggerRecipes() {
        itemsToCraft.remove(Items.HEAVY_WEIGHTED_PRESSURE_PLATE);
        itemsToCraft.remove(Items.LIGHT_WEIGHTED_PRESSURE_PLATE);
        itemsToCraft.remove(Items.STONE_BUTTON);
        itemsToCraft.remove(Items.FLINT_AND_STEEL);
    }

    public static void lightUpTNT(PlayerEntity player) {
        GlobalExecutor globalExecutor = BotEngine.getInstance().getModule(GlobalExecutor.class);
        PathExecutor pathExecutor = BotEngine.getInstance().getModule(PathExecutor.class);
        if (player == null || !player.isAlive() || MinecraftClient.getInstance().world == null) {
            if (pathExecutor != null) pathExecutor.stop();
            return;
        }

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
                    globalExecutor.resetWorld("TNT 激活物合成失败");
                    return;
                }

                switch (tntCraftPhase) {
                    case EVALUATE:
                        if (InventoryHelper.findItemSlot(player, Items.FLINT_AND_STEEL) >= 0) {
                            removeTntTriggerRecipes();
                            tntState = TNTState.PLACE_TNT_AND_TRIGGER;
                            break;
                        }
                        if (canCraftTntLighter(player)) {
                            tempCraftTarget = Items.FLINT_AND_STEEL;
                            tntCraftPhase = TNTCraftPhase.CRAFT_TRIGGER;
                            break;
                        }
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
                            removeTntTriggerRecipes();
                            tntState = TNTState.PLACE_TNT_AND_TRIGGER;
                            tntCraftPhase = TNTCraftPhase.EVALUATE;
                        } else {
                            tntCrafter.startCrafting(tempCraftTarget, 1); // 下达指令
                        }
                        break;
                }
                break;
            case PLACE_TNT_AND_TRIGGER:
                tntLootPolicy.remember(player.world, btPos, 32);
                tntLootPolicy.remember(player.world, TNTSetupPos, 16);
                if (player.isOnGround()) {
                    player.jump();
                    break;
                }

                if (player.getY() - TNTSetupPos.getY() >= 0.8) {
                    player.pitch = 90f;

                    int tntSlot = InventoryHelper.findItemSlot(player, Items.TNT);
                    if (tntSlot == -1) {
                        BotEngine.getInstance().getModule(GlobalExecutor.class).resetWorld("背包找不到 TNT");
                        System.out.println("Fredbot: reset because cannot found TNT");
                        return;
                    }
                    InventoryHelper.moveItemToHotbar(MinecraftClient.getInstance(), player, tntSlot, 0);

                    BlockHitResult tntHit = new BlockHitResult(Vec3d.ofCenter(TNTSetupPos), Direction.UP, TNTSetupPos.down(), false);
                    player.inventory.selectedSlot = 0;
                    MinecraftClient.getInstance().interactionManager.interactBlock((ClientPlayerEntity) player, MinecraftClient.getInstance().world, net.minecraft.util.Hand.MAIN_HAND, tntHit);

                    int lighterSlot = InventoryHelper.findItemSlot(player, Items.FLINT_AND_STEEL);
                    if (lighterSlot >= 0) {
                        InventoryHelper.moveItemToHotbar(MinecraftClient.getInstance(), player, lighterSlot, 1);
                        player.inventory.selectedSlot = 1;
                        MinecraftClient.getInstance().interactionManager.interactBlock((ClientPlayerEntity) player, MinecraftClient.getInstance().world,
                                net.minecraft.util.Hand.MAIN_HAND, new BlockHitResult(Vec3d.ofCenter(TNTSetupPos), Direction.UP, TNTSetupPos, false));
                        tntState = TNTState.TAKING_COVER_MOVE;
                        tntWaitTicks = 0;
                        break;
                    }
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
                        BotEngine.getInstance().getModule(GlobalExecutor.class).resetWorld("背包找不到 TNT 激活物");
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
                        tntState = TNTState.TAKING_COVER_MOVE;
                        tntWaitTicks = 0;
                    } else {

                        if (player.getY() > TNTSetupPos.getY() + 1.1) {
                            return;
                        }
                        tntState = TNTState.TAKING_COVER_MOVE;
                        tntWaitTicks = 0;
                    }
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
                    List<SimplePathfinder.Node> takeCoverBehave = calcTakeCoverBehave(coverPos);
                    pathExecutor.executeCustomPath(takeCoverBehave);
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
                if (tntLootTarget != null && (!tntLootTarget.isAlive() || tntLootTarget.getStack().isEmpty())) {
                    tntLootTarget = null;
                    tntLootFirstTier = false;
                }
                if (tntLootTarget != null && tntLootFirstTier && pathExecutor.getSearchFailureSerial() != tntLootFailureSerial) {
                    boolean wood = tntLootTarget.getStack().getItem().isIn(ItemTags.LOGS);
                    pathExecutor.stopAndForgetGoal();
                    if (wood) {
                        globalExecutor.resetWorld("TNT 爆炸后树叶或木头上的木头首级寻路失败");
                        return;
                    }
                    tntLootPolicy.discard(player.world, tntLootTarget);
                    System.out.println("FredoBot: 放弃树叶或木头上的非木头掉落物：首级寻路失败，pos=" + tntLootTarget.getBlockPos().toShortString());
                    tntLootTarget = null;
                    tntLootFirstTier = false;
                }
                List<ItemEntity> drops = MinecraftClient.getInstance().world.getEntities(ItemEntity.class, new Box(TNTSetupPos).expand(12.0),
                        e -> tntLootPolicy.eligible(player.world, e));

                if (!drops.isEmpty()) {
                    if (!pathExecutor.isBusy()) {
                        List<ItemEntity> settledDrops = new ArrayList<>();
                        for (ItemEntity drop : drops)
                            if (DropPickupReadiness.ready(player.world, drop)) settledDrops.add(drop);
                        // Airborne useful loot is still pending; wait with the world running.
                        if (settledDrops.isEmpty()) break;
                        ItemEntity nearestDrop = tntPickupOrder.next(player, settledDrops);
                        if (nearestDrop == null) {
                            tntState = TNTState.DONE;
                            break;
                        }
                        BlockPos safePos = new BlockPos(nearestDrop.getX(), nearestDrop.getY() + 0.5, nearestDrop.getZ());
                        tntLootTarget = nearestDrop;
                        tntLootFirstTier = tntLootInLeaves(player.world, nearestDrop);
                        tntLootFailureSerial = pathExecutor.getSearchFailureSerial();
                        pathExecutor.setGoal(safePos, tntLootFirstTier ? "tnt foliage loot" : "drop pos");
                    }
                } else {
                    tntState = TNTState.DONE;
                }
                break;
        }
    }

    private static boolean tntLootInLeaves(World world, ItemEntity drop) {
        Box body = drop.getBoundingBox();
        for (int x = (int) Math.floor(body.minX); x <= (int) Math.floor(body.maxX - 1e-6); x++)
            for (int y = (int) Math.floor(body.minY - .05); y <= (int) Math.floor(body.maxY - 1e-6); y++)
                for (int z = (int) Math.floor(body.minZ); z <= (int) Math.floor(body.maxZ - 1e-6); z++)
                    if (world.getBlockState(new BlockPos(x, y, z)).isIn(BlockTags.LEAVES)
                            || world.getBlockState(new BlockPos(x, y, z)).isIn(BlockTags.LOGS)) return true;
        return false;
    }

    private static List<SimplePathfinder.Node> calcTakeCoverBehave(BlockPos coverPos) {
        return TntActions.coverPath(coverPos);
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
                if (waitTicks > 60) globalExecutor.resetWorld("宝藏区块加载超时");
                System.out.println("Fredobot: reset because bt chunk cannot loaded");
                return;
            }

            setBtPos();
            if (btPos == null) {
                globalExecutor.resetWorld("目标宝藏箱子不存在");
                System.out.println("Fredobot: reset because btPos is empty");
                return;
            }
            if (btStandingPos == null) {
                btStandingPos = findAProperPosThen();
            }
        }

        switch (globalExecutor.currentState) {
            case IDLE:
                pathExecutor.setGoal(btStandingPos, "bt pos");
                globalExecutor.currentState = GlobalExecutor.GlobalState.GOING_TO_BT_STANDING_PLACE;
                break;

            case GOING_TO_BT_STANDING_PLACE:
                if (PlayerHelper.isNear(player, btStandingPos, 1) && !pathExecutor.isBusy()) {
                    globalExecutor.currentState = GlobalExecutor.GlobalState.DIGGING_BT;
                    break;
                }
                if (!pathExecutor.isBusy()) pathExecutor.setGoal(btStandingPos, "bt pos");
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
                    TreasureLootValidation.compare(minecraftClient,
                            (GenericContainerScreenHandler) player.currentScreenHandler, btPos);
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
                        globalExecutor.resetWorld("宝藏资源不达标（实际开箱）");
                        System.out.println("Fredodebut: reset because bt not enough");
                        return;
                    }
                }
                TntLootPolicy.rememberExcavation(minecraftClient.world, btPos);
                globalExecutor.currentState = GlobalExecutor.GlobalState.LOOKING_FOR_TREES;
                break;

            case LOOKING_FOR_TREES:
                if (BotEngine.getInstance().getModule(MiscController.class).isBusy()) break;
                if (!MiningHelper.blockToMine.isEmpty()) {
                    if (pathExecutor.isBusy()) break;
                    MiningHelper.dispatchNextMineAndCollectTask();
                    break;
                }
                if (hasTNT && !tntQueueGenerated) {
                    TNTSetupStaffs(player);
                    break;
                }
                if (!treeQueueGenerated) {
                    if (pathExecutor.isBusy()) break;
                    if (hasTNT) {
                        if (itemsToCraft.contains(Items.STONE_BUTTON) && InventoryHelper.findItemSlot(player, Items.FLINT_AND_STEEL) < 0
                                && !canCraftTntLighter(player)) {
                            MiningHelper.mineAndCollect(player, InventoryHelper.anyLogs, 1, 100);
                        }
                    } else {
                        MiningHelper.mineAndCollect(player, InventoryHelper.anyLogs, 2, 100);
                    }
                    treeQueueGenerated = true;
                    break;
                }

                if (hasTNT) {
                    if (tntState == TNTState.INIT) {
                        double dX = player.getX() - (TNTSetupPos.getX() + 0.5);
                        double dZ = player.getZ() - (TNTSetupPos.getZ() + 0.5);
                        double dY = Math.abs(player.getY() - TNTSetupPos.getY());
                        boolean isCentered = (dX * dX + dZ * dZ <= 0.15) && (dY <= 0.5);

                        if (!isCentered) {
                            if (!pathExecutor.isBusy()) pathExecutor.setGoal(TNTSetupPos, "tnt setup pos");
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
                    globalExecutor.resetWorld("主流程合成失败");
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
                        if (pathExecutor.isBusy()) return;
                        if (itemsToCraft.isEmpty() && InventoryHelper.findItemSlot(player, Items.CRAFTING_TABLE) == -1) {
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
                        workbenchAnchor = craftingTablePos;
                        workbenchRequest = null;
                        rejectedWorkbenchStances.clear();
                        if (craftingTablePos == null) {
                            globalExecutor.resetWorld("无法放置工作台");
                            return;
                        }

                        player.inventory.selectedSlot = 4;

                        Vec3d tableTopCenter = new Vec3d(craftingTablePos.getX() + 0.5, craftingTablePos.getY(), craftingTablePos.getZ() + 0.5);

                        double eyeY = player.getY() + player.getStandingEyeHeight();
                        double dx = tableTopCenter.x - player.getX();
                        double dy = tableTopCenter.y - eyeY;
                        double dz = tableTopCenter.z - player.getZ();
                        player.yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
                        player.pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));

                        // 放置工作台
                        BlockHitResult placeHit = new BlockHitResult(tableTopCenter, Direction.UP, craftingTablePos.down(), false);
                        if (minecraftClient.interactionManager.interactBlock(player, minecraftClient.world, net.minecraft.util.Hand.MAIN_HAND, placeHit) != ActionResult.SUCCESS) {
                            System.out.println("Fredodebug: fail to place the crafting table, trying to move");
                            List<SimplePathfinder.Node> customeNodeList = calcCraftingTableUnblockMove(player);
                            pathExecutor.executeCustomPath(customeNodeList);
                            return;
                        }

                        // ==========================================
                        // 【核心修复】：智能识别被挡住的顶面
                        // 如果工作台上面有实体方块，绝不去点 UP 面，找一个露出来的侧面点！
                        // ==========================================
                        Direction openFace = Direction.UP;
                        if (!minecraftClient.world.getBlockState(craftingTablePos.up()).isAir()) {
                            for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                                if (minecraftClient.world.getBlockState(craftingTablePos.offset(d)).isAir()) {
                                    openFace = d;
                                    break;
                                }
                            }
                        }

                        Vec3d clickPos = new Vec3d(
                                craftingTablePos.getX() + 0.5 + openFace.getOffsetX() * 0.5,
                                craftingTablePos.getY() + 0.5 + openFace.getOffsetY() * 0.5,
                                craftingTablePos.getZ() + 0.5 + openFace.getOffsetZ() * 0.5
                        );

                        BlockHitResult openHit = new BlockHitResult(clickPos, openFace, craftingTablePos, false);
                        minecraftClient.interactionManager.interactBlock(player, minecraftClient.world, net.minecraft.util.Hand.MAIN_HAND, openHit);

                        craftingWaitTicks = 10;
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
                            globalExecutor.resetWorld("返回合成时工作台坐标丢失");
                            return;
                        }
                        if (minecraftClient.world.getBlockState(craftingTablePos).getBlock() != Blocks.CRAFTING_TABLE) {
                            System.out.println("Fredodebug: craftingtablePos: " + craftingTablePos.toShortString());
                            System.out.println("FredoBot [严重异常]: 原坐标的工作台不翼而飞！退回重新放置阶段...");
                            craftingTablePos = null; // 清空无效坐标
                            btCraftPhase = BtCraftPhase.MAKE_TABLE; // 重新造桌子（包里刚好有40个木板，完美衔接）
                            return;
                        }

                        if (MiningHelper.blockToMine.contains(craftingTablePos)) {
                            System.out.println("FredoBot [拦截]: 寻路试图将工作台作为障碍物挖掉，已被拦截！");
                            MiningHelper.blockToMine.remove(craftingTablePos);
                        }

                        BlockHitResult returnHit = WorkbenchApproach.interaction(minecraftClient, craftingTablePos,
                                new Vec3d(player.getX(), player.getEyeY(), player.getZ()));
                        if (returnHit == null || !player.isOnGround()) {
                            if (!MiningHelper.blockToMine.isEmpty()) {
                                MiningHelper.dispatchNextMineAndCollectTask();
                                break;
                            }
                            if (workbenchRequest != null && !workbenchRequest.finished()) return;
                            if (workbenchRequest != null && workbenchRequest.cancelled()) {
                                workbenchRequest = null;
                                return;
                            }
                            if (workbenchRequest != null) {
                                BlockPos stand = workbenchRequest.position();
                                if (stand == null) {
                                    workbenchRequest = null;
                                    globalExecutor.resetWorld("工作台周围没有可站立且能交互的位置");
                                    return;
                                }
                                // Do not revisit an attempted stance if arrival still cannot interact.
                                rejectedWorkbenchStances.add(stand);
                                workbenchRequest = null;
                                if (WorkbenchApproach.standable(minecraftClient, stand))
                                    pathExecutor.setGoal(stand, Collections.singleton(craftingTablePos), "crafting table interaction stance");
                                return;
                            }
                            MovementController movement = BotEngine.getInstance().getModule(MovementController.class);
                            if (movement != null) movement.resetKeys();
                            BlockPos requestedTable = craftingTablePos;
                            workbenchRequest = WorkbenchApproach.begin(minecraftClient, requestedTable, rejectedWorkbenchStances,
                                    () -> btCraftPhase == BtCraftPhase.RETURN_TO_TABLE && requestedTable.equals(craftingTablePos));
                            return;
                        }
                        WorkbenchApproach.cancel();
                        workbenchRequest = null;
                        rejectedWorkbenchStances.clear();
                        if (!MiningHelper.blockToMine.isEmpty()) MiningHelper.blockToMine.clear();
                        pathExecutor.stopAndForgetGoal();
                        MovementController movement = BotEngine.getInstance().getModule(MovementController.class);
                        if (movement != null) movement.resetKeys();
                        WorkbenchApproach.face(player, returnHit.getPos());
                        System.out.println("FredoBot: 已到达工作台交互站位，准备打开...");
                        minecraftClient.interactionManager.interactBlock(player, minecraftClient.world, Hand.MAIN_HAND, returnHit);

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
                        WorkbenchApproach.cancel();
                        workbenchRequest = null;
                        rejectedWorkbenchStances.clear();
                        pathExecutor.stopAndForgetGoal();
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
                        if (!hasShears || currentLeaves >= 64 || MiningHelper.harvestExhaustedIn(minecraftClient.world)) {
                            if (MiningHelper.harvestExhaustedIn(minecraftClient.world) && currentLeaves < 64) {
                                System.out.println("FredoBot: no further safe harvest target or usable shears; continue without repeating the same search");
                            } else if (!hasShears) {
                                System.out.println("FredoBot : no shears go next");
                            } else {
                                System.out.println("FredoBot : 64 leaves done go next");
                            }
                            btCraftPhase = BtCraftPhase.INVENTORY_MANAGEMENT;
                        } else {
                            System.out.println("FredoBot: current leaves count:  " + currentLeaves);
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
                        InventoryHelper.inventoryManagement();
                        InventoryHelper.moveItemToHotbar(minecraftClient, player, InventoryHelper.doorType, 8);
                        globalExecutor.currentState = GlobalExecutor.GlobalState.PRE_NETHER;
                        System.out.println("Fredobot: Inventory management done");
                        break;
                }
                break;
        }
    }

    private static List<SimplePathfinder.Node> calcCraftingTableUnblockMove(ClientPlayerEntity player) {
        List<SimplePathfinder.Node> result = new ArrayList<>();
        BlockPos pos = new BlockPos(Math.floor(player.getX()), player.getY(), Math.floor(player.getZ()));
        result.add(new SimplePathfinder.Node(pos, null, null, 0, 0, SimplePathfinder.MovementState.WALKING, 0));
        return result;
    }
}
