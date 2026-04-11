package com.fredoseep.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.*;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

import java.util.List;
import java.util.function.Predicate;

public class InventoryHelper {

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
}