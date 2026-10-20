package com.fredoseep.utils.player;

import com.fredoseep.algorithm.planning.TrunkPlan;
import com.fredoseep.behave.MiscController;
import com.fredoseep.behave.MovementController;
import com.fredoseep.excutor.PathExecutor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.tag.BlockTags;
import net.minecraft.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RayTraceContext;
import java.util.*;

/** Tree approach, grounded interruption, quantity-capped local log batch, optional leaves, then collection. */
public final class TreeHarvestSession {
    private enum Phase { SELECT,TRUNK,BATCH_LOGS,STUMP,LEAVES,COLLECT }
    private Phase phase=Phase.SELECT;
    private final int logsRequired,leavesRequired,radius;
    private final Set<Long> visitedColumns=new HashSet<>();
    private final Set<BlockPos> visitedLeafSites=new HashSet<>();
    private boolean leafToolsUnavailable;
    private boolean exhausted;
    private final Set<BlockPos> failedLeaves=new HashSet<>();
    private final List<BlockPos> trunk=new ArrayList<>();
    private BlockPos stump,target;
    private int requests,breakTicks,batchStartLogs,batchBroken;
    private boolean resumeTrunk;
    private BlockPos pendingLog;
    private Vec3d batchFeet;
    private boolean stable(PlayerEntity player){
        // Vanilla travel applies gravity after ground collision, leaving vy ~= -0.0784 even at rest.
        // The physical onGround flag is required; velocity alone must never authorize an airborne cut.
        return player.isOnGround() && !player.isTouchingWater() && Math.abs(player.getVelocity().y)<=.1;
    }
    private void startBatch(PlayerEntity player,PathExecutor path,MiscController misc){
        path.stop();misc.resetKeys();phase=Phase.BATCH_LOGS;
        batchFeet=null;batchStartLogs=InventoryHelper.countItem(player,ItemTags.LOGS);batchBroken=0;
        pendingLog=null;target=null;breakTicks=0;requests=0;resumeTrunk=true;
    }
    /** Called before MotionCoordinator's global ownership gate, but never during an airborne reach. */
    public void interruptApproach(MinecraftClient client,PlayerEntity player,PathExecutor path,MiscController misc){
        if(phase!=Phase.TRUNK || !path.isBusy() || !stable(player))return;
        BlockPos reachable=target;
        if(reachable==null || !client.world.getBlockState(reachable).isIn(BlockTags.LOGS) || !visible(client,player,reachable))
            reachable=trunk.stream().filter(p -> MiningHelper.logTargetAllowed(p) && visible(client,player,p)).findFirst().orElse(null);
        if(reachable!=null && visible(client,player,reachable))startBatch(player,path,misc);
    }
    private int suppliedLogs(PlayerEntity player){
        return Math.max(InventoryHelper.countItem(player,ItemTags.LOGS),batchStartLogs+batchBroken);
    }

    public TreeHarvestSession(PlayerEntity player,int requestedLogs,int radius) {
        this(player,requestedLogs,Math.max(0,64-InventoryHelper.countItem(player,ItemTags.LEAVES)),radius);
    }
    public TreeHarvestSession(PlayerEntity player,int requestedLogs,int requestedLeaves,int radius) {
        logsRequired=InventoryHelper.countItem(player,ItemTags.LOGS)+requestedLogs;
        leavesRequired=InventoryHelper.countItem(player,ItemTags.LEAVES)+requestedLeaves;this.radius=radius;
    }
    private static long column(BlockPos p){return ((long)p.getX()<<32) ^ (p.getZ()&0xffffffffL);}
    public void collected(){phase=resumeTrunk && !trunk.isEmpty()?Phase.TRUNK:Phase.SELECT;
        resumeTrunk=false;target=null;requests=0;breakTicks=0;pendingLog=null;batchFeet=null;}
    public boolean resourceShortage(){return exhausted;}
    public String hudStatus() {
        return switch(phase) {
            case SELECT -> "采树：选择下一棵树或树叶";
            case TRUNK -> "采树：接近主干";
            case BATCH_LOGS -> "采树：原地砍可触及的木头";
            case STUMP -> "采树：移动到主干地面";
            case LEAVES -> "采树：剪可触及的树叶";
            case COLLECT -> "采树：集中收集掉落物";
        };
    }
    private boolean visible(MinecraftClient client,PlayerEntity player,BlockPos p) {
        Vec3d eye=new Vec3d(player.getX(),player.getEyeY(),player.getZ());
        if(eye.squaredDistanceTo(Vec3d.ofCenter(p))>20)return false;
        var hit=client.world.rayTrace(new RayTraceContext(eye,Vec3d.ofCenter(p),RayTraceContext.ShapeType.OUTLINE,
                RayTraceContext.FluidHandling.NONE,player));
        return hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(p);
    }
    private void collect(MiscController misc) {
        phase=Phase.COLLECT;misc.beginTreePickup(stump);
        System.out.println("FredoBot: TREE_ROUND_COLLECT stump="+stump.toShortString());
    }
    public boolean tick(MinecraftClient client,PlayerEntity player,PathExecutor path,MiscController misc) {
        if(phase==Phase.COLLECT)return false;
        if(phase==Phase.SELECT) {
            boolean wood=InventoryHelper.countItem(player,ItemTags.LOGS)<logsRequired;
            boolean leaves=!leafToolsUnavailable && InventoryHelper.findItemSlot(player,Items.SHEARS)>=0 && InventoryHelper.countItem(player,ItemTags.LEAVES)<leavesRequired;
            if(!wood && !leaves){exhausted=InventoryHelper.countItem(player,ItemTags.LEAVES)<leavesRequired;return true;}
            if(wood) {
                var seeds=MiningHelper.findNearestBlocks(player.getBlockPos(),InventoryHelper.anyLogs,1,radius,
                        p -> !visitedColumns.contains(column(p)));
                if(seeds.isEmpty()){exhausted=true;System.out.println("FredoBot: TREE_ROUND_STOP no remaining trunk in range");return true;}
                MiningHelper.currentTargetBlocks.clear();MiningHelper.currentTargetBlocks.addAll(InventoryHelper.anyLogs);
                trunk.clear();trunk.addAll(TrunkPlan.find(seeds.get(0),p -> client.world.getBlockState(p).isIn(BlockTags.LOGS) && MiningHelper.logTargetAllowed(p)));
                if(trunk.isEmpty()){visitedColumns.add(column(seeds.get(0)));return false;}
                stump=trunk.get(0);for(BlockPos p:trunk)visitedColumns.add(column(p));
                phase=Phase.TRUNK;
            } else {
                MiningHelper.currentTargetBlocks.clear();MiningHelper.currentTargetBlocks.addAll(BlockTags.LEAVES.values());
                var leavesOnly=MiningHelper.findNearestBlocks(player.getBlockPos(),new HashSet<>(BlockTags.LEAVES.values()),1,radius,
                        p -> !visitedLeafSites.contains(p));
                if(leavesOnly.isEmpty()){exhausted=true;System.out.println("FredoBot: TREE_ROUND_STOP no reachable leaves in range");return true;}
                BlockPos leaf=leavesOnly.get(0);visitedLeafSites.add(leaf);
                stump=misc.findHarvestApproach(client,player,leaf,true);
                if(stump==null)return false;
                phase=Phase.STUMP;
            }
            target=null;requests=0;breakTicks=0;
        }
        interruptApproach(client,player,path,misc);
        if(path.isBusy())return false;
        if(phase==Phase.TRUNK && InventoryHelper.countItem(player,ItemTags.LOGS)>=logsRequired){
            trunk.clear();phase=Phase.SELECT;return false;
        }
        if(phase==Phase.TRUNK) {
            trunk.removeIf(p -> !client.world.getBlockState(p).isIn(BlockTags.LOGS) || !MiningHelper.logTargetAllowed(p));
            if(target!=null && !trunk.contains(target)){target=null;requests=0;breakTicks=0;}
            if(trunk.isEmpty()){phase=Phase.SELECT;target=null;requests=0;return false;}
            else {
                if(target==null)target=trunk.stream().filter(p -> visible(client,player,p)).findFirst().orElse(trunk.get(0));
                if(!visible(client,player,target)) {
                    BlockPos stand=misc.findHarvestApproach(client,player,target,false);
                    if(stand==null || requests>=1) {
                        MiningHelper.blacklistLogTarget(target);
                        System.out.println("FredoBot: TREE_TRUNK_SKIP inaccessible="+target.toShortString());
                        trunk.remove(target);target=null;requests=0;breakTicks=0;return false;
                    }
                    if(path.trySetGoal(stand,"tree trunk approach"))requests++;
                    return false;
                }
                if(!stable(player))return false;
                startBatch(player,path,misc);
                return false;
            }
        }
        if(phase==Phase.BATCH_LOGS){
            misc.resetKeys();
            if(pendingLog!=null && !client.world.getBlockState(pendingLog).isIn(BlockTags.LOGS)){
                batchBroken++;trunk.remove(pendingLog);pendingLog=null;target=null;breakTicks=0;
            }
            // Stop naturally before choosing the fixed station; no mid-jump mining or repeated jumping.
            Vec3d velocity=player.getVelocity();
            if(!stable(player) || velocity.x*velocity.x+velocity.z*velocity.z>.0049)return false;
            if(batchFeet==null)batchFeet=player.getPos();
            if(player.getPos().squaredDistanceTo(batchFeet)>.25){collect(misc);return false;}
            if(suppliedLogs(player)>=logsRequired){target=null;pendingLog=null;finishLogBatch(player,misc);return false;}
            if(target==null || !visible(client,player,target)){
                if(pendingLog!=null){client.interactionManager.cancelBlockBreaking();pendingLog=null;}
                target=null;double best=Double.POSITIVE_INFINITY;
                BlockPos feet=player.getBlockPos();
                for(int x=-4;x<=4;x++)for(int y=-3;y<=5;y++)for(int z=-4;z<=4;z++){
                    BlockPos p=feet.add(x,y,z);
                    if(!client.world.getBlockState(p).isIn(BlockTags.LOGS) || !MiningHelper.logTargetAllowed(p) || !visible(client,player,p))continue;
                    double distance=Vec3d.ofCenter(p).squaredDistanceTo(player.getPos());
                    if(distance<best){target=p;best=distance;}
                }
                breakTicks=0;
                if(target==null){finishLogBatch(player,misc);return false;}
            }
            ToolsHelper.equipBestTool(player,target,false);
            if(++breakTicks>300){MiningHelper.blacklistLogTarget(target);target=null;pendingLog=null;breakTicks=0;return false;}
            pendingLog=target;mine(client,player,target);return false;
        }
        if(phase==Phase.STUMP) {
            if(!misc.harvestStandable(client,stump)) {
                BlockPos alternative=null;
                for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})
                    if(misc.harvestStandable(client,stump.add(d[0],0,d[1]))){alternative=stump.add(d[0],0,d[1]);break;}
                if(alternative==null){collect(misc);return false;}stump=alternative;
            }
            double dx=player.getX()-stump.getX()-.5,dz=player.getZ()-stump.getZ()-.5;
            if(dx*dx+dz*dz>.06 || Math.abs(player.getY()-stump.getY())>.3 || !player.isOnGround()) {
                if(requests>=3){collect(misc);return false;}
                if(path.trySetGoal(stump,"return to tree stump"))requests++;
                return false;
            }
            if(leafToolsUnavailable || InventoryHelper.findItemSlot(player,Items.SHEARS)<0
                    || InventoryHelper.countItem(player,ItemTags.LEAVES)>=leavesRequired) {collect(misc);return false;}
            requests=0;phase=Phase.LEAVES;target=null;breakTicks=0;
            MiningHelper.currentTargetBlocks.clear();MiningHelper.currentTargetBlocks.addAll(BlockTags.LEAVES.values());
        }
        if(phase==Phase.LEAVES) {
            if(!stable(player))return false;
            if(InventoryHelper.findItemSlot(player,Items.SHEARS)<0){collect(misc);return false;}
            if(target!=null && !client.world.getBlockState(target).isIn(BlockTags.LEAVES)){target=null;breakTicks=0;}
            if(target==null) {
                BlockPos feet=player.getBlockPos();double best=Double.POSITIVE_INFINITY;
                for(int x=-4;x<=4;x++)for(int y=-3;y<=5;y++)for(int z=-4;z<=4;z++) {
                    BlockPos p=feet.add(x,y,z);
                    if(failedLeaves.contains(p) || !client.world.getBlockState(p).isIn(BlockTags.LEAVES) || !visible(client,player,p))continue;
                    double distance=p.getSquaredDistance(feet);
                    if(distance<best){best=distance;target=p;}
                }
                if(target==null){collect(misc);return false;}
            }
            misc.resetKeys();
            if(!ToolsHelper.equipPlannedTool(player,target,Items.SHEARS)){leafToolsUnavailable=true;collect(misc);return false;}
            if(++breakTicks>100 || !visible(client,player,target)){failedLeaves.add(target);target=null;breakTicks=0;return false;}
            mine(client,player,target);return false;
        }
        return false;
    }
    private void finishLogBatch(PlayerEntity player,MiscController misc){
        stump=player.getBlockPos();target=null;breakTicks=0;
        if(!leafToolsUnavailable && InventoryHelper.findItemSlot(player,Items.SHEARS)>=0
                && InventoryHelper.countItem(player,ItemTags.LEAVES)<leavesRequired)phase=Phase.LEAVES;
        else collect(misc);
    }
    private void mine(MinecraftClient client,PlayerEntity player,BlockPos p) {
        float[] look=MiningHelper.getValidMiningAngle(player,p);
        MovementController.setLookDirection(player,look[0],look[1]);
        client.interactionManager.updateBlockBreakingProgress(p,Direction.UP);
        if(!player.handSwinging)player.swingHand(Hand.MAIN_HAND);
    }
}
