package com.fredoseep.utils.prenether;

import com.fredoseep.excutor.PathExecutor;
import com.fredoseep.logging.GlobalStateLogger;
import com.fredoseep.utils.player.InventoryHelper;
import com.fredoseep.utils.player.MiningAim;
import com.fredoseep.utils.player.ToolsHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import java.util.Map;
import java.util.LinkedHashMap;
import net.minecraft.block.Block;

/** Short, observed one-block pillar steps out of a dry gravel shaft. No route search or gravel consumption. */
public final class WarmGravelExit {
    private final World world;
    private final int x,z,surfaceY;
    private int stepY,progressAge,pendingAge,lastAge=-1,settled;
    private BlockPos pending;
    private boolean done,failed;
    private String phase="CENTER";
    public WarmGravelExit(World world,PlayerEntity player,int surfaceY){
        this.world=world;x=player.getBlockPos().getX();z=player.getBlockPos().getZ();
        this.surfaceY=surfaceY;stepY=player.getBlockPos().getY();progressAge=player.age;
        event(player,"started",Map.of("surface_y",surfaceY,"gravel_allowed",false));
    }
    public boolean done(){return done;}
    public boolean failed(){return failed;}
    public String hudStatus(){return "暖海沙砾：垫回地表 "+stepY+"/"+surfaceY+" "+phase;}
    private void event(PlayerEntity p,String event,Map<String,?> detail){
        var data=new LinkedHashMap<String,Object>(detail);
        data.put("pos",p.getPos().toString());data.put("phase",phase);
        GlobalStateLogger.logCustomEvent("warm_gravel_exit_"+event,data);
    }
    private void phase(PlayerEntity p,String next){if(!phase.equals(next)){phase=next;event(p,"phase",Map.of());}}
    private void fail(MinecraftClient c,PlayerEntity p,String reason){failed=true;release(c);c.interactionManager.cancelBlockBreaking();event(p,"failed",Map.of("reason",reason));}
    public static void release(MinecraftClient c){MagmaDescent.release(c);key(c.options.keyUse,false);}
    private static void key(KeyBinding k,boolean down){KeyBinding.setKeyPressed(k.getDefaultKey(),down);}
    /** Price-table candidates only; exclude gravel independently of flint inventory/cost. */
    public static Item material(PlayerEntity player){
        Item best=null;int cost=Integer.MAX_VALUE;
        for(var stack:player.inventory.main){
            if(stack.isEmpty() || stack.getItem()==Items.GRAVEL)continue;
            var candidate=InventoryHelper.PlaceableBlock.getPlaceable(stack.getItem());
            if(candidate!=null && candidate.getCost()<cost){cost=candidate.getCost();best=stack.getItem();}
        }
        return best;
    }
    public void tick(MinecraftClient c,PlayerEntity p,PathExecutor path){
        if(done || failed || lastAge==p.age)return;lastAge=p.age;
        release(c);p.setSprinting(false);
        if(c.world!=world || !p.isAlive() || p.hasVehicle()){fail(c,p,"OWNER_CHANGED");return;}
        if(path.isBusy()){fail(c,p,"UNEXPECTED_ROUTE_DURING_EXIT");return;}
        if(!world.getChunkManager().isChunkLoaded(x>>4,z>>4)){fail(c,p,"COLUMN_UNLOADED");return;}
        if(p.isTouchingWater()){fail(c,p,"DRY_SHAFT_FLOODED");return;}
        if(p.age-progressAge>200){fail(c,p,"NO_CONFIRMED_STEP_FOR_200_TICKS");return;}
        double dx=x+.5-p.getX(),dz=z+.5-p.getZ();
        if(dx*dx+dz*dz>1 || p.getY()<stepY-.5){fail(c,p,"LEFT_SHAFT_OR_FELL");return;}
        if(pending!=null){
            var support=world.getBlockState(pending);
            if(Block.isShapeFullCube(support.getCollisionShape(world,pending))){
                if(p.isOnGround() && Math.abs(p.getY()-pending.getY()-1)<.1){
                    if(++settled<3)return;
                    stepY=pending.getY()+1;pending=null;settled=0;progressAge=p.age;
                    event(p,"step_confirmed",Map.of("feet_y",stepY));
                }else{settled=0;phase(p,"LAND");return;}
            }else if(p.age-pendingAge<=15){phase(p,"WAIT_BLOCK");return;}
            else{pending=null;settled=0;phase(p,"RETRY_STEP");}
        }
        if(p.isOnGround() && p.getY()>=surfaceY-.1){
            if(++settled>=3){done=true;c.interactionManager.cancelBlockBreaking();event(p,"complete",Map.of("surface_y",surfaceY));}
            return;
        }
        settled=0;
        if(dx*dx+dz*dz>.01){
            if(!p.isOnGround())return;
            phase(p,"CENTER");p.yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));p.pitch=70;
            key(c.options.keyForward,true);key(c.options.keySneak,true);return;
        }
        BlockPos support=new BlockPos(x,stepY,z);
        for(int y=stepY+1;y<=stepY+2;y++){
            BlockPos head=new BlockPos(x,y,z);var state=world.getBlockState(head);
            if(!state.getFluidState().isEmpty()){fail(c,p,"FLUID_ABOVE");return;}
            if(!state.getCollisionShape(world,head).isEmpty()){
                if(!p.isOnGround())return;
                phase(p,"CLEAR_HEAD");var hit=MiningAim.visibleFace(world,p,head);
                if(hit==null || state.getHardness(world,head)<0){fail(c,p,"HEAD_BLOCK_NOT_MINEABLE");return;}
                Vec3d d=hit.getPos().subtract(p.getCameraPosVec(1));
                p.yaw=(float)Math.toDegrees(Math.atan2(-d.x,d.z));p.pitch=(float)-Math.toDegrees(Math.atan2(d.y,Math.hypot(d.x,d.z)));
                ToolsHelper.equipBestTool(p,head,false);look(c,p);
                c.interactionManager.updateBlockBreakingProgress(head,hit.getSide());p.swingHand(Hand.MAIN_HAND);return;
            }
        }
        if(!world.getBlockState(support).getMaterial().isReplaceable()){fail(c,p,"FEET_CELL_NOT_REPLACEABLE");return;}
        if(!Block.isShapeFullCube(world.getBlockState(support.down()).getCollisionShape(world,support.down()))){
            fail(c,p,"NO_FULL_SUPPORT_FOR_PILLAR");return;
        }
        c.interactionManager.cancelBlockBreaking();
        Item block=material(p);
        if(block==null){fail(c,p,"NO_BUILDING_BLOCK_EXCLUDING_GRAVEL");return;}
        if(p.isOnGround() && Math.hypot(p.getVelocity().x,p.getVelocity().z)>.025)return;
        phase(p,"JUMP_PLACE");p.pitch=90;
        key(c.options.keyJump,true);
        if(p.getY()<stepY+1.01)return;
        if(!InventoryHelper.selectBuildingBlock(p,true,block)){fail(c,p,"MATERIAL_DISAPPEARED");return;}
        look(c,p);
        c.interactionManager.interactBlock(c.player,c.world,Hand.MAIN_HAND,
                new BlockHitResult(new Vec3d(x+.5,stepY,z+.5),Direction.UP,support.down(),false));
        p.swingHand(Hand.MAIN_HAND);pending=support;pendingAge=p.age;settled=0;
        event(p,"place_sent",Map.of("support",support.toShortString(),"material",block.toString()));
    }
    private static void look(MinecraftClient c,PlayerEntity p){c.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookOnly(p.yaw,p.pitch,p.isOnGround()));}
}
