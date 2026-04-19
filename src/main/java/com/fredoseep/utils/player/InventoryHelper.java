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

import java.util.ArrayList;
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

        // 1. 拦截目标快捷栏越界
        if (targetHotbarSlot < 0 || targetHotbarSlot > 8) {
            System.out.println("FredoBot: 目标快捷栏越界 (" + targetHotbarSlot + ")");
            return false;
        }

        // 2. 拦截物品不存在的情况，防止走到最后的 out of range
        if (itemInventorySlot == -1) {
            System.out.println("FredoBot: 无法移动物品 -> 背包中未找到该物品 (-1)");
            return false;
        }

        // 3. 【核心修复】拦截已经在对应位置的物品（原版 2x2 容器中，快捷栏的偏移量是 36）
        if (itemInventorySlot == targetHotbarSlot + 36) {
            return true; // 已经在那里了，直接放行
        }

        // 4. 合法的全局包裹位置 (9-45 包含主背包、快捷栏和副手)
        if (itemInventorySlot >= 9 && itemInventorySlot <= 45) {
            int syncId = player.currentScreenHandler.syncId;
            client.interactionManager.clickSlot(
                    syncId,
                    itemInventorySlot,
                    targetHotbarSlot, // 发给服务器的 SWAP 目标索引只接受 0~8
                    SlotActionType.SWAP,
                    player
            );
            return true;
        }

        System.out.println("FredoBot: 未知异常，槽位坐标错乱: " + itemInventorySlot);
        return false;
    }

    public static boolean putShovelToHotbar(PlayerEntity player) {
        int shovelSlot = findItemSlot(player, ShovelItem.class);
        return moveItemToHotbar(MinecraftClient.getInstance(), player, shovelSlot, 2);
    }

    public static boolean putAxeToHotbar(PlayerEntity player) {
        int axeSlot = findItemSlot(player, AxeItem.class);
        return moveItemToHotbar(MinecraftClient.getInstance(), player, axeSlot, 0);
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


    public enum PlaceableBlock {
        DIRT(Items.DIRT, false, 3),            // 泥土最便宜，首选
        COBBLESTONE(Items.COBBLESTONE, false, 4), // 圆石第二
        NETHERRACK(Items.NETHERRACK, false, 3),   // 地狱岩第三
        SAND(Items.SAND, true, 1),             // 沙子/沙砾受重力影响，酌情使用
        GRAVEL(Items.GRAVEL, true, 2),
        STONE(Items.STONE, false, 3),         // 烧好的石头比较贵
        OAK_PLANKS(Items.OAK_PLANKS, false, 20); // 木板最贵，极其不舍得用！

        private final Item item;
        private final boolean isGravity;
        private final int cost; // 【新增】价格属性

        PlaceableBlock(Item item, boolean isGravity, int cost) {
            this.item = item;
            this.isGravity = isGravity;
            this.cost = cost;
        }

        public Item getItem() {
            return item;
        }

        public boolean isGravity() {
            return isGravity;
        }

        // 【新增】获取价格的方法
        public int getCost() {
            return cost;
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


    public static boolean requestSearchCrafting(PlayerEntity player, net.minecraft.item.Item targetOutput, int times) {
        MinecraftClient client = MinecraftClient.getInstance();
        int syncId = player.currentScreenHandler.syncId;
        boolean foundAny = false;

        // 遍历配方本寻找目标
        for (net.minecraft.recipe.Recipe<?> recipe : client.world.getRecipeManager().values()) {
            if (recipe.getOutput().getItem() == targetOutput) {
                foundAny = true;

                // 【核心修复】：不要 break！向服务器发送所有可能产出该物品的配方！
                // 服务器会自动丢弃缺少材料的配方（比如竹子），只执行有材料的配方（比如木板）
                if (times == 0) {
                    client.interactionManager.clickRecipe(syncId, recipe, true);
                } else {
                    for (int i = 0; i < times; i++) {
                        client.interactionManager.clickRecipe(syncId, recipe, false);
                    }
                }
            }
        }

        if (!foundAny) {
            System.out.println("FredoBot [警告]: 客户端未找到该物品的任何配方 -> " + targetOutput.toString());
        }
        return foundAny;
    }

    public static void collectCraftingResult(PlayerEntity player) {
        MinecraftClient client = MinecraftClient.getInstance();
        int syncId = player.currentScreenHandler.syncId;
        client.interactionManager.clickSlot(syncId, 0, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, player);
    }

    public static Item getTargetButtonType(PlayerEntity player) {
        for (int i = 0; i < 36; i++) {
            net.minecraft.item.Item item = player.inventory.getStack(i).getItem();
            if (item == Items.SPRUCE_PLANKS || item == Items.SPRUCE_LOG) return Items.SPRUCE_BUTTON;
            if (item == Items.BIRCH_PLANKS || item == Items.BIRCH_LOG) return Items.BIRCH_BUTTON;
            if (item == Items.JUNGLE_PLANKS || item == Items.JUNGLE_LOG) return Items.JUNGLE_BUTTON;
            if (item == Items.ACACIA_PLANKS || item == Items.ACACIA_LOG) return Items.ACACIA_BUTTON;
            if (item == Items.DARK_OAK_PLANKS || item == Items.DARK_OAK_LOG) return Items.DARK_OAK_BUTTON;
        }
        return Items.OAK_BUTTON; // 默认兜底 (包含橡木)
    }

    public static List<Item> getTargetPlankType(PlayerEntity player) {
        List<Item> plankType = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            net.minecraft.item.Item item = player.inventory.getStack(i).getItem();
            if ((item == Items.SPRUCE_PLANKS || item == Items.SPRUCE_LOG || item == Items.SPRUCE_WOOD || item == Items.STRIPPED_SPRUCE_LOG || item == Items.STRIPPED_SPRUCE_WOOD) && !plankType.contains(Items.SPRUCE_PLANKS))
                plankType.add(Items.SPRUCE_PLANKS);
            if ((item == Items.BIRCH_PLANKS || item == Items.BIRCH_LOG || item == Items.BIRCH_WOOD || item == Items.STRIPPED_BIRCH_LOG || item == Items.STRIPPED_BIRCH_WOOD) && !plankType.contains(Items.BIRCH_PLANKS))
                plankType.add(Items.BIRCH_PLANKS);
            if ((item == Items.JUNGLE_PLANKS || item == Items.JUNGLE_LOG || item == Items.JUNGLE_WOOD || item == Items.STRIPPED_JUNGLE_LOG || item == Items.STRIPPED_JUNGLE_WOOD) && !plankType.contains(Items.JUNGLE_PLANKS))
                plankType.add(Items.JUNGLE_PLANKS);
            if ((item == Items.ACACIA_PLANKS || item == Items.ACACIA_LOG || item == Items.ACACIA_WOOD || item == Items.STRIPPED_ACACIA_LOG || item == Items.STRIPPED_ACACIA_WOOD) && !plankType.contains(Items.ACACIA_PLANKS))
                plankType.add(Items.ACACIA_PLANKS);
            if ((item == Items.DARK_OAK_PLANKS || item == Items.DARK_OAK_LOG || item == Items.DARK_OAK_WOOD || item == Items.STRIPPED_DARK_OAK_LOG || item == Items.STRIPPED_DARK_OAK_WOOD) && !plankType.contains(Items.DARK_OAK_PLANKS))
                plankType.add(Items.DARK_OAK_PLANKS);
            // 【关键补全】：加上最常见的橡木！
            if ((item == Items.OAK_PLANKS || item == Items.OAK_LOG || item == Items.OAK_WOOD || item == Items.STRIPPED_OAK_LOG || item == Items.STRIPPED_OAK_WOOD) && !plankType.contains(Items.OAK_PLANKS))
                plankType.add(Items.OAK_PLANKS);
        }
        return plankType;
    }
    public static List<Item> getPlankTypesToCraftFromLogs(PlayerEntity player) {
        List<Item> plankTypes = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            Item item = player.inventory.getStack(i).getItem();
            if ((item == Items.SPRUCE_LOG || item == Items.SPRUCE_WOOD || item == Items.STRIPPED_SPRUCE_LOG || item == Items.STRIPPED_SPRUCE_WOOD) && !plankTypes.contains(Items.SPRUCE_PLANKS))
                plankTypes.add(Items.SPRUCE_PLANKS);
            if ((item == Items.BIRCH_LOG || item == Items.BIRCH_WOOD || item == Items.STRIPPED_BIRCH_LOG || item == Items.STRIPPED_BIRCH_WOOD) && !plankTypes.contains(Items.BIRCH_PLANKS))
                plankTypes.add(Items.BIRCH_PLANKS);
            if ((item == Items.JUNGLE_LOG || item == Items.JUNGLE_WOOD || item == Items.STRIPPED_JUNGLE_LOG || item == Items.STRIPPED_JUNGLE_WOOD) && !plankTypes.contains(Items.JUNGLE_PLANKS))
                plankTypes.add(Items.JUNGLE_PLANKS);
            if ((item == Items.ACACIA_LOG || item == Items.ACACIA_WOOD || item == Items.STRIPPED_ACACIA_LOG || item == Items.STRIPPED_ACACIA_WOOD) && !plankTypes.contains(Items.ACACIA_PLANKS))
                plankTypes.add(Items.ACACIA_PLANKS);
            if ((item == Items.DARK_OAK_LOG || item == Items.DARK_OAK_WOOD || item == Items.STRIPPED_DARK_OAK_LOG || item == Items.STRIPPED_DARK_OAK_WOOD) && !plankTypes.contains(Items.DARK_OAK_PLANKS))
                plankTypes.add(Items.DARK_OAK_PLANKS);
            if ((item == Items.OAK_LOG || item == Items.OAK_WOOD || item == Items.STRIPPED_OAK_LOG || item == Items.STRIPPED_OAK_WOOD) && !plankTypes.contains(Items.OAK_PLANKS))
                plankTypes.add(Items.OAK_PLANKS);
        }
        return plankTypes;
    }
    public static Item getTargetBoatType(PlayerEntity player) {
        // 船需要 5 个同种木板
        Item abundantPlank = getAbundantPlank(player, 5);
        if (abundantPlank == Items.SPRUCE_PLANKS) return Items.SPRUCE_BOAT;
        if (abundantPlank == Items.BIRCH_PLANKS) return Items.BIRCH_BOAT;
        if (abundantPlank == Items.JUNGLE_PLANKS) return Items.JUNGLE_BOAT;
        if (abundantPlank == Items.ACACIA_PLANKS) return Items.ACACIA_BOAT;
        if (abundantPlank == Items.DARK_OAK_PLANKS) return Items.DARK_OAK_BOAT;
        return Items.OAK_BOAT;
    }

    public static Item getTargetDoorType(PlayerEntity player) {
        // 门需要 6 个同种木板
        Item abundantPlank = getAbundantPlank(player, 6);
        if (abundantPlank == Items.SPRUCE_PLANKS) return Items.SPRUCE_DOOR;
        if (abundantPlank == Items.BIRCH_PLANKS) return Items.BIRCH_DOOR;
        if (abundantPlank == Items.JUNGLE_PLANKS) return Items.JUNGLE_DOOR;
        if (abundantPlank == Items.ACACIA_PLANKS) return Items.ACACIA_DOOR;
        if (abundantPlank == Items.DARK_OAK_PLANKS) return Items.DARK_OAK_DOOR;
        return Items.OAK_DOOR;
    }

    /**
     * 核心智能分析：统计背包里每种木板的总数，返回数量达标的木板类型
     */
    private static Item getAbundantPlank(PlayerEntity player, int requiredCount) {
        java.util.Map<Item, Integer> plankCounts = new java.util.HashMap<>();

        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.inventory.getStack(i);
            if (stack.getItem().isIn(net.minecraft.tag.ItemTags.PLANKS)) {
                // 累计每种木板的数量 (应对分散堆叠的情况)
                plankCounts.put(stack.getItem(), plankCounts.getOrDefault(stack.getItem(), 0) + stack.getCount());
            }
        }

        // 遍历统计结果，只要哪种木头数量满足所需，就立刻拍板用它！
        for (java.util.Map.Entry<Item, Integer> entry : plankCounts.entrySet()) {
            if (entry.getValue() >= requiredCount) {
                return entry.getKey();
            }
        }

        // 兜底返回，防止空指针
        return Items.OAK_PLANKS;
    }

}