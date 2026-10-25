package com.fredoseep.testing.combat;

import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Read-only, UUID-scoped arrow spawn observer. Captures arrows even if they collide within one tick. */
public final class RangedArrowProbe implements AutoCloseable {
    private static volatile RangedArrowProbe active;
    private final UUID player,target;
    private final ConcurrentLinkedQueue<Map<String,Object>> events=new ConcurrentLinkedQueue<>();
    public RangedArrowProbe(UUID player,UUID target){this.player=player;this.target=target;active=this;}
    public static void spawned(ServerWorld world,Entity entity){
        RangedArrowProbe probe=active;
        if(probe==null || !(entity instanceof PersistentProjectileEntity arrow))return;
        Entity owner=arrow.getOwner();if(owner==null || !probe.target.equals(owner.getUuid()))return;
        Entity found=world.getEntity(probe.player);if(!(found instanceof ServerPlayerEntity p))return;
        Map<String,Object> row=new LinkedHashMap<>();row.put("tick",world.getTime());row.put("arrow",arrow.getUuid().toString());
        row.put("pos",List.of(arrow.getX(),arrow.getY(),arrow.getZ()));row.put("velocity",List.of(arrow.getVelocity().x,arrow.getVelocity().y,arrow.getVelocity().z));
        row.put("playerBox",box(p.getBoundingBox()));row.put("ownerBox",box(owner.getBoundingBox()));
        row.put("overlap",p.getBoundingBox().intersects(owner.getBoundingBox()));
        row.put("spawnInsidePlayerBox",p.getBoundingBox().contains(arrow.getX(),arrow.getY(),arrow.getZ()));
        probe.events.add(Map.copyOf(row));
    }
    static List<Double> box(Box b){return List.of(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ);}
    public List<Map<String,Object>> snapshot(){return List.copyOf(events);}
    @Override public void close(){if(active==this)active=null;}
}
