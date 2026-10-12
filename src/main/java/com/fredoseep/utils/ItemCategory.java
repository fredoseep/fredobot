package com.fredoseep.utils;

import net.minecraft.item.Item;
import net.minecraft.item.Items;

public class ItemCategory {
    public static boolean isSapling(Item item){
        return item == Items.ACACIA_SAPLING || item == Items.SPRUCE_SAPLING || item == Items.BIRCH_SAPLING || item == Items.JUNGLE_SAPLING || item == Items.OAK_SAPLING || item == Items.DARK_OAK_SAPLING;
    }
}
