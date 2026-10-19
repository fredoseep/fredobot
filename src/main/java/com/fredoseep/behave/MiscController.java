package com.fredoseep.behave;

import com.fredoseep.utils.player.DropCollectionOrder;

import com.fredoseep.algorithm.SimplePathfinder;
import com.fredoseep.algorithm.planning.BuriedMiningAccess;
import com.fredoseep.algorithm.planning.TreeBreathingPolicy;
import com.fredoseep.logging.GlobalStateLogger;
import java.util.Map;
import com.fredoseep.algorithm.planning.LeafLootSupport;
import com.fredoseep.utils.ItemCategory;
import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.GlobalExecutor;
import com.fredoseep.excutor.PathExecutor;
import com.fredoseep.utils.bt.BtStuff;
import com.fredoseep.utils.player.*;

import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.BoatEntity;

import net.minecraft.item.BoatItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.tag.BlockTags;
import net.minecraft.tag.ItemTags;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Direction;
import com.fredoseep.utils.prenether.PreNether;
import com.fredoseep.algorithm.planning.UnderwaterGravelWork;
import net.minecraft.world.RayTraceContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Set;
import com.fredoseep.algorithm.planning.LeafTargetPolicy;
import com.fredoseep.algorithm.planning.MiningApproach;
import java.util.HashSet;
import java.util.function.Predicate;
import net.minecraft.tag.FluidTags;

public class MiscController implements IBotModule {

    private TreeHarvestSession treeHarvest;
    private UnderwaterGravelWork dryGravelWork;
    private BlockPos dryGravelExitSurface;
    private BlockPos dryIssuedGravel;
    private BuriedMiningAccess.Plan localStoneAccess;
    private BlockPos localStoneCover,failedLocalStone;
    private int localStoneCoverAge;
    private void resetLocalStoneAccess(){localStoneAccess=null;localStoneCover=null;}

    private int dryRequiredGravel;
    private BuriedMiningAccess.Plan buriedGravel;
    private BlockPos buriedCover;
    private boolean buriedEntryRequested,buriedDescending;
    private int buriedCoverAge,buriedCenterTicks,buriedProgressAge;
    private void resetBuriedGravel(){buriedGravel=null;buriedCover=null;buriedEntryRequested=false;buriedDescending=false;buriedCenterTicks=0;}
    private int finalRecoveryStartAge=-1;
    private boolean finalRecoverySawDrop;
    private boolean pressForward = false;
    private boolean pressLeft = false;
    private boolean pressBack = false;
    private boolean pressRight = false;
    private boolean pressJump = false;
    private boolean pressSneak = false;
    private boolean pressAttack = false;
    private boolean pressUse = false;
    private boolean pressSprint = false;
    private float yaw;
    private float pitch;

    private BoatEntity boatEntity;
    private int pickupCoolDownTick = 0;
    private int pathingFailTicks = 0;
    private int miningSearchFailures;
    private long observedSearchFailureSerial;
    private BlockPos lastMiningApproach;
    private final Set<BlockPos> failedMiningApproaches=new HashSet<>();
    private ItemEntity leafLogDrop;
    private BlockPos lastLeafApproach;
    private long leafFailureSerial;
    private int leafApproachFailures;
    private boolean uwGravelMission;
    private UnderwaterGravelWork uwGravelWork;
    private BlockPos uwIssuedTarget;
    private BlockPos uwDoorAdjustedTarget;
    private int leafSettleTicks;
    private final Set<BlockPos> failedLeafApproaches=new HashSet<>();
    private boolean gotOffBoat = false;
    private boolean boatRecoveryForDescent;
    private final Set<Item> validDrops = new HashSet<>();
    private final Set<Entity> unreachableEntities = new HashSet<>();

    public enum MiscType {
        HARVEST_TREE,
        MINE_BLOCK_ABOVE_HEAD,
        BACK_FROM_SWIMMING,
        MINE_THE_BLOCK,
        MINE_THE_BLOCK_AND_COLLECT_THE_DROP;
    }
    public enum UwDoorPhase {
        NONE,               // 正常挖掘模式
        SINK_AND_CLEAR,     // 潜水触底并清理水草
        PLACE_DOOR,         // 放置呼吸门
        MINE_TARGETS,       // 躲在门里挖目标
        COLLECT_DROPS,      // 收集掉落物
        RETURN_AND_BREAK,   // 回到门里并恢复氧气
        BREAK_FOUNDATION,   // 回氧完成后独立拆地基，不再要求站在门内
        COLLECT_FINAL_DROPS // 收尾：捡起掉落的门和最后的地基方块
    }

    public UwDoorPhase uwPhase = UwDoorPhase.NONE;
    public BlockPos doorPos = null;
    public int uwWaitTicks = 0;

    private final DropCollectionOrder pickupOrder=new DropCollectionOrder();
    private MiscType currentTask = null;
    private BlockPos targetPos = null;
    public Entity targetEntity = null;

    public void startTask(MiscType type, BlockPos pos) {
        resetBuriedGravel();resetLocalStoneAccess();failedLocalStone=null;
        dryGravelWork=null;dryIssuedGravel=null;dryGravelExitSurface=null;
        pickupOrder.reset();
        if(type==MiscType.BACK_FROM_SWIMMING) {
            resetKeys();
            MinecraftClient client=MinecraftClient.getInstance();
            if(client.player!=null)client.player.setSprinting(false);
        }
        boatRecoveryForDescent=false;
        this.treeHarvest=null;
        this.currentTask = type;
        this.targetPos = pos;
        var taskClient=MinecraftClient.getInstance();
        uwGravelMission=taskClient.world!=null && pos!=null && taskClient.world.getBlockState(pos).getBlock()==Blocks.GRAVEL;
        uwGravelWork=new UnderwaterGravelWork();uwIssuedTarget=null;uwDoorAdjustedTarget=null;
        leafLogDrop=null;lastLeafApproach=null;failedLeafApproaches.clear();leafSettleTicks=0;
        miningSearchFailures=0;lastMiningApproach=null;failedMiningApproaches.clear();
        PathExecutor executor=BotEngine.getInstance().getModule(PathExecutor.class);
        observedSearchFailureSerial=executor==null?0:executor.getSearchFailureSerial();
        if(type==MiscType.MINE_THE_BLOCK_AND_COLLECT_THE_DROP) {
            var client=MinecraftClient.getInstance();
            if(client.world!=null && client.player!=null)pruneHighLeaves(client,client.player);
        }
        System.out.println("FredoBot: 开始杂项任务 -> " + type.name());
    }

    public void startBoatRecoveryForDescent(BlockPos destination) {
        startTask(MiscType.BACK_FROM_SWIMMING, destination);
        boatRecoveryForDescent = true;
    }

    public void interruptTreeApproach(MinecraftClient client) {
        if(currentTask==MiscType.HARVEST_TREE && treeHarvest!=null)
            treeHarvest.interruptApproach(client,client.player,BotEngine.getInstance().getModule(PathExecutor.class),this);
    }
    /** Live emergency movement, before any land-tree search or oxygen-starved retry. */
    public void floatForTreeBreathing(MinecraftClient client,PlayerEntity player){
        resetKeys();
        boolean submerged=player.isSubmergedIn(FluidTags.WATER);
        boolean roof=submerged && !client.world.doesNotCollide(player,player.getBoundingBox().offset(0,.4,0));
        var control=TreeBreathingPolicy.control(submerged,player.isTouchingWater(),roof,player.horizontalCollision,player.age,player.yaw);
        if(submerged)MovementController.setLookDirection(player,control.yaw(),control.pitch());
        pressForward=control.forward();pressJump=control.jump();pressSprint=control.sprint();player.setSprinting(pressSprint);
        applyKeys(client);
    }
    public void startTreeHarvest(TreeHarvestSession session) {
        stopTask();treeHarvest=session;currentTask=MiscType.HARVEST_TREE;
    }
    public BlockPos findHarvestApproach(MinecraftClient client,PlayerEntity player,BlockPos block,boolean leaves) {
        return findMiningApproach(client,player,block,Set.of(),leaves);
    }
    public boolean harvestStandable(MinecraftClient client,BlockPos pos) {
        return isMiningStance(client,pos,Set.of(),true);
    }
    public void beginTreePickup(BlockPos stump) {
        currentTask=MiscType.MINE_THE_BLOCK_AND_COLLECT_THE_DROP;targetPos=stump;
        uwGravelMission=false;uwPhase=UwDoorPhase.NONE;targetEntity=null;pickupCoolDownTick=1;pathingFailTicks=0;
        validDrops.clear();validDrops.addAll(ItemTags.LOGS.values());validDrops.addAll(ItemTags.LEAVES.values());
        leafLogDrop=null;unreachableEntities.clear();resetKeys();
    }

    public boolean isBusy() {
        return currentTask != null;
    }

    public MiscType getCurrentTask() {
        return this.currentTask;
    }

    public BlockPos getTargetPos() {
        return this.targetPos;
    }

    public boolean isUwGravelMission() {
        return this.uwGravelMission;
    }

    public UnderwaterGravelWork getUwGravelWork() {
        return this.uwGravelWork;
    }

    public BlockPos getUwIssuedTarget() {
        return this.uwIssuedTarget;
    }
    public String hudStatus(PlayerEntity player) {
        if(currentTask==null)return "等待任务";
        if(uwPhase!=UwDoorPhase.NONE)return switch(uwPhase) {
            case SINK_AND_CLEAR -> "水下：下潜并清理放门位置";
            case PLACE_DOOR -> "水下：放置呼吸门";
            case MINE_TARGETS -> "水下：在门内挖掘"+(uwGravelMission?"，已确认 "+uwGravelWork.count()+"/4":"");
            case COLLECT_DROPS -> "水下：收集沙砾或燧石";
            case RETURN_AND_BREAK -> insideBreathingDoor(player)?"水下：恢复氧气 "+player.getAir()+"/"+player.getMaxAir():"水下：返回呼吸门";
            case BREAK_FOUNDATION -> "水下：破坏门下地基";
            case COLLECT_FINAL_DROPS -> "水下：回收门和地基掉落物";
            default -> "水下作业";
        };
        if(buriedGravel!=null)return "沙砾："+(buriedCover==null?"前往顶部／等待落地":"挖覆盖层 "+buriedCover.toShortString());
        if(leafLogDrop!=null)return "收集：剪掉掉落物下方的树叶";
        if(treeHarvest!=null)return treeHarvest.hudStatus();
        if(targetEntity!=null || pickupCoolDownTick>0)return "收集掉落物";
        String action=switch(currentTask) {
            case HARVEST_TREE -> "采集木头和树叶";
            case MINE_BLOCK_ABOVE_HEAD -> "挖掘头顶方块";
            case BACK_FROM_SWIMMING -> "上岸或退出游泳";
            case MINE_THE_BLOCK,MINE_THE_BLOCK_AND_COLLECT_THE_DROP -> "挖掘材料";
        };
        return action+(targetPos==null?"":"："+targetPos.toShortString());
    }
    private void resetMiningApproachFailures() {
        resetBuriedGravel();resetLocalStoneAccess();failedLocalStone=null;
        miningSearchFailures=0;lastMiningApproach=null;failedMiningApproaches.clear();
        PathExecutor executor=BotEngine.getInstance().getModule(PathExecutor.class);
        observedSearchFailureSerial=executor==null?0:executor.getSearchFailureSerial();
    }
    private BlockPos findMiningApproach(MinecraftClient client,PlayerEntity player,BlockPos target) {
        return findMiningApproach(client,player,target,failedMiningApproaches);
    }
    private boolean isMiningStance(MinecraftClient client,BlockPos p,Set<BlockPos> excluded,boolean avoidTrees) {
        var world=client.world;
        if(excluded.contains(p) || p.getY()<0 || p.getY()>254
                || !world.getChunkManager().isChunkLoaded(p.getX()>>4,p.getZ()>>4))return false;
        BlockState support=world.getBlockState(p.down());
        if((avoidTrees && (support.isIn(BlockTags.LEAVES) || support.isIn(BlockTags.LOGS)))
                || support.getBlock()==Blocks.MAGMA_BLOCK
                || !Block.isShapeFullCube(support.getCollisionShape(world,p.down())))return false;
        BlockState feet=world.getBlockState(p),head=world.getBlockState(p.up());
        return feet.getFluidState().isEmpty() && head.getFluidState().isEmpty()
                && feet.getBlock()!=Blocks.FIRE && feet.getBlock()!=Blocks.SOUL_FIRE
                && head.getBlock()!=Blocks.FIRE && head.getBlock()!=Blocks.SOUL_FIRE
                && feet.getCollisionShape(world,p).isEmpty() && head.getCollisionShape(world,p.up()).isEmpty();
    }
    private boolean miningVisible(MinecraftClient client,PlayerEntity player,Vec3d eye,BlockPos target) {
        var hit=client.world.rayTrace(new RayTraceContext(eye,Vec3d.ofCenter(target),
                RayTraceContext.ShapeType.OUTLINE,RayTraceContext.FluidHandling.NONE,player));
        return hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
    }
    private BlockPos findMiningApproach(MinecraftClient client,PlayerEntity player,BlockPos target,Set<BlockPos> excluded) {
        return findMiningApproach(client,player,target,excluded,false);
    }
    private BlockPos findMiningApproach(MinecraftClient client,PlayerEntity player,BlockPos target,Set<BlockPos> excluded,boolean avoidTrees) {
        return MiningApproach.select(target,player.getBlockPos(),player.getStandingEyeHeight(),
                p -> isMiningStance(client,p,excluded,avoidTrees),p -> miningVisible(client,player,
                        new Vec3d(p.getX()+.5,p.getY()+player.getStandingEyeHeight(),p.getZ()+.5),target));
    }
    /** One batch shares stance probes; high leaves are excluded before quota selection or any A* request. */
    public Predicate<BlockPos> leafTargetFilter(MinecraftClient client,PlayerEntity player) {
        Vec3d eye=new Vec3d(player.getX(),player.getEyeY(),player.getZ());
        var policy=new LeafTargetPolicy(player.getBlockPos(),player.getStandingEyeHeight(),
                p -> isMiningStance(client,p,Set.of(),true),
                p -> eye.squaredDistanceTo(Vec3d.ofCenter(p))<=20 && miningVisible(client,player,eye,p),
                (stance,target) -> miningVisible(client,player,new Vec3d(stance.getX()+.5,
                        stance.getY()+player.getStandingEyeHeight(),stance.getZ()+.5),target));
        return p -> !client.world.getBlockState(p).isIn(BlockTags.LEAVES) || policy.test(p);
    }
    private void pruneHighLeaves(MinecraftClient client,PlayerEntity player) {
        var eligible=leafTargetFilter(client,player);int before=MiningHelper.blockToMine.size();
        MiningHelper.blockToMine.removeIf(p -> !eligible.test(p));
        boolean skipCurrent=targetPos!=null && !eligible.test(targetPos);
        int skipped=before-MiningHelper.blockToMine.size()+(skipCurrent?1:0);
        if(skipped>0)System.out.println("FredoBot: LEAF_BATCH_SKIP count="+skipped+" reason=NO_GROUND_MINING_STANCE; no path search requested");
        if(skipCurrent) {
            if(!MiningHelper.blockToMine.isEmpty())targetPos=MiningHelper.blockToMine.remove(0);
            else {targetPos=player.getBlockPos();pickupCoolDownTick=1;}
        }
    }

    public void stopTask() {
        if(treeHarvest!=null){MiningHelper.blockToMine.clear();MiningHelper.currentTargetBlocks.clear();}
        treeHarvest=null;resetBuriedGravel();resetLocalStoneAccess();failedLocalStone=null;dryGravelWork=null;dryIssuedGravel=null;dryGravelExitSurface=null;
        leafLogDrop=null;lastLeafApproach=null;failedLeafApproaches.clear();leafSettleTicks=0;
        if(currentTask==MiscType.MINE_THE_BLOCK_AND_COLLECT_THE_DROP)MiningHelper.currentTargetBlocks.clear();
        this.currentTask = null;
        this.targetPos = null;
        this.boatEntity = null;
        this.gotOffBoat = false;
        pickupCoolDownTick = 0;
        targetEntity = null;
        pathingFailTicks = 0;
        uwPhase = UwDoorPhase.NONE;
        uwGravelMission=false;uwGravelWork=null;uwIssuedTarget=null;uwDoorAdjustedTarget=null;
        doorPos = null;
        uwWaitTicks = 0;
        finalRecoveryStartAge=-1;
        validDrops.clear();
        unreachableEntities.clear();
        resetKeys();
    }

    @Override
    public void onEnable() {
        stopTask();
    }

    @Override
    public void onDisable() {
        stopTask();
    }

    @Override
    public String getName() {
        return "Misc_Controller";
    }

    @Override
    public int getPriority() {
        return 50;
    }

    @Override
    public void onTick(MinecraftClient client, PlayerEntity player) {
        if (currentTask == null) return;

        PathExecutor pathExecutor = BotEngine.getInstance().getModule(PathExecutor.class);

        switch (currentTask) {
            case HARVEST_TREE:
                if(treeHarvest==null || treeHarvest.tick(client,player,pathExecutor,this)) {
                    if(treeHarvest!=null)MiningHelper.harvestResourceExhausted=treeHarvest.resourceShortage();
                    MiningHelper.blockToMine.clear();MiningHelper.currentTargetBlocks.clear();stopTask();
                }
                return;
            case MINE_BLOCK_ABOVE_HEAD:
                if (targetPos == null || client.world == null) {
                    stopTask();
                    break;
                }

                if (client.world.getBlockState(targetPos).isAir()||client.world.getBlockState(targetPos).getMaterial().isLiquid()) {
                    System.out.println("FredoBot: 头顶方块清理完毕！");
                    stopTask();
                    pathExecutor.resume();
                    return;
                }

                prepareMineBlockAboveHead(player, targetPos);

                client.interactionManager.updateBlockBreakingProgress(targetPos, Direction.DOWN);
                if (!player.handSwinging) {
                    player.swingHand(Hand.MAIN_HAND);
                }
                break;

            case BACK_FROM_SWIMMING:
                if (PlayerHelper.isDrivingBoat(player)) {
                    boatEntity = (BoatEntity) player.getVehicle();
                    pressSneak = true;
                    InventoryHelper.putAxeToHotbar(player);
                    player.inventory.selectedSlot = 0;
                } else if (boatEntity != null && boatEntity.isAlive()) {
                    pressSneak = false;
                    pressForward = false;
                    float coolDown = player.getAttackCooldownProgress(0.0F);
                    if (coolDown >= 1.0F) {
                        MinecraftClient.getInstance().interactionManager.attackEntity(player, boatEntity);
                    }
                } else if (boatEntity != null && !boatEntity.isAlive()) {
                    pressAttack = false;
                    if (InventoryHelper.hasBoat(player)) {
                        System.out.println(boatRecoveryForDescent?"FredoBot: 船只回收完毕，开始垂直下潜！":"FredoBot: 船只回收完毕，准备游向岸边！");
                        boatEntity = null;
                        if (boatRecoveryForDescent) { stopTask(); return; }
                        pathExecutor.resumeSuspendedGoal();
                    } else {
                        ItemEntity droppedBoat = InventoryHelper.findNearestDroppedItem(player, 8.0, BoatItem.class);
                        if (droppedBoat != null) {
                            // 【核心修复 1】：等待船只落地或落水后再去捡
                            if (!droppedBoat.isTouchingWater() && !droppedBoat.isOnGround()) return;
                            walkTowardsEntity(player, droppedBoat);
                        } else {
                            System.out.println("FredoBot: 掉落的船丢失，放弃拾取！");
                            boatEntity = null;
                        }
                    }
                } else {
                    if (boatRecoveryForDescent) { stopTask(); return; }
                    if(pathExecutor.getCurrentNode().state == SimplePathfinder.MovementState.DIVING){
                        System.out.println("FredoBot: start diving");
                        stopTask();
                        pathExecutor.resume();
                        return;
                    }
                    if (player.isTouchingWater()) {
                        pressSneak=false;pressSprint=false;player.setSprinting(false);
                        float[] angles = PlayerHelper.getLookAngles(player, targetPos.getX(), targetPos.getY(), targetPos.getZ());
                        MovementController.setLookDirection(player, angles[0], angles[1]);

                        pressForward = true;
                        pressJump = true;
                        pressSprint = false;
                    } else {
                        System.out.println("FredoBot: 成功登陆目标海岸，恢复寻路！");
                        stopTask();
                        pathExecutor.resume();
                    }
                }
                break;

            case MINE_THE_BLOCK:
                if (pathExecutor.isBusy()) break;
                if (MiningHelper.blockToMine.isEmpty()) {
                    stopTask();
                    break;
                }
                pathExecutor.setGoal(MiningHelper.blockToMine.get(0),"block to mine pos");
                MiningHelper.blockToMine.remove(0);
                break;

            case MINE_THE_BLOCK_AND_COLLECT_THE_DROP:
                if (targetPos == null || client.world == null) {
                    stopTask();
                    break;
                }

                if (uwPhase == UwDoorPhase.NONE) {
                    boolean isUnderwater = client.world.getBlockState(targetPos).getBlock() == Blocks.WATER ||
                            client.world.getBlockState(targetPos.up()).getBlock() == Blocks.WATER;

                    int doorSlot = -1;
                    for (int i = 0; i < 36; i++) {
                        if (player.inventory.getStack(i).getItem().isIn(ItemTags.DOORS)) {
                            doorSlot = i;
                            break;
                        }
                    }


                    if (isUnderwater && doorSlot != -1) {
                        BlockPos bestSpot=null,fallbackSpot=null;
                        for(Direction d:Direction.Type.HORIZONTAL) {
                            BlockPos p=targetPos.up().offset(d);
                            if(client.world.getBlockState(p).getMaterial().isLiquid() && isSafeDoorFoundation(client,p.down())
                                    && !p.down().equals(targetPos)) {
                                if(fallbackSpot==null)fallbackSpot=p;
                                if(!MiningHelper.blockToMine.contains(p.down()) && client.world.getBlockState(p.down()).getBlock()!=Blocks.GRAVEL) {
                                    bestSpot=p;break;
                                }
                            }
                        }
                        if(bestSpot==null)bestSpot=fallbackSpot;
                        if(bestSpot==null && client.world.getBlockState(targetPos.up()).getMaterial().isLiquid()
                                && isSafeDoorFoundation(client,targetPos))bestSpot=targetPos.up();

                        if (bestSpot != null) {
                            doorPos = bestSpot;
                            uwPhase = UwDoorPhase.SINK_AND_CLEAR;
                            System.out.println("FredoBot [水下作战]: 发现目标处于水下，启用门呼吸策略！doorPos: " + doorPos.toShortString());
                        }
                    }
                }
                if (uwPhase != UwDoorPhase.NONE) {
                    if(uwGravelMission && (uwPhase==UwDoorPhase.MINE_TARGETS || uwPhase==UwDoorPhase.COLLECT_DROPS
                            || uwPhase==UwDoorPhase.COLLECT_FINAL_DROPS)) {
                        boolean flint=InventoryHelper.countItem(player,Items.FLINT)>0 || !client.world.getEntities(ItemEntity.class,
                                new Box(doorPos).expand(6),e -> e.isAlive() && !e.getStack().isEmpty() && e.getStack().getItem()==Items.FLINT).isEmpty();
                        uwGravelWork.observeFlint(flint);
                        if(uwIssuedTarget!=null && uwGravelWork.confirmRemoval(client.world.getBlockState(uwIssuedTarget).isAir()
                                || client.world.getBlockState(uwIssuedTarget).getMaterial().isLiquid())) {
                            System.out.println("FredoBot: UW_CONFIRMED_BREAK target="+uwIssuedTarget.toShortString());
                            uwGravelWork.broken();uwIssuedTarget=null;uwWaitTicks=2;
                        }
                        if(uwPhase==UwDoorPhase.MINE_TARGETS && uwGravelWork.collect()) {
                            resetKeys();uwPhase=UwDoorPhase.COLLECT_DROPS;pickupCoolDownTick=0;
                            System.out.println("FredoBot: UW_COLLECT mined="+uwGravelWork.count()+" flint="+uwGravelWork.hasFlint());
                        }
                        if(uwPhase==UwDoorPhase.COLLECT_DROPS && uwGravelWork.hasFlint() && targetEntity instanceof ItemEntity
                                && ((ItemEntity)targetEntity).getStack().getItem()!=Items.FLINT) {
                            if(pathExecutor.isBusy())pathExecutor.stop();targetEntity=null;
                        }
                    }
                    switch (uwPhase) {
                        case SINK_AND_CLEAR:
                            if (player.squaredDistanceTo(Vec3d.ofBottomCenter(doorPos)) > 0.65 || !player.isOnGround()) {
                                if (!pathExecutor.isBusy()) pathExecutor.setGoal(doorPos,"door pos");
                                return;
                            }
                            if (pathExecutor.isBusy()) pathExecutor.stop();
                            Block blockAtDoor = client.world.getBlockState(doorPos).getBlock();
                            if (blockAtDoor == Blocks.SEAGRASS ||
                                    blockAtDoor == Blocks.TALL_SEAGRASS ||
                                    blockAtDoor == Blocks.KELP ||
                                    blockAtDoor == Blocks.KELP_PLANT) {
                                client.interactionManager.updateBlockBreakingProgress(doorPos, Direction.UP);
                                player.swingHand(Hand.MAIN_HAND);
                                return; // 等待下一帧水草消失
                            }

                            uwPhase = UwDoorPhase.PLACE_DOOR;
                            return;

                        case PLACE_DOOR:
                            int dSlot = -1;
                            for (int i = 0; i < 36; i++) {
                                if (player.inventory.getStack(i).getItem().isIn(ItemTags.DOORS)) {
                                    dSlot = i; break;
                                }
                            }
                            if (dSlot != -1) {
                                if (!RelevantDirectionHelper.placeDoorSmartly(client, player, doorPos, dSlot)) {
                                    return; // 视角未转正，强行 return 等待下一个 tick
                                }
                                uwWaitTicks = 5; // 放置成功，给服务器一点发包反应时间
                            }
                            uwPhase = UwDoorPhase.MINE_TARGETS;
                            return;
                        case MINE_TARGETS:
                            if (uwWaitTicks > 0) { uwWaitTicks--; return; }
                            if(uwIssuedTarget!=null && (client.world.getBlockState(uwIssuedTarget).isAir()
                                    || client.world.getBlockState(uwIssuedTarget).getMaterial().isLiquid()))return;
                            if (!insideBreathingDoor(player)) {
                                if (!pathExecutor.isBusy()) {
                                    resetKeys();
                                    pathExecutor.trySetGoal(doorPos,"breathing door");
                                    System.out.println("FredoBot [水下作战]: 门已放置，正在进入氧气舱...");
                                }
                                return; // 没站进门里之前，坚决不准挥镐子！
                            }
                            if (pathExecutor.isBusy()) pathExecutor.stop();
                            resetKeys();

                            Vec3d doorEye=new Vec3d(player.getX(),player.getEyeY(),player.getZ());
                            boolean targetStillSolid=!client.world.getBlockState(targetPos).isAir()
                                    && !client.world.getBlockState(targetPos).getMaterial().isLiquid();
                            BlockHitResult miningHit=targetStillSolid?MiningAim.visibleFace(client.world,player,targetPos):null;
                            if (targetStillSolid && (doorEye.squaredDistanceTo(Vec3d.ofCenter(targetPos))>20
                                    || miningHit==null)) {
                                if(miningHit==null && doorEye.squaredDistanceTo(Vec3d.ofCenter(targetPos))<=20
                                        && !targetPos.equals(uwDoorAdjustedTarget) && client.world.getBlockState(doorPos).getBlock() instanceof DoorBlock) {
                                    uwDoorAdjustedTarget=targetPos.toImmutable();
                                    client.interactionManager.interactBlock(client.player,client.world,Hand.MAIN_HAND,
                                            new BlockHitResult(Vec3d.ofCenter(doorPos),Direction.UP,doorPos,false));
                                    uwWaitTicks=5;
                                    System.out.println("FredoBot: UW_DOOR_OCCLUDED target="+targetPos.toShortString()+" adjusting door; not counted as mined");
                                    return;
                                }
                                System.out.println("FredoBot: UW_TARGET_SKIPPED target="+targetPos.toShortString()+" reason=NO_VISIBLE_FACE not counted as mined");
                                advanceDoorTarget(client,player);return;
                            }

                            if (targetPos.equals(doorPos) || targetPos.equals(doorPos.down()) || targetPos.equals(doorPos.up())) {
                                advanceDoorTarget(client,player);return;
                            }

                            boolean isMined = client.world.getBlockState(targetPos).isAir() || client.world.getBlockState(targetPos).getMaterial().isLiquid();
                            if (!isMined) {
                                // 正常挖掘周边目标
                                registerValidDrop(client.world.getBlockState(targetPos).getBlock());

                                Vec3d aim=miningHit.getPos();
                                double aimX=aim.x-doorEye.x,aimY=aim.y-doorEye.y,aimZ=aim.z-doorEye.z;
                                yaw=(float)Math.toDegrees(Math.atan2(-aimX,aimZ));
                                pitch=(float)-Math.toDegrees(Math.atan2(aimY,Math.hypot(aimX,aimZ)));
                                MovementController.setLookDirection(player, yaw, pitch);
                                Vec3d look=player.getRotationVec(1F);
                                var actualHit=client.world.rayTrace(new RayTraceContext(doorEye,
                                        new Vec3d(doorEye.x+look.x*4.5,doorEye.y+look.y*4.5,doorEye.z+look.z*4.5),
                                        RayTraceContext.ShapeType.OUTLINE,RayTraceContext.FluidHandling.NONE,player));
                                if(actualHit.getType()!=HitResult.Type.BLOCK || !actualHit.getBlockPos().equals(targetPos))return;
                                if(uwGravelMission && !targetPos.equals(uwIssuedTarget)) {
                                    uwIssuedTarget=targetPos.toImmutable();uwGravelWork.issued();
                                }
                                ToolsHelper.equipBestTool(player, targetPos, false);
                                client.interactionManager.updateBlockBreakingProgress(targetPos, actualHit.getSide());
                                if (!player.handSwinging) player.swingHand(Hand.MAIN_HAND);
                            } else {
                                advanceDoorTarget(client,player);
                            }
                            return;

                        case COLLECT_DROPS:
                            if(uwGravelMission && uwGravelWork.hasFlint() && InventoryHelper.countItem(player,Items.FLINT)>0) {
                                if(pathExecutor.isBusy())pathExecutor.stop();targetEntity=null;
                                uwPhase=UwDoorPhase.RETURN_AND_BREAK;return;
                            }
                            // 【核心修复】：防止疯狂发包重算！
                            if (pathExecutor.isBusy()) return;
                            resetKeys();pickupCoolDownTick++;

                            Box searchBox = new Box(doorPos).expand(15.0, 10.0, 15.0);
                            List<ItemEntity> drops = client.world.getEntities(
                                    ItemEntity.class,
                                    searchBox,
                                    e -> underwaterLootEligible(e,false)
                                            && !unreachableEntities.contains(e)
                                            // 【核心修复】：直接在这里过滤，只找落地/落水/完全静止的掉落物，坚决不追半空中的东西！
                                            && (e.isOnGround() || e.isTouchingWater() || Math.abs(e.getVelocity().y) < 0.001)
                            );

                            if (!drops.isEmpty()) {
                                ItemEntity nearestDrop = drops.get(0);
                                double minDist = player.squaredDistanceTo(nearestDrop);
                                for (ItemEntity drop : drops) {
                                    double d = player.squaredDistanceTo(drop);
                                    if (d < minDist) { minDist = d; nearestDrop = drop; }
                                }
                                // 【核心删除】：将原有的 if (!nearestDrop.isTouchingWater() && !nearestDrop.isOnGround()) return; 这句话彻底删掉！

                                if (targetEntity == nearestDrop) {
                                    if (player.squaredDistanceTo(nearestDrop) > 4.0 || pickupCoolDownTick > 60) {
                                        System.out.println("FredoBot [水下]: 掉落物不可达或超时，加入黑名单放弃！");
                                        unreachableEntities.add(nearestDrop);
                                        targetEntity = null;
                                        pickupCoolDownTick = 0;
                                    }
                                    return; // 等待下一 Tick 扫其他的
                                }

                                walkTowardsEntity(player, nearestDrop);
                                pickupCoolDownTick = 0;
                            } else {
                                if (pickupCoolDownTick < 40) {
                                    if (pickupCoolDownTick % 10 == 0) System.out.println("FredoBot [调试]: 等待周边掉落物刷新中... (" + pickupCoolDownTick + "/40)");
                                    return;
                                } else {
                                    uwPhase = UwDoorPhase.RETURN_AND_BREAK;
                                }
                            }
                            return;

                        case RETURN_AND_BREAK:
                            // 地基消失后玩家会下落；必须先收尾，不能再请求回到已消失的氧气舱。
                            if (doorTeardownComplete(client)) {
                                finishDoorTeardown();return;
                            }
                            // 游回门里准备拆家
                            if (!insideBreathingDoor(player)) {
                                if (!pathExecutor.isBusy()) {
                                    resetKeys();pathExecutor.trySetGoal(doorPos,"breathing door");
                                }
                                return;
                            }
                            if (pathExecutor.isBusy()) pathExecutor.stop();
                            resetKeys();

                            // ==========================================
                            // 【核心新增】：深呼吸！等待氧气完全回满再拆门
                            // ==========================================
                            if (player.getAir() < player.getMaxAir()) {
                                // 每 10 个 tick（0.5秒）打印一次，防止控制台刷屏
                                if (player.age % 10 == 0) {
                                    System.out.println("FredoBot [水下作战]: 正在氧气舱内大口呼吸 (" + player.getAir() + "/" + player.getMaxAir() + ")...");
                                }
                                return; // 挂起当前 Tick，什么都不做，就在门里站着回氧气
                            }

                            uwPhase=UwDoorPhase.BREAK_FOUNDATION;
                            System.out.println("FredoBot: UW_BREAK_FOUNDATION air="+player.getAir()+" door="+doorPos.toShortString());
                            return;

                        case BREAK_FOUNDATION:
                            if (doorTeardownComplete(client)) {
                                finishDoorTeardown();return;
                            }
                            resetKeys();
                            BlockPos foundation = doorPos.down();
                            boolean foundationMined = client.world.getBlockState(foundation).isAir() || client.world.getBlockState(foundation).getMaterial().isLiquid();

                            // ==========================================
                            // 【终极收尾】：将地基方块和所有的门加入白名单！
                            // 保证我们接下来爆出来的地基和门能被捡起来。
                            // ==========================================
                            registerValidDrop(client.world.getBlockState(foundation).getBlock());
                            validDrops.add(Items.OAK_DOOR);
                            validDrops.add(Items.SPRUCE_DOOR);
                            validDrops.add(Items.BIRCH_DOOR);
                            validDrops.add(Items.JUNGLE_DOOR);
                            validDrops.add(Items.ACACIA_DOOR);
                            validDrops.add(Items.DARK_OAK_DOOR);
                            validDrops.add(Items.IRON_DOOR);

                            // 对地基进行致命一击，挖掉地基，门自然爆裂！
                            if (!foundationMined && client.world.getBlockState(doorPos).getBlock() instanceof DoorBlock) {
                                float[] angs = MiningHelper.getValidMiningAngle(player, foundation);
                                MovementController.setLookDirection(player, angs[0], angs[1]);
                                ToolsHelper.equipBestTool(player, foundation, false);
                                client.interactionManager.updateBlockBreakingProgress(foundation, Direction.UP);
                                if (!player.handSwinging) player.swingHand(Hand.MAIN_HAND);
                            } else {
                                finishDoorTeardown();
                            }
                            return;

                        case COLLECT_FINAL_DROPS:
                            if(targetEntity instanceof ItemEntity drop && (!drop.isAlive() || drop.getStack().isEmpty())) {
                                pathExecutor.stop();targetEntity=null;pickupCoolDownTick=0;finalRecoverySawDrop=true;
                                GlobalStateLogger.logCustomEvent("underwater_pickup_target_gone",Map.of("entity",drop.getEntityId(),"pos",player.getPos().toString()));
                            }
                            if(UnderwaterGravelWork.finalRecoveryExpired(finalRecoveryStartAge,player.age,player.getAir(),player.getMaxAir())) {
                                finishFinalRecovery(player,pathExecutor,"TIME_OR_AIR_LIMIT");return;
                            }
                            if (pathExecutor.isBusy()) return; // 防止疯狂发包
                            pickupCoolDownTick++;

                            Box finalSearchBox = new Box(doorPos).expand(8.0, 8.0, 8.0);
                             drops = client.world.getEntities(
                                    ItemEntity.class,
                                    finalSearchBox,
                                    e -> underwaterLootEligible(e,true)
                                            && !unreachableEntities.contains(e)
                                            // 【核心修复】：直接在这里过滤，只找落地/落水/完全静止的掉落物，坚决不追半空中的东西！
                                            && (e.isOnGround() || e.isTouchingWater() || Math.abs(e.getVelocity().y) < 0.001)
                            );

                            if (!drops.isEmpty()) {
                                finalRecoverySawDrop=true;
                                ItemEntity nearestDrop = drops.get(0);
                                double minDist = player.squaredDistanceTo(nearestDrop);
                                for (ItemEntity drop : drops) {
                                    double d = player.squaredDistanceTo(drop);
                                    if (d < minDist) { minDist = d; nearestDrop = drop; }
                                }
                                // 【核心删除】：将原有的 if (!nearestDrop.isTouchingWater() && !nearestDrop.isOnGround()) return; 这句话彻底删掉！

                                if (targetEntity == nearestDrop) {
                                    if (player.squaredDistanceTo(nearestDrop) > 4.0 || pickupCoolDownTick > 60) {
                                        unreachableEntities.add(nearestDrop);
                                        targetEntity = null;
                                        pickupCoolDownTick = 0;
                                    }
                                    return;
                                }

                                walkTowardsEntity(player, nearestDrop);
                                pickupCoolDownTick = 0;
                            } else {
                                if (pickupCoolDownTick < UnderwaterGravelWork.finalRecoveryQuietTicks(finalRecoverySawDrop)) {
                                    return;
                                } else {
                                    finishFinalRecovery(player,pathExecutor,"NO_MORE_ELIGIBLE_DROPS");
                                }
                            }
                            return;

                    }
                }

                // =================================================================
                // 3. 陆地普通挖掘逻辑 (严格的 挖掘->清扫->拾取->下一地点 强状态机)
                // =================================================================
                if(pickupCoolDownTick==0 && targetEntity==null && !pathExecutor.isBusy() && player.isOnGround()
                        && localStoneAccess==null && Math.abs(player.getVelocity().y)<=.1 && client.world.getBlockState(targetPos).getBlock()==Blocks.STONE){
                    BlockPos nearby=MiningHelper.findNearbyStone(player);
                    if(nearby!=null && nearby.getY()>=Math.min(targetPos.getY(),player.getBlockPos().getY()) && !nearby.equals(targetPos)){
                        BlockPos previous=targetPos;
                        if(MiningHelper.blockToMine.remove(nearby) && !MiningHelper.blockToMine.contains(previous))
                            MiningHelper.blockToMine.add(previous.toImmutable());
                        targetPos=nearby;resetMiningApproachFailures();pathingFailTicks=0;resetKeys();
                        client.interactionManager.cancelBlockBreaking();
                        GlobalStateLogger.logCustomEvent("stone_local_target",Map.of("previous",previous.toShortString(),
                                "target",nearby.toShortString(),"pos",player.getBlockPos().toShortString(),
                                "remaining_targets",MiningHelper.blockToMine.size()+1));
                    }
                }
                if(pickupCoolDownTick==0 && targetEntity==null
                        && handleNearbyBuriedStone(client,player,pathExecutor))return;
                if(uwGravelMission && handleDryGravel(client,player,pathExecutor))return;
                if(client.world.getBlockState(targetPos).getBlock()==Blocks.GRAVEL && pickupCoolDownTick==0 && targetEntity==null
                        && handleBuriedGravel(client,player,pathExecutor))return;
                boolean isBlockMined = !MiningHelper.gravelTargetAllowed(targetPos) || !MiningHelper.logTargetAllowed(targetPos) || client.world.getBlockState(targetPos).isAir() ||
                        client.world.getBlockState(targetPos).getMaterial().isLiquid();

                // 核心状态控制：只要正在收集掉落物（pickupCoolDownTick > 0），就绝对不准被新进入范围的方块打断！
                boolean isCollectingDrops = targetEntity != null || pickupCoolDownTick > 0;

                if (!isBlockMined && !isCollectingDrops) {
                    // --- 阶段 1 & 2：走向目标并站桩连挖 ---
                    registerValidDrop(client.world.getBlockState(targetPos).getBlock());
                    Vec3d eyePos = new Vec3d(player.getX(), player.getEyeY(), player.getZ());
                    double distSq = eyePos.squaredDistanceTo(Vec3d.ofCenter(targetPos));

                    if (distSq > 20.0 || ((client.world.getBlockState(targetPos).getBlock()==Blocks.STONE
                            || client.world.getBlockState(targetPos).getBlock()==Blocks.GRAVEL
                            || client.world.getBlockState(targetPos).isIn(BlockTags.LOGS))
                            && !miningVisible(client,player,eyePos,targetPos))) {
                        long failureSerial=pathExecutor.getSearchFailureSerial();
                        if(failureSerial!=observedSearchFailureSerial) {
                            observedSearchFailureSerial=failureSerial;miningSearchFailures++;
                            if(lastMiningApproach!=null)failedMiningApproaches.add(lastMiningApproach);
                        }
                        boolean failedLog=client.world.getBlockState(targetPos).isIn(BlockTags.LOGS)
                                && lastMiningApproach!=null && !pathExecutor.isBusy();
                        if(failedLog) {
                            MiningHelper.blacklistLogTarget(targetPos);
                            pathingFailTicks=301;
                        }
                        // 阶段 1：距离太远，寻路靠近
                        if (!pathExecutor.isBusy() && player.isOnGround() && miningSearchFailures<=6 && !failedLog) {
                            BlockPos topPos = new BlockPos(targetPos.getX(), BtStuff.groundYNoLeavesOrTrees(targetPos)+1, targetPos.getZ());
                            double horizDistSq = Math.pow(player.getX() - (targetPos.getX() + 0.5), 2) + Math.pow(player.getZ() - (targetPos.getZ() + 0.5), 2);

                            boolean leafTarget=client.world.getBlockState(targetPos).isIn(BlockTags.LEAVES);
                            BlockPos approach=findMiningApproach(client,player,targetPos,failedMiningApproaches,leafTarget);
                            boolean buried=targetPos.getY()<topPos.getY()-3;
                            // A covered stone is still a valid material target. Let the planner clear the
                            // required approach blocks instead of rejecting shallow buried stone.
                            if (approach == null && client.world.getBlockState(targetPos).getBlock() == Blocks.STONE)
                                approach = targetPos;
                            if(approach==null && !leafTarget && buried)approach=horizDistSq>16?topPos:targetPos;
                            if(approach==null && !leafTarget && InventoryHelper.countAvailableBuildingBlocks(player).pillaringBlocks>0)approach=targetPos;
                            if(approach==null) {
                                System.out.println("FredoBot: 没有可直接挖掘的站位，跳过当前采集目标。");
                                pathingFailTicks=301;
                            } else if(pathExecutor.trySetGoal(approach,client.world.getBlockState(targetPos).isIn(BlockTags.LOGS)?"log mining approach":"mining approach")) {
                                lastMiningApproach=approach;
                                pathExecutor.recordMiningTarget(targetPos,approach);
                                System.out.println("FredoBot: 寻路至挖掘站位 "+approach.toShortString()+"，方块目标 "+targetPos.toShortString());
                            }
                        }

                        if (pathingFailTicks > 300 || miningSearchFailures>6) {
                            System.out.println("FredoBot: 远距离寻路多次受阻，果断跳过该节点！");
                            if (MiningHelper.blockToMine != null && !MiningHelper.blockToMine.isEmpty()) {
                                targetPos = MiningHelper.blockToMine.remove(0);
                                resetMiningApproachFailures();
                                pathingFailTicks = 0;
                                miningSearchFailures=0;failedMiningApproaches.clear();lastMiningApproach=null;
                                if (pathExecutor.isBusy()) pathExecutor.stop();
                            } else {
                                targetPos = player.getBlockPos(); // 强制进入打扫战场阶段
                                pickupCoolDownTick = 1;
                            }
                        }
                        return;
                    }

                    // 阶段 2：在挖掘范围内，站桩抡镐子，坚决不捡东西
                    if (pathExecutor.isBusy() && player.isOnGround()) {
                        pathExecutor.stop(); // 停下脚步，立定开挖
                    }

                    float[] angles = MiningHelper.getValidMiningAngle(player, targetPos);
                    yaw = angles[0];
                    pitch = angles[1];
                    MovementController.setLookDirection(player, yaw, pitch);
                    ToolsHelper.equipBestTool(player, targetPos, false);

                    if(dryGravelWork!=null && client.world.getBlockState(targetPos).getBlock()==Blocks.GRAVEL
                            && !targetPos.equals(dryIssuedGravel)){dryIssuedGravel=targetPos.toImmutable();dryGravelWork.issued();}
                    client.interactionManager.updateBlockBreakingProgress(targetPos, Direction.UP);
                    if (!player.handSwinging) {
                        player.swingHand(Hand.MAIN_HAND);
                    }

                    pathingFailTicks++;
                    if (pathingFailTicks > 300) {
                        System.out.println("FredoBot: 近战挖掘耗时过长，放弃当前挖掘！");
                        if (MiningHelper.blockToMine != null && !MiningHelper.blockToMine.isEmpty()) {
                            targetPos = MiningHelper.blockToMine.remove(0);
                            resetMiningApproachFailures();
                            pathingFailTicks = 0;
                        } else {
                            targetPos = player.getBlockPos();
                            pickupCoolDownTick = 1; // 强制进入打扫战场
                        }
                    }

                } else {
                    // --- 阶段 3 & 4：收集掉落物与切换大区域 ---

                    if (!isCollectingDrops) {
                        // 刚刚挖掉了一个方块！第一优先级：扫描原地还能不能挖到其他的
                        BlockPos nextReachable = null;
                        if (MiningHelper.blockToMine != null && !MiningHelper.blockToMine.isEmpty()) {
                            Vec3d eyePos = new Vec3d(player.getX(), player.getEyeY(), player.getZ());
                            for (int i = 0; i < MiningHelper.blockToMine.size(); i++) {
                                BlockPos candidate = MiningHelper.blockToMine.get(i);
                                boolean candidateMined = client.world.getBlockState(candidate).isAir() ||
                                        client.world.getBlockState(candidate).getMaterial().isLiquid();

                                if (candidateMined) {
                                    MiningHelper.blockToMine.remove(i);
                                    i--;
                                    continue;
                                }

                                // 站桩清扫：发现能够得着的目标
                                if (eyePos.squaredDistanceTo(Vec3d.ofCenter(candidate)) <= 20.0) {
                                    nextReachable = candidate;
                                    MiningHelper.blockToMine.remove(i);
                                    break;
                                }
                            }
                        }

                        if (nextReachable != null) {
                            // 原地有目标，无缝衔接挖掘，绝不进入掉落物收集状态！
                            targetPos = nextReachable;
                            resetMiningApproachFailures();
                            pathingFailTicks = 0;
                            return;
                        } else {
                            // 够得着的全挖完了，或配额已满，正式锁定为掉落物收集阶段
                            pickupCoolDownTick = 1;
                            pressAttack = false;
                            pressForward = false;
                            pressJump = false;
                            if (pathExecutor.isBusy()) {
                                pathExecutor.stop();
                            }
                        }
                    }

                    // 阶段 3：真正的集中收集掉落物 (已锁定拾取模式)
                    if(leafLogDrop!=null) {
                        if(leafLogDrop.isAlive() && !leafLogDrop.getStack().isEmpty()) {
                            if(releaseLeafLog(client,player,pathExecutor,leafLogDrop))return;
                        } else if(pathExecutor.isBusy())pathExecutor.stop();
                        if(targetEntity==leafLogDrop)targetEntity=null;
                        leafLogDrop=null;lastLeafApproach=null;failedLeafApproaches.clear();
                    }
                    if (pathExecutor.isBusy()) {
                        return; // 如果在走向掉落物的路上，耐心等待，绝不被挖矿逻辑打断
                    }

                    pickupCoolDownTick++;
                    if (pickupCoolDownTick <= 5) return; // 刚结束挖掘，给天上飞的掉落物一点落地的时间

                    Box searchBox = new Box(player.getBlockPos()).expand(15.0, 10.0, 15.0);
                    List<ItemEntity> drops = client.world.getEntities(
                            ItemEntity.class,
                            searchBox,
                            e -> (validDrops.isEmpty() || validDrops.contains(e.getStack().getItem()))
                                    && !ItemCategory.isSapling(e.getStack().getItem())
                                    && !unreachableEntities.contains(e)
                                    // 【核心防空中截停】：只找落地/落水/完全静止的掉落物
                                    && (e.isOnGround() || e.isTouchingWater() || Math.abs(e.getVelocity().y) < 0.001)
                    );

                    if (!drops.isEmpty()) {
                        ItemEntity nearestDrop=pickupOrder.next(player,drops);

                        if (nearestDrop != null) {
                            Item loot=nearestDrop.getStack().getItem();
                            if(LeafLootSupport.shouldRelease(loot.isIn(ItemTags.LOGS),loot.isIn(ItemTags.LEAVES),
                                    leafSupport(client,nearestDrop)!=null)) {
                                leafLogDrop=nearestDrop;targetEntity=nearestDrop;
                                leafSettleTicks=0;
                                leafFailureSerial=pathExecutor.getSearchFailureSerial();leafApproachFailures=0;lastLeafApproach=null;failedLeafApproaches.clear();
                                releaseLeafLog(client,player,pathExecutor,nearestDrop);return;
                            }
                            if (targetEntity == nearestDrop) {
                                // 尝试去捡过，但失败了
                                if (player.squaredDistanceTo(nearestDrop) > 4.0 || pickupCoolDownTick > 60) {
                                    System.out.println("FredoBot [陆地]: 掉落物不可达或超时，加入黑名单果断放弃！");
                                    unreachableEntities.add(nearestDrop);
                                    targetEntity = null;
                                    pickupCoolDownTick = 5; // 回退tick，立刻扫下一个掉落物
                                }
                                return;
                            }

                            // 锁定地上的掉落物，下发寻路指令
                            walkTowardsEntity(player, nearestDrop);
                            pickupCoolDownTick = 5; // 重置超时判定
                            return;
                        }
                    } else {
                        // 区域内地上的掉落物彻底捡完了
                        if (pickupCoolDownTick < 20) {
                            return; // 再等会儿，说不定天上的刚好掉下来
                        } else {
                            if(treeHarvest!=null) {
                                treeHarvest.collected();currentTask=MiscType.HARVEST_TREE;
                                targetEntity=null;leafLogDrop=null;pickupCoolDownTick=0;resetKeys();return;
                            }
                            // 阶段 4：打扫完毕，彻底退出拾取模式，前往下一片区域
                            if (MiningHelper.blockToMine != null && !MiningHelper.blockToMine.isEmpty()) {
                                targetPos = MiningHelper.blockToMine.remove(0);
                                resetMiningApproachFailures();
                                System.out.println("FredoBot [陆地]: 当前驻点已清空并打扫完毕，前往下一片目标区域！ -> " + targetPos.toShortString());
                                pickupCoolDownTick = 0; // 【重置标志位】：解除拾取状态锁，回到挖掘模式
                                pathingFailTicks = 0;
                                targetEntity = null;
                                return;
                            } else {
                                // 队列彻底空了
                                System.out.println("FredoBot: 挖掘队列与掉落物处理完毕；材料是否达标由后续任务复核。");
                                if(dryGravelWork!=null)GlobalStateLogger.logCustomEvent("dry_gravel_finished",Map.of(
                                        "confirmed_mined",dryGravelWork.count(),"inventory_gravel",InventoryHelper.countItem(player,Items.GRAVEL),
                                        "inventory_flint",InventoryHelper.countItem(player,Items.FLINT)));
                                pickupCoolDownTick = 0;
                                pathingFailTicks = 0;
                                targetEntity = null;
                                boolean exiting=dryGravelWork!=null && PreNether.startWarmGravelExit(player,dryGravelExitSurface);
                                if(!exiting)pathExecutor.resumeSuspendedGoal();
                                stopTask();
                            }
                        }
                    }
                }
                break;
        }

        applyKeys(client);
    }

    /** Continue the exposed vein from the real shaft, instead of insisting on four preselected columns. */
    private boolean handleDryGravel(MinecraftClient client,PlayerEntity player,PathExecutor path){
        if(dryGravelWork==null){
            dryGravelWork=new UnderwaterGravelWork();dryRequiredGravel=Math.max(0,4-InventoryHelper.countItem(player,Items.GRAVEL));
        }
        double surfaceDx=player.getX()-Vec3d.ofCenter(targetPos).x,surfaceDz=player.getZ()-Vec3d.ofCenter(targetPos).z;
        if(dryGravelExitSurface==null && player.isOnGround() && surfaceDx*surfaceDx+surfaceDz*surfaceDz<=4)
            dryGravelExitSurface=player.getBlockPos().toImmutable();
        if(dryIssuedGravel!=null && client.world.getBlockState(dryIssuedGravel).getBlock()!=Blocks.GRAVEL){
            resetKeys();client.interactionManager.cancelBlockBreaking();
            if(!dryGravelWork.confirmRemoval(true))return true;
            dryGravelWork.broken();
            GlobalStateLogger.logCustomEvent("dry_gravel_mined",Map.of("block",dryIssuedGravel.toShortString(),
                    "confirmed_mined",dryGravelWork.count(),"pos",player.getBlockPos().toShortString()));
            dryIssuedGravel=null;
        } else if(dryIssuedGravel!=null)dryGravelWork.confirmRemoval(false);
        boolean flint=InventoryHelper.countItem(player,Items.FLINT)>0 || InventoryHelper.countItem(player,Items.FLINT_AND_STEEL)>0
                || !client.world.getEntities(ItemEntity.class,new Box(player.getBlockPos()).expand(6),
                    e->e.isAlive() && e.getStack().getItem()==Items.FLINT).isEmpty();
        dryGravelWork.observeFlint(flint);
        if(dryGravelWork.hasFlint() || dryGravelWork.count()>=dryRequiredGravel || InventoryHelper.countItem(player,Items.GRAVEL)>=4){
            MiningHelper.blockToMine.clear();
            if(dryGravelWork.hasFlint()){validDrops.clear();validDrops.add(Items.FLINT);}
            if(pickupCoolDownTick==0 && targetEntity==null){
                path.stop();resetKeys();pickupCoolDownTick=1;
                GlobalStateLogger.logCustomEvent("dry_gravel_collect",Map.of("confirmed_mined",dryGravelWork.count(),"flint",flint));
            }
            return false;
        }
        if(pickupCoolDownTick>0 || targetEntity!=null || path.isBusy() || buriedGravel!=null)return false;
        // Underfoot digging must finish falling before selecting another block or rejecting its access.
        if(!player.isOnGround() || Math.abs(player.getVelocity().y)>.1){resetKeys();return true;}
        Vec3d eye=new Vec3d(player.getX(),player.getEyeY(),player.getZ());
        BlockPos nearest=null;double best=Double.POSITIVE_INFINITY;
        for(BlockPos p:BlockPos.iterate(player.getBlockPos().add(-4,-4,-4),player.getBlockPos().add(4,3,4))){
            if(!MiningHelper.gravelTargetAllowed(p) || client.world.getBlockState(p).getBlock()!=Blocks.GRAVEL)continue;
            double d=eye.squaredDistanceTo(Vec3d.ofCenter(p));
            if(d<=20 && d<best && miningVisible(client,player,eye,p)){nearest=p.toImmutable();best=d;}
        }
        if(nearest!=null && !nearest.equals(targetPos)){
            BlockPos previous=targetPos;
            if(client.world.getBlockState(previous).getBlock()==Blocks.GRAVEL && MiningHelper.gravelTargetAllowed(previous)
                    && !MiningHelper.blockToMine.contains(previous))MiningHelper.blockToMine.add(previous.toImmutable());
            MiningHelper.blockToMine.remove(nearest);targetPos=nearest;resetMiningApproachFailures();pathingFailTicks=0;
            GlobalStateLogger.logCustomEvent("dry_gravel_local_target",Map.of("previous",previous.toShortString(),
                    "target",nearest.toShortString(),"confirmed_mined",dryGravelWork.count()));
        }
        return false;
    }

    private boolean dryClear(MinecraftClient client,BlockPos p){
        return client.world.getChunkManager().isChunkLoaded(p.getX()>>4,p.getZ()>>4)
                && client.world.getBlockState(p).getFluidState().isEmpty()
                && client.world.getBlockState(p).getCollisionShape(client.world,p).isEmpty();
    }
    private boolean safeGravelCover(MinecraftClient client,BlockPos p){
        if(!client.world.getChunkManager().isChunkLoaded(p.getX()>>4,p.getZ()>>4))return false;
        BlockState s=client.world.getBlockState(p);
        if(s.getHardness(client.world,p)<0 || client.world.getBlockEntity(p)!=null || !s.getFluidState().isEmpty()
                || s.getBlock()==Blocks.CRAFTING_TABLE || s.getBlock() instanceof FallingBlock || s.getBlock() instanceof IceBlock || s.getBlock()==Blocks.MAGMA_BLOCK || s.isIn(BlockTags.LOGS) || s.isIn(BlockTags.LEAVES)
                || !Block.isShapeFullCube(s.getCollisionShape(client.world,p)))return false;
        for(Direction d:Direction.values()){
            BlockPos n=p.offset(d);
            if(!client.world.getChunkManager().isChunkLoaded(n.getX()>>4,n.getZ()>>4)
                    || !client.world.getBlockState(n).getFluidState().isEmpty())return false;
        }
        return true;
    }
    private void failBuriedGravel(MinecraftClient client,PathExecutor path,String reason){
        GlobalStateLogger.logCustomEvent("buried_gravel_access_failed",Map.of("target",targetPos.toShortString(),"reason",reason));
        MiningHelper.blacklistGravelTarget(targetPos);path.stop();resetKeys();resetBuriedGravel();pickupCoolDownTick=1;
    }
    /** Dry covered gravel is reached through an actual confirmed shaft, never a feet goal inside gravel. */
    private boolean handleBuriedGravel(MinecraftClient client,PlayerEntity player,PathExecutor path){
        if(!MiningHelper.gravelTargetAllowed(targetPos))return false;
        Vec3d eye=new Vec3d(player.getX(),player.getEyeY(),player.getZ());
        if(eye.squaredDistanceTo(Vec3d.ofCenter(targetPos))<=20 && miningVisible(client,player,eye,targetPos)){
            if(buriedGravel!=null){
                resetKeys();if(!player.isOnGround() || Math.abs(player.getVelocity().y)>.1)return true;
                if(buriedCover!=null && dryClear(client,buriedCover))GlobalStateLogger.logCustomEvent("buried_gravel_cover_confirmed",
                        Map.of("block",buriedCover.toShortString(),"target",targetPos.toShortString()));
                path.stop();resetBuriedGravel();
            }
            return false;
        }
        if(buriedGravel==null){
            if(dryClear(client,targetPos.up()) || !client.world.getBlockState(targetPos.up()).getFluidState().isEmpty())return false;
            if(findMiningApproach(client,player,targetPos,failedMiningApproaches)!=null)return false;
            buriedGravel=BuriedMiningAccess.find(targetPos,16,p->dryClear(client,p),p->safeGravelCover(client,p));
            if(buriedGravel==null){failBuriedGravel(client,path,"NO_SAFE_DRY_COVER_COLUMN");return true;}
            dryGravelExitSurface=buriedGravel.entry().toImmutable();
            resetKeys();buriedProgressAge=player.age;
            GlobalStateLogger.logCustomEvent("buried_gravel_access_planned",Map.of("target",targetPos.toShortString(),
                    "entry",buriedGravel.entry().toShortString(),"cover_blocks",buriedGravel.entry().getY()-targetPos.getY()-1));
        }
        if(path.isBusy())return true;
        double dx=player.getX()-buriedGravel.entry().getX()-.5,dz=player.getZ()-buriedGravel.entry().getZ()-.5;
        if(!buriedDescending){
            if(dx*dx+dz*dz<=.25 && Math.abs(player.getY()-buriedGravel.entry().getY())<.5 && player.isOnGround()){buriedDescending=true;buriedProgressAge=player.age;}
            else{
                if(buriedEntryRequested){failBuriedGravel(client,path,"ENTRY_ROUTE_DID_NOT_ARRIVE");return true;}
                if(path.trySetGoal(buriedGravel.entry(),"buried gravel entry"))buriedEntryRequested=true;
                return true;
            }
        }
        if(player.isTouchingWater() || player.age-buriedProgressAge>600){
            failBuriedGravel(client,path,player.isTouchingWater()?"ACCESS_FLOODED":"DESCENT_NO_PROGRESS");return true;
        }
        if(dx*dx+dz*dz>1 || player.getY()<targetPos.getY()){
            failBuriedGravel(client,path,"LEFT_ACCESS_COLUMN");return true;
        }
        resetKeys();player.setSprinting(false);
        if(!player.isOnGround() || player.isTouchingWater() || Math.abs(player.getVelocity().y)>.1)return true;
        if(dx*dx+dz*dz>.0225){
            if(++buriedCenterTicks>100){failBuriedGravel(client,path,"COLUMN_CENTER_TIMEOUT");return true;}
            yaw=BuriedMiningAccess.centerYaw(dx,dz);pitch=70;
            MovementController.setLookDirection(player,yaw,pitch);pressForward=Math.abs(MathHelper.wrapDegrees(player.yaw-yaw))<8;pressSneak=true;applyKeys(client);return true;
        }
        buriedCenterTicks=0;
        if(Math.hypot(player.getVelocity().x,player.getVelocity().z)>.035)return true;
        if(buriedCover!=null && dryClear(client,buriedCover)){
            GlobalStateLogger.logCustomEvent("buried_gravel_cover_confirmed",Map.of("block",buriedCover.toShortString(),"target",targetPos.toShortString()));
            buriedCover=null;buriedProgressAge=player.age;client.interactionManager.cancelBlockBreaking();
        }
        BlockPos cover=BuriedMiningAccess.nextCover(buriedGravel,p->dryClear(client,p));
        if(cover==null){failBuriedGravel(client,path,"TARGET_NOT_VISIBLE_AFTER_OPENING_COLUMN");return true;}
        // Removing one floor may not be followed by digging a second floor before actually landing.
        if(!BuriedMiningAccess.readyForCover(cover,player.getBlockPos(),player.isOnGround(),player.getVelocity().y))return true;
        if(!safeGravelCover(client,cover)){failBuriedGravel(client,path,"COVER_CHANGED_OR_UNSAFE");return true;}
        BlockHitResult hit=MiningAim.visibleFace(client.world,player,cover);
        if(hit==null){failBuriedGravel(client,path,"COVER_NOT_REACHABLE");return true;}
        if(!cover.equals(buriedCover)){
            buriedCover=cover;buriedCoverAge=player.age;
            GlobalStateLogger.logCustomEvent("buried_gravel_cover_started",Map.of("block",cover.toShortString(),"target",targetPos.toShortString()));
        }
        if(player.age-buriedCoverAge>400){failBuriedGravel(client,path,"COVER_BREAK_TIMEOUT");return true;}
        float[] aim=MiningHelper.getValidMiningAngle(player,cover);
        MovementController.setLookDirection(player,aim[0],aim[1]);ToolsHelper.equipBestTool(player,cover,false);
        client.interactionManager.updateBlockBreakingProgress(cover,hit.getSide());
        if(!player.handSwinging)player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    /** Open a shallow dry cover directly below the player instead of searching for a remote exposed face. */
    private boolean handleNearbyBuriedStone(MinecraftClient client,PlayerEntity player,PathExecutor path){
        if(localStoneAccess!=null && !localStoneAccess.target().equals(targetPos))resetLocalStoneAccess();
        if(client.world.getBlockState(targetPos).getBlock()!=Blocks.STONE){resetLocalStoneAccess();return false;}
        Vec3d eye=new Vec3d(player.getX(),player.getEyeY(),player.getZ());
        boolean visible=eye.squaredDistanceTo(Vec3d.ofCenter(targetPos))<=20 && miningVisible(client,player,eye,targetPos);
        if(localStoneAccess==null){
            if(visible || path.isBusy() || targetPos.equals(failedLocalStone) || player.isTouchingWater())return false;
            BlockPos feet=player.getBlockPos();
            if(feet.getX()!=targetPos.getX() || feet.getZ()!=targetPos.getZ()
                    || feet.getY()<=targetPos.getY() || feet.getY()-targetPos.getY()>6)return false;
            localStoneAccess=BuriedMiningAccess.find(targetPos,6,p->dryClear(client,p),p->safeGravelCover(client,p));
            if(localStoneAccess==null)return false;
            GlobalStateLogger.logCustomEvent("stone_cover_access",Map.of("target",targetPos.toShortString(),
                    "entry",localStoneAccess.entry().toShortString(),"pos",player.getPos().toString()));
        }
        resetKeys();player.setSprinting(false);
        if(player.isTouchingWater() || !safeGravelCover(client,targetPos)){
            failedLocalStone=targetPos.toImmutable();resetLocalStoneAccess();return false;
        }
        if(!player.isOnGround() || Math.abs(player.getVelocity().y)>.1)return true;
        if(dryClear(client,player.getBlockPos().down()))return true;
        if(visible){
            if(localStoneCover!=null && dryClear(client,localStoneCover))GlobalStateLogger.logCustomEvent("stone_cover_removed",Map.of("block",localStoneCover.toShortString()));
            resetLocalStoneAccess();client.interactionManager.cancelBlockBreaking();return false;
        }
        double dx=player.getX()-targetPos.getX()-.5,dz=player.getZ()-targetPos.getZ()-.5;
        if(dx*dx+dz*dz>1){failedLocalStone=targetPos.toImmutable();resetLocalStoneAccess();return false;}
        if(dx*dx+dz*dz>.0225){
            yaw=BuriedMiningAccess.centerYaw(dx,dz);pitch=70;
            MovementController.setLookDirection(player,yaw,pitch);pressForward=true;pressSneak=true;applyKeys(client);return true;
        }
        if(Math.hypot(player.getVelocity().x,player.getVelocity().z)>.035)return true;
        BlockPos cover=BuriedMiningAccess.nextCover(localStoneAccess,p->dryClear(client,p));
        if(cover==null){failedLocalStone=targetPos.toImmutable();resetLocalStoneAccess();return false;}
        if(!BuriedMiningAccess.readyForCover(cover,player.getBlockPos(),player.isOnGround(),player.getVelocity().y))return true;
        BlockHitResult hit=MiningAim.visibleFace(client.world,player,cover);
        if(!safeGravelCover(client,cover) || hit==null){failedLocalStone=targetPos.toImmutable();resetLocalStoneAccess();return false;}
        if(!cover.equals(localStoneCover)){
            if(localStoneCover!=null)GlobalStateLogger.logCustomEvent("stone_cover_removed",Map.of("block",localStoneCover.toShortString()));
            localStoneCover=cover.toImmutable();localStoneCoverAge=player.age;client.interactionManager.cancelBlockBreaking();
            GlobalStateLogger.logCustomEvent("stone_cover_mining",Map.of("block",cover.toShortString(),"target",targetPos.toShortString()));
        }
        if(player.age-localStoneCoverAge>400){failedLocalStone=targetPos.toImmutable();resetLocalStoneAccess();return false;}
        float[] aim=MiningHelper.getValidMiningAngle(player,cover);
        MovementController.setLookDirection(player,aim[0],aim[1]);ToolsHelper.equipBestTool(player,cover,false);
        client.interactionManager.updateBlockBreakingProgress(cover,hit.getSide());
        if(!player.handSwinging)player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    public void prepareMineBlockAboveHead(PlayerEntity player, BlockPos extraPos) {
        float[] angles = MiningHelper.getValidMiningAngle(player, extraPos);
        yaw = angles[0];
        pitch = angles[1];
        ToolsHelper.equipBestTool(player, extraPos, false);
        MovementController.setLookDirection(player, yaw, pitch);
    }

    private void applyKeys(MinecraftClient client) {
        if (client.options == null) return;
        KeyBinding.setKeyPressed(client.options.keyBack.getDefaultKey(), pressBack);
        KeyBinding.setKeyPressed(client.options.keyRight.getDefaultKey(), pressRight);
        KeyBinding.setKeyPressed(client.options.keyLeft.getDefaultKey(), pressLeft);
        KeyBinding.setKeyPressed(client.options.keyForward.getDefaultKey(), pressForward);
        KeyBinding.setKeyPressed(client.options.keyJump.getDefaultKey(), pressJump);
        KeyBinding.setKeyPressed(client.options.keyUse.getDefaultKey(), pressUse);
        KeyBinding.setKeyPressed(client.options.keySneak.getDefaultKey(), pressSneak);
        KeyBinding.setKeyPressed(client.options.keyAttack.getDefaultKey(), pressAttack);
        KeyBinding.setKeyPressed(client.options.keySprint.getDefaultKey(), pressSprint);
    }

    public void resetKeys() {
        this.pressBack = false;
        this.pressRight = false;
        this.pressLeft = false;
        this.pressForward = false;
        this.pressJump = false;
        this.pressUse = false;
        this.pressSneak = false;
        this.pressAttack = false;
        this.pressSprint = false;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.options != null) {
            KeyBinding.setKeyPressed(client.options.keyBack.getDefaultKey(), false);
            KeyBinding.setKeyPressed(client.options.keyRight.getDefaultKey(), false);
            KeyBinding.setKeyPressed(client.options.keyLeft.getDefaultKey(), false);
            KeyBinding.setKeyPressed(client.options.keyForward.getDefaultKey(), false);
            KeyBinding.setKeyPressed(client.options.keyJump.getDefaultKey(), false);
            KeyBinding.setKeyPressed(client.options.keyUse.getDefaultKey(), false);
            KeyBinding.setKeyPressed(client.options.keySneak.getDefaultKey(), false);
            KeyBinding.setKeyPressed(client.options.keyAttack.getDefaultKey(), false);
            KeyBinding.setKeyPressed(client.options.keySprint.getDefaultKey(), false);
        }
    }

    private void walkTowardsEntity(PlayerEntity player, Entity entity) {
        PathExecutor pathExecutor = BotEngine.getInstance().getModule(PathExecutor.class);
        targetEntity = entity;
        BlockPos entityBlockPos = entity.getBlockPos();
        System.out.println("Fredodebug: tempGoal is Entity: "+ entity.toString());
        pathExecutor.setTemporaryGoal(new BlockPos(entityBlockPos.getX(), entityBlockPos.getY() + 0.5D, entityBlockPos.getZ()), PathExecutor.TempMissionType.GO_TO_ENTITY);
    }
    private boolean doorTeardownComplete(MinecraftClient client) {
        BlockState foundation=client.world.getBlockState(doorPos.down());
        return UnderwaterGravelWork.teardownComplete(
                !foundation.isAir() && !foundation.getMaterial().isLiquid(),
                client.world.getBlockState(doorPos).getBlock() instanceof DoorBlock);
    }

    private void finishDoorTeardown() {
        PathExecutor path=BotEngine.getInstance().getModule(PathExecutor.class);
        if(path.isBusy())path.stop();
        resetKeys();targetEntity=null;pickupCoolDownTick=0;
        finalRecoveryStartAge=MinecraftClient.getInstance().player.age;finalRecoverySawDrop=false;
        uwPhase=UwDoorPhase.COLLECT_FINAL_DROPS;
        System.out.println("FredoBot: UW_COLLECT_FINAL_DROPS door="+doorPos.toShortString()+" teardown complete; no return-to-door request");
    }

    private void finishFinalRecovery(PlayerEntity player,PathExecutor path,String reason) {
        GlobalStateLogger.logCustomEvent("underwater_final_recovery_finished",Map.of("reason",reason,
                "pos",player.getPos().toString(),"air",player.getAir(),"elapsed_ticks",Math.max(0,player.age-finalRecoveryStartAge),
                "gravel",InventoryHelper.countItem(player,Items.GRAVEL),"flint",InventoryHelper.countItem(player,Items.FLINT)));
        path.stop();
        BlockPos returnMagma=PreNether.magmaPos;
        BlockPos departedDoor=doorPos==null?null:doorPos.toImmutable();
        stopTask();
        if(returnMagma!=null)PreNether.requestMagmaRoute(player,path,departedDoor);
        else path.resumeSuspendedGoal();
    }

    private boolean insideBreathingDoor(PlayerEntity player) {
        return UnderwaterGravelWork.inside(
                player.getX()-doorPos.getX()-.5,player.getY()-doorPos.getY(),player.getZ()-doorPos.getZ()-.5,
                player.isOnGround(),player.isSubmergedIn(FluidTags.WATER));
    }
    private boolean underwaterLootEligible(ItemEntity drop,boolean finalRecovery) {
        if(!drop.isAlive() || drop.getStack().isEmpty())return false;
        Item item=drop.getStack().getItem();
        if(finalRecovery && item.isIn(ItemTags.DOORS))return true;
        if(uwGravelMission) {
            if(finalRecovery && uwGravelWork.hasFlint())return item==Items.FLINT;
            return uwGravelWork.wants(item==Items.GRAVEL,item==Items.FLINT);
        }
        return validDrops.isEmpty() || validDrops.contains(item);
    }
    private void advanceDoorTarget(MinecraftClient client,PlayerEntity player) {
        Vec3d eye=new Vec3d(player.getX(),player.getEyeY(),player.getZ());
        if(!uwGravelMission || !uwGravelWork.collect()) {
            for(int i=0;i<MiningHelper.blockToMine.size();i++) {
                BlockPos candidate=MiningHelper.blockToMine.get(i);
                if(candidate.equals(doorPos.down()) || candidate.equals(doorPos) || candidate.equals(doorPos.up()))continue;
                if(uwGravelMission && client.world.getBlockState(candidate).getBlock()!=Blocks.GRAVEL)continue;
                if(eye.squaredDistanceTo(Vec3d.ofCenter(candidate))>20 || MiningAim.visibleFace(client.world,player,candidate)==null)continue;
                targetPos=MiningHelper.blockToMine.remove(i);resetMiningApproachFailures();return;
            }
            // Give a reachable but occluded candidate one door adjustment before abandoning it.
            for(int i=0;i<MiningHelper.blockToMine.size();i++) {
                BlockPos candidate=MiningHelper.blockToMine.get(i);
                if(candidate.equals(doorPos.down()) || candidate.equals(doorPos) || candidate.equals(doorPos.up()))continue;
                if(uwGravelMission && client.world.getBlockState(candidate).getBlock()!=Blocks.GRAVEL)continue;
                if(eye.squaredDistanceTo(Vec3d.ofCenter(candidate))>20)continue;
                targetPos=MiningHelper.blockToMine.remove(i);resetMiningApproachFailures();return;
            }
        }
        if(uwGravelMission && !uwGravelWork.collect())System.out.println("FredoBot: UW_WORK_SHORTAGE confirmed="+uwGravelWork.count()+"/4 remaining targets occluded or out of reach; not counted as mined");
        if(uwGravelMission)MiningHelper.blockToMine.clear();
        uwPhase=UwDoorPhase.COLLECT_DROPS;pickupCoolDownTick=0;resetKeys();
        System.out.println("FredoBot: UW_COLLECT no more in-reach work targets");
    }
    private BlockPos leafSupport(MinecraftClient client,ItemEntity drop) {
        Box box=drop.getBoundingBox();
        return LeafLootSupport.find(box.minX,box.maxX,box.minY,box.minZ,box.maxZ,
                p -> client.world.getBlockState(p).isIn(BlockTags.LEAVES));
    }
    private boolean releaseLeafLog(MinecraftClient client,PlayerEntity player,PathExecutor executor,ItemEntity drop) {
        pickupCoolDownTick=5; // Track logs and leaf items across successive supporting leaf layers.
        BlockPos support=leafSupport(client,drop);
        if(support==null) {
            leafSettleTicks++;
            if(leafSettleTicks<=5 || (!drop.isOnGround() && !drop.isTouchingWater())) {
                if(executor.isBusy())executor.stop();
                resetKeys();return true; // Follow the same entity while it falls to the next layer.
            }
            if(!executor.isBusy() && player.squaredDistanceTo(drop)>4) {
                BlockPos pickup=new BlockPos(drop.getX(),drop.getY()+.5,drop.getZ());
                executor.trySetGoal(pickup,"collect released tree loot");
            }
            return true; // Wait for this entity to be picked up, not an arbitrary pickup timeout.
        }
        leafSettleTicks=0;
        Vec3d eye=new Vec3d(player.getX(),player.getEyeY(),player.getZ());
        var hit=client.world.rayTrace(new RayTraceContext(eye,Vec3d.ofCenter(support),
                RayTraceContext.ShapeType.OUTLINE,RayTraceContext.FluidHandling.NONE,player));
        if(hit.getType()==HitResult.Type.BLOCK) {
            BlockPos leaf=hit.getBlockPos();
            if(leaf.getY()<=support.getY() && client.world.getBlockState(leaf).isIn(BlockTags.LEAVES)
                    && eye.squaredDistanceTo(Vec3d.ofCenter(leaf))<=20) {
                if(executor.isBusy())executor.stop();
                resetKeys();
                if(InventoryHelper.findItemSlot(player,Items.SHEARS)<0
                        || !ToolsHelper.equipPlannedTool(player,leaf,Items.SHEARS))ToolsHelper.equipBestTool(player,leaf,false);
                float[] angles=MiningHelper.getValidMiningAngle(player,leaf);
                MovementController.setLookDirection(player,angles[0],angles[1]);
                client.interactionManager.updateBlockBreakingProgress(leaf,hit.getSide());
                if(!player.handSwinging)player.swingHand(Hand.MAIN_HAND);
                return true;
            }
        }
        if(drop.getStack().getItem().isIn(ItemTags.LEAVES)) {
            // Leaf drops do not justify a canopy route: clip reachable support or choose another pickup.
            if(executor.isBusy())executor.stop();
            unreachableEntities.add(drop);resetKeys();pickupCoolDownTick=5;
            if(targetEntity==drop)targetEntity=null;
            leafLogDrop=null;lastLeafApproach=null;failedLeafApproaches.clear();
            System.out.println("FredoBot: LEAF_DROP_SKIP support="+support.toShortString()+" reason=SUPPORT_NOT_IN_REACH_NO_CANOPY_ROUTE");
            return false;
        }
        long failures=executor.getSearchFailureSerial();
        if(failures!=leafFailureSerial) {
            leafFailureSerial=failures;leafApproachFailures++;
            if(lastLeafApproach!=null)failedLeafApproaches.add(lastLeafApproach);
        }
        if(!executor.isBusy() && player.isOnGround()) {
            BlockPos approach=leafApproachFailures>=2?null:findMiningApproach(client,player,support,failedLeafApproaches,true);
            if(approach==null) {
                unreachableEntities.add(drop);resetKeys();pickupCoolDownTick=5;
                if(targetEntity==drop)targetEntity=null;
                leafLogDrop=null;lastLeafApproach=null;failedLeafApproaches.clear();
                System.out.println("FredoBot: LEAF_DROP_SKIP support="+support.toShortString()
                        +" reason="+(leafApproachFailures>=2?"APPROACH_FAILURE_LIMIT":"NO_GROUND_MINING_STANCE"));
                return false; // Abandon this entire dropped-log objective, rather than trying each canopy leaf.
            }
            if(approach!=null && executor.trySetGoal(approach,"release log from leaves")) {
                lastLeafApproach=approach;executor.recordMiningTarget(support,approach);
            }
        }
        return true;
    }
    private void registerValidDrop(Block block) {
        if (block == Blocks.AIR || block == Blocks.WATER) return;
        Item baseItem = block.asItem();
        if (baseItem != Items.AIR) validDrops.add(baseItem);

        if (block.isIn(BlockTags.LEAVES)) {
            validDrops.add(Items.STICK);
            validDrops.add(Items.APPLE);
            validDrops.add(Items.OAK_SAPLING);
            validDrops.add(Items.SPRUCE_SAPLING);
            validDrops.add(Items.BIRCH_SAPLING);
            validDrops.add(Items.JUNGLE_SAPLING);
            validDrops.add(Items.ACACIA_SAPLING);
            validDrops.add(Items.DARK_OAK_SAPLING);
        } else if (block == Blocks.GRAVEL) {
            validDrops.add(Items.FLINT);
        } else if (block == Blocks.STONE) {
            validDrops.add(Items.COBBLESTONE);
        } else if (block == Blocks.COAL_ORE) {
            validDrops.add(Items.COAL);
        } else if (block == Blocks.IRON_ORE) {
            validDrops.add(Items.IRON_ORE);
        } else if (block == Blocks.GOLD_ORE) {
            validDrops.add(Items.GOLD_ORE);
        } else if (block == Blocks.DIAMOND_ORE) {
            validDrops.add(Items.DIAMOND);
        } else if (block == Blocks.GRASS_BLOCK || block == Blocks.GRASS_PATH) {
            validDrops.add(Items.DIRT);
        }
    }
    private boolean isSafeDoorFoundation(MinecraftClient client, BlockPos foundationPos) {
        BlockState foundationState = client.world.getBlockState(foundationPos);
        if (!foundationState.getMaterial().isSolid()) return false;
        if (foundationState.getBlock() instanceof FallingBlock) {
            BlockState stateBelow = client.world.getBlockState(foundationPos.down());
            if (!stateBelow.getMaterial().isSolid() || stateBelow.getMaterial().isLiquid() || stateBelow.getMaterial().isReplaceable()) {
                return false;
            }
        }

        return true;
    }
}
