package com.fredoseep.utils.bt;

import com.fredoseep.behave.MiscController;
import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.GlobalExecutor;
import com.fredoseep.excutor.PathExecutor;
import com.fredoseep.utils.player.InventoryHelper;
import com.fredoseep.utils.player.PlayerHelper;
import com.fredoseep.utils.player.ToolsHelper;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.tag.BlockTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import org.lwjgl.system.CallbackI;

import java.util.ArrayList;
import java.util.List;

public class BtStuff {
    public static boolean hasTNT = false;
    private static int MIN_IRON_NEEDED = 7;
    private static int ironIngotCount = 0;
    private static int goldIngotCount = 0;
    private static int diamondCount = 0;


    public static final List<Item> itemsToCraft= new ArrayList<>();
    public enum TNTState {
        INIT, CLEAR_HEAD, CRAFTING, PLACE_TNT_AND_TRIGGER, TAKING_COVER_MOVE, TAKING_COVER_DIG, WAIT_EXPLOSION, COLLECT_LOOT, DONE
    }
    public static TNTState tntState = TNTState.INIT;
    private static int tntWaitTicks = 0;
    private static BlockPos coverPos = null;
    private static int craftingStep = 0;
    private static int craftingWaitTicks = 0;

    public static  int groundYNoLeavesOrTrees(BlockPos checkCol){
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
                    if (!world.getChunkManager().isChunkLoaded(worldX >> 4, worldZ >> 4)) {
                        continue;
                    }
                    BlockPos checkCol = new BlockPos(worldX, 0, worldZ);
                    int groundY = groundYNoLeavesOrTrees(checkCol); // 假设你这里已经写好了正确的下钻算法
                    if (groundY <= 40) continue;

                    BlockPos surfacePos = new BlockPos(worldX, groundY + 1, worldZ);
                    BlockState surfaceState = world.getBlockState(surfacePos);

                    boolean isSurfaceClear = surfaceState.isAir() ||
                            surfaceState.isIn(BlockTags.LEAVES) ||
                            surfaceState.getMaterial().isReplaceable() ||
                            surfaceState.getHardness(world, surfacePos) == 0.0f;

                    if (!isSurfaceClear || surfaceState.getMaterial().isLiquid()) {
                        continue;
                    }
                    int treeCount = 0;
                    treeSearchLoop:
                    for (int dx = -3; dx <= 3; dx++) {
                        for (int dz = -3; dz <= 3; dz++) {
                            if (dx * dx + dz * dz > 9) continue;
                            for (int dy = 1; dy <= 2; dy++) {
                                BlockPos treeCheckPos = new BlockPos(worldX + dx, groundY + dy, worldZ + dz);

                                if (world.getBlockState(treeCheckPos).isIn(BlockTags.LOGS)) {
                                    treeCount++;
                                    if (treeCount >= 3) {
                                        break treeSearchLoop; // 瞬间结束周围树木扫描！
                                    }
                                    break; // 这根柱子已经算一棵树了，没必要再往上看了，直接看下一个 (dx, dz)
                                }
                            }
                        }
                    }

                    int targetY = -1;
                    if (treeCount >= 3) {
                        targetY = groundY + 1; // 3棵树：贴地引爆
                    } else if (treeCount == 2) {
                        targetY = groundY + 2; // 2棵树：垫高 1 格
                    } else if (treeCount == 1) {
                        targetY = groundY + 3; // 1棵树：垫高 2 格
                    } else {
                        continue;
                    }

                    BlockPos candidate = new BlockPos(worldX, targetY, worldZ);
                    BlockState candidateState = world.getBlockState(candidate);

                    boolean canPlaceTNT = candidateState.isAir() ||
                            candidateState.isIn(BlockTags.LEAVES) ||
                            candidateState.getMaterial().isReplaceable() ||
                            candidateState.getHardness(world, candidate) == 0.0f;

                    if (!canPlaceTNT || candidateState.getMaterial().isLiquid()) {
                        continue;
                    }

                    if (treeCount >= 3) {
                        System.out.printf("FredoBot: 锁定极品引爆点 (3棵树) -> %s\n", candidate.toShortString());
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
            System.out.printf("FredoBot: 降级锁定引爆点 (2棵树) -> %s\n", bestTwoTreePos.toShortString());
            return bestTwoTreePos;
        }
        if (bestOneTreePos != null) {
            System.out.printf("FredoBot: 降级锁定引爆点 (1棵树) -> %s\n", bestOneTreePos.toShortString());
            return bestOneTreePos;
        }

        System.out.println("FredoBot: 50格内无符合条件的 TNT 点位。");
        return new BlockPos(-1, -1, -1);
    }


    public static boolean evaluateBt(PlayerEntity player) {
        for (int slot = 0; slot < 36 || slot == 40; slot++) {
            if (player.inventory.getStack(slot).getItem() == Items.IRON_INGOT) {
                ironIngotCount = player.inventory.getStack(slot).getCount();
            } else if (player.inventory.getStack(slot).getItem() == Items.GOLD_INGOT) {
                goldIngotCount = player.inventory.getStack(slot).getCount();
            } else if (player.inventory.getStack(slot).getItem() == Items.DIAMOND) {
                diamondCount = player.inventory.getStack(slot).getCount();
            }
            else if(player.inventory.getStack(slot).getItem()==Items.TNT){
                hasTNT = true;
            }
            if (ironIngotCount != 0 && goldIngotCount != 0 && diamondCount != 0) break;
            if (slot == 35) slot = 40;
        }
        if(diamondCount>=3){
            MIN_IRON_NEEDED = 4;
        }
        if(ironIngotCount<MIN_IRON_NEEDED)return false;
        int currentIronCount = ironIngotCount;
        int currentGoldCount = goldIngotCount;
        boolean pickaxeCrafted = false;
        boolean axeCrafted = false;
        boolean shovelCrafted = false;

        int currentDiamondCount = diamondCount;
        if(currentDiamondCount>=3){
            itemsToCraft.add(Items.DIAMOND_PICKAXE);
            pickaxeCrafted =true;
            currentDiamondCount-=3;
        }
        if(!pickaxeCrafted){
            itemsToCraft.add(Items.IRON_PICKAXE);
            pickaxeCrafted = true;
            currentIronCount-=3;
        }
        itemsToCraft.add(Items.BUCKET);
        currentIronCount-=3;
        if(currentDiamondCount==3){
            itemsToCraft.add(Items.DIAMOND_AXE);
            axeCrafted =true;
            currentDiamondCount-=3;
        }
        if(currentDiamondCount==2){
            itemsToCraft.add(Items.DIAMOND_SWORD);
            currentDiamondCount-=2;
        }
        if(currentDiamondCount==1){
            itemsToCraft.add(Items.DIAMOND_SHOVEL);
            shovelCrafted = true;
            currentDiamondCount--;
        }

        if(hasTNT){
            if(currentIronCount>=3){
                itemsToCraft.add(Items.HEAVY_WEIGHTED_PRESSURE_PLATE);
                currentIronCount-=2;
            }
            else if(currentGoldCount>=2){
                itemsToCraft.add(Items.LIGHT_WEIGHTED_PRESSURE_PLATE);
                currentGoldCount-=2;
            }
            else{
                itemsToCraft.add(Items.STONE_BUTTON);
            }
        }
        else{
            if(currentIronCount>=3){
                itemsToCraft.add(Items.SHEARS);
                currentIronCount-=2;
            }
        }
        if(!axeCrafted&&currentIronCount>=4){
            itemsToCraft.add(Items.IRON_AXE);
            axeCrafted = true;
            currentIronCount-=3;
        }
        if(!shovelCrafted&&currentIronCount>=2){
            itemsToCraft.add(Items.IRON_SHOVEL);
            shovelCrafted = true;
            currentIronCount--;
        }
        if(!axeCrafted&&currentGoldCount>=3){
            itemsToCraft.add(Items.GOLDEN_AXE);
            axeCrafted = true;
            currentGoldCount-=3;
        }
        if(!shovelCrafted&&currentGoldCount>=1){
            itemsToCraft.add(Items.GOLDEN_SHOVEL);
            shovelCrafted = true;
            currentGoldCount--;
        }
        if(!axeCrafted){
            itemsToCraft.add(Items.STONE_AXE);
            axeCrafted = true;
        }
        if(!shovelCrafted){
            itemsToCraft.add(Items.STONE_SHOVEL);
            shovelCrafted = true;
        }

        return true;
    }

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
    }
    public static void lightUpTNT(PlayerEntity player) {
        GlobalExecutor globalExecutor = BotEngine.getInstance().getModule(GlobalExecutor.class);
        PathExecutor pathExecutor = BotEngine.getInstance().getModule(PathExecutor.class);

        switch (tntState) {
            case INIT:
                // 1. 检查头顶是否有方块阻挡我们跳跃
                BlockPos headPos = globalExecutor.TNTSetupPos.up(2);
                if (!MinecraftClient.getInstance().world.getBlockState(headPos).isAir()) {
                    System.out.println("FredoBot: 发现头顶有障碍物，执行清障！");
                    BotEngine.getInstance().getModule(MiscController.class).startTask(MiscController.MiscType.MINE_BLOCK_ABOVE_HEAD, headPos);
                    tntState = TNTState.CLEAR_HEAD;
                } else {
                    tntState = TNTState.CRAFTING;
                }
                break;

            case CLEAR_HEAD:
                // 等待 MiscController 把头顶方块挖掉
                if (!BotEngine.getInstance().getModule(MiscController.class).isBusy()) {
                    tntState = TNTState.CRAFTING;
                }
                break;

            case CRAFTING:
                // ==========================================
                // Search Crafting 异步微操状态机
                // ==========================================
                Item tempCraftTarget;
                Item tempPlankTarget;
                // 如果正在等待服务器处理，直接跳过本 Tick
                if (craftingWaitTicks > 0) {
                    craftingWaitTicks--;
                    break;
                }

                if (craftingStep == 0) {
                    System.out.println("FredoBot: 启动配方书发包协议 (Search Crafting)...");
                    boolean needIronPlate = itemsToCraft.contains(Items.HEAVY_WEIGHTED_PRESSURE_PLATE);
                    boolean needGoldPlate = itemsToCraft.contains(Items.LIGHT_WEIGHTED_PRESSURE_PLATE);

                    if (needIronPlate) {
                        tempCraftTarget = Items.HEAVY_WEIGHTED_PRESSURE_PLATE;
                        craftingStep = 3; // 直接跳到最终合成
                    } else if (needGoldPlate) {
                        tempCraftTarget = Items.LIGHT_WEIGHTED_PRESSURE_PLATE;
                        craftingStep = 3; // 直接跳到最终合成
                    } else {
                        // 动态木材处理路线
                        tempCraftTarget = InventoryHelper.getTargetButtonType(player);
                        tempPlankTarget = InventoryHelper.getTargetPlankType(tempCraftTarget);

                        if (InventoryHelper.findItemSlot(player, tempPlankTarget) == -1) {
                            craftingStep = 1; // 包里没木板，必须先搓木板
                        } else {
                            craftingStep = 3; // 包里有木板，直接搓按钮
                        }
                    }
                }
                else if (craftingStep == 1) {
                    if(!InventoryHelper.requestSearchCrafting(player, InventoryHelper.getTargetPlankType(InventoryHelper.getTargetButtonType(player)))){
                        globalExecutor.resetWorld(); return;
                    }
                    craftingWaitTicks = 2; // 给服务器 2 tick 延迟摆材料
                    craftingStep = 2;
                }
                else if (craftingStep == 2) {
                    // 第 2 步：拿走木板
                    InventoryHelper.collectCraftingResult(player);
                    craftingStep = 3; // 继续去搓按钮
                }
                else if (craftingStep == 3) {
                    // 第 3 步：请求合成最终触发器 (压力板 或 按钮)
                    if(!InventoryHelper.requestSearchCrafting(player, InventoryHelper.getTargetButtonType(player))){
                        globalExecutor.resetWorld(); return;
                    }
                    craftingWaitTicks = 2; // 再次给服务器 2 tick
                    craftingStep = 4;
                }
                else if (craftingStep == 4) {
                    // 第 4 步：拿走最终触发器
                    InventoryHelper.collectCraftingResult(player);

                    // 清理任务列表
                    itemsToCraft.remove(Items.HEAVY_WEIGHTED_PRESSURE_PLATE);
                    itemsToCraft.remove(Items.LIGHT_WEIGHTED_PRESSURE_PLATE);
                    itemsToCraft.remove(Items.STONE_BUTTON);

                    System.out.println("FredoBot: Search Crafting 异步收割完毕！准备爆破！");
                    tntState = TNTState.PLACE_TNT_AND_TRIGGER;
                    craftingStep = 0; // 重置子状态机
                }
                break;

            case PLACE_TNT_AND_TRIGGER:
                // 3. 凌空脚下放置 TNT 与 触发器
                if (player.isOnGround()) {
                    player.jump(); // 起跳！
                    break;
                }

                // 等待跳到最高点（Y坐标提升超过 0.8 格），确保方块不会卡到自己的碰撞箱
                if (player.getY() - globalExecutor.TNTSetupPos.getY() >= 0.8) {
                    player.pitch = 90f; // 低头

                    // 放置 TNT
                    InventoryHelper.moveItemToHotbar(MinecraftClient.getInstance(),player,InventoryHelper.findItemSlot(player,Items.TNT),0);
                    BlockHitResult tntHit = new BlockHitResult(Vec3d.ofCenter(globalExecutor.TNTSetupPos), Direction.UP, globalExecutor.TNTSetupPos.down(), false);
                    MinecraftClient.getInstance().interactionManager.interactBlock((ClientPlayerEntity) player, MinecraftClient.getInstance().world, net.minecraft.util.Hand.MAIN_HAND, tntHit);

                    // 选择刚才合成好的触发器
                    Item triggerItem = Items.OAK_BUTTON; // 初始兜底
                    if (InventoryHelper.findItemSlot(player, Items.HEAVY_WEIGHTED_PRESSURE_PLATE) != -1) {
                        triggerItem = Items.HEAVY_WEIGHTED_PRESSURE_PLATE;
                    } else if (InventoryHelper.findItemSlot(player, Items.LIGHT_WEIGHTED_PRESSURE_PLATE) != -1) {
                        triggerItem = Items.LIGHT_WEIGHTED_PRESSURE_PLATE;
                    } else {
                        // 动态扫描背包里的任意按钮！
                        for (int i = 0; i < 36; i++) {
                            Item item = player.inventory.getStack(i).getItem();
                            // 利用底层 Tag 识别：只要是按钮，统统抓出来！
                            if (item.isIn(net.minecraft.tag.ItemTags.BUTTONS)) {
                                triggerItem = item;
                                break;
                            }
                        }
                    }

                    InventoryHelper.moveItemToHotbar(MinecraftClient.getInstance(), player, InventoryHelper.findItemSlot(player, triggerItem), 1);

                    // 在 TNT 正上方放置触发器 (无视实体碰撞箱)
                    BlockPos triggerPos = globalExecutor.TNTSetupPos.up();
                    BlockHitResult triggerHit = new BlockHitResult(Vec3d.ofCenter(triggerPos), Direction.UP, globalExecutor.TNTSetupPos, false);
                    MinecraftClient.getInstance().interactionManager.interactBlock((ClientPlayerEntity) player, MinecraftClient.getInstance().world, net.minecraft.util.Hand.MAIN_HAND, triggerHit);

                    // ==========================================
                    // 智能激活：利用 Tag 判断是否是按钮，如果是，秒点右键激活！
                    // ==========================================
                    if (triggerItem.isIn(net.minecraft.tag.ItemTags.BUTTONS)) {
                        MinecraftClient.getInstance().interactionManager.interactBlock((ClientPlayerEntity) player, MinecraftClient.getInstance().world, net.minecraft.util.Hand.MAIN_HAND, new BlockHitResult(Vec3d.ofCenter(triggerPos), Direction.UP, triggerPos, false));
                    }

                    System.out.println("FredoBot: Fire in the hole! 开始寻找掩体！");
                    tntState = TNTState.TAKING_COVER_MOVE;
                }
                break;

            case TAKING_COVER_MOVE:
                // 4. 随便往旁边挪一格
                if (coverPos == null) {
                    for (Direction dir : new Direction[]{Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH}) {
                        BlockPos check = globalExecutor.TNTSetupPos.offset(dir);
                        if ( MinecraftClient.getInstance().world.getBlockState(check).isAir() &&  MinecraftClient.getInstance().world.getBlockState(check.up()).isAir()) {
                            coverPos = check;
                            break;
                        }
                    }
                    if (coverPos == null) coverPos = globalExecutor.TNTSetupPos.east(); // 绝对兜底
                    pathExecutor.setGoal(coverPos);
                }

                if (!pathExecutor.isBusy() && PlayerHelper.isNear(player, coverPos, 1)) {
                    tntState = TNTState.TAKING_COVER_DIG;
                }
                break;

            case TAKING_COVER_DIG:
                // 5. 极速往下挖两格，实现半颗心无伤防爆！
                BlockPos dig1 = coverPos.down();
                BlockPos dig2 = coverPos.down(2);

                if (! MinecraftClient.getInstance().world.getBlockState(dig1).isAir()) {
                    ToolsHelper.equipBestTool(player, dig1, false);
                    player.pitch = 90f;
                    MinecraftClient.getInstance().interactionManager.updateBlockBreakingProgress(dig1, Direction.UP);
                    player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                } else if (! MinecraftClient.getInstance().world.getBlockState(dig2).isAir()) {
                    ToolsHelper.equipBestTool(player, dig2, false);
                    player.pitch = 90f;
                    MinecraftClient.getInstance().interactionManager.updateBlockBreakingProgress(dig2, Direction.UP);
                    player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                } else {
                    // 已成功缩进深坑
                    tntState = TNTState.WAIT_EXPLOSION;
                    tntWaitTicks = 0;
                }
                break;

            case WAIT_EXPLOSION:
                // 6. TNT 原版引信为 80 tick (4秒)，我们稍微等 90 tick 确保彻底炸完
                tntWaitTicks++;
                if (tntWaitTicks > 90) {
                    System.out.println("FredoBot: 爆炸完毕，出坑洗劫！");
                    tntState = TNTState.COLLECT_LOOT;
                    tntWaitTicks = 0;
                }
                break;

            case COLLECT_LOOT:
                // 7. 雷达扫除半径 12 格内所有的掉落物，全部喂给 PathExecutor
                java.util.List<net.minecraft.entity.ItemEntity> drops =  MinecraftClient.getInstance().world.getEntities(
                        net.minecraft.entity.ItemEntity.class,
                        new net.minecraft.util.math.Box(globalExecutor.TNTSetupPos).expand(12.0),
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
                        // 给坐标加上 0.5 防浮点下陷 Bug
                        BlockPos safePos = new BlockPos(nearestDrop.getX(), nearestDrop.getY() + 0.5, nearestDrop.getZ());
                        pathExecutor.setGoal(safePos);
                    }
                } else {
                    // 12 格内干干净净，一个不留！
                    tntState = TNTState.DONE;
                }
                break;
        }
    }
}
