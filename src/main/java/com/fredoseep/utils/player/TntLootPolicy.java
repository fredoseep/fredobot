package com.fredoseep.utils.player;

import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.tag.ItemTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Only useful blast loot; old excavation entities must not cause a return to the treasure shaft. */
public final class TntLootPolicy {
    private static World excavationWorld;
    private static final Set<UUID> excavationDrops=new HashSet<>();
    private World world;
    private final Set<UUID> oldDrops=new HashSet<>();
    public static boolean allowed(Item item){
        return item.isIn(ItemTags.LOGS) || item.isIn(ItemTags.PLANKS) || item.isFood()
                || item==Items.DIRT || item==Items.COARSE_DIRT || item==Items.SAND || item==Items.RED_SAND
                || item==Items.GRAVEL || item==Items.FLINT || item==Items.COBBLESTONE || item==Items.STICK
                || item==Items.SWEET_BERRIES;
    }
    public void reset(){world=null;oldDrops.clear();excavationWorld=null;excavationDrops.clear();}
    public static void rememberExcavation(World world,BlockPos treasure){
        if(excavationWorld!=world){excavationWorld=world;excavationDrops.clear();}
        if(treasure==null)return;
        for(ItemEntity e:world.getEntities(ItemEntity.class,new Box(treasure).expand(16),ItemEntity::isAlive))
            excavationDrops.add(e.getUuid());
    }
    public static boolean excavationEligible(World world,ItemEntity e){
        return world!=excavationWorld || !excavationDrops.contains(e.getUuid());
    }
    /** Observe before ignition, while all known drops still belong to excavation/earlier collection. */
    public void remember(World world,BlockPos center,double radius){
        if(this.world!=world){oldDrops.clear();this.world=world;}
        if(center==null)return;
        for(ItemEntity e:world.getEntities(ItemEntity.class,new Box(center).expand(radius),ItemEntity::isAlive))
            oldDrops.add(e.getUuid());
    }
    public boolean eligible(World world,ItemEntity e){
        return e.isAlive() && !e.getStack().isEmpty() && allowed(e.getStack().getItem()) && excavationEligible(world,e)
                && (this.world!=world || !oldDrops.contains(e.getUuid()));
    }
}
