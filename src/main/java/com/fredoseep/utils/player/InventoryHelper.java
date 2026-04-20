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

        if (targetHotbarSlot < 0 || targetHotbarSlot > 8) {
            System.out.println("FredoBot: 目标快捷栏越界 (" + targetHotbarSlot + ")");
            return false;
        }

        if (itemInventorySlot == -1) {
            System.out.println("FredoBot: 无法移动物品 -> 背包中未找到该物品 (-1)");
            return false;
        }

        if (itemInventorySlot == targetHotbarSlot + 36) {
            return true; 
        }

        if (itemInventorySlot >= 9 && itemInventorySlot <= 45) {
            int syncId = player.currentScreenHandler.syncId;
            client.interactionManager.clickSlot(
                    syncId,
                    itemInventorySlot,
                    targetHotbarSlot, 
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

        Box searchArea = player.getBoundingBox().expand(searchRadius, searchRadius, searchRadius);

        List<ItemEntity> droppedItems = world.getEntities(
                ItemEntity.class,
                searchArea,
                itemEntity -> condition.test(itemEntity.getStack())
        );

        if (droppedItems.isEmpty()) return null;

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
        return findNearestDroppedItem(player, searchRadius, stack -> stack.getItem() == targetItem);
    }

    /**
     * 重载 2：模糊寻找某一大类物品 (找任何船、任何剑专用)
     */
    public static ItemEntity findNearestDroppedItem(PlayerEntity player, double searchRadius, Class<? extends Item> itemClass) {
        return findNearestDroppedItem(player, searchRadius, stack -> itemClass.isInstance(stack.getItem()));
    }


    public enum PlaceableBlock {
        DIRT(Items.DIRT, false, 3),           
        COBBLESTONE(Items.COBBLESTONE, false, 4), 
        NETHERRACK(Items.NETHERRACK, false, 3),  
        SAND(Items.SAND, true, 1),            
        GRAVEL(Items.GRAVEL, true, 2),
        STONE(Items.STONE, false, 3),         
        OAK_PLANKS(Items.OAK_PLANKS, false, 20),
        SPRUCE_PLANKS(Items.SPRUCE_PLANKS, false,20),
        BIRCH_PLANKS(Items.BIRCH_PLANKS,false,20),
        JUNGLE_PLANKS(Items.JUNGLE_PLANKS,false,20),
        ACACIA_PLANKS(Items.ACACIA_PLANKS,false,20),
        DARK_OAK_PLANKS(Items.DARK_OAK_PLANKS,false,20),
        CRIMSON_PLANKS(Items.CRIMSON_PLANKS,false,20),
        WARPED_PLANKS(Items.WARPED_PLANKS,false,20),
        OAK_LEAVES(Items.OAK_LEAVES,false,3),
        SPRUCE_LEAVES(Items.SPRUCE_LEAVES,false,3),
        BIRCH_LEAVES(Items.BIRCH_LEAVES,false,3),
        JUNGLE_LEAVES(Items.JUNGLE_LEAVES,false,3),
        ACACIA_LEAVES(Items.ACACIA_LEAVES,false,3),
        DARK_OAK_LEAVES(Items.DARK_OAK_LEAVES,false,3);
        
        
        private final Item item;
        private final boolean isGravity;
        private final int cost; 

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


    public static class BlockCounts {
        public int bridgingBlocks = 0;  // 非重力
        public int pillaringBlocks = 0; // 含重力方块
    }

    /**
     * 遍历玩家物品栏，分类统计可用方块
     */
    public static BlockCounts countAvailableBuildingBlocks(PlayerEntity player) {
        BlockCounts counts = new BlockCounts();
        if (player == null) return counts;

        for (int i = 0; i < player.inventory.main.size(); i++) {
            ItemStack stack = player.inventory.main.get(i);
            addStackToCounts(stack, counts);
        }
        ItemStack offHand = player.inventory.offHand.get(0);
        addStackToCounts(offHand, counts);

        return counts;
    }

    private static void addStackToCounts(ItemStack stack, BlockCounts counts) {
        if (!stack.isEmpty()) {
            PlaceableBlock pb = PlaceableBlock.getPlaceable(stack.getItem());
            if (pb != null) {
                counts.pillaringBlocks += stack.getCount();
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

        for (net.minecraft.recipe.Recipe<?> recipe : client.world.getRecipeManager().values()) {
            if (recipe.getOutput().getItem() == targetOutput) {
                foundAny = true;

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
        return Items.OAK_BUTTON;
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
        Item abundantPlank = getAbundantPlank(player, 5);
        if (abundantPlank == Items.SPRUCE_PLANKS) return Items.SPRUCE_BOAT;
        if (abundantPlank == Items.BIRCH_PLANKS) return Items.BIRCH_BOAT;
        if (abundantPlank == Items.JUNGLE_PLANKS) return Items.JUNGLE_BOAT;
        if (abundantPlank == Items.ACACIA_PLANKS) return Items.ACACIA_BOAT;
        if (abundantPlank == Items.DARK_OAK_PLANKS) return Items.DARK_OAK_BOAT;
        return Items.OAK_BOAT;
    }

    public static Item getTargetDoorType(PlayerEntity player) {
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
                plankCounts.put(stack.getItem(), plankCounts.getOrDefault(stack.getItem(), 0) + stack.getCount());
            }
        }

        for (java.util.Map.Entry<Item, Integer> entry : plankCounts.entrySet()) {
            if (entry.getValue() >= requiredCount) {
                return entry.getKey();
            }
        }
        return Items.OAK_PLANKS;
    }

}