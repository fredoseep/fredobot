package com.fredoseep.utils.player;

import com.fredoseep.utils.bt.BtStuff;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.tag.BlockTags;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public class InventoryHelper {
    public static Set<Block> anyLogs = new HashSet<>(BlockTags.LOGS.values());

    public static boolean hasBoat(PlayerEntity player) {
        if (player == null || player.inventory == null) return false;

        for (int i = 0; i < player.inventory.size(); i++) {
            ItemStack itemStack = player.inventory.getStack(i);
            if (itemStack.getItem() instanceof BoatItem) {
                return true;
            }
        }
        return player.getOffHandStack().getItem() instanceof BoatItem;
    }

    public static boolean putBoatToHotBar(PlayerEntity player) {
        if (!hasBoat(player)) {
            return false;
        }
        int boatSlot = InventoryHelper.findItemSlot(player, BoatItem.class);
        return moveItemToHotbar(MinecraftClient.getInstance(), player, boatSlot, 7);

    }

    /**
     * @param player
     * @return ui index of the boat
     */
    public static int findItemSlot(PlayerEntity player, Item targetItem) {
        if (player == null || player.inventory == null || targetItem == null) return -1;

        for (int i = 0; i < 9; i++) if (player.inventory.main.get(i).getItem() == targetItem) return i + 36;
        for (int i = 9; i < 36; i++) if (player.inventory.main.get(i).getItem() == targetItem) return i;
        if (player.inventory.offHand.get(0).getItem() == targetItem) return 45;

        return -1;
    }

    /**
     * 重载 2：模糊查找大类物品 (找任何船专用)
     */
    public static int findItemSlot(PlayerEntity player, Class<? extends Item> itemClass) {
        if (player == null || player.inventory == null || itemClass == null) return -1;

        for (int i = 0; i < 9; i++) if (itemClass.isInstance(player.inventory.main.get(i).getItem())) return i + 36;
        for (int i = 9; i < 36; i++) if (itemClass.isInstance(player.inventory.main.get(i).getItem())) return i;
        if (itemClass.isInstance(player.inventory.offHand.get(0).getItem())) return 45;

        return -1;
    }

    public static boolean moveItemToHotbar(MinecraftClient client, PlayerEntity player, int itemInventorySlot, int targetHotbarSlot) {
        if (client.interactionManager == null || player == null) return false;
        if (targetHotbarSlot < 0 || targetHotbarSlot > 8) {
            System.out.println("out of targetHotbarSlot range");
            return false;
        }
        if (itemInventorySlot == targetHotbarSlot) return true;
        if (itemInventorySlot >= 9 && itemInventorySlot <= 45) {
            int syncId = player.currentScreenHandler.syncId;
            int containerSlot = itemInventorySlot;
            client.interactionManager.clickSlot(
                    syncId,
                    containerSlot,
                    targetHotbarSlot,
                    SlotActionType.SWAP,
                    player
            );
            return true;
        }
        System.out.println("itemInventorySlot out of range");
        return false;
    }

    public static boolean putShovelToHotbar(PlayerEntity player){
        int shovelSlot = findItemSlot(player, ShovelItem.class);
        return moveItemToHotbar(MinecraftClient.getInstance(),player,shovelSlot,2);
    }
    public static boolean putAxeToHotbar(PlayerEntity player) {
        int axeSlot = findItemSlot(player,AxeItem.class);
        return moveItemToHotbar(MinecraftClient.getInstance(),player,axeSlot,0);
    }
    public static ItemEntity findNearestDroppedItem(PlayerEntity player, double searchRadius, Predicate<ItemStack> condition) {
        World world = player.world;
        if (world == null) return null;

        // 1. 划定扫描范围
        Box searchArea = player.getBoundingBox().expand(searchRadius, searchRadius, searchRadius);

        // 2. 扫描区域内的所有 ItemEntity，并立刻用调用者传来的 condition 进行过滤
        List<ItemEntity> droppedItems = world.getEntities(
                ItemEntity.class,
                searchArea,
                itemEntity -> condition.test(itemEntity.getStack())
        );

        if (droppedItems.isEmpty()) return null;

        // 3. 寻找距离玩家最近的那一个
        ItemEntity closestItem = null;
        double minDistanceSq = Double.MAX_VALUE;

        for (ItemEntity item : droppedItems) {
            double distSq = player.squaredDistanceTo(item);
            if (distSq < minDistanceSq) {
                minDistanceSq = distSq;
                closestItem = item;
            }
        }

        return closestItem;
    }

    /**
     * 重载 1：精准寻找特定的某个物品 (找泥土、钻石专用)
     */
    public static ItemEntity findNearestDroppedItem(PlayerEntity player, double searchRadius, Item targetItem) {
        // 直接调用核心方法，传入一个比对 targetItem 的 Lambda 表达式
        return findNearestDroppedItem(player, searchRadius, stack -> stack.getItem() == targetItem);
    }

    /**
     * 重载 2：模糊寻找某一大类物品 (找任何船、任何剑专用)
     */
    public static ItemEntity findNearestDroppedItem(PlayerEntity player, double searchRadius, Class<? extends Item> itemClass) {
        // 直接调用核心方法，传入一个 isInstance 的 Lambda 表达式
        return findNearestDroppedItem(player, searchRadius, stack -> itemClass.isInstance(stack.getItem()));
    }

    // 给垫脚方块增加 isGravity (是否受重力影响) 属性
    public enum PlaceableBlock {
        DIRT(Items.DIRT, false),
        COBBLESTONE(Items.COBBLESTONE, false),
        NETHERRACK(Items.NETHERRACK, false),
        OAK_PLANKS(Items.OAK_PLANKS, false),
        STONE(Items.STONE, false),
        SAND(Items.SAND, true),     // 重力方块
        GRAVEL(Items.GRAVEL, true); // 重力方块

        private final Item item;
        private final boolean isGravity;

        PlaceableBlock(Item item, boolean isGravity) {
            this.item = item;
            this.isGravity = isGravity;
        }

        public Item getItem() {
            return item;
        }

        public boolean isGravity() {
            return isGravity;
        }

        public static PlaceableBlock getPlaceable(Item itemToCheck) {
            for (PlaceableBlock block : values()) {
                if (block.getItem() == itemToCheck) return block;
            }
            return null;
        }
    }

    // 内部类：用于同时储存两种方块数量
    public static class BlockCounts {
        public int bridgingBlocks = 0;  // 只能用于悬空搭桥的方块 (非重力)
        public int pillaringBlocks = 0; // 可以用于原地垫高的方块 (包含重力方块)
    }

    /**
     * 遍历玩家物品栏，分类统计可用方块
     */
    public static BlockCounts countAvailableBuildingBlocks(PlayerEntity player) {
        BlockCounts counts = new BlockCounts();
        if (player == null) return counts;

        // 统计主手及快捷栏
        for (int i = 0; i < player.inventory.main.size(); i++) {
            ItemStack stack = player.inventory.main.get(i);
            addStackToCounts(stack, counts);
        }
        // 统计副手
        ItemStack offHand = player.inventory.offHand.get(0);
        addStackToCounts(offHand, counts);

        return counts;
    }

    private static void addStackToCounts(ItemStack stack, BlockCounts counts) {
        if (!stack.isEmpty()) {
            PlaceableBlock pb = PlaceableBlock.getPlaceable(stack.getItem());
            if (pb != null) {
                counts.pillaringBlocks += stack.getCount();
                // 只有非重力方块才计入搭桥储备
                if (!pb.isGravity()) {
                    counts.bridgingBlocks += stack.getCount();
                }
            }
        }
    }

    public enum BtUsefulStaff {
        IRON_INGOT(Items.IRON_INGOT),
        GOLD_INGOT(Items.GOLD_INGOT),
        DIAMOND(Items.DIAMOND),
        IRON_SWORD(Items.IRON_SWORD),
        TNT(Items.TNT),
        COOKED_SALMON(Items.COOKED_SALMON),
        COOKED_COD(Items.COOKED_COD);

        private final Item item;

        BtUsefulStaff(Item item) {
            this.item = item;
        }

        public Item getItem() {
            return item;
        }
        public static boolean isUseful(Item itemToCheck) {
            for (BtUsefulStaff staff : values()) {
                if (staff.getItem() == itemToCheck) {
                    return true;
                }
            }
            return false;
        }
        public static boolean isUseful(ItemStack stack) {
            if (stack == null || stack.isEmpty()) return false;
            return isUseful(stack.getItem());
        }
    }
    public static boolean executeTNTCrafting(PlayerEntity player) {
        // 注意：你目前的列表里装的是 HEAVY_WEIGHTED_PRESSURE_PLATE
        boolean needIronPlate = com.fredoseep.utils.bt.BtStuff.itemsToCraft.contains(net.minecraft.item.Items.HEAVY_WEIGHTED_PRESSURE_PLATE);
        boolean needGoldPlate = com.fredoseep.utils.bt.BtStuff.itemsToCraft.contains(net.minecraft.item.Items.LIGHT_WEIGHTED_PRESSURE_PLATE);

        if (needIronPlate) {
            if (craftViaRecipe(player, net.minecraft.item.Items.HEAVY_WEIGHTED_PRESSURE_PLATE)) {
                com.fredoseep.utils.bt.BtStuff.itemsToCraft.remove(net.minecraft.item.Items.HEAVY_WEIGHTED_PRESSURE_PLATE);
                return true;
            }
        } else if (needGoldPlate) {
            if (craftViaRecipe(player, net.minecraft.item.Items.LIGHT_WEIGHTED_PRESSURE_PLATE)) {
                com.fredoseep.utils.bt.BtStuff.itemsToCraft.remove(net.minecraft.item.Items.LIGHT_WEIGHTED_PRESSURE_PLATE);
                return true;
            }
        }

        // ====== 动态木板与按钮逻辑 ======
        net.minecraft.item.Item targetButton = getTargetButtonType(player);
        net.minecraft.item.Item targetPlanks = getTargetPlankType(targetButton);

        // 如果包里没有这种木板，发包秒搓木板
        if (findItemSlot(player, targetPlanks) == -1) {
            craftViaRecipe(player, targetPlanks);
        }

        // 发包秒搓对应的按钮
        if (craftViaRecipe(player, targetButton)) {
            com.fredoseep.utils.bt.BtStuff.itemsToCraft.remove(net.minecraft.item.Items.STONE_BUTTON); // 移除占位符
            return true;
        }

        return false;
    }
    public static boolean craftViaRecipe(PlayerEntity player, net.minecraft.item.Item targetOutput) {
        MinecraftClient client = MinecraftClient.getInstance();
        int syncId = player.playerScreenHandler.syncId; // 自带 2x2 背包的 SyncId 永远是 0

        // 遍历客户端缓存的配方管理器，找到输出目标物品的配方
        net.minecraft.recipe.Recipe<?> targetRecipe = null;
        for (net.minecraft.recipe.Recipe<?> recipe : client.world.getRecipeManager().values()) {
            if (recipe.getOutput().getItem() == targetOutput) {
                targetRecipe = recipe;
                break;
            }
        }

        if (targetRecipe != null) {
            // 【发包 1】：调用 Recipe Book 协议，请求服务器自动将材料摆入网格
            client.interactionManager.clickRecipe(syncId, targetRecipe, false);
            // 【发包 2】：Shift-Click (QUICK_MOVE) 第 0 格（输出格），瞬间拿走合成产物
            client.interactionManager.clickSlot(syncId, 0, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, player);
            return true;
        }
        return false;
    }
    // ==========================================
    // 1. 发送 Search Crafting 请求 (通知服务器摆配方)
    // ==========================================
    public static boolean requestSearchCrafting(PlayerEntity player, net.minecraft.item.Item targetOutput) {
        MinecraftClient client = MinecraftClient.getInstance();
        int syncId = player.playerScreenHandler.syncId;

        // 遍历配方本寻找目标
        net.minecraft.recipe.Recipe<?> targetRecipe = null;
        for (net.minecraft.recipe.Recipe<?> recipe : client.world.getRecipeManager().values()) {
            if (recipe.getOutput().getItem() == targetOutput) {
                targetRecipe = recipe;
                break;
            }
        }

        if (targetRecipe != null) {
            // 纯发包：告诉服务器“我要合成这个，帮我把包里的材料填进 2x2 网格”
            client.interactionManager.clickRecipe(syncId, targetRecipe, false);
            return true;
        }
        System.out.println("FredoBot [警告]: 客户端未找到该物品的配方或未解锁 -> " + targetOutput.toString());
        return false;
    }

    // ==========================================
    // 2. 拿走成品 (发包拿取)
    // ==========================================
    public static void collectCraftingResult(PlayerEntity player) {
        MinecraftClient client = MinecraftClient.getInstance();
        int syncId = player.playerScreenHandler.syncId;
        // Shift-Click 第 0 格（输出格），瞬间拿走合成产物
        client.interactionManager.clickSlot(syncId, 0, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, player);
    }

    // ==========================================
    // 3. 动态木材嗅探器
    // ==========================================
    public static net.minecraft.item.Item getTargetButtonType(PlayerEntity player) {
        for (int i = 0; i < 36; i++) {
            net.minecraft.item.Item item = player.inventory.getStack(i).getItem();
            if (item == net.minecraft.item.Items.SPRUCE_PLANKS || item == net.minecraft.item.Items.SPRUCE_LOG) return net.minecraft.item.Items.SPRUCE_BUTTON;
            if (item == net.minecraft.item.Items.BIRCH_PLANKS || item == net.minecraft.item.Items.BIRCH_LOG) return net.minecraft.item.Items.BIRCH_BUTTON;
            if (item == net.minecraft.item.Items.JUNGLE_PLANKS || item == net.minecraft.item.Items.JUNGLE_LOG) return net.minecraft.item.Items.JUNGLE_BUTTON;
            if (item == net.minecraft.item.Items.ACACIA_PLANKS || item == net.minecraft.item.Items.ACACIA_LOG) return net.minecraft.item.Items.ACACIA_BUTTON;
            if (item == net.minecraft.item.Items.DARK_OAK_PLANKS || item == net.minecraft.item.Items.DARK_OAK_LOG) return net.minecraft.item.Items.DARK_OAK_BUTTON;
        }
        return net.minecraft.item.Items.OAK_BUTTON; // 默认兜底
    }

    public static net.minecraft.item.Item getTargetPlankType(Item buttonType) {
        if (buttonType == net.minecraft.item.Items.SPRUCE_BUTTON) return net.minecraft.item.Items.SPRUCE_PLANKS;
        if (buttonType == net.minecraft.item.Items.BIRCH_BUTTON) return net.minecraft.item.Items.BIRCH_PLANKS;
        if (buttonType == net.minecraft.item.Items.JUNGLE_BUTTON) return net.minecraft.item.Items.JUNGLE_PLANKS;
        if (buttonType == net.minecraft.item.Items.ACACIA_BUTTON) return net.minecraft.item.Items.ACACIA_PLANKS;
        if (buttonType == net.minecraft.item.Items.DARK_OAK_BUTTON) return net.minecraft.item.Items.DARK_OAK_PLANKS;
        return net.minecraft.item.Items.OAK_PLANKS;
    }

}